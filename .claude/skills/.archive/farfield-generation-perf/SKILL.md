---
name: farfield-generation-perf
description: Use when Farfield lags or LOD/preview generation is slow.
category: benchmarking
---

# Farfield generation performance

When the user says "perf" for Farfield, he means how fast LODs and the seed (noise) preview get built, not render FPS. Measure generation first; render cost is a side note.

1. Order the work nearest first, then in front of the view, and reselect when the view turns. A change reorders the work and keeps the total the same (see `farfield-lod-renderer`, `farfield-noise-preview`).
2. Before quoting any percentage, run the JMH benchmark at least twice in the same session. A first run can read +40 % where the second reads flat: the first is cold-start noise.
3. The benchmark must cover the branch you changed. `PreviewBenchmark` had no ice and no fluid case, so add that case before measuring a translucent change.
4. Measurement switches (`-Dfarfield.meshInFlight`) are temporary: read the value from the ready log line, pick one, and remove the switch in the same commit.
5. Run long benchmarks in the background and read their output file later. In a `.bat` runner, a `|` inside a regex is read by cmd as a pipe: run one command per line.
6. Judge from log timings, and say which build and which world each number came from.
7. One game client at a time. Two clients on one world share the CPU and fight over the world, so their timings are invalid. Count the game JVMs before each run and abort if one exists. A series with an overlapping run gets thrown out, not corrected by hand.
8. Profile before changing code. Add a timer per stage to the log line, then sample with JFR: start it at runtime with `jcmd <pid> JFR.start` (the JVM pid), never through `JAVA_TOOL_OPTIONS`, which broke Gradle's JDK detection. Read the samples with `jfr print --events jdk.ExecutionSample --stack-depth 60`, then count the top frame and the inclusive frames per thread name (for example `noise-preview`) in a short python script. A vanilla noise function at the top of the samples usually means recomputation that vanilla's cache markers would avoid (see `farfield-noise-preview`). Before and after a cache change, diff the output folder with `diff -rq`: identical output is part of the proof.
