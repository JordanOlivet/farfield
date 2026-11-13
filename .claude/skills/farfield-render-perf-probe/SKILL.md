---
name: farfield-render-perf-probe
description: Use when timing Farfield LOD renderer frames or uploads.
category: minecraft-modding
---

# Farfield render perf probe

Measure renderer costs with a `// TEMP`-marked probe plus an A/B pack run, then revert the file with `git checkout -- <file>`, confirm `grep -c TEMP` returns 0, and rebuild the clean jars before reporting.

- Read the `LOD renderer:` stat line and the `the longest LOD frame` breakdown in the pack's `latest.log` (needs `-Dfarfield.perfLogs=true`). Field meanings and the in-flight baseline: `references/stat-line-fields.md`.
- A/B: run the pack bench once per jar (HEAD vs working tree) on the same flight and teleport, minimized window, 3840x2019 override. Keep each jar and log under the scratchpad with a label per run.
- Upload backlog: wrap the `done` queue poll in `uploadMeshes()` with counters (wait per mesh, frames cut by the 3 ms budget, quads per frame). Remove them with the file checkout, not by hand.
- Before reporting, rebuild with `./gradlew.bat build --no-daemon -q -x test` and check `git status` shows no leftover changes.
