---
name: farfield-jfr-profiling
description: Use when profiling a Farfield dev server with JFR or jcmd.
category: debugging
---

# Farfield JFR profiling

Attach Flight Recorder to the already-running dev server with `jcmd`. Never export `JAVA_TOOL_OPTIONS` for a Gradle run.

Why: `JAVA_TOOL_OPTIONS` prints `Picked up JAVA_TOOL_OPTIONS` into the JVM output, and Gradle's toolchain probe then fails with `Cannot find a Java installation ... languageVersion=21` before the server starts (`BUILD FAILED in 19s`).

Steps:
1. Write the feed script (server stdin commands plus the `jcmd` calls) to the scratchpad as a `.sh` file. Run it with `bash <file>.sh`, not as a compound one-liner. Pipe it into `./gradlew.bat :neoforge:runServer --no-daemon --console plain > "$LOG" 2>&1`.
2. After the server is up, run `jcmd -l` to list JVMs. Pick the game JVM by its main class (`devlaunch`, `neoforge`, `minecraft`). Skip `GradleDaemon` and `GradleWrapperMain`.
   Pick the PID with a helper `.ps1` file (Write it to the scratchpad, call `powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w <file>)"`), never an inline PowerShell `Get-CimInstance` inside a bash double-quoted string: the `$_` and escaped quotes break the bash parse. The helper matches the command line on `farfield`, drops the Gradle processes and keeps the largest `java.exe` by `WorkingSetSize`. A PID that matches nothing makes `jcmd` fail with `Could not find any processes matching`, and the run's JFR file is then missing or wrong: check the PID is the game's before the timed actions start.
3. `jcmd <pid> JFR.start name=ff settings=profile`
4. Send the timed action on stdin. Wait for the log's marker that the work is done (for example `pregen spawn: done`) instead of a fixed sleep whenever the work's length varies.
5. `jcmd <pid> JFR.dump name=ff filename=<scratchpad>/jfr-<name>.jfr`
6. Send `stop` and wait for the run to exit. Sweep leftover `java.exe` processes (see Windows gotchas in CLAUDE.md).
7. Analyse with `python .claude/skills/farfield-jfr-profiling/scripts/jfr_top.py <jfr-print.txt> <thread substring> [frame substring ...]`, where `<jfr-print.txt>` comes from `jfr print --events jdk.ExecutionSample <file.jfr> > <jfr-print.txt>`. Read the self-time and inclusive tables, not the raw events.
8. When the self table names a hot method without saying why, run `python .claude/skills/farfield-jfr-profiling/scripts/jfr_under.py <jfr-print.txt> <thread substring> <method substring>`. It prints the share of the thread's samples under that method, its direct callees and its self frames.

9. GC pauses and allocation, from any `.jfr` (a dev run or a pack run in production). Do not judge a GC change from one number. Count pauses from the flight only: wait for the prod log to echo the flight's first `tp` line (after the launch's mtime) before the counted window starts, and never let a launch's world load into the count.
   - Pauses: `jfr print --events jdk.GarbageCollection <file.jfr>`, then read each event's `name` and `longestPause`. Split the young collections (`G1New`) from the rest and report count, mean, max and the count over 15 ms, for each file.
   - Allocation: `jfr print --events jdk.ObjectAllocationSample --stack-depth 30 <file.jfr>`, then sum `weight` by `eventThread` (which subsystem allocates: mesher, noise preview, render thread, worker) and by `objectClass` with its top frames.
   - A fix that removes one class's boxing can shrink that class's share while total GB stays flat and the young-GC count stays the same. Judge by total allocation and GC count per minute, not by a single class.
   - A GC A/B (for example G1 against `-XX:+UseZGC -XX:+ZGenerational`) runs on the same route and session, through the runner's `--jvm-arg=` lines. Production pack runs take `-Xmx8G` with G1 by default, as the user's instance does.
   - A/B of an allocation or GC fix: build the baseline jar from HEAD in a temporary worktree (`git worktree add <scratchpad>/wt-base HEAD`, copy `local.properties` in, `./gradlew.bat :fabric:build --no-daemon -x test`), keep it under `jars/base/`, and point a copy of the runner script's `JAR=` line at it. Confirm the two jars' hashes differ. Run both sides back to back on the same flight, the same window size and the same JVM arguments. Never compare against a run from another session or another window size: a 4K run set against minimized runs showed a false gain.
   - Judge a fix by total allocation GB per minute, Farfield's share of it (weight of `jdk.ObjectAllocationSample` events whose stack holds `io.lakio.farfield`), and the GC count. Also compare the mesh job total (`LOD meshing:` lines): a lower allocation with fewer jobs is a smaller workload, not a fix.
   - Spike fixes: the stat line's `the longest LOD frame` split (uploads, grown, quads, blends) names the step that owns each worst frame. Presizing a buffer to the last session's size removed only part of the first-use growth: at 16,384 slots a 43 ms growth remained, so another buffer likely grew. Read each buffer's own growth line before presizing the next, and judge on the same route with the same window.
   - Allocation fixes that held: a per-section work array made on first use (a getter that allocates when null), and a `ThreadLocal` map per mesher thread that is `clear()`ed per build instead of a new map per build.

Toolchain: `JAVA_HOME` may point at a JDK other than 21. Gradle needs a local JDK 21, because the foojay download fails offline. `jcmd` and `jfr` can come from any installed JDK's `bin`.
