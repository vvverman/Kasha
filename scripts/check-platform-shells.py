#!/usr/bin/env python3
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
errors = []

def require(path: str, reason: str) -> None:
    if not (root / path).exists():
        errors.append(f"missing {path}: {reason}")

def text(path: str) -> str:
    p = root / path
    return p.read_text(encoding="utf-8") if p.exists() else ""

# One Core + shared AI catalog + shared UI.
require("kashaCore", "single business Core")
require("aiCatalog", "concrete AI catalog outside Core")
require("composeApp", "shared Kasha UI")

# Six physical delivery shells.
require("desktopApp", "macOS/Windows/Linux desktop shell")
require("androidApp", "Android app shell")
require("iosApp", "iOS app shell")
require("composeApp/src/iosMain", "iOS adapters")
require("composeApp/src/wasmJsMain", "Web shell")

settings = text("settings.gradle.kts")
for module in (":kashaCore", ":aiCatalog", ":composeApp", ":desktopApp", ":androidApp"):
    if module not in settings:
        errors.append(f"settings.gradle.kts does not include {module}")

architecture = text("docs/ARCHITECTURE.md")
for platform in ("macOS", "Windows", "Linux", "Android", "iOS", "Web"):
    if platform not in architecture:
        errors.append(f"docs/ARCHITECTURE.md misses platform {platform}")

# Desktop must produce three OS families, not a macOS-only package.
desktop_gradle = text("desktopApp/build.gradle.kts")
for marker in ("TargetFormat.Dmg", "TargetFormat.Msi", "TargetFormat.Exe", "TargetFormat.Deb", "TargetFormat.Rpm"):
    if marker not in desktop_gradle:
        errors.append(f"desktop packaging misses {marker}")

# Android must be an app shell over the shared modules, not a second implementation.
android_gradle = text("androidApp/build.gradle.kts")
for dep in ('project(":kashaCore")', 'project(":aiCatalog")', 'project(":composeApp")'):
    if dep not in android_gradle:
        errors.append(f"androidApp must depend on shared layer: {dep}")
require("androidApp/src/main/AndroidManifest.xml", "installable Android application")
require("androidApp/src/main/kotlin/ru/vrmn/kasha/android/MainActivity.kt", "thin Android composition root")

# Core must stay inward-only.
core_files = list((root / "kashaCore").rglob("*.kt")) if (root / "kashaCore").exists() else []
forbidden_core = (
    "android.", "androidx.", "platform.UIKit", "platform.AVFAudio", "platform.UserNotifications",
    "java.awt", "javax.sound", "org.jetbrains.compose", "androidApp", "desktopApp", "iosApp",
    "openai.com", "anthropic.com", "generativelanguage.googleapis.com", "openrouter.ai",
)
for file in core_files:
    body = file.read_text(encoding="utf-8")
    for token in forbidden_core:
        if token in body:
            errors.append(f"Core boundary violation: {file.relative_to(root)} contains {token}")

# No second Core-like source module may appear accidentally.
for child in root.iterdir():
    if not child.is_dir() or child.name == "kashaCore":
        continue
    low = child.name.lower()
    if low.endswith("core") or low.startswith("core"):
        errors.append(f"second Core-like module found: {child.name}")

# Platform composition roots should remain small and depend inward.
main_activity = text("androidApp/src/main/kotlin/ru/vrmn/kasha/android/MainActivity.kt")
if "StudioState(" not in main_activity or "StudioApp(state)" not in main_activity:
    errors.append("Android shell is not composed around shared StudioState/StudioApp")

desktop_main = text("desktopApp/src/main/kotlin/brain/desktop/Main.kt")
if "StudioState(" not in desktop_main or "StudioApp(state)" not in desktop_main:
    errors.append("Desktop shell is not composed around shared StudioState/StudioApp")

if errors:
    print("Platform shell boundary: FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("Platform shell boundary: OK (1 Core + shared UI + macOS/Windows/Linux/Android/iOS/Web)")
