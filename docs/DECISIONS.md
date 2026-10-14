# Architecture Decision Records

Lightweight ADR log. One entry per structural decision; newest at the bottom.
Decisions here are **binding**: revisit one only with a new entry that names
the one it replaces (and set the old entry's status to `superseded by
ADR-NNN`), never by rewriting history.

Status values: `proposed` (written, awaiting the user's call) · `accepted` ·
`superseded by ADR-NNN`.

Background for most entries: `docs/analysis/lod-engine-state-of-the-art.md`
(the analysis and Q&A of 2026-10-14).

## ADR-001 — A fresh project, and what we may reuse

**Date**: 2026-10-14 · **Status**: accepted

Farfield is a new LOD mod in a new repository, replacing our Neo-Voxy fork of
Voxy. Voxy is "All rights reserved, do not redistribute", so a fork can never
be published.

- Neo-Voxy was never published, and much of it is our own design (server
  streaming with cache epochs, bloom filters and congestion control, debug
  tooling, the multi-loader layer). Those **concepts are reused freely**.
- No code is copied as is from Neo-Voxy. Parts that came from Voxy are
  rewritten from their observed behavior, never ported.
- Distant Horizons (LGPL-3.0), Iris (LGPL-3.0), Sodium (PolyForm Shield) and
  research papers are read for ideas; copying their code is out unless a
  later ADR accepts the license consequences.

## ADR-002 — Identity

**Date**: 2026-10-14 · **Status**: accepted

- Name **Farfield** (the far field of a renderer). Modrinth slug `farfield`
  and the CurseForge name were free on 2026-10-14.
- Mod id `farfield`, Java package root `io.lakio.farfield` (same domain as
  Krios).
- Repository: private `JordanOlivet/farfield` until the mod is presentable.

## ADR-003 — License deferred

**Date**: 2026-10-14 · **Status**: accepted

The license is chosen before the first public release (options compared in
the analysis, §2). Until then the code is private and "All rights reserved"
(`LICENSE`, `license=All-Rights-Reserved` in the mod metadata).

## ADR-004 — Minecraft versions

**Date**: 2026-10-14 · **Status**: accepted

- **1.21.1 first** (Java 21): the version the user plays, with the surrounding
  modpack ecosystem to test against.
- **26.x planned from the start**: Minecraft made Vulkan its default graphics
  API in 26.4 and requires Java 25 since 26.1. Everything Minecraft-specific
  stays in thin adapters (ADR-006) so a 26.x adapter and a Vulkan backend
  (ADR-007) can be added without touching the engine core.
- How several Minecraft versions are built (per-version modules, Stonecutter,
  branches) is decided when the 26.x work starts.

## ADR-005 — Loaders and server platforms

**Date**: 2026-10-14 · **Status**: accepted

- NeoForge and Fabric from day 1, MultiLoader Template pattern as in Krios
  (`common` compiled against vanilla, thin loader projects, Java
  `ServiceLoader` platform services). No Architectury.
- Server side: the NeoForge/Fabric server mod first; a Paper/Folia plugin once
  the network protocol is stable.
- Target operating systems: Windows and Linux first. macOS stays possible
  later (Mojang ships Vulkan on macOS through MoltenVK).

## ADR-006 — Project layout

**Date**: 2026-10-14 · **Status**: accepted

| Project | Content | Minecraft on the classpath |
|---|---|---|
| `api` | Public API surface (internal until the public release) | No |
| `core` | The engine: data model, mips, storage, compression, LOD selection, meshing, jobs, protocol codecs | No |
| `common` | Minecraft-facing code: chunk snapshots, registries, events, rendering hooks, mixins | Vanilla (NeoForm + Parchment) |
| `neoforge`, `fabric` | Entry points and platform services | Loader |

- `api` and `core` never import Minecraft or loader classes: they are unit
  tested and benchmarked on their own, and survive Minecraft updates.
- Each loader jar is compiled from the sources of `api`, `core`, `common` and
  the loader project: one jar per loader, no nested jar for our own code.

## ADR-007 — Rendering architecture

**Date**: 2026-10-14 · **Status**: accepted

- **Direction "A then D"** (analysis §15): 3D voxel bricks with a mip
  pyramid, rendered by a GPU-driven rasterizer (GPU culling and LOD
  selection, indirect draws). The design leaves room for a later far tier for
  the extreme horizon (2.5D summary or ray marching).
- A rendering hardware interface (RHI) with capability tiers. **OpenGL 4.6
  first** (1.21.1). Native Vulkan for 26.x, adopting the game's Vulkan device.
- No CPU-driven fallback renderer for GPUs without compute for now; the RHI
  keeps the door open if players need it.
- No announced minimum hardware: presets and options scale the cost up or
  down.

## ADR-008 — Data model

**Date**: 2026-10-14 · **Status**: accepted

- Canonical model: **3D paletted bricks** with a mip pyramid (analysis §5,
  option C), not Distant Horizons-style columns.
- A 2.5D summary level (height, top material, color) is added later for the
  farthest ring and the future world map.
- Deduplication of identical bricks/subtrees (DAG-style) is evaluated at the
  storage layer in spike S1.
- Persisted block and biome identities are string keys plus the data version;
  an unknown key decodes to a placeholder and never throws.

## ADR-009 — Storage

**Date**: 2026-10-14 · **Status**: accepted

- The on-disk format is chosen from measurements of spike S1 on real worlds:
  a custom paged format vs LMDB, both with domain transforms first (palette,
  bit packing, run lengths, parent-mip residuals) then zstd with trained
  dictionaries.
- Default preset balances size and speed.
- RocksDB is rejected (native weight, compaction behavior; our Neo-Voxy
  database was 1.7x the size of the world's region files).

## ADR-010 — Visual target

**Date**: 2026-10-14 · **Status**: accepted

Faithful near, colors far: real textures (8 to 16 px per face) on the first
LOD levels, averaged colors on the coarse levels. Exact level split tuned
later per preset.

## ADR-011 — Hand-off with vanilla terrain

**Date**: 2026-10-14 · **Status**: accepted

Sodium (or vanilla) renders near terrain, Farfield renders beyond. The
transition band is a first-class feature: exact occupancy instead of coarse
bounding boxes, the same lighting, AO, normals and fog as vanilla, and a
dithered fade. Replacing the near-terrain renderer is not planned.

## ADR-012 — Shader packs

**Date**: 2026-10-14 · **Status**: accepted

- Goal: any shader pack works without pack-specific configuration, as far as
  possible (spike S7 measures how far).
- Paths: feed the packs' existing Distant Horizons programs where Iris
  allows it; draw through the packs' own terrain programs otherwise; propose a
  generic LOD contract to Iris and its Vulkan successor (Aperture).
- Reference packs: Complementary Reimagined, BSL, Photon, plus one pack with no
  LOD support at all.

## ADR-013 — Multiplayer and network

**Date**: 2026-10-14 · **Status**: accepted

- **Surface only by default**: the server never sends enclosed or underground
  data unless the operator enables full data (anti-x-ray, privacy, bandwidth).
- LOD data travels over the game connection under a **strict bandwidth
  budget** in small interleaved frames, so gameplay packets are never delayed
  (lesson of Neo-Voxy's 16 MB/s flood). An optional side channel (dedicated
  port or HTTP tiles) comes later.
- Client-driven pull with per-section versions or hashes; protocol versioning
  and capability negotiation from the first release.
- Designed for large servers: load tests, bounded memory per player, metrics
  for operators.

## ADR-014 — Language and toolchain

**Date**: 2026-10-14 · **Status**: accepted

Java (no Kotlin runtime dependency). Gradle 8.10, Fabric Loom 1.8, ModDevGradle
2.0.49-beta, Parchment mappings, JUnit 5 for `api`/`core`. NeoForge 21.1.229
in dev (the Forgified Fabric API inside Sodium 0.6.x needs 21.1.115+), Fabric
Loader 0.16.9 and Fabric API 0.109.0+1.21.1 (newer Fabric API builds need a
newer Loom).

## ADR-015 — Conventions and process

**Date**: 2026-10-14 · **Status**: accepted

Based on Krios and Neo-Voxy:

- Everything in git and on GitHub is in English (code, comments, docs,
  `CLAUDE.md`, commits, PRs). Conversation with the user is in French.
- ADRs here; plans in `docs/plans/` as checklists with item IDs; one progress
  log per working day in `docs/progress/YYYY-MM-DD.md`, committed with the
  work it describes.
- Conventional Commits, with a `Co-Authored-By` trailer when Claude wrote the
  change.
- Bootstrap pushes go directly to `main`; once the user declares the base
  done, branches and PRs (the repository squash-merges).
- CI: thin caller in `.github/workflows/` delegating to
  `JordanOlivet/ci-workflows`.

## ADR-016 — Deferred scope

**Date**: 2026-10-14 · **Status**: accepted

Planned but not in the first milestones: importers for Distant Horizons, Voxy
and Neo-Voxy data; the public API (an internal `api` project exists from the
start); the Paper plugin; the LOD side channel; a CPU-driven fallback
renderer; the world map showing each chunk's LOD state and errors (v2).

## ADR-017 — Region-file import

**Date**: 2026-10-14 · **Status**: accepted

Outcome of spike S5 (numbers in `docs/progress/2026-10-14.md`).

- The Anvil reader lives in `core` and has **no dependency**: region header,
  external `.mcc` chunks, zlib/gzip through the JDK, and our own decoder of
  the "LZ4Block" stream that 1.20.5+ writes for compression type 4 (checked
  against lz4-java in tests only). NBT is walked in place by a cursor that
  reads the fields LODs need and skips the rest, without building tag trees.
- Supported chunk format: 1.18 and later (DataVersion 2844+), which covers
  1.20.1 and 1.21.x. Older chunks are counted and skipped.
- Decoded chunks keep the game's shape: per-section palette of registry ids
  over canonical string keys (`name[k=v,...]`, properties sorted), 4096
  palette indices, 64 biome indices, sky/block light nibbles. Holders are
  reused per worker, so a sink sees a chunk only for the duration of the
  call.
- Only `full` chunks are imported by default; partially generated chunks
  are counted per status.
- Import pipeline: one worker per region file, nearest region first; the
  sink runs on the worker (backpressure by blocking); a shared rate limit in
  chunks per second; pause, resume, cancel at chunk granularity; an
  append-only checkpoint of completed regions (keyed by name, size and
  modification time) to resume an interrupted import.

## ADR-018 — Meshing on the CPU with binary greedy meshing

**Date**: 2026-10-14 · **Status**: accepted

Outcome of spike S2 (numbers in `docs/progress/2026-10-14.md`).

- LOD geometry is meshed **on the CPU** in `core` by binary greedy meshing
  (bit-column occupancy, face culling 32 voxels at a time, greedy merge of
  32x32 bit planes). At 122 to 139 µs per 32³ section on one core with no
  allocation, GPU meshing in compute is not needed; it stays an option.
- Output: **8-byte quads** for vertex pulling (position, size, direction,
  20-bit material, 16 bits reserved for light and ambient occlusion),
  **grouped by face direction**.
- Mesher input: `LodSection`, 32³ voxels in 8³ paletted bricks, uniform
  bricks without data. Its storage encoding is decided by spike S1
  (ADR-009); this ADR only fixes the in-memory shape the mesher reads.
- Material classes (air, opaque, translucent) come from the Minecraft side;
  faces between two translucent voxels are culled whatever their materials.
- Not decided yet: how light and AO enter the merge key (it lowers the merge
  ratio), shapes of non-cube blocks, cracks between LOD levels.
- The user's concern: CPU-heavy modpacks may leave no CPU for meshing.
  Mitigations: meshing runs only when a section arrives or changes (not per
  frame), on low-priority workers under a configurable budget; the CPU
  share of LOD work is measured in the user's ~400-mod pack during the
  in-game check (E.5). GPU meshing stays possible later without touching
  the renderer, since a compute shader can emit the same 8-byte quads; it
  gets its own spike once the OpenGL 4.6 RHI exists (after E.3), or earlier
  if E.5 shows the CPU cannot keep up.
