#!/usr/bin/env python3
"""Не даёт продуктовым экранам обходить Kasha UI или возвращать чужие icon packs."""
from pathlib import Path
import re
import sys

REPO = Path(__file__).resolve().parents[1]
ROOT = REPO / "composeApp/src/commonMain/kotlin/brain/studio"
ALLOWED_CONTROLS = {(ROOT / "ui/KashaUi.kt").resolve(), (ROOT / "ui/KashaNoteText.kt").resolve()}
CONTROL_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_])(?:Button|IconButton|TextField|OutlinedTextField|Switch|Checkbox|RadioButton|Slider|RangeSlider|BasicTextField)\s*\("
)
FORBIDDEN_ICON_PATTERNS = (
    "androidx.compose.material.icons",
    "Icons.Default",
    "Icons.Filled",
    "Icons.Outlined",
    "lucide",
    "SF Symbols",
    "SFSymbol",
    "PhosphorFillPaths",
    "KashaPhosphor",
)

violations = []
for path in ROOT.rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    if path.resolve() not in ALLOWED_CONTROLS:
        for number, line in enumerate(text.splitlines(), 1):
            if CONTROL_PATTERN.search(line):
                violations.append(f"{path.relative_to(ROOT.parents[4])}:{number}: базовый контрол вне Kasha UI: {line.strip()}")
    for number, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith(("//", "/*", "*", "*/")):
            continue
        if any(pattern.lower() in line.lower() for pattern in FORBIDDEN_ICON_PATTERNS):
            violations.append(f"{path.relative_to(ROOT.parents[4])}:{number}: запрещённый источник иконок: {line.strip()}")

legacy = list(ROOT.rglob("*BrainUi*")) + list(ROOT.rglob("*BrainNavigation*"))
for path in legacy:
    violations.append(f"{path.relative_to(ROOT.parents[4])}: legacy Brain UI должен быть удалён")

icons_file = ROOT / "ui/KashaIcons.kt"
if not icons_file.exists():
    violations.append("ui/KashaIcons.kt: единый Kasha Icons слой отсутствует")
else:
    icon_text = icons_file.read_text(encoding="utf-8")
    if "enum class Glyph" not in icon_text or "private fun motion" not in icon_text:
        violations.append("ui/KashaIcons.kt: собственная геометрия и motion Kasha должны находиться в одном слое")

if (ROOT / "ui/PhosphorFillPaths.kt").exists():
    violations.append("ui/PhosphorFillPaths.kt: legacy Phosphor должен быть удалён")

studio_file = ROOT / "StudioApp.kt"
if not studio_file.exists():
    violations.append("StudioApp.kt: общая оболочка Kasha отсутствует")
else:
    studio = studio_file.read_text(encoding="utf-8")
    adaptive_contract = {
        "WindowInsets.safeDrawing": "корневой shell обязан учитывать safe area",
        "1024.dp": "desktop breakpoint 1024dp отсутствует",
        "1440.dp": "максимальная ширина общей desktop-композиции отсутствует",
        "KashaNavigationLayout.Sidebar": "desktop sidebar отсутствует",
        "KashaNavigationSurface": "единая nav surface отсутствует",
    }
    for token, message in adaptive_contract.items():
        if token not in studio:
            violations.append(f"StudioApp.kt: {message}")
    if "widthIn(max = 430.dp)" in studio or "width(430.dp)" in studio:
        violations.append("StudioApp.kt: запрещён legacy phone clamp 430dp")

web_index = REPO / "composeApp/src/wasmJsMain/resources/index.html"
if web_index.exists():
    html = web_index.read_text(encoding="utf-8").replace(" ", "").lower()
    if "max-width:430px" in html:
        violations.append("wasm index.html: запрещён legacy phone clamp 430px")

if violations:
    print("Kasha UI boundary нарушен:\n" + "\n".join(violations), file=sys.stderr)
    sys.exit(1)

print("Kasha UI + Kasha Icons + adaptive shell boundary: OK")
