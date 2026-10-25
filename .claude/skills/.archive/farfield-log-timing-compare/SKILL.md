---
name: farfield-log-timing-compare
description: Use when comparing Farfield timings across saved game logs.
category: benchmarking
---

# Farfield timings from saved game logs

Compare Farfield timings from the game logs already on disk before launching a client or adding a log line. The render stat lines come every 10 s since 2026-10-25 (every minute before, so older logs have fewer samples per window), and are off by default in user packs unless the performance logs setting is on. Always state the thread count next to each number, since sessions differ by settings.

- Logs live in the pack's `logs` folder (CurseForge: `curseforge/minecraft/Instances/<pack>/logs`, rotated `*.log.gz` plus `latest.log`) and in the dev run dirs (`fabric/runs/client/logs`, `neoforge/run/logs`).
- Read `.gz` with `zcat` and grep with `grep -a`. A loop over the folders that prints weighted ms per tile is in `references/log-compare.md`.
- Stat lines to grep: `Noise preview of ...: N tiles built in the last minute (M ms each`, `Noise preview threads: N`, `LOD renderer: ... meshing ... meshes uploaded`, `LOD CPU usage over 60 s: ... mesher ..., noise-preview ...`.
- Weight each average by the tile count, group by file date, and compare sessions with the same thread count.
- Attribute a fps drop to a pass by converting its µs per frame into a share of the frame time: at 450 fps a frame is about 2222 µs, so 175 µs is about 8 % of it. Only then say the pass explains the drop; the rest is unexplained and stays open.
- Before blaming a change for a drop, compare the old and new builds at the same viewpoint and the same settings. A spot can give identical numbers on both builds, which points at the spot, not the change.
- Grep the code and the logs for an existing stat before proposing a new log line.
