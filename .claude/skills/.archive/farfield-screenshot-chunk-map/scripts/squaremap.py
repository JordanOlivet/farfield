"""Projects the dark pixels of a no-shader capture onto the sea plane and
compares their chunks with the chunks present in the world's region files.

usage: python squaremap.py CAPTURE WORLD_DIR OUT_PNG
Set CAM, PITCH, FOV and SEA to the camera pose and water height of the capture.
Needs Pillow.
"""
import math
import os
import struct
import sys
from PIL import Image

shot, world, out = sys.argv[1:4]
CAM = (1808.5, 151.62, 1700.5)
PITCH = math.radians(25)
FOV = math.radians(90)
SEA = 62.9

present = set()
region = os.path.join(world, 'region')
for f in os.listdir(region):
    if not f.endswith('.mca') or os.path.getsize(os.path.join(region, f)) < 8192:
        continue
    rx, rz = map(int, f[2:-4].split('.'))
    with open(os.path.join(region, f), 'rb') as fh:
        header = fh.read(4096)
    for i in range(1024):
        if struct.unpack('>I', header[i * 4:i * 4 + 4])[0] != 0:
            present.add((rx * 32 + i % 32, rz * 32 + i // 32))

im = Image.open(shot).convert('L')
w, h = im.size
px = im.load()
fy = math.tan(FOV / 2)
fx = fy * w / h
fwd = (0.0, -math.sin(PITCH), math.cos(PITCH))
up = (0.0, math.cos(PITCH), math.sin(PITCH))
right = (-1.0, 0.0, 0.0)
dark = {}
seen = {}
for y in range(int(h * 0.3), h):
    for x in range(w):
        sx = (2 * (x + 0.5) / w - 1) * fx
        sy = (1 - 2 * (y + 0.5) / h) * fy
        d = [fwd[i] + sx * right[i] + sy * up[i] for i in range(3)]
        if d[1] >= -1e-6:
            continue
        t = (SEA - CAM[1]) / d[1]
        wx, wz = CAM[0] + t * d[0], CAM[2] + t * d[2]
        c = (math.floor(wx / 16), math.floor(wz / 16))
        seen[c] = seen.get(c, 0) + 1
        if px[x, y] < 25:
            dark[c] = dark.get(c, 0) + 1

dark_chunks = {c for c, n in dark.items() if n >= 0.5 * seen[c]}
both = dark_chunks & present
print(f'chunks seen {len(seen)}, dark {len(dark_chunks)}, in region files {len(present)},'
      f' dark and in region files {len(both)}')
xs = [c[0] for c in seen]
zs = [c[1] for c in seen]
x0, x1, z0, z1 = min(xs), max(xs), min(zs), max(zs)
x0, x1 = min(x0, min(c[0] for c in present)), max(x1, max(c[0] for c in present))
z0, z1 = min(z0, min(c[1] for c in present)), max(z1, max(c[1] for c in present))
scale = 4
img = Image.new('RGB', ((x1 - x0 + 1) * scale, (z1 - z0 + 1) * scale), (255, 255, 255))
for (cx, cz) in seen:
    color = (200, 200, 255)
    if (cx, cz) in present:
        color = (120, 200, 120)
    if (cx, cz) in dark_chunks:
        color = (0, 0, 0) if (cx, cz) not in present else (255, 0, 0)
    for a in range(scale):
        for b in range(scale):
            img.putpixel(((cx - x0) * scale + a, (cz - z0) * scale + b), color)
for (cx, cz) in present - set(seen):
    for a in range(scale):
        for b in range(scale):
            img.putpixel(((cx - x0) * scale + a, (cz - z0) * scale + b), (180, 180, 180))
img.save(out)
print('x', x0 * 16, x1 * 16, 'z', z0 * 16, z1 * 16)
cells = {}
for (cx, cz) in dark_chunks - present:
    k = (cx >> 2, cz >> 2)  # level 1 sections: 64 blocks, 4 chunks
    cells[k] = cells.get(k, 0) + 1
top = sorted(cells.items(), key=lambda e: -e[1])[:12]
print('level-1 cells (x, z) with dark preview chunks:', top)
