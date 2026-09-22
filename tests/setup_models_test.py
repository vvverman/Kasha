"""Offline setup orchestration tests; no native compilation or model inference.

Only external build/download commands are stubbed. SHA-256 is computed by the
real platform tool over small fixture files; production pins remain unchanged.
Run: python3 tests/setup_models_test.py
"""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/setup-models.sh"
MANIFEST = ROOT / "aiCatalog/src/commonMain/kotlin/brain/ai/ModelArtifacts.kt"

STUB = r'''
import os
from pathlib import Path
import sys
from urllib.parse import urlsplit
name = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ["SETUP_TEST_LOG"], "a") as log:
    log.write(name + " " + repr(args) + "\n")
if name == "git":
    directory = Path(args[1])
    if "init" in args:
        (directory / ".git").mkdir(exist_ok=True)
    elif "fetch" in args:
        (directory / ".mock-rev").write_text(args[-1])
    elif "rev-parse" in args:
        print((directory / ".mock-rev").read_text())
elif name == "cmake" and "--build" in args:
    target = args[args.index("--target") + 1]
    binary = Path(args[args.index("--build") + 1]) / "bin" / target
    binary.parent.mkdir(parents=True, exist_ok=True)
    binary.write_text("#!/bin/sh\nexit 0\n")
    binary.chmod(0o755)
elif name == "curl":
    url = next(arg for arg in args if arg.startswith("https://"))
    filename = Path(urlsplit(url).path).name
    data = (Path(os.environ["SETUP_TEST_FIXTURES"]) / filename).read_bytes()
    if filename == os.environ.get("SETUP_TEST_CORRUPT"):
        data += b"corrupt"
    Path(args[args.index("-o") + 1]).write_bytes(data)
'''


class SetupModelsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="kasha-setup-test-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.tools = self.base / "tools with spaces"
        self.bin = self.base / "bin"
        self.bin.mkdir()
        self.fixtures = self.base / "fixtures"
        self.fixtures.mkdir()
        self.log = self.base / "calls.log"
        self.log.touch()
        source = SCRIPT.read_text(encoding="utf-8")
        artifacts = re.findall(
            r'ModelArtifact\(\s*AiSelection\.(DEFAULT_\w+),\s*"([^"]+)",\s*"([^"]+)",\s*"([a-f0-9]{64})"',
            MANIFEST.read_text(encoding="utf-8"),
        )
        self.assertEqual({"DEFAULT_STT", "DEFAULT_TEXT", "DEFAULT_ROUTING"}, {a[0] for a in artifacts})
        self.models = {}
        for role, filename, url, digest in artifacts:
            self.assertIn(filename, source, role)
            self.assertIn(url.split("?")[0], source, role)
            self.assertIn(digest, source, role)
            self.models[role] = filename
            data = ("fixture:" + filename).encode()
            (self.fixtures / filename).write_bytes(data)
            # Only the temporary script accepts the small fixtures, never production.
            source = source.replace(digest, hashlib.sha256(data).hexdigest())
        self.script = self.base / "setup-models.sh"
        self.script.write_text(source, encoding="utf-8")
        for name in ("git", "cmake", "curl", "ffmpeg"):
            stub = self.bin / name
            stub.write_text("#!" + sys.executable + "\n" + STUB)
            stub.chmod(0o755)
        self.env = {k: v for k, v in os.environ.items() if not k.startswith(("KASHA_", "SETUP_TEST_"))}
        self.env.update(
            PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
            KASHA_TOOLS_HOME=str(self.tools),
            SETUP_TEST_LOG=str(self.log),
            SETUP_TEST_FIXTURES=str(self.fixtures),
        )

    def run_setup(self, corrupt=None):
        env = self.env.copy()
        if corrupt:
            env["SETUP_TEST_CORRUPT"] = corrupt
        return subprocess.run(["bash", str(self.script)], env=env, capture_output=True, text=True, timeout=30)

    def assert_ready(self):
        exports = subprocess.run(
            ["bash", "-c", 'source "$1"; printf "%s\\n" "$KASHA_WHISPER_CLI" "$KASHA_LLAMA_CLI" "$KASHA_EMBEDDING_CLI" "$KASHA_WHISPER_MODEL" "$KASHA_LLAMA_MODEL" "$KASHA_ROUTING_MODEL" "$KASHA_FFMPEG"',
             "setup-test", str(self.tools / "models.env")],
            env=self.env, capture_output=True, text=True, check=True, timeout=5,
        ).stdout.splitlines()
        self.assertEqual(7, len(exports))
        self.assertEqual(["whisper-cli", "llama-completion", "llama-embedding"], [Path(p).name for p in exports[:3]])
        self.assertEqual([self.models[r] for r in ("DEFAULT_STT", "DEFAULT_TEXT", "DEFAULT_ROUTING")], [Path(p).name for p in exports[3:6]])
        for index, value in enumerate(exports):
            self.assertTrue(Path(value).is_file(), value)
            if index < 3 or index == 6:
                self.assertTrue(os.access(value, os.X_OK), value)
        self.assertFalse((self.tools / ".setup-lock").exists())
        self.assertFalse((self.tools / "models.env").stat().st_mode & 0o077)
        self.assertFalse(list((self.tools / "models").glob("*.part")))

    def test_fresh_setup_prepares_all_three_roles(self):
        result = self.run_setup()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_ready()
        self.assertEqual(3, self.log.read_text().count("curl "))

    def test_repeat_reuses_models_and_repairs_only_missing_cleanup(self):
        self.assertEqual(0, self.run_setup().returncode)
        before = self.log.read_text()
        self.assertEqual(0, self.run_setup().returncode)
        self.assertEqual(before, self.log.read_text())
        (self.tools / "models" / self.models["DEFAULT_TEXT"]).unlink()
        self.assertEqual(0, self.run_setup().returncode)
        self.assert_ready()
        self.assertEqual(1, self.log.read_text()[len(before):].count("curl "))

    def test_bad_checksum_does_not_publish_first_install(self):
        cleanup = self.models["DEFAULT_TEXT"]
        result = self.run_setup(corrupt=cleanup)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Неверная контрольная сумма", result.stderr)
        self.assertFalse((self.tools / "models.env").exists())
        self.assertFalse((self.tools / "models" / cleanup).exists())
        self.assertFalse((self.tools / ".setup-lock").exists())

    def test_bad_download_preserves_previous_model_and_environment(self):
        self.assertEqual(0, self.run_setup().returncode)
        environment = (self.tools / "models.env").read_bytes()
        cleanup = self.models["DEFAULT_TEXT"]
        previous = self.tools / "models" / cleanup
        previous.write_bytes(b"previous file requiring replacement")
        result = self.run_setup(corrupt=cleanup)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"previous file requiring replacement", previous.read_bytes())
        self.assertEqual(environment, (self.tools / "models.env").read_bytes())
        self.assertFalse((self.tools / ".setup-lock").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
