"""Сборка ресурсов Kasha: один закреплённый Geologica Variable и проверка языков."""

import hashlib
import io
from pathlib import Path
from urllib.request import urlopen

from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
GOOGLE_FONTS_COMMIT = "685f38d7c9e86b0c8530204c97ddcaf6558dd17b"
BASE = f"https://raw.githubusercontent.com/googlefonts/geologica/{GOOGLE_FONTS_COMMIT}/"
FONT_URL = BASE + "fonts/variable/Geologica%5BCRSV%2CSHRP%2Cslnt%2Cwght%5D.ttf"
LICENSE_URL = BASE + "OFL.txt"
FONT_BLOB_SHA = "9e7771e32575873bba48b16b6ef1696b63087a2a"
LICENSE_BLOB_SHA = "ebe73cf731875468cbc35c1d8857ac037328918f"
FONT_SHA256 = "9124d9e88ac6c11d761f35241713a51d68e2c4ebedce0edaca834717a00959ec"
LICENSE_SHA256 = "778186245840aea0e60bec6a46e7fb1442e0cd78e41afeadffcd3e8824b379e0"

resources = ROOT / "composeApp/src/commonMain/composeResources"
font_dir = resources / "font"
license_dir = resources / "files/licenses"
font_dir.mkdir(parents=True, exist_ok=True)
license_dir.mkdir(parents=True, exist_ok=True)

required = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюяІіЇїЄєҐґЎўӘәҒғҚқҢңӨөҰұҮүҺһÑñÁáÉéÍíÓóÚúÜüÇçÀàÂâÊêËëÎîÏïÔôÙùÛûŸÿŒœÄäÖöß")


def blob_sha(data: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def load(url: str, expected_blob: str, expected_sha256: str) -> bytes:
    with urlopen(url, timeout=60) as response:
        data = response.read()
    assert blob_sha(data) == expected_blob, f"Unexpected Git blob for {url}"
    assert hashlib.sha256(data).hexdigest() == expected_sha256, f"Unexpected SHA-256 for {url}"
    return data


font_data = load(FONT_URL, FONT_BLOB_SHA, FONT_SHA256)
font = TTFont(io.BytesIO(font_data))
missing = sorted(ord(char) for char in required if ord(char) not in font.getBestCmap())
assert not missing, f"Geologica lacks required glyphs: {missing}"

axes = {axis.axisTag: (axis.minValue, axis.defaultValue, axis.maxValue) for axis in font["fvar"].axes}
assert axes == {
    "wght": (100.0, 100.0, 900.0),
    "CRSV": (0.0, 0.0, 1.0),
    "SHRP": (0.0, 0.0, 100.0),
    "slnt": (-12.0, 0.0, 0.0),
}, axes

(font_dir / "geologica_variable.ttf").write_bytes(font_data)
license_data = load(LICENSE_URL, LICENSE_BLOB_SHA, LICENSE_SHA256)
(license_dir / "Geologica-OFL.txt").write_bytes(license_data)
print("Geologica Variable: wght/CRSV/SHRP/slnt; all 8 Kasha alphabets verified")
