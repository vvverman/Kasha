#!/usr/bin/env python3
import argparse
import datetime as dt
import json
import plistlib
import zipfile
from pathlib import Path, PurePosixPath

RELEASE_BASE = "https://github.com/vvverman/Kasha/releases/download/sidestore-latest"
BUNDLE_ID = "ru.vrmn.kasha.test"


def read_app_info(ipa: Path, version: str, build: str) -> dict:
    """Проверить состав готового IPA и прочитать его реальные метаданные."""
    with zipfile.ZipFile(ipa) as archive:
        names = archive.namelist()
        plists = [
            name for name in names
            if len(PurePosixPath(name).parts) == 3
            and PurePosixPath(name).parts[0] == "Payload"
            and PurePosixPath(name).parts[1].endswith(".app")
            and PurePosixPath(name).name == "Info.plist"
        ]
        if len(plists) != 1:
            raise ValueError("IPA должен содержать ровно одно приложение в Payload")
        info = plistlib.loads(archive.read(plists[0]))
        expected = {
            "CFBundleIdentifier": BUNDLE_ID,
            "CFBundleShortVersionString": version,
            "CFBundleVersion": build,
        }
        for key, value in expected.items():
            if info.get(key) != value:
                raise ValueError(f"IPA: неверное значение {key}")
        root = str(PurePosixPath(plists[0]).parent)
        executable = info.get("CFBundleExecutable", "")
        if not executable or archive.getinfo(f"{root}/{executable}").file_size == 0:
            raise ValueError("В IPA нет исполняемого файла")
        for key in ("NSMicrophoneUsageDescription", "NSSpeechRecognitionUsageDescription"):
            if not isinstance(info.get(key), str) or not info[key].strip():
                raise ValueError(f"В IPA не задано разрешение {key}")
        if "audio" not in info.get("UIBackgroundModes", []):
            raise ValueError("В IPA не включена фоновая работа аудио")
        resources = [name for name in names if name.startswith(f"{root}/compose-resources/")]
        if not any("/font/" in name and name.endswith((".ttf", ".otf")) for name in resources):
            raise ValueError("В IPA отсутствуют шрифты Kasha UI")
        if not any(name.endswith("/drawable/kasha_logo.svg") for name in resources):
            raise ValueError("В IPA отсутствуют изображения Kasha UI")
        return info


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ipa", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--build", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    ipa = Path(args.ipa)
    try:
        info = read_app_info(ipa, args.version, args.build)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, plistlib.InvalidFileException) as error:
        raise SystemExit(f"Не удалось подготовить SideStore: {error}") from error

    today = dt.datetime.now(dt.timezone.utc).date().isoformat()
    source = {
        "name": "Kasha Test",
        "subtitle": "Тестовые iOS-сборки Kasha",
        "apps": [
            {
                "name": info.get("CFBundleDisplayName", info["CFBundleName"]),
                "bundleIdentifier": info["CFBundleIdentifier"],
                "developerName": "Vyacheslav Verman",
                "subtitle": "Голосовые заметки и задачи с локальным хранением",
                "localizedDescription": (
                    "Запись и прослушивание голоса, заметки, проекты, задачи и локальные напоминания. "
                    "Распознавание Apple Speech выполняется на устройстве, когда оно доступно. "
                    "Внешний AI подключается отдельно, только после выбора и согласия пользователя. "
                    "Для основной работы аккаунт Kasha и удалённый сервер не нужны."
                ),
                "iconURL": f"{RELEASE_BASE}/Kasha-icon.png",
                "tintColor": "#191715",
                "category": "utilities",
                "versions": [
                    {
                        "version": info["CFBundleShortVersionString"],
                        "buildVersion": info["CFBundleVersion"],
                        "date": today,
                        "localizedDescription": "Автоматическая тестовая сборка из актуального Kasha.",
                        "downloadURL": f"{RELEASE_BASE}/Kasha.ipa",
                        "size": ipa.stat().st_size,
                        "minOSVersion": info["MinimumOSVersion"],
                    }
                ],
                "appPermissions": {
                    # IPA не подписан; идентификаторы подписи добавляет SideStore.
                    "entitlements": [],
                    "privacy": {
                        key: value for key, value in info.items()
                        if key.endswith("UsageDescription") and isinstance(value, str)
                    },
                },
            }
        ],
        "news": [],
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(source, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
