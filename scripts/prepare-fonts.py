"""Подготавливает канонический Geologica Variable для общего Kasha UI и проверяет покрытие языков."""
import hashlib
import io
from pathlib import Path

from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "docs/design/assets/fonts"
SOURCE_FONT = SOURCE_DIR / "Geologica-Variable.ttf"
SOURCE_LICENSE = SOURCE_DIR / "OFL.txt"
resources = ROOT / "composeApp/src/commonMain/composeResources"
font_dir = resources / "font"
license_dir = resources / "files/licenses"
font_dir.mkdir(parents=True, exist_ok=True)
license_dir.mkdir(parents=True, exist_ok=True)

TARGET_FONT = font_dir / "geologica_variable.ttf"
TARGET_LICENSE = license_dir / "Geologica-OFL.txt"
EXPECTED_FONT_SHA256 = "9124d9e88ac6c11d761f35241713a51d68e2c4ebedce0edaca834717a00959ec"
EXPECTED_LICENSE_SHA256 = "778186245840aea0e60bec6a46e7fb1442e0cd78e41afeadffcd3e8824b379e0"

required = set(
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
    "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
    "ІіЇїЄєҐґЎўӘәҒғҚқҢңӨөҰұҮүҺһ"
    "ÑñÁáÉéÍíÓóÚúÜüÇçÀàÂâÊêËëÎîÏïÔôÙùÛûŸÿŒœÄäÖöß"
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_bytes(data: bytes, name: str) -> None:
    font = TTFont(io.BytesIO(data))
    missing = sorted(ord(char) for char in required if ord(char) not in font.getBestCmap())
    assert not missing, f"{name} lacks required glyphs: {missing}"
    axes = {axis.axisTag for axis in font["fvar"].axes}
    expected_axes = {"wght", "CRSV", "SHRP", "slnt"}
    assert expected_axes.issubset(axes), f"{name} lacks variable axes: {sorted(expected_axes - axes)}"


font_data = SOURCE_FONT.read_bytes()
license_data = SOURCE_LICENSE.read_bytes()
assert sha256(font_data) == EXPECTED_FONT_SHA256, "Unexpected Geologica binary"
assert sha256(license_data) == EXPECTED_LICENSE_SHA256, "Unexpected Geologica OFL license"
verify_bytes(font_data, "Geologica Variable")

TARGET_FONT.write_bytes(font_data)
TARGET_LICENSE.write_bytes(license_data)

print("Geologica Variable: local pinned asset, wght/CRSV/SHRP/slnt axes and all Kasha alphabets verified")
