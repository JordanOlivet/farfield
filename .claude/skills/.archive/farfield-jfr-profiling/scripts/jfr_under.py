"""Callees and self frames under one method, for one thread. Usage: jfr_under.py <print output> <thread> <method substring>"""
import collections, re, sys

path, thread, key = sys.argv[1:4]
text = open(path, encoding="utf-8", errors="replace").read()
callee = collections.Counter()
selfc = collections.Counter()
total = 0
hit = 0
for e in text.split("jdk.ExecutionSample {")[1:]:
    m = re.search(r'sampledThread = "([^"]*)"', e)
    if not m or thread not in m.group(1):
        continue
    frames = re.findall(r"^\s+([\w.$<>]+)\(.*?\)?\s+line:\s*(\d+)", e, re.M)
    if not frames:
        continue
    total += 1
    idx = next((i for i, f in enumerate(frames) if key in f[0]), None)
    if idx is None:
        continue
    hit += 1
    callee[(frames[idx - 1][0] if idx > 0 else "(self) line " + frames[idx][1])] += 1
    selfc[frames[0][0]] += 1
print(f"{hit} of {total} samples under {key} ({100*hit/total:.1f}%)")
print("-- direct callees")
for f, n in callee.most_common(14):
    print(f"{100*n/total:5.1f}%  {f}")
print("-- self frames")
for f, n in selfc.most_common(10):
    print(f"{100*n/total:5.1f}%  {f}")
