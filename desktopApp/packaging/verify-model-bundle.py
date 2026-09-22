"""Проверка содержимого собранного приложения по общему manifest, без загрузок.

Python нужен только сборочной машине. Пользовательский runtime его не вызывает.
"""
import argparse
import hashlib
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'aiCatalog/src/commonMain/kotlin/brain/ai/ModelArtifacts.kt'
ROLES = {'DEFAULT_STT', 'DEFAULT_TEXT', 'DEFAULT_ROUTING'}
BINARIES = ('whisper-cli', 'llama-completion', 'llama-embedding', 'ffmpeg')
MAX_MODEL_BYTES = 2_000_000_000


def model_pins(manifest: Path = MANIFEST) -> dict[str, tuple[str, str]]:
    matches = re.findall(
        r'ModelArtifact\(\s*AiSelection\.(DEFAULT_\w+),\s*"([^"\r\n]+)",'
        r'\s*"https://[^"\r\n]+",\s*"([a-f0-9]{64})"\s*,?\s*\)',
        manifest.read_text(encoding='utf-8'),
    )
    if len(matches) != 3 or {role for role, _, _ in matches} != ROLES:
        raise ValueError('Manifest должен однозначно описывать все три локальные роли')
    if len({name for _, name, _ in matches}) != 3:
        raise ValueError('Каждая роль должна иметь отдельный файл модели')
    for _, name, _ in matches:
        if '/' in name or '\\' in name or ':' in name or name in ('.', '..'):
            raise ValueError('Некорректное имя файла модели: ' + name)
    return {role: (name, digest) for role, name, digest in matches}


def verify(resources: Path, *, suffix: str = '', manifest: Path = MANIFEST) -> list[str]:
    if suffix not in ('', '.exe'):
        raise ValueError('Неизвестный суффикс исполняемых файлов')
    pins = model_pins(manifest)
    verified = []
    for binary in BINARIES:
        path = resources / 'bin' / (binary + suffix)
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError('В приложении отсутствует непустой исполняемый файл: ' + str(path))
    for role, (name, expected) in pins.items():
        path = resources / 'models' / name
        if not path.is_file():
            raise ValueError('В приложении отсутствует модель ' + role + ': ' + name)
        size = path.stat().st_size
        if not 0 < size < MAX_MODEL_BYTES:
            raise ValueError('Недопустимый размер модели ' + name + ': ' + str(size))
        digest = hashlib.sha256()
        with path.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
        if digest.hexdigest() != expected:
            raise ValueError('Не совпала SHA-256 модели: ' + name)
        verified.append(role + ': ' + name)
    return verified


def main():
    # PowerShell перенаправляет stdout в pipe: Python на Windows может выбрать
    # CP1252. Диагностика всегда UTF-8, включая русские пути и сообщения ошибок.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('resources', type=Path)
    parser.add_argument('--suffix', choices=('', '.exe'), default='')
    args = parser.parse_args()
    for row in verify(args.resources, suffix=args.suffix):
        print(row + ' — SHA-256 OK')
    print('Все три модели и четыре исполняемых файла присутствуют')


if __name__ == '__main__':
    main()
