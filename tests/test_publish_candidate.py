"""Изолированные проверки упаковки: сеть, секреты и пользовательские файлы не нужны."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


publish = module("publisher", ROOT / "scripts/publish-candidate.py")
restore = module("restore", ROOT / "scripts/restore-packages.py")


class CandidateTests(unittest.TestCase):
    def test_selects_only_latest_main_push_for_exact_revision(self):
        def run(number, sha="a", branch="main", event="push", attempt=1):
            return {"id": number, "head_sha": sha, "head_branch": branch, "event": event,
                    "path": ".github/workflows/kotlin.yml", "run_attempt": attempt}
        actual = publish.selected_runs([run(1), run(2), run(9, branch="feature"),
                                        run(10, event="pull_request"), run(11, sha="b")], "a")
        self.assertEqual([2], [r["id"] for r in actual.values()])

    def test_failed_ci_blocks_release(self):
        from unittest.mock import patch
        run = {"id": 1, "head_sha": "a", "head_branch": "main", "event": "push",
               "path": ".github/workflows/kotlin.yml", "name": "Kotlin", "status": "completed", "conclusion": "failure"}
        with patch.object(publish, "api", return_value={"total_count": 1, "workflow_runs": [run]}):
            with self.assertRaises(RuntimeError):
                publish.wait_for_matrix("a", seconds=1)

    def test_all_eight_successful_runs_are_required(self):
        from unittest.mock import patch
        runs = [{"id": i, "head_sha": "a", "head_branch": "main", "event": "push",
                 "path": path, "name": name, "status": "completed", "conclusion": "success"}
                for i, (path, name) in enumerate(publish.WORKFLOWS.items())]
        with patch.object(publish, "api", return_value={"total_count": 8, "workflow_runs": runs}):
            self.assertEqual(8, len(publish.wait_for_matrix("a", seconds=1)))

    def test_truncated_input_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(RuntimeError):
                publish.split_stream(io.BytesIO(b"abc"), 4, "test.dmg", Path(directory), lambda *_: None)

    def test_roundtrip_and_part_hashes(self):
        data = b"0123456789abcdef"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            out = root / "out"
            out.mkdir()
            def upload(path, row):
                (root / path.name).write_bytes(path.read_bytes())
                self.assertEqual(row["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            package = publish.split_stream(io.BytesIO(data), len(data), "Kasha.dmg", out, upload, limit=8, part_size=5)
            self.assertEqual(4, len(package["parts"]))
            output = restore.restore(package, root)
            self.assertEqual(data, output.read_bytes())
            self.assertEqual(output, restore.restore(package, root))

    def test_corrupt_part_never_creates_installer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "test.part001").write_bytes(b"bad")
            package = {"name": "test.dmg", "size": 3, "sha256": "0" * 64,
                       "parts": [{"name": "test.part001", "size": 3, "sha256": "0" * 64}]}
            with self.assertRaises(ValueError):
                restore.restore(package, root)
            self.assertFalse((root / "test.dmg").exists())

    def test_unsafe_filenames_rejected(self):
        for name in ["../bad", "/bad", "dir\\bad", "bad\nname"]:
            with self.assertRaises((RuntimeError, ValueError)):
                publish.safe_name(name)
            with self.assertRaises(ValueError):
                restore.safe(name)

    def test_existing_different_file_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "test.dmg").write_bytes(b"keep")
            with self.assertRaises(ValueError):
                restore.restore({"name": "test.dmg", "size": 4, "sha256": "0" * 64, "parts": []}, root)
            self.assertEqual(b"keep", (root / "test.dmg").read_bytes())

    def test_existing_temporary_file_is_not_deleted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            part = b"abc"
            (root / "test.part001").write_bytes(part)
            (root / "test.dmg.assembling").write_bytes(b"keep")
            row = {"name": "test.dmg", "size": 3, "sha256": hashlib.sha256(part).hexdigest(),
                   "parts": [{"name": "test.part001", "size": 3, "sha256": hashlib.sha256(part).hexdigest()}]}
            with self.assertRaises(FileExistsError):
                restore.restore(row, root)
            self.assertEqual(b"keep", (root / "test.dmg.assembling").read_bytes())

    def test_macos_assembler_uses_only_built_in_shell_tools(self):
        import subprocess
        code = publish.macos_assembler({"name": "Kasha.dmg", "sha256": "0" * 64,
                                       "parts": [{"name": "Kasha.dmg.part001", "sha256": "1" * 64},
                                                 {"name": "Kasha.dmg.part002", "sha256": "2" * 64}]})
        subprocess.run(["bash", "-n"], input=code, text=True, check=True)
        self.assertNotIn("python", code)
        self.assertIn("shasum -a 256 -c -", code)
        self.assertIn('ln "$tmp"', code)

    def test_web_bundle_is_deterministic_and_excludes_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.zip"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("composeApp/build/dist/wasmJs/productionExecutable/index.html", "ok")
                archive.writestr("runtime/build/install/runtime/bin/runtime", "run")
                archive.writestr("runtime.log", "not for publication")
            for name in ("a.tgz", "b.tgz"):
                with zipfile.ZipFile(source) as archive:
                    publish.make_web_bundle(archive, root / name)
            self.assertEqual((root / "a.tgz").read_bytes(), (root / "b.tgz").read_bytes())
            import tarfile
            with tarfile.open(root / "a.tgz") as archive:
                self.assertNotIn("runtime.log", archive.getnames())
                self.assertEqual(0o755, archive.getmember("runtime/bin/runtime").mode)


if __name__ == "__main__":
    unittest.main()
