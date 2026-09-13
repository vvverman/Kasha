#!/usr/bin/env python3
"""Физическая архитектура Kasha: reusable modules inward, six delivery shells outward."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
errors = []


def require(path: str, reason: str) -> None:
    if not (root / path).exists():
        errors.append(f"missing {path}: {reason}")


def text(path: str) -> str:
    p = root / path
    return p.read_text(encoding="utf-8") if p.exists() else ""

# Reusable layers: only under modules/.
for path, reason in (
    ("modules/core", "single business Core"),
    ("modules/ai/catalog", "concrete AI catalog outside Core"),
    ("modules/ai/connectors", "shared external AI protocol"),
    ("modules/ui", "shared Kasha UI"),
    ("modules/infrastructure/jvm", "shared JVM infrastructure"),
):
    require(path, reason)

# Delivery shells: only under platforms/.
for path, reason in (
    ("platforms/desktop", "shared JVM desktop shell for macOS/Windows/Linux"),
    ("platforms/android", "Android shell"),
    ("platforms/ios/shared", "iOS Kotlin shell/framework"),
    ("platforms/ios/app", "iOS Xcode wrapper"),
    ("platforms/web", "Web shell"),
):
    require(path, reason)

# Old ambiguous top-level module folders are forbidden.
for legacy in ("kashaCore", "aiCatalog", "composeApp", "runtime", "desktopApp", "androidApp", "iosApp"):
    if (root / legacy).exists():
        errors.append(f"legacy top-level module must not exist: {legacy}")

settings = text("settings.gradle.kts")
expected_mapping = {
    ":kashaCore": "modules/core",
    ":aiCatalog": "modules/ai/catalog",
    ":aiConnectors": "modules/ai/connectors",
    ":composeApp": "modules/ui",
    ":runtime": "modules/infrastructure/jvm",
    ":desktopApp": "platforms/desktop",
    ":androidApp": "platforms/android",
    ":iosShell": "platforms/ios/shared",
    ":webApp": "platforms/web",
}
for module, directory in expected_mapping.items():
    if module not in settings or directory not in settings:
        errors.append(f"settings.gradle.kts mapping missing: {module} -> {directory}")

# Shared UI must physically stay platform-neutral.
for forbidden in ("iosMain", "wasmJsMain", "androidMain", "jvmMain"):
    if (root / "modules/ui/src" / forbidden).exists():
        errors.append(f"shared UI contains platform source set: modules/ui/src/{forbidden}")

# Core must stay inward-only.
core_files = list((root / "modules/core").rglob("*.kt")) if (root / "modules/core").exists() else []
forbidden_core = (
    "android.", "androidx.", "platform.UIKit", "platform.AVFAudio", "platform.UserNotifications",
    "java.awt", "javax.sound", "org.jetbrains.compose", "platforms/", "api.openai.com",
    "api.anthropic.com", "generativelanguage.googleapis.com", "openrouter.ai",
)
for file in core_files:
    body = file.read_text(encoding="utf-8")
    for token in forbidden_core:
        if token in body:
            errors.append(f"Core boundary violation: {file.relative_to(root)} contains {token}")

# Concrete provider URLs belong only to ai/connectors (legacy JVM adapter is forbidden after migration).
provider_hosts = ("api.openai.com", "api.anthropic.com", "generativelanguage.googleapis.com", "openrouter.ai")
for scope in (root / "modules", root / "platforms"):
    if not scope.exists():
        continue
    for file in scope.rglob("*.kt"):
        body = file.read_text(encoding="utf-8")
        if any(host in body for host in provider_hosts):
            rel = file.relative_to(root).as_posix()
            if not rel.startswith("modules/ai/connectors/"):
                errors.append(f"provider protocol leaked outside ai/connectors: {rel}")

# Desktop is one reusable implementation but must ship as three OS families.
desktop_gradle = text("platforms/desktop/build.gradle.kts")
for marker in ("TargetFormat.Dmg", "TargetFormat.Msi", "TargetFormat.Exe", "TargetFormat.Deb", "TargetFormat.Rpm"):
    if marker not in desktop_gradle:
        errors.append(f"desktop packaging misses {marker}")
for script in ("build-native.sh", "build-native-windows.sh", "build-native-linux.sh"):
    require(f"platforms/desktop/packaging/{script}", f"desktop OS native bundle: {script}")

# Composition roots depend inward on shared UI/state.
android_main = text("platforms/android/src/main/kotlin/ru/vrmn/kasha/android/MainActivity.kt")
if "StudioState(" not in android_main or "StudioApp(state)" not in android_main:
    errors.append("Android shell is not composed around shared StudioState/StudioApp")

desktop_main = text("platforms/desktop/src/main/kotlin/brain/desktop/Main.kt")
if "StudioState(" not in desktop_main or "StudioApp(state)" not in desktop_main:
    errors.append("Desktop shell is not composed around shared StudioState/StudioApp")

ios_entry = text("platforms/ios/shared/src/iosMain/kotlin/brain/ios/IosEntry.kt")
if "StudioState(" not in ios_entry or "StudioApp(state)" not in ios_entry:
    errors.append("iOS shell is not composed around shared StudioState/StudioApp")

web_entry = text("platforms/web/src/wasmJsMain/kotlin/main.kt")
if "StudioState(" not in web_entry or "StudioApp(state)" not in web_entry:
    errors.append("Web shell is not composed around shared StudioState/StudioApp")

if errors:
    print("Platform shell boundary: FAILED")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)

print("Platform shell boundary: OK (modules inward; macOS/Windows/Linux/Android/iOS/Web outward)")
