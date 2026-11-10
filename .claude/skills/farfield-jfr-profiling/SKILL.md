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
3. `jcmd <pid> JFR.start name=ff settings=profile`
4. Send the timed action on stdin. Wait for the log's marker that the work is done (for example `pregen spawn: done`) instead of a fixed sleep whenever the work's length varies.
5. `jcmd <pid> JFR.dump name=ff filename=<scratchpad>/jfr-<name>.jfr`
6. Send `stop` and wait for the run to exit. Sweep leftover `java.exe` processes (see Windows gotchas in CLAUDE.md).
7. Analyse with `python scripts/jfr_top.py <jfr-print.txt> <thread substring> [frame substring ...]`, where `<jfr-print.txt>` comes from `jfr print --events jdk.ExecutionSample <file.jfr> > <jfr-print.txt>`. Read the self-time and inclusive tables, not the raw events.
8. When the self table names a hot method without saying why, run `python scripts/jfr_under.py <jfr-print.txt> <thread substring> <method substring>`. It prints the share of the thread's samples under that method, its direct callees and its self frames.

Toolchain: `JAVA_HOME` may point at a JDK other than 21. Gradle needs a local JDK 21, because the foojay download fails offline. `jcmd` and `jfr` can come from any installed JDK's `bin`.
