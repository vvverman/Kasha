"""Регрессия состава установщика: тестовые байты, настоящая SHA-256, без inference."""
import hashlib
import os
import subprocess
import sys
import importlib.util
import re
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('model_bundle', ROOT / 'desktopApp/packaging/verify-model-bundle.py')
BUNDLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUNDLE)


class ModelBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='kasha-model-bundle-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.resources = self.root / 'app resources'
        (self.resources / 'bin').mkdir(parents=True)
        (self.resources / 'models').mkdir()
        self.pins = BUNDLE.model_pins()
        self.manifest = self.root / 'ModelArtifacts.kt'
        rows = []
        for role, (name, _) in self.pins.items():
            value = ('контрольная модель:' + role).encode()
            (self.resources / 'models' / name).write_bytes(value)
            rows.append(f'ModelArtifact(AiSelection.{role}, "{name}", "https://example.invalid/{name}", "{hashlib.sha256(value).hexdigest()}",)')
        self.manifest.write_text('\n'.join(rows), encoding='utf-8')
        for name in BUNDLE.BINARIES:
            (self.resources / 'bin' / (name + '.exe')).write_bytes(b'test executable')

    def verify(self):
        return BUNDLE.verify(self.resources, suffix='.exe', manifest=self.manifest)

    def test_complete_small_bundle_needs_no_legacy_shards(self):
        self.assertEqual(3, len(self.verify()))
        self.assertEqual(3, len(list((self.resources / 'models').iterdir())))

    def test_missing_model_blocks_each_role(self):
        for name, _ in self.pins.values():
            path = self.resources / 'models' / name
            data = path.read_bytes()
            path.unlink()
            with self.assertRaisesRegex(ValueError, 'отсутствует модель'):
                self.verify()
            path.write_bytes(data)

    def test_corrupted_model_is_rejected(self):
        path = self.resources / 'models' / self.pins['DEFAULT_TEXT'][0]
        path.write_bytes(path.read_bytes() + b'corrupted')
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            self.verify()

    def test_missing_embedding_binary_is_rejected(self):
        (self.resources / 'bin/llama-embedding.exe').unlink()
        with self.assertRaisesRegex(ValueError, 'исполняемый файл'):
            self.verify()

    def test_empty_binary_is_rejected(self):
        (self.resources / 'bin/llama-completion.exe').write_bytes(b'')
        with self.assertRaisesRegex(ValueError, 'исполняемый файл'):
            self.verify()

    def test_file_size_ceiling_remains_enforced(self):
        with patch.object(BUNDLE, 'MAX_MODEL_BYTES', 1):
            with self.assertRaisesRegex(ValueError, 'размер модели'):
                self.verify()

    def test_duplicate_or_missing_role_is_rejected(self):
        source = self.manifest.read_text()
        for broken in (source.replace('DEFAULT_ROUTING', 'DEFAULT_TEXT'), '\n'.join(source.splitlines()[:2])):
            self.manifest.write_text(broken)
            with self.assertRaisesRegex(ValueError, 'три локальные роли'):
                self.verify()

    def test_nonlocal_filename_is_rejected(self):
        name = self.pins['DEFAULT_TEXT'][0]
        self.manifest.write_text(self.manifest.read_text().replace('"' + name + '"', '"../' + name + '"'))
        with self.assertRaisesRegex(ValueError, 'имя файла'):
            self.verify()

    def cli(self):
        # Запускаем настоящий main в отдельном процессе с ограниченной кодировкой.
        # Подменён только путь к manifest с маленькими fixture-весами.
        command = (
            "import runpy,sys; "
            "from pathlib import Path; "
            "m=runpy.run_path(sys.argv[1]); "
            "main=m['main']; "
            "verify=main.__globals__['verify']; "
            "verify.__kwdefaults__['manifest']=Path(sys.argv[2]); "
            "sys.argv=[sys.argv[1],sys.argv[3],'--suffix','.exe']; main()"
        )
        return subprocess.run(
            [sys.executable, '-c', command, str(SPEC.origin), str(self.manifest), str(self.resources)],
            env=dict(os.environ, PYTHONIOENCODING='cp1252', PYTHONUTF8='0'),
            capture_output=True, timeout=10,
        )

    def test_cli_success_is_utf8_with_cp1252_pipe(self):
        result = self.cli()
        self.assertEqual(0, result.returncode, result.stderr.decode('utf-8'))
        output = result.stdout.decode('utf-8')
        self.assertEqual(3, output.count('SHA-256 OK'))
        self.assertIn('Все три модели', output)
        self.assertNotIn('UnicodeEncodeError', result.stderr.decode('utf-8'))

    def test_cli_failure_keeps_diagnostic_with_cp1252_pipe(self):
        (self.resources / 'bin/llama-embedding.exe').unlink()
        result = self.cli()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('отсутствует непустой исполняемый файл', result.stderr.decode('utf-8'))
        self.assertNotIn('UnicodeEncodeError', result.stderr.decode('utf-8'))

    def test_linux_permissions_cover_all_bundled_engines(self):
        script = (ROOT / 'desktopApp/build.gradle.kts').read_text(encoding='utf-8')
        match = re.search(r'for \(name in listOf\(([^)]*)\)\)', script)
        self.assertIsNotNone(match, 'Не найден этап прав нативных движков')
        self.assertEqual(set(BUNDLE.BINARIES), set(re.findall(r'"([^"]+)"', match[1])))
        self.assertIn('executable.setExecutable(true, false)', script)
        self.assertIn('executable.canExecute()', script)

    def test_linux_installed_resources_checked_before_ui(self):
        workflow = (ROOT / '.github/workflows/desktop-platforms.yml').read_text(encoding='utf-8')
        install = workflow.index('timeout 20 "$APP" --install-smoke')
        ui = workflow.index('timeout 120 xvfb-run')
        self.assertLess(install, ui)
        self.assertIn('cat "$OUT/install-smoke.log"', workflow[install:ui])

    def test_windows_ci_accepts_single_cab_like_packager(self):
        workflow = (ROOT / '.github/workflows/desktop-platforms.yml').read_text(encoding='utf-8')
        self.assertIn('$cabs.Count -lt 1', workflow)
        self.assertNotIn('$cabs.Count -lt 2', workflow)

    def test_windows_packaging_calls_shared_check_not_old_qwen_split(self):
        script = (ROOT / 'desktopApp/packaging/package-windows.ps1').read_text(encoding='utf-8')
        self.assertIn('verify-model-bundle.py', script)
        self.assertNotIn('Qwen3-4B', script)
        self.assertNotIn('ggml-small.bin', script)
        self.assertIn('$Cabs.Count -lt 1', script)
        self.assertIn('$cab.Length -ge 1900000000', script)


if __name__ == '__main__':
    unittest.main(verbosity=2)
