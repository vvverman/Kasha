"""Регрессия состава установщика: тестовые байты, настоящая SHA-256, без inference."""
import hashlib
import importlib.util
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

    def test_windows_packaging_calls_shared_check_not_old_qwen_split(self):
        script = (ROOT / 'desktopApp/packaging/package-windows.ps1').read_text(encoding='utf-8')
        self.assertIn('verify-model-bundle.py', script)
        self.assertNotIn('Qwen3-4B', script)
        self.assertNotIn('ggml-small.bin', script)
        self.assertIn('$Cabs.Count -lt 1', script)
        self.assertIn('$cab.Length -ge 1900000000', script)


if __name__ == '__main__':
    unittest.main(verbosity=2)
