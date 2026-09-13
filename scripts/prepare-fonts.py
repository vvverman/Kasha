"""Сборка ресурсов Kasha: закреплённый OFL-шрифт Commissioner и проверка всех языков интерфейса."""
import hashlib
import io
from pathlib import Path
from urllib.request import urlopen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parents[1]
GOOGLE_FONTS_COMMIT = '8e44913e4ff26fc997e6856c1ec40ff4791c98c5'
BASE = f'https://raw.githubusercontent.com/google/fonts/{GOOGLE_FONTS_COMMIT}/ofl/commissioner/'
resources = ROOT / 'modules/ui/src/commonMain/composeResources'
font_dir = resources / 'font'
license_dir = resources / 'files/licenses'
font_dir.mkdir(parents=True, exist_ok=True)
license_dir.mkdir(parents=True, exist_ok=True)

required = set('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzАБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюяІіЇїЄєҐґЎўӘәҒғҚқҢңӨөҰұҮүҺһÑñÁáÉéÍíÓóÚúÜüÇçÀàÂâÊêËëÎîÏïÔôÙùÛûŸÿŒœÄäÖöß')

def blob_sha(data: bytes) -> str:
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()

def load(url: str, expected: str) -> bytes:
    with urlopen(url, timeout=60) as response:
        data = response.read()
    assert blob_sha(data) == expected, f'Unexpected font blob for {url}'
    return data

def verify_bytes(data: bytes, name: str) -> None:
    font = TTFont(io.BytesIO(data))
    missing = sorted(ord(char) for char in required if ord(char) not in font.getBestCmap())
    assert not missing, f'{name} lacks required glyphs: {missing}'

def save_instance(variable_data: bytes, target: str, weight: int, flair: int = 0) -> None:
    source = TTFont(io.BytesIO(variable_data))
    instance = instantiateVariableFont(source, {'wght': weight, 'FLAR': flair, 'VOLM': 0, 'slnt': 0}, inplace=True)
    path = font_dir / target
    instance.save(path)
    verify_bytes(path.read_bytes(), target)

variable = load(
    BASE + 'Commissioner%5BFLAR%2CVOLM%2Cslnt%2Cwght%5D.ttf',
    '2ac22fba70bcf5d36052dfa604b43333b826996f',
)
verify_bytes(variable, 'Commissioner variable')

save_instance(variable, 'commissioner_regular.ttf', 400)
save_instance(variable, 'commissioner_medium.ttf', 500)
save_instance(variable, 'commissioner_semibold.ttf', 600)
save_instance(variable, 'commissioner_display_semibold.ttf', 600, flair=10)

license_data = load(BASE + 'OFL.txt', 'eaa7c1c436f0303948dd0d6ec51cfce14bf376e8')
(license_dir / 'Commissioner-OFL.txt').write_bytes(license_data)
print('Commissioner: 400/500/600 + display 600 FLAR=10; all 8 Kasha alphabets verified')
