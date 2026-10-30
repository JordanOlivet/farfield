"""For each far band of a frame probe session, its chunks on the sea plane and
the renderer's notes touching them (ADR-111): those of the frame the band
appeared in, then those of the frame it went.

    python dev/agent/band-keys.py <frames session dir> [bands, default 8]

A note reads `[+-]` (put in or out of the draw list) or `looks` (new looks),
`R` real or `P` preview, the level, then the section [x,y,z]; `select` and
`swap...` notes say the selection ran or a mesh was swapped in that frame.
"""
import json
import math
import re
import sys

# The probe's capture size (FrameProbe), and the sea plane the view is projected on.
W, H = 427, 240
SEA = 63
FAR = 70


def world(px, py, cam):
    cx, cy, cz, yaw, pitch = cam
    ndx = 2 * (px + 0.5) / W - 1
    ndy = 1 - 2 * (py + 0.5) / H
    r = (ndx * W / H, ndy, 1.0)
    p = math.radians(pitch)
    yw = math.radians(yaw)
    fy = r[1] * math.cos(p) - r[2] * math.sin(p)
    fz = r[1] * math.sin(p) + r[2] * math.cos(p)
    dx = -math.sin(yw) * fz - math.cos(yw) * r[0]
    dz = math.cos(yw) * fz - math.sin(yw) * r[0]
    if fy >= 0:
        return None
    s = (cy - SEA) / (-fy)
    return cx + dx * s, cz + dz * s


NOTE = re.compile(r'([+-]|looks)?([RP])(\d)\[(-?\d+),(-?\d+),(-?\d+)\](\S*)')


def touches(note, cx0, cx1, cz0, cz1):
    m = NOTE.fullmatch(note)
    if not m:
        return False
    side = 2 << int(m.group(3))
    x, z = int(m.group(4)), int(m.group(6))
    return x * side <= cx1 and (x + 1) * side - 1 >= cx0 and z * side <= cz1 and (z + 1) * side - 1 >= cz0


d = sys.argv[1]
limit = int(sys.argv[2]) if len(sys.argv) > 2 else 8
events = [json.loads(line) for line in open(d + '/events.jsonl')]
n = 0
for e in events:
    if not e['kind'].startswith('lods-'):
        continue
    x0, y0, x1, y1 = map(int, e['box'].split(','))
    if y1 - y0 < 3:
        continue
    cam = list(map(float, e['camera'].split()))
    a = world(x0, y1, cam)
    b = world(x1, y0, cam)
    if not a or not b or abs(b[1] - cam[2]) < FAR:
        continue
    xs = sorted([a[0], b[0]])
    zs = sorted([a[1], b[1]])
    chunks = (int(xs[0]) >> 4, int(xs[1]) >> 4, int(zs[0]) >> 4, int(zs[1]) >> 4)
    print(e['frame'], e['kind'], 'chunks x%d..%d z%d..%d' % chunks, 'camera z %.0f' % cam[2])
    for label, field in (('  in ', 'putIn'), ('  out', 'putInAfter')):
        notes = [x for x in e.get(field, '').split() if touches(x, *chunks)]
        other = [x for x in e.get(field, '').split() if x == 'select' or x.startswith('swap')]
        print(label, ' '.join(notes)[:400], '|', ' '.join(other))
    n += 1
    if n >= limit:
        break
