"""Renders the two 1200x400 banners of the design canvas to PNG:

- banner-scene.png: the logo's ridges across the width, "Farfield" in the sky
  (Modrinth and CurseForge pages);
- banner-icon.png: the logo, then the name and the description (GitHub README).

    python docs/brand/render_banners.py

Fonts (OFL, in fonts/): Silkscreen Bold for the name, Outfit 500 for the rest.
Rendered at 2x, then box-averaged. The icon is rendered by render_logo.py.
"""
import subprocess
import sys
import tempfile
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
K = 2
W, H = 1200, 400
NAME = 'Farfield'
TAGLINE = 'Far-distance terrain rendering with levels of detail'
PLATFORMS = 'Minecraft 1.21.1 · NeoForge and Fabric'

# The logo's colors: sky, moon, then per ridge (cell, joint) and cell size.
SKY = '#1E2A44'
MOON = '#F3E3B5'
RIDGES = {
    'far': (64, '#3C5478', '#5E79A0', 2),
    'mid': (32, '#2F5A4C', '#4B7E69', 1.5),
    'near': (16, '#1A3A2C', '#335C48', 1),
}
SCENE = {
    'far': 'M0 400 V192 H128 V256 H192 V192 H640 V256 H704 V192 H768 V128 H896 V64 H960 V128 H1024 V192 H1200 V400 Z',
    'mid': 'M0 400 V288 H32 V256 H64 V288 H96 V256 H256 V288 H320 V256 H512 V224 H640 V256 H672 V288 H736 V320'
           ' H960 V288 H1024 V320 H1152 V288 H1184 V256 H1200 V400 Z',
    'near': 'M0 400 V352 H64 V368 H96 V352 H112 V336 H176 V352 H192 V368 H208 V352 H224 V336 H256 V352 H272 V336'
            ' H288 V320 H320 V336 H352 V352 H384 V336 H400 V352 H448 V336 H464 V352 H480 V336 H560 V352 H576 V368'
            ' H608 V352 H640 V368 H816 V352 H896 V336 H912 V352 H976 V336 H1008 V352 H1072 V336 H1088 V352 H1104'
            ' V368 H1168 V352 H1184 V368 H1200 V400 Z',
}


def font(name, size, weight=None):
    f = ImageFont.truetype(str(HERE / 'fonts' / name), size * K)
    if weight is not None:
        f.set_variation_by_axes([weight])
    return f


def text(draw, x, top, line_height, s, f, fill, spacing=0):
    """Draws s as a CSS line box of line_height px starting at top (half-leading above)."""
    ascent, descent = f.getmetrics()
    baseline = top * K + (line_height * K - (ascent + descent)) / 2 + ascent
    cx = x * K
    for ch in s:
        draw.text((cx, baseline), ch, font=f, fill=fill, anchor='ls')
        cx += f.getlength(ch) + spacing * K


def points(d):
    pts, x, y = [], 0.0, 0.0
    tokens = d.replace('M', ' M ').replace('V', ' V ').replace('H', ' H ').replace('Z', ' Z ').split()
    i = 0
    while i < len(tokens):
        t = tokens[i]
        if t == 'M':
            x, y = float(tokens[i + 1]), float(tokens[i + 2])
            i += 3
        elif t == 'V':
            y = float(tokens[i + 1])
            i += 2
        elif t == 'H':
            x = float(tokens[i + 1])
            i += 2
        else:
            i += 1
            continue
        pts.append((x * K, y * K))
    return pts


def ridge(img, d, cell, face, joint, inset):
    s = cell * K
    tile = Image.new('RGBA', (s, s), joint)
    ImageDraw.Draw(tile).rectangle([inset * K, inset * K, s - inset * K - 1, s - inset * K - 1], fill=face)
    pattern = Image.new('RGBA', img.size)
    for x in range(0, img.size[0], s):
        for y in range(0, img.size[1], s):
            pattern.paste(tile, (x, y))
    mask = Image.new('L', img.size)
    ImageDraw.Draw(mask).polygon(points(d), fill=255)
    img.paste(pattern, (0, 0), mask)


def scene():
    img = Image.new('RGBA', (W * K, H * K), SKY)
    draw = ImageDraw.Draw(img)
    draw.rectangle([1088 * K, 40 * K, 1152 * K - 1, 104 * K - 1], fill=MOON)
    for key in ('far', 'mid', 'near'):
        ridge(img, SCENE[key], *RIDGES[key])
    draw = ImageDraw.Draw(img)
    text(draw, 64, 36, 96, NAME, font('Silkscreen-Bold.ttf', 96), MOON, spacing=1)
    text(draw, 64, 36 + 96 + 12, 24 * 1.2, TAGLINE, font('Outfit[wght].ttf', 24, 500), '#D3DCEA')
    return img


def icon_banner():
    img = Image.new('RGBA', (W * K, H * K), '#16203A')
    with tempfile.TemporaryDirectory() as tmp:
        png = Path(tmp) / 'icon.png'
        subprocess.run([sys.executable, str(HERE / 'render_logo.py'), str(HERE / 'farfield-logo.svg'), str(png),
                        str(272 * K)], check=True, stdout=subprocess.DEVNULL)
        icon = Image.open(png).convert('RGBA')
        icon.load()
    img.alpha_composite(icon, (96 * K, 64 * K))
    draw = ImageDraw.Draw(img)
    # The text column, centered: 112 + 16 + 28 * 1.3 + 16 + 20 * 1.3 px tall.
    top = (H - (112 + 16 + 28 * 1.3 + 16 + 20 * 1.3)) / 2
    text(draw, 432, top, 112, NAME, font('Silkscreen-Bold.ttf', 112), MOON, spacing=1)
    top += 112 + 16
    text(draw, 432, top, 28 * 1.3, TAGLINE, font('Outfit[wght].ttf', 28, 500), '#D3DCEA')
    top += 28 * 1.3 + 16
    text(draw, 432, top, 20 * 1.3, PLATFORMS, font('Outfit[wght].ttf', 20, 500), '#9FB0C8')
    return img


for name, image in (('banner-scene.png', scene()), ('banner-icon.png', icon_banner())):
    image.convert('RGB').resize((W, H), Image.Resampling.BOX).save(HERE / name, optimize=True)
    print('saved', HERE / name)
