---
name: farfield-render-perf-probe
description: Use when timing Farfield LOD renderer frames, uploads, or an A/B bench of two pack jars (HEAD vs working tree).
category: minecraft-modding
---

# Farfield render perf probe

Measure renderer costs with a `// TEMP`-marked probe plus an A/B pack run, then revert the file with `git checkout -- <file>`, confirm `grep -c TEMP` returns 0, and rebuild the clean jars before reporting.

- Read the `LOD renderer:` stat line and the `the longest LOD frame` breakdown in the pack's `latest.log` (needs `-Dfarfield.perfLogs=true`). Field meanings and the in-flight baseline: `references/stat-line-fields.md`.
- A/B: run the pack bench once per jar (HEAD vs working tree) on the same flight and teleport, minimized window, 3840x2019 override. Keep each jar and log under the scratchpad with a label per run.
- Build the HEAD jar without `git stash`: it fails on untracked files (a new `.comp` shader). Copy the modified file to the scratchpad, `git checkout -- <file>`, build, copy the jar to `jars/head/`, copy the work file back, then `git diff --stat` must match the stat from before. Confirm the two jars differ with `sha256sum`.
- A bench runs about 7 min solo and about 15 min as an A/B. The Bash tool stops waiting at 600000 ms and moves the command to the background: put the runs in a `.sh` file, start it with `run_in_background`, and read its output file when the completion notice arrives. Never start a second copy while it runs.
- Upload backlog: wrap the `done` queue poll in `uploadMeshes()` with counters (wait per mesh, frames cut by the 3 ms budget, quads per frame). Remove them with the file checkout, not by hand.
- Before reporting, rebuild with `./gradlew.bat build --no-daemon -q -x test` and check `git status` shows no leftover changes.
