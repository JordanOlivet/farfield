"""Renders docs/brand/farfield-logo.svg to PNG: only the shapes it uses
(rect, M/V/H/Z paths, square-cell patterns, a rounded-rect clip on the
content group), 4x supersampled then box-averaged; transparent outside.

    python docs/brand/render_logo.py docs/brand/farfield-logo.svg common/src/main/resources/farfield.png 512
"""
import re
import sys
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

SRC, OUT, SIZE = sys.argv[1], sys.argv[2], int(sys.argv[3])
K = 4
N = 512 * K
NS = '{http://www.w3.org/2000/svg}'
root = ET.parse(SRC).getroot()


def rgb(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


def draw_rects(img, el, ox=0, oy=0):
    d = ImageDraw.Draw(img)
    for r in el.findall(NS + 'rect'):
        x = float(r.get('x', 0)) * K
        y = float(r.get('y', 0)) * K
        w = float(r.get('width')) * K
        h = float(r.get('height')) * K
        d.rectangle([x + ox, y + oy, x + ox + w - 1, y + oy + h - 1], fill=rgb(r.get('fill')))


patterns = {}
for p in root.iter(NS + 'pattern'):
    s = int(p.get('width')) * K
    tile = Image.new('RGBA', (s, s))
    draw_rects(tile, p)
    full = Image.new('RGBA', (N, N))
    for x in range(0, N, s):
        for y in range(0, N, s):
            full.paste(tile, (x, y))
    patterns[p.get('id')] = full

content = root.find(NS + 'g')
if content is None:
    content = root
img = Image.new('RGBA', (N, N))
draw_rects(img, content)
for path in content.findall(NS + 'path'):
    pts = []
    x = y = 0
    for cmd, val in re.findall(r'([MVHZ])\s*([-\d.]*(?:\s+[-\d.]+)?)', path.get('d')):
        if cmd == 'M':
            x, y = (float(v) for v in val.split())
        elif cmd == 'V':
            y = float(val)
        elif cmd == 'H':
            x = float(val)
        else:
            continue
        pts.append((x * K, y * K))
    mask = Image.new('L', (N, N))
    ImageDraw.Draw(mask).polygon(pts, fill=255)
    pid = re.match(r'url\(#(.+)\)', path.get('fill')).group(1)
    img.paste(patterns[pid], (0, 0), mask)

clip = root.find(f'{NS}defs/{NS}clipPath/{NS}rect')
if clip is not None:
    alpha = Image.new('L', (N, N))
    r = float(clip.get('rx', 0)) * K
    ImageDraw.Draw(alpha).rounded_rectangle([0, 0, N - 1, N - 1], radius=r, fill=255)
    img.putalpha(alpha)
# Box-average with premultiplied alpha, so the corners' edge keeps its color.
img.resize((SIZE, SIZE), Image.Resampling.BOX, reducing_gap=None).save(OUT, optimize=True)
print('saved', OUT, SIZE)
