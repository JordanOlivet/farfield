---
name: farfield-storage-benchmark
description: Use when benchmarking LOD storage, LogStore or LMDB.
category: benchmarking
---

# Farfield storage benchmark (spike S1)

Run from repo root:

`.\gradlew.bat :core:storageBench --no-daemon -q --args="<farfield.corpus.dir>/<world> --threads 14 [--containers] [--limit-regions 3]"`

- Smoke with `--limit-regions 3` first, then the full corpus as a background script with a log.
- Crash test: `--args="<world> --crash-test 20"`. Kills the writer process mid-write. Pass only if every durable record reads back intact. A process kill is not a power loss; claim only that.
- Read the `total (all levels)` table: compare `codec+zstd3` and `hidden+face, codec` against `dense+zstd3`. Check round-trip count before trusting any size.
- Keep the `--add-opens` jvmArgs and the JVM 21 toolchain on the task (see gradle-javaexec-toolchain).
- Findings so far and open items: `references/s1-results-2026-10-14.md`.
