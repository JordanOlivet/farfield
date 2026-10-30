"""Counts the flashes of a frame probe session (ADR-111).

    python dev/agent/score-flashes.py <frames session dir>...

Per session: far bands (LOD-layer flashes 4 rows high or more, more than 70
blocks away on the sea plane) and all LOD-layer flashes; then, of the far
bands whose two dumps exist, how many the flagged frame shows lighter than
the frame before in the box (the bands at the generation front, ADR-100)
and how many darker (holes at the game's render edge). The lighter split
needs Pillow; without it only the counts print.
"""
import collections
import json
import math
import os
import sys

# The probe's capture size (FrameProbe), and the sea plane the distances are measured on.
H = 240
SEA = 63
FAR = 70


def dist(e):
    x0, y0, x1, y1 = map(int, e['box'].split(','))
    cam = e['camera'].split()
    a = math.radians(float(cam[4])) - math.atan(1 - 2 * ((y0 + y1) / 2 + 0.5) / H)
    return (float(cam[1]) - SEA) / math.tan(a) if a > 0 else 9999


def far_band(e):
    x0, y0, x1, y1 = map(int, e['box'].split(','))
    return y1 - y0 >= 3 and dist(e) > FAR


def lum(path, box):
    from PIL import Image
    im = Image.open(path).convert('RGB').crop(box)
    px = list(im.get_flattened_data()) if hasattr(im, 'get_flattened_data') else list(im.getdata())
    return sum(0.2126 * r + 0.7152 * g + 0.0722 * b for r, g, b in px) / max(1, len(px))


def split(d, bands):
    lighter = darker = unwritten = 0
    for e in bands:
        name = '%06d-%s' % (e['frame'], e['kind'])
        before = os.path.join(d, name + '-lods-0.png')
        flagged = os.path.join(d, name + '-lods-1.png')
        if not (os.path.exists(before) and os.path.exists(flagged)):
            unwritten += 1
            continue
        x0, y0, x1, y1 = map(int, e['box'].split(','))
        box = (x0, y0, x1 + 1, y1 + 1)
        if lum(flagged, box) > lum(before, box):
            lighter += 1
        else:
            darker += 1
    return 'lighter %d, darker %d, not dumped %d' % (lighter, darker, unwritten)


for d in sys.argv[1:]:
    events = [json.loads(line) for line in open(os.path.join(d, 'events.jsonl'))]
    kinds = collections.Counter(e['kind'] for e in events)
    lods = [e for e in events if e['kind'].startswith('lods-')]
    bands = [e for e in lods if far_band(e)]
    far = [e for e in lods if dist(e) > FAR]
    ends = sum(kinds[k] for k in ('flash1', 'flash2'))
    try:
        light = split(d, bands)
    except ImportError:
        light = 'no Pillow'
    print('%s: far bands %d (%s), LOD flashes %d, far %d, render-edge flashes %d, columns %d'
          % (os.path.basename(d.rstrip('/\\')), len(bands), light, len(lods), len(far), ends, kinds['column']))
