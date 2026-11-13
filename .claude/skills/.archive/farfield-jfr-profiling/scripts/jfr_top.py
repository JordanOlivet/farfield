"""Summarize jdk.ExecutionSample events of one thread from `jfr print` output.

Usage: python jfr_top.py <jfr-print.txt> <thread substring> [frame substring ...]
Frame substrings pick the frames for the innermost and inclusive tables
(default: io.lakio.farfield).
"""
import collections
import re
import sys

if len(sys.argv) < 3:
    sys.exit(__doc__)

path, thread = sys.argv[1], sys.argv[2]
wanted = sys.argv[3:] or ["io.lakio.farfield"]
text = open(path, encoding="utf-8", errors="replace").read()

total = 0
self_counts = collections.Counter()
inner_counts = collections.Counter()
incl_counts = collections.Counter()

for event in text.split("jdk.ExecutionSample {")[1:]:
    m = re.search(r'sampledThread = "([^"]*)"', event)
    if not m or thread not in m.group(1):
        continue
    frames = re.findall(r"^\s+([\w.$<>]+)\(.*?\)?\s+line:", event, re.M)
    if not frames:
        continue
    total += 1
    self_counts[frames[0]] += 1
    for f in set(frames):
        incl_counts[f] += 1
    inner = next((f for f in frames if any(w in f for w in wanted)), None)
    if inner:
        inner_counts[inner] += 1

print(f"{total} samples from threads matching {thread!r}")
print("\n-- self time, top 15")
for f, n in self_counts.most_common(15):
    print(f"{100 * n / total:5.1f}%  {f}")
print("\n-- innermost wanted frame, top 15")
for f, n in inner_counts.most_common(15):
    print(f"{100 * n / total:5.1f}%  {f}")
print("\n-- inclusive, best match per wanted substring")
for w in wanted:
    best = max(((n, f) for f, n in incl_counts.items() if w in f), default=None)
    if best:
        print(f"{100 * best[0] / total:5.1f}%  {best[1]}")
