---
name: farfield-log-timing-compare
description: Use when comparing Farfield timings across saved game logs.
category: benchmarking
---

# Farfield timings from saved game logs

Compare Farfield timings from the game logs already on disk before launching a client or adding a log line. Each stat line is printed every minute; always state the thread count next to each number, since sessions differ by settings.

- Logs live in the pack's `logs` folder (CurseForge: `curseforge/minecraft/Instances/<pack>/logs`, rotated `*.log.gz` plus `latest.log`) and in the dev run dirs (`fabric/runs/client/logs`, `neoforge/run/logs`).
- Read `.gz` with `zcat` and grep with `grep -a`. A loop over the folders that prints weighted ms per tile is in `references/log-compare.md`.
- Stat lines to grep: `Noise preview of ...: N tiles built in the last minute (M ms each`, `Noise preview threads: N`, `LOD renderer: ... meshing ... meshes uploaded`, `LOD CPU usage over 60 s: ... mesher ..., noise-preview ...`.
- Weight each average by the tile count, group by file date, and compare sessions with the same thread count.
- Grep the code and the logs for an existing stat before proposing a new log line.
