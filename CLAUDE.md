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
.\gradlew.bat :neoforge:runServer        # dev server (run dir: neoforge\run, eula.txt required)
.\gradlew.bat :neoforge:runClient        # dev client (run dir: neoforge\run)
.\gradlew.bat :fabric:runServer          # dev server (run dir: fabric\runs\server, eula.txt required)
.\gradlew.bat :fabric:runClient          # dev client (run dir: fabric\runs\client)
```

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
  in his real install. His game logs are in
  `C:\Users\Lakio\AppData\Roaming\.minecraft\logs\latest.log`, not in the run
  dirs.
- Prove a code path ran from logs: a path that logs nothing on success cannot
  be verified, add one `info` line on the success branch.
- Scripted dev clients (window capture, chat commands, clicks) work only while
  the Windows session is unlocked and the user is not using the PC; ask first.

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
