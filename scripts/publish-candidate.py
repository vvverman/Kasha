#!/usr/bin/env python3
"""Публикация предрелиза из проверенных артефактов одной ревизии main."""
from __future__ import annotations

import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import tarfile
import tempfile
import time
import zipfile

REPO = "vvverman/Kasha"
WORKFLOWS = {
    ".github/workflows/kotlin.yml": "Kotlin Multiplatform",
    ".github/workflows/desktop-adapters.yml": "Kasha Desktop Adapters",
    ".github/workflows/android-shell.yml": "Android shell verification",
    ".github/workflows/ios-core.yml": "Kasha iOS Shared",
    ".github/workflows/ios-sidestore.yml": "Kasha iOS SideStore",
    ".github/workflows/macos.yml": "Автономный установщик macOS",
    ".github/workflows/desktop-platforms.yml": "Kasha Desktop Windows Linux",
    ".github/workflows/ai-android.yml": "Kasha Android AI offline",
}
MAX_ASSET = 1900 * 1024 * 1024  # Ниже ограничения GitHub 2 GiB.
PART_SIZE = 1024 * 1024 * 1024
BLOCK = 1024 * 1024


def gh(*args: str, **kwargs):
    return subprocess.run(["gh", *args], check=True, timeout=1800, **kwargs)


def api(path: str):
    result = gh("api", f"repos/{REPO}/{path}", capture_output=True, text=True)
    return json.loads(result.stdout)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def selected_runs(runs: list[dict], sha: str) -> dict[str, dict]:
    selected = {}
    for run in runs:
        path = run["path"].split("@", 1)[0]
        if (path not in WORKFLOWS or run.get("event") != "push"
                or run.get("head_branch") != "main" or run.get("head_sha") != sha):
            continue
        old = selected.get(path)
        if old is None or (run["id"], run.get("run_attempt", 1)) > (old["id"], old.get("run_attempt", 1)):
            selected[path] = run
    return selected


def wait_for_matrix(sha: str, seconds: int = 9000) -> dict[str, dict]:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        data = api(f"actions/runs?head_sha={sha}&event=push&per_page=100")
        require(data["total_count"] <= 100, "Слишком много запусков: требуется явная пагинация")
        runs = selected_runs(data["workflow_runs"], sha)
        failed = [r["name"] for r in runs.values()
                  if r["status"] == "completed" and r["conclusion"] != "success"]
        require(not failed, f"Публикация запрещена: неуспешный CI {failed}")
        pending = [name for path, name in WORKFLOWS.items()
                   if path not in runs or runs[path]["status"] != "completed"]
        if not pending:
            return runs
        print("Ожидается завершение CI той же ревизии: " + ", ".join(pending), flush=True)
        time.sleep(60)
    raise RuntimeError("Полная CI-матрица не завершилась; релиз не опубликован")


def check_versions(root: Path, version: str) -> None:
    checks = {
        "androidApp/build.gradle.kts": rf'versionName\s*=\s*"{re.escape(version)}"',
        "desktopApp/build.gradle.kts": rf'packageVersion\s*=\s*"{re.escape(version)}"',
        "iosApp/project.yml": rf'MARKETING_VERSION:\s*{re.escape(version)}(?:\s|$)',
    }
    for filename, pattern in checks.items():
        require(re.search(pattern, (root / filename).read_text()) is not None,
                f"Версия не совпадает: {filename}")


def safe_name(name: str) -> str:
    path = PurePosixPath(name)
    require(not path.is_absolute() and ".." not in path.parts and "\\" not in name,
            "Небезопасный путь в артефакте")
    require(re.fullmatch(r"[A-Za-z0-9_.+ -]+", path.name) is not None,
            f"Недопустимое имя пакета: {path.name!r}")
    return path.name


def split_stream(stream, size: int, name: str, directory: Path, publish,
                 limit: int = MAX_ASSET, part_size: int = PART_SIZE) -> dict:
    """Хешируем исходный файл и каждую часть; не загружаем установщик в память."""
    require(size > 0, f"Пустой пакет: {name}")
    whole = hashlib.sha256()
    parts = []
    remaining = size
    while remaining:
        length = min(remaining, part_size) if size > limit else remaining
        asset_name = f"{name}.part{len(parts) + 1:03d}" if size > limit else name
        path = directory / asset_name
        part_hash = hashlib.sha256()
        with path.open("xb") as target:
            left = length
            while left:
                block = stream.read(min(BLOCK, left))
                require(bool(block), f"Оборванный пакет: {name}")
                target.write(block)
                whole.update(block)
                part_hash.update(block)
                left -= len(block)
        part = {"name": asset_name, "size": length, "sha256": part_hash.hexdigest()}
        publish(path, part)
        path.unlink()
        parts.append(part)
        remaining -= length
    require(not stream.read(1), f"Размер пакета больше заявленного: {name}")
    return {"name": name, "size": size, "sha256": whole.hexdigest(), "parts": parts}


def make_web_bundle(archive: zipfile.ZipFile, output: Path) -> None:
    mappings = {
        "composeApp/build/dist/wasmJs/productionExecutable/": "web/",
        "runtime/build/install/runtime/": "runtime/",
    }
    found = set()
    with output.open("wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed, tarfile.open(fileobj=compressed, mode="w|") as bundle:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            for prefix, target in mappings.items():
                if entry.filename.startswith(prefix):
                    relative = entry.filename[len(prefix):]
                    safe_name(relative)
                    info = tarfile.TarInfo(target + relative)
                    info.size = entry.file_size
                    info.mode = 0o755 if target == "runtime/" and relative.startswith("bin/") else 0o644
                    with archive.open(entry) as stream:
                        bundle.addfile(info, stream)
                    found.add(target)
        require(found == {"web/", "runtime/"}, "В артефакте нет полной Web/runtime поставки")
        readme = ("Kasha Web + локальный runtime. Это не автономный Desktop-установщик.\n"
                  "Требуется Java 21. На macOS/Linux из каталога распаковки:\n"
                  'KASHA_WEB_ROOT="$PWD/web" bash runtime/bin/runtime\n'
                  "Открыть http://127.0.0.1:8787 . Для аудиообработки нужны настроенные FFmpeg/AI-движки.\n"
                  "Локальные AI-веса в этот архив не включены.\n").encode()
        info = tarfile.TarInfo("README-RU.txt")
        info.size = len(readme)
        info.mode = 0o644
        bundle.addfile(info, io.BytesIO(readme))


def macos_assembler(package: dict) -> str:
    q = lambda name: shlex.quote("./" + safe_name(name))
    checks = ["#!/bin/bash", "set -euo pipefail", 'cd "$(dirname "$0")"']
    def check_file(name, sha):
        return [f"test ! -L {q(name)}", "printf '%s  %s\\n' " + shlex.quote(sha) + " " + q(name) + " | shasum -a 256 -c -"]
    for part in package["parts"]:
        checks.extend(check_file(part["name"], part["sha256"]))
    if len(package["parts"]) > 1:
        checks += [f"if [ ! -e {q(package['name'])} ] && [ ! -L {q(package['name'])} ]; then",
                   '  tmp=$(mktemp "./.kasha-dmg.XXXXXX")',
                   "  trap 'rm -f \"$tmp\"' EXIT",
                   "  cat " + " ".join(q(part["name"]) for part in package["parts"]) + ' > "$tmp"',
                   "  printf '%s  %s\\n' " + shlex.quote(package["sha256"]) + ' "$tmp" | shasum -a 256 -c -',
                   f'  ln "$tmp" {q(package["name"])}', "fi"]
    checks.extend(check_file(package["name"], package["sha256"]))
    checks.append(f"open {q(package['name'])}")
    return "\n".join(checks) + "\n"


def main() -> None:
    require(os.environ.get("GITHUB_REPOSITORY") == REPO, "Не тот репозиторий")
    require(os.environ.get("GITHUB_REF") == "refs/heads/main", "Публикация разрешена только из main")
    root = Path.cwd()
    sha = os.environ["GITHUB_SHA"]
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == sha,
            "Checkout не совпадает с публикуемой ревизией")
    config = json.loads((root / ".github/releases/candidate.json").read_text())
    version, tag = config["version"], config["tag"]
    require(re.fullmatch(r"\d+\.\d+\.\d+", version) is not None, "Некорректная версия")
    require(re.fullmatch(re.escape("v" + version) + r"-rc\.\d+", tag) is not None, "Только rc-теги")
    check_versions(root, version)
    runs = wait_for_matrix(sha)
    tags = [ref for ref in api(f"git/matching-refs/tags/{tag}")
            if ref["ref"] == f"refs/tags/{tag}"]
    require(not tags or (tags[0]["object"]["type"] == "commit" and tags[0]["object"]["sha"] == sha),
            "Существующий тег относится к другой ревизии")
    releases = api("releases?per_page=100")
    require(len(releases) < 100, "Требуется пагинация релизов")
    existing = next((release for release in releases if release["tag_name"] == tag), None)
    if existing:
        require(existing["draft"] and existing["target_commitish"] == sha,
                "Существующий опубликованный релиз/чужой тег не перезаписывается")
    else:
        gh("release", "create", tag, "--repo", REPO, "--target", sha, "--draft", "--prerelease",
           "--latest=false", "--title", f"Kasha {version} — кандидат 1", "--notes-file",
           str(root / "docs/releases/1.2.0-rc.1.md"))
    if existing is None:
        existing = next(release for release in api("releases?per_page=100") if release["tag_name"] == tag)
    require(existing["draft"] and existing["target_commitish"] == sha, "Некорректный черновик релиза")
    release_id = existing["id"]
    assets = api(f"releases/{release_id}")["assets"]
    known = {asset["name"]: asset for asset in assets}
    used_names = set()
    asset_rows = []

    def publish(path: Path, row: dict) -> None:
        require(row["name"] not in used_names, f"Повторное имя файла: {row['name']}")
        used_names.add(row["name"])
        previous = known.get(row["name"])
        if previous:
            require(previous.get("digest") == "sha256:" + row["sha256"]
                    and previous["size"] == row["size"], "Артефакт предрелиза уже существует с другими байтами")
        else:
            gh("release", "upload", tag, str(path), "--repo", REPO)
        asset_rows.append(row)

    def publish_small(path: Path) -> None:
        publish(path, {"name": path.name, "size": path.stat().st_size, "sha256": digest(path)})

    manifest = {"schemaVersion": 1, "version": version, "tag": tag, "sourceCommit": sha,
                "prerelease": True, "productionSigningVerified": False,
                "hardwareAcceptanceCompleted": False,
                "ci": [{"name": WORKFLOWS[path], "runId": run["id"], "url": run["html_url"]}
                       for path, run in sorted(runs.items())], "packages": []}
    plan = [
        (".github/workflows/android-shell.yml", f"Kasha-android-debug-{sha}", "android-debug", {".apk"}),
        (".github/workflows/android-shell.yml", f"Kasha-android-release-unsigned-{sha}", "android-unsigned", {".apk", ".aab"}),
        (".github/workflows/ios-sidestore.yml", "Kasha-iOS-SideStore-", "ios-sidestore", {".ipa"}),
        (".github/workflows/macos.yml", "Kasha-macOS-arm64", "macos-arm64", {".dmg"}),
        (".github/workflows/desktop-platforms.yml", "Kasha-Windows-x64", "windows-x64", {".msi", ".cab", ".exe"}),
        (".github/workflows/desktop-platforms.yml", "Kasha-Linux-x64", "linux-x64", {".deb", ".rpm"}),
        (".github/workflows/kotlin.yml", f"kasha-web-verification-{sha}", "web-runtime", set()),
    ]
    with tempfile.TemporaryDirectory(prefix="kasha-release-") as directory:
        work = Path(directory)
        upload_directory = work / "upload"
        upload_directory.mkdir()
        for workflow, name, platform, suffixes in plan:
            result = api(f"actions/runs/{runs[workflow]['id']}/artifacts?per_page=100")
            require(result["total_count"] <= 100, "Требуется пагинация артефактов")
            matches = [a for a in result["artifacts"] if
                       (a["name"].startswith(name) if platform == "ios-sidestore" else a["name"] == name)]
            require(len(matches) == 1, f"Нет единственного артефакта {name}")
            artifact = matches[0]
            detail = api(f"actions/artifacts/{artifact['id']}")
            require(not detail["expired"] and detail["workflow_run"]["head_sha"] == sha,
                    "Артефакт истёк или принадлежит другой ревизии")
            archive_path = work / "artifact.zip"
            with archive_path.open("wb") as stream:
                gh("api", f"repos/{REPO}/actions/artifacts/{artifact['id']}/zip", stdout=stream)
            require(detail.get("digest") == "sha256:" + digest(archive_path),
                    "Контрольная сумма скачанного CI-артефакта не совпала")
            count = 0
            with zipfile.ZipFile(archive_path) as archive:
                if platform == "web-runtime":
                    bundle = work / f"Kasha-Web-runtime-{version}.tar.gz"
                    make_web_bundle(archive, bundle)
                    with bundle.open("rb") as stream:
                        row = split_stream(stream, bundle.stat().st_size, bundle.name, upload_directory, publish)
                    bundle.unlink()
                    row.update(platform=platform, sourceArtifact=artifact["id"])
                    manifest["packages"].append(row)
                    count = 1
                else:
                    for entry in archive.infolist():
                        if entry.is_dir() or PurePosixPath(entry.filename).suffix.lower() not in suffixes:
                            continue
                        if "androidtest" in entry.filename.lower():
                            continue
                        filename = safe_name(entry.filename)
                        with archive.open(entry) as stream:
                            row = split_stream(stream, entry.file_size, filename, upload_directory, publish)
                        row.update(platform=platform, sourceArtifact=artifact["id"])
                        manifest["packages"].append(row)
                        count += 1
            require(count > 0, f"Нет установочных файлов: {platform}")
            archive_path.unlink()
        document = work / "release-manifest.json"
        document.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        publish_small(document)
        mac = [row for row in manifest["packages"] if row["platform"] == "macos-arm64"]
        require(len(mac) == 1, "Ожидался один macOS DMG")
        assembler = work / "assemble-macos.command"
        assembler.write_text(macos_assembler(mac[0]))
        publish_small(assembler)
        for helper in ("restore-packages.py",):
            publish_small(root / "scripts" / helper)
        notes = work / "README-RU.md"
        notes.write_text((root / "docs/releases/1.2.0-rc.1.md").read_text()
                         + f"\nИсходная ревизия: `{sha}`.\n")
        publish_small(notes)
        sums = work / "SHA256SUMS.txt"
        sums.write_text("".join(f"{row['sha256']}  {row['name']}\n" for row in asset_rows))
        publish_small(sums)
    uploaded = {a["name"]: a for a in api(f"releases/{release_id}")["assets"]}
    require(set(uploaded) == used_names, "Набор загруженных файлов не совпал с манифестом")
    for row in asset_rows:
        require(uploaded[row["name"]].get("digest") == "sha256:" + row["sha256"]
                and uploaded[row["name"]]["size"] == row["size"], "Проверка опубликованных байтов не прошла")
    gh("release", "edit", tag, "--repo", REPO, "--draft=false", "--prerelease", "--latest=false")
    print(f"Предрелиз опубликован: https://github.com/{REPO}/releases/tag/{tag}", flush=True)


if __name__ == "__main__":
    main()
