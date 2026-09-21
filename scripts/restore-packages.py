#!/usr/bin/env python3
"""Проверить и собрать выбранные пакеты из частей, не запуская установщики."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re


def safe(name: str) -> str:
    if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_.+ -]+", name) or name in (".", ".."):
        raise ValueError("Недопустимое имя файла")
    return name


def verify(path: Path, size: int, expected: str) -> None:
    if path.is_symlink() or not path.is_file() or path.stat().st_size != size:
        raise ValueError(f"Файл отсутствует или имеет неверный размер: {path.name}")
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != expected:
        raise ValueError(f"Контрольная сумма не совпадает: {path.name}")


def restore(package: dict, root: Path) -> Path:
    output = root / safe(package["name"])
    if output.exists():
        verify(output, package["size"], package["sha256"])
        return output
    parts = package["parts"]
    if not parts or sum(part["size"] for part in parts) != package["size"]:
        raise ValueError("Неверный состав пакета")
    for part in parts:
        verify(root / safe(part["name"]), part["size"], part["sha256"])
    temporary = output.with_name(output.name + ".assembling")
    owned = False
    try:
        with temporary.open("xb") as target:
            owned = True
            for part in parts:
                with (root / safe(part["name"])).open("rb") as source:
                    while block := source.read(1024 * 1024):
                        target.write(block)
        verify(temporary, package["size"], package["sha256"])
        # Не перезаписывать существующий пользовательский файл.
        if output.exists() or output.is_symlink():
            raise FileExistsError(output)
        temporary.rename(output)
    except Exception:
        if owned and temporary.is_file() and not temporary.is_symlink():
            temporary.unlink()
        raise
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", required=True,
                        choices=["macos-arm64", "windows-x64", "linux-x64", "android-debug", "android-unsigned", "ios-sidestore", "web-runtime"])
    parser.add_argument("--directory", type=Path, default=Path.cwd())
    args = parser.parse_args()
    root = args.directory.resolve()
    manifest = json.loads((root / "release-manifest.json").read_text(encoding="utf-8"))
    packages = [package for package in manifest["packages"] if package["platform"] == args.platform]
    if not packages:
        raise ValueError("Платформа отсутствует в манифесте")
    for package in packages:
        print("Проверен:", restore(package, root))


if __name__ == "__main__":
    main()
