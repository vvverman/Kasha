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

# Канонический дизайн должен приходить из общего слоя, а не из платформенных скинов.
tokens_file = ROOT / "ui/KashaTokens.kt"
studio_file = ROOT / "StudioApp.kt"
brand_file = ROOT / "Brand.kt"
design_file = ROOT / "Design.kt"
web_index = REPO / "composeApp/src/wasmJsMain/resources/index.html"
macro_asset = REPO / "composeApp/src/commonMain/composeResources/drawable/buckwheat_dark_macro.png"

if not tokens_file.exists():
    violations.append("ui/KashaTokens.kt: семантические tokens из docs/design обязательны")
else:
    token_text = tokens_file.read_text(encoding="utf-8")
    for required in ("KashaDarkColors", "KashaLightColors", "desktopBreakpoint", "orbIdle", "navigationBaseHeight"):
        if required not in token_text:
            violations.append(f"ui/KashaTokens.kt: отсутствует обязательный design token {required}")

if studio_file.exists():
    studio_text = studio_file.read_text(encoding="utf-8")
    if "widthIn(max = 430.dp)" in studio_text:
        violations.append("StudioApp.kt: запрещена старая телефонная рамка 430 dp")
    for required in ("KashaMetrics.desktopBreakpoint", "KashaBottomNavigation", "KashaSidebarNavigation"):
        if required not in studio_text:
            violations.append(f"StudioApp.kt: адаптивная общая оболочка не использует {required}")

if design_file.exists() and "Commissioner" in design_file.read_text(encoding="utf-8"):
    violations.append("Design.kt: Commissioner запрещён, канонический шрифт — Geologica")

if brand_file.exists():
    brand_text = brand_file.read_text(encoding="utf-8")
    if "buckwheat_dark_macro" not in brand_text:
        violations.append("Brand.kt: тёмный splash должен использовать утверждённую макрогречку")
if not macro_asset.exists():
    violations.append("composeResources/drawable/buckwheat_dark_macro.png: splash asset отсутствует")

if web_index.exists() and "max-width:430px" in web_index.read_text(encoding="utf-8").replace(" ", ""):
    violations.append("wasm index.html: Web нельзя зажимать в max-width 430 px")

if violations:
    print("Kasha UI boundary нарушен:\n" + "\n".join(violations), file=sys.stderr)
    sys.exit(1)

print("Kasha UI + Kasha Icons + canonical design boundary: OK")
