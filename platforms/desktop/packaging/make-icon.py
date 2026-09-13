"""Генерирует macOS app icon из официального solid-логотипа Kasha."""
from pathlib import Path
from PIL import Image, ImageDraw
import subprocess

root = Path(__file__).resolve().parent
repo = root.parents[1]
source = repo / "composeApp/src/commonMain/composeResources/drawable/kasha_logo_solid.svg"
folder = root / "Kasha.iconset"
folder.mkdir(exist_ok=True)

# Геометрия знака берётся буквально из официального SVG. Для Dock меняется
# только цвет, а сам знак помещается на спокойный тёплый фон Kasha.
render_svg = root / ".kasha-logo-render.svg"
render_png = root / ".kasha-logo-render.png"
svg = source.read_text(encoding="utf-8").replace('fill="black"', 'fill="#F7F5EE"')
render_svg.write_text(svg, encoding="utf-8")
try:
    subprocess.run(
        ["rsvg-convert", "-h", "620", "-o", str(render_png), str(render_svg)],
        check=True,
    )
    logo = Image.open(render_png).convert("RGBA")

    image = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((38, 38, 986, 986), radius=218, fill="#191715")
    x = (1024 - logo.width) // 2
    y = (1024 - logo.height) // 2
    image.alpha_composite(logo, (x, y))

    for size in (16, 32, 128, 256, 512):
        image.resize((size, size), Image.Resampling.LANCZOS).save(folder / f"icon_{size}x{size}.png")
        image.resize((size * 2, size * 2), Image.Resampling.LANCZOS).save(folder / f"icon_{size}x{size}@2x.png")

    subprocess.run(["iconutil", "-c", "icns", str(folder), "-o", str(root / "Kasha.icns")], check=True)
finally:
    render_svg.unlink(missing_ok=True)
    render_png.unlink(missing_ok=True)
