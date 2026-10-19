---
name: farfield-import-benchmark
description: Use when benchmarking farfield region import throughput.
category: benchmarking
---

# Farfield region import benchmark

Run from the repo root (Windows, Gradle wrapper):

`.\gradlew.bat :core:importBench --no-daemon -q --args="C:/Users/Lakio/farfield-corpus/<world> --threads 1,8,16 --repeat 2"`

- Corpus root: `C:\Users\Lakio\farfield-corpus\<world>`. The region dir is resolved automatically.
- Options: `--threads`, `--repeat`, `--all-status`, `--limit-regions N`, `--center X,Z`.
- The full corpus is long. Run it as a background script with a log, and wait per the background-command skill.
- Read `chunks/s imported` first, then `chunks/s read`. `skippedStatus` counts non-full chunks. `worker time per chunk` shows the dominant stage.
- Check the `JVM:` line: the task is pinned to JVM 21 by toolchain.
- Measured numbers and open items: `references/s5-results-2026-10-14.md`.
