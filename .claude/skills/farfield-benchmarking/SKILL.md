---
name: farfield-benchmarking
description: Use when Farfield lags, or benchmarking import throughput.
category: benchmarking
---

# Farfield benchmarking

A speed claim is a number from a log or a JMH run, taken twice in one session, and reported with the build and the world it came from. Measure before changing code, and judge a change only against the same measurement.

## Generation speed (lag, slow LOD or preview build)

"Perf" for Farfield means how fast LODs and the noise preview get built, not render FPS.

1. Order the work nearest first, then in front of the view, and reselect when the view turns. A change reorders the work and keeps the total the same (see `farfield-lod-renderer` and `farfield-noise-preview`).
2. Run the JMH benchmark at least twice before quoting a percentage: a first run can read +40 % where the second reads flat.
3. The benchmark must cover the branch you changed. `PreviewBenchmark` had no ice and no fluid case: add that case before measuring a translucent change.
4. Measurement switches (`-Dfarfield.meshInFlight`) are temporary: read the value from the ready log line, pick one, and remove the switch in the same commit.
5. Run long benchmarks in the background and read their output file later. In a `.bat` runner, a `|` inside a regex is read by cmd as a pipe: run one command per line.
6. Judge from log timings, and say which build and which world each number came from.
7. One game client at a time. Count the game JVMs before each run and abort if one exists; an overlapping series is thrown out, not corrected by hand.
8. Profile before changing code: add a timer per stage to the log line, then sample with JFR. Start it at runtime with `jcmd PID JFR.start` (the JVM pid), never through `JAVA_TOOL_OPTIONS`, which broke Gradle's JDK detection. Read with `jfr print --events jdk.ExecutionSample --stack-depth 60`, then count the top frame and the inclusive frames per thread name in a short python script. Before and after a cache change, diff the output folder with `diff -rq`: identical output is part of the proof.

## Region import throughput (`importBench`)

Run from the repo root in PowerShell with the Gradle wrapper:

`.\gradlew.bat :core:importBench --no-daemon -q --args="CORPUS_DIR/WORLD --threads 1,8,16 --repeat 2"`

- `CORPUS_DIR` is the `farfield.corpus.dir` key of `local.properties`, written out in full: Gradle does not expand it in `--args`. The region directory is resolved automatically.
- Options: `--threads`, `--repeat`, `--all-status`, `--limit-regions N`, `--center X,Z`.
- The full corpus is long: run it in the background with a log file.
- Read `chunks/s imported` first, then `chunks/s read`. `skippedStatus` counts non-full chunks; `worker time per chunk` names the dominant stage.
- Check the `JVM:` line: the task is pinned to JVM 21 by toolchain.
- Measured numbers and open items: `references/s5-results-2026-10-14.md`.
