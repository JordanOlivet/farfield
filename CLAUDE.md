# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in this repository.

## What this is

**Farfield**, a far-distance terrain rendering mod for Minecraft using levels
of detail (LOD). MC 1.21.1 first (Java 21), NeoForge + Fabric from one
codebase (MultiLoader pattern, no Architectury), Minecraft 26.x / Vulkan
planned. Private repository until the mod is presentable (ADR-002, ADR-003).

It replaces our Neo-Voxy fork of Voxy. Reuse Neo-Voxy *concepts*, never copy
its code as is, and never port Voxy-derived code (ADR-001).

Read first, in order: the latest `docs/progress/YYYY-MM-DD.md` (session resume
point), `docs/DECISIONS.md` (ADRs, binding), the active plan in `docs/plans/`,
`docs/VISION.md`. Background and state of the art:
`docs/analysis/lod-engine-state-of-the-art.md`.

## Commands

```powershell
.\gradlew.bat build --no-daemon          # all projects, core unit tests, one jar per loader
.\gradlew.bat :core:test                 # engine unit tests only
.\gradlew.bat :neoforge:runServer        # dev server (run dir: neoforge\run)
.\gradlew.bat :neoforge:runClient        # dev client (run dir: neoforge\run)
.\gradlew.bat :fabric:runServer          # dev server (run dir: fabric\runs\server)
.\gradlew.bat :fabric:runClient          # dev client (run dir: fabric\runs\client)
.\gradlew.bat runVanillaServer           # vanilla server for client mode checks (runs\vanilla-server)
.\gradlew.bat copyTestJars               # both loader jars into farfield.testJars.dir, for pack tests
```

Double-click launchers at the root (they open the test world `farfield-test`
directly and remove a stale `session.lock` first): `client-fabric.bat`,
`client-neoforge.bat`, and `clients.bat` for both at once (builds once, then
one window per client). Extra arguments go to Gradle.

## Machine setup (ADR-042)

- Copy `local.properties.example` to `local.properties` (gitignored) and set
  this machine's paths. Anything machine-specific goes there, as a key;
  never write a path outside the repository into a tracked file, cite the
  key instead (`<farfield.corpus.dir>/<world>`).
- Run dirs are gitignored and set up from `dev/`: `runClient`, `runServer`
  and `runVanillaServer` first copy the files missing from the run dir
  (`eula.txt`, `server.properties`, `ops.json`, `options.txt`, the test
  world), and never overwrite one. The test world, its seed and the places
  worth a look: `docs/test-worlds.md`. To reset a run dir file or the test
  world, delete it; the next run copies it again.
- Project skills are in `.claude/skills/` (tracked); autoharness counters
  (`.ledger.jsonl`, `.sidecar.json`) stay local.

Releases (ADR-031): push a tag `v<version from gradle.properties>[-alpha.N]`,
e.g. `git tag v0.1.0-alpha.2 && git push origin v0.1.0-alpha.2`;
`.github/workflows/release.yml` publishes both jars to Modrinth, CurseForge
and a GitHub Release.

Shippable jars: `neoforge/build/libs/farfield-neoforge-1.21.1-<version>.jar`
and `fabric/build/libs/farfield-fabric-1.21.1-<version>.jar`.

## Architecture (ADR-006)

| Project | Content | May import Minecraft |
|---|---|---|
| `api` | Public API surface (internal for now) | No |
| `core` | The engine: data model, storage, meshing, LOD selection, jobs, protocol | No |
| `common` | Minecraft-facing code compiled against vanilla (NeoForm + Parchment) | Vanilla only |
| `neoforge`, `fabric` | Entry points, platform services, loader events | Yes |

- `api` and `core` never import `net.minecraft`, `com.mojang`,
  `net.neoforged` or `net.fabricmc`. Everything testable lives there.
- `common` never imports loader APIs. Loader-specific behavior goes through
  `ServiceLoader` services in `common/.../platform/services/`, implemented in
  each loader and declared in `META-INF/services/<fully qualified interface>`.
- Loader jars are compiled from the sources of `api` + `core` + `common` + the
  loader project (`buildSrc/.../multiloader-loader.gradle`).
- Rendering (ADR-007): GPU-driven, OpenGL 4.6 first behind an RHI; Vulkan for
  26.x later. Data model (ADR-008): 3D paletted bricks with a mip pyramid.

## Conventions (ADR-015)

- Everything in git and on GitHub is in English: code, comments, docs, this
  file, commits, PRs. Talk to the user in French.
- ADRs in `docs/DECISIONS.md`: append a new entry to change a decision, never
  rewrite history.
- Plans in `docs/plans/` as checklists with item IDs; tick `[x]` only for what
  was verified, `[~]` names what remains, `[-]` gives the reason.
- **Progress log (mandatory)**: one `docs/progress/YYYY-MM-DD.md` per working
  day (complete it if it already exists), written before the final commit and
  committed with the work: commits, what was done, what was verified and what
  was not, prioritized next steps.
- Conventional Commits; add `Co-Authored-By: Claude <model> <noreply@anthropic.com>`
  when Claude wrote the change.
- Bootstrap phase: direct pushes to `main`. Once the user declares the base
  done: branches + PRs (the repository squash-merges; rebase stacked branches
  onto `main` with `--onto` after each squash).
- CI: `.github/workflows/build.yml` calls `JordanOlivet/ci-workflows`.

## Testing

- `core` logic: JUnit 5 unit tests and, for hot paths, JMH benchmarks.
- Minecraft side: dev servers/clients boot with no error, then the user tests
  in his real install. His game log is `latest.log` in the
  `farfield.gameLogs.dir` folder of `local.properties`, not in the run dirs.
- Prove a code path ran from logs: a path that logs nothing on success cannot
  be verified, add one `info` line on the success branch.
- Dev servers read console commands from stdin, so a check can be scripted:
  `(sleep 90; echo "farfield import"; sleep 20; echo stop) | ./gradlew.bat :neoforge:runServer --no-daemon --console plain > log`
  (Git Bash). Stopping with `stop` closes the LOD stores and logs their
  counters.
- Client mode (E.6) is checked against the vanilla 1.21.1 server of
  `runVanillaServer` (downloaded from Mojang's piston-meta manifest, JDK 21,
  `online-mode=false`, `Dev` operator, the test world; reads stdin like the
  dev servers): `runClient -PquickPlayServer=localhost:25565
  -PclientUsername=Dev` joins it with a stable offline name. Use spectator
  mode before `/tp` high up, or the player falls.
- Scripted dev clients (window capture, chat commands, clicks) work only while
  the Windows session is unlocked and the user is not using the PC; ask first.
  `-PquickPlay=<save>` opens a world directly (`runClient`, both loaders);
  build its LODs beforehand with the dev server pointed at the save
  (`level-name=saves/<save>` in `server.properties`, restore it after). Capture
  the window with `PrintWindow` (flag 2) from a DPI-aware process: it works
  while other windows cover the game.

## Windows gotchas

- A backgrounded or killed Gradle run orphans the game JVM. Sweep leftovers:
  `Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -match 'farfield' }`
  (exclude Gradle daemons).
- Run-dir config files (`eula.txt`, `server.properties`) must be ASCII without
  BOM: a PowerShell `>` redirect writes a UTF-8 BOM and the server ignores the
  first key.
- Never round-trip source files through Windows PowerShell 5.1 text cmdlets
  (`Get-Content -Raw` without `-Encoding` mangles UTF-8, `Set-Content -Encoding utf8`
  writes a BOM javac rejects). Use the edit tools or Python.
- Git Bash rewrites `branch:path` arguments (`git show main:docs/x.md` fails
  with "ambiguous argument"): prefix with `MSYS_NO_PATHCONV=1`.
- A dev client started while the session is locked never renders past the
  loading screen.
