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

## ADR-019 — LOD storage format and container

**Date**: 2026-10-14 · **Status**: accepted

Outcome of spike S1 (numbers in `docs/progress/2026-10-14.md`). Refines
ADR-009.

- **Record format**: one record per 32³ section and level, encoded by
  `SectionCodec` (palette of material ids, uniform bricks as one value,
  bit-packed indices, light as nibble bricks, biome cells), then **zstd**,
  level 9 by default (13 % smaller than level 3; writes are background
  work, reads cost the same).
- **Two store-time transforms**, applied when a section is written:
  - *hidden voxels*: an opaque full cube whose six neighbors are opaque full
    cubes is replaced by the most common visible material of its brick. It
    never shows, and the LOD is rebuilt from the real chunk when anything
    around it changes. Mips are built before the transform, from full data.
  - *face light*: light is kept only on non-opaque voxels next to a
    non-air voxel (where a face can take its light); other voxels take the
    brick's most common kept value.
  Together they make the store 36 % smaller than the plain codec. They need
  the opacity of block states at store time (Minecraft side, like the
  mesher's `MaterialClassifier`); partial blocks must not count as full
  cubes once level 0 renders shapes (analysis §8.2).
- **Not adopted** (measured, no gain): trained zstd dictionaries (sections
  are 20 to 30 KB raw, dictionaries made them about 4 to 5 % *larger*),
  residuals against the parent mip (0 to 0.6 % larger), brick deduplication
  (1.6 to 4.9 % of bricks are duplicates), whole-byte indices with an "up"
  filter (16 % larger).
- **Container: `LogStore`**, our append-only log with CRC32C per record, an
  index snapshot for fast open, torn-tail recovery and offline compaction;
  one file per level and 32x32 section columns. Chosen over LMDB: 1 % disk
  overhead instead of 45 to 49 % (LMDB stores 2 to 5 KB values in whole
  overflow pages, and on Windows its file is preallocated to the map size),
  no native library, and it survived the same kill tests. LMDB reads
  faster (3.6 µs vs 6.6 to 7.0 µs per record on one thread, 2.3 M vs 0.8 M
  records/s on 14 threads), far above what rendering needs.
- **Open**: which zstd implementation ships (a pure-Java one such as
  aircompressor if it is fast enough, otherwise LWJGL's zstd bindings and
  their natives), decided when the store is wired in; compaction policy
  (garbage ratio, when to run); per-section versions for the network sync.

## ADR-020 — Downsampling rule for the mip levels

**Date**: 2026-10-14 · **Status**: accepted

Plan item E.1, analysis §5.3. `MipBuilder` builds level N+1 from 2x2x2
voxels of level N; it replaces the provisional majority vote of spike S1.

- **Block shapes** come from the Minecraft side (`MipBuilder.Shapes`):
  empty, sparse (plants, flowers, torches, rails, fences, panes, walls),
  cover (slabs, snow layers, carpets, paths), full (any full cube, opaque or
  not: stone, leaves, glass, water).
- **Occupancy**: weighted vote, full 4, cover 2, sparse 1; the parent voxel
  is solid from a weight of 8. Two full children are enough, so one-block
  walls, floors and trunks survive (a majority vote erases them and makes
  forests bald), an isolated block disappears, and plants alone never raise
  the ground. A feature survives to level k when it is at least 2^k blocks
  long along one axis.
- **Material**: what is seen from above. Per child column, the highest
  non-sparse voxel, else the highest sparse one; the most frequent of the
  four, ties to one seen in the upper layer. Water over sand stays water,
  snow layers over grass stay snow, plants over a grass block show the
  block.
- **Light**: maximum of the children. **Biome**: most frequent known biome
  of the eight child cells.
- Cost: mip building on `server-pregen` took 26 s of worker time instead of
  39 s with the S1 rule (brick-wise, uniform bricks without per-voxel work);
  levels 1 to 4 now add 25 % to the store instead of 21 %.
- **Not yet**: averaged colors per cell for the far levels (needs the
  material layer, analysis §5.4), a coverage value for leaves, and the
  selection hysteresis against flicker between levels (§9.2). The weights
  may be tuned after the in-game look check (E.5).

## ADR-021 — zstd implementation and the LOD world engine

**Date**: 2026-10-14 · **Status**: accepted

Closes the open points of ADR-019 that the ingest path (E.2) needed.

- **zstd: aircompressor 0.27** (pure Java, Apache 2.0, 255 KB, nested in
  both loader jars; no natives). Measured on 2,000 level-0 sections of
  `main-install` (codec bytes, one thread): same size as zstd-jni level 3,
  compression 62 µs vs 24 µs (level 3) and 152 µs (level 9), decompression
  13 to 17 µs vs 8 to 11 µs. Its compressor has no level setting, so the
  store is about 12 % larger than at level 9; any later compressor writes
  frames the same decoder reads, so switching costs no migration.
- **Store layout** (`SectionStore`): one `LogStore` per level and region of
  32x32 section columns, `sections/L<level>/r.<rx>.<rz>.ffl`, opened
  lazily; record payload = raw length (4 bytes) + zstd frame of the
  `SectionCodec` bytes. Block-state and biome keys in `block_states.txt` and
  `biomes.txt`, one per line in id order, forced to disk before any record
  that may use a new key.
- **`LodWorld`** (one per dimension, owns no thread): `ingest(ChunkData)`
  writes a chunk into its level-0 sections through a write-back cache
  (2,048 sections by default; idle sections flushed by the caller, the
  oldest written when it overflows), so the four chunks of a 32-block
  column cost one write; unchanged encodings are not rewritten; writes mark
  parents dirty and `buildMips` rebuilds them level by level, in parallel
  within a level. The importer feeds chunks in 2x2 groups for this.
- Measured on `server-pregen` (8 threads): 4,750 chunks/s from region files
  to level 0, mips 1.6 s for 10,596 parents, store 111 MB (42 % of the
  region files, store-time transforms not applied yet).
- **Not yet**: the store-time transforms of ADR-019 (they need the
  material classes at write time and neighbor-aware rebuilds), compaction
  of the region logs, per-section versions for the network.

## ADR-022 — Who builds the LODs: three modes, one engine

**Date**: 2026-10-14 · **Status**: accepted

Plan items E.2, E.2b and E.6. Rewritten before acceptance: the first
version covered the logical server only; the user asked that a client
with Farfield on a server without it still builds and renders LODs, and
that this be designed from the start.

- **One engine, several producers.** `LodWorld` (ADR-021) only sees
  `ChunkData` and owns no thread. A side-neutral pipeline (`LodPipeline`:
  snapshots on the owner thread, conversion and ingest on low-priority
  workers, a maintenance thread) feeds it from the server or from the
  client. The renderer never asks a producer directly: a provider hands it
  the `LodWorld` of the current dimension, whatever its source.

| Situation | Who builds the LODs | Where they are stored |
|---|---|---|
| Single player (and the host of a LAN game) | the integrated server, live chunks and `/farfield import` | `<dimension folder>/farfield/` |
| Server without Farfield | **the client**, from the chunks it receives | `<game dir>/farfield/servers/<server>/<seed hash>/<dimension>/` |
| Server with Farfield | the server, sent over the protocol (ADR-013) | a client cache in the same format |

- **Server mode** (built in E.2): a chunk is snapshotted on the server
  thread 2 s after it loads (once its light is correct) and 5 s after its
  last block change (mixin on `LevelChunk.setBlockState`), at most 16
  chunks and 2 ms per tick and 256 in flight; a changed chunk unloading
  before that is snapshotted at unload. The snapshot copies the section
  palettes and light layers (`PalettedContainer.copy`, `DataLayer.copy`).
  Workers: cores / 4, 1 to 4 threads. `/farfield import [threads]`
  (operators, or the owner of a single-player world) imports the region
  files with a resumable checkpoint, skipping loaded chunks;
  `/farfield import stop|status`. The maintenance thread writes sections
  idle for 3 s, rebuilds dirty mips every second and syncs every 30 s;
  everything is written at server stop.
- **Client mode** (E.6): the same pipeline on the client level, fed by the
  chunks the server sends and by block changes (the same mixin). Coverage
  grows as the player explores: no import is possible without the region
  files. On servers with an anti-x-ray that sends fake ores, data under the
  surface is wrong; the surface is not affected.
- **Identity of a remote world**: server address (as typed, normalized)
  plus the hashed seed the server sends at login and respawn (used for
  biome zoom), plus the dimension key. The seed hash tells apart the
  worlds behind one address (proxies such as Velocity or BungeeCord). A
  command will force or rename the identity when this guesses wrong. A
  LAN game whose port changes gets a new identity each time.
- **Choosing the mode**: in single player the client never ingests (the
  integrated server does, so chunks are not written twice). On a remote
  server, if the server announces Farfield on its network channel, its
  protocol is authoritative and the client does not ingest (filling gaps
  locally can come later as an option); otherwise the client ingests.
- Materials (mesher class, mip shape) come from block states on either
  side (`BlockKeys.resolve`); unknown keys are opaque cubes.
- Later: per-section versions (open in ADR-021) once the protocol mode
  merges server data with a client cache.

## ADR-023 — Screen-space level of detail

**Date**: 2026-10-15 · **Status**: accepted

- A section splits into its finer level when its voxels would exceed a
  target size on screen at its nearest point: `detail = focal length in
  pixels / (32 * voxel pixels)`, with the focal length from the window
  height and the projection. The criterion follows resolution and field of
  view; the fixed ratio before (split within 4 section sizes) gave voxels
  up to about 20 px on a 4K screen and read coarse at middle distance.
- Default 4 px, set in game with `/farfield detail <1..32>` (single
  player for now; a client option comes with the settings screen).
  Measured in the Fabric dev client with Sodium, 4K, middle-distance view
  over forest and lake: 12 px 30 MB of quads, 0.19 ms render thread,
  2.35 ms GPU; 6 px 102 MB, 0.41 ms, 3.06 ms; 4 px 203 MB, 0.70 ms,
  3.85 ms; 2 px 593 MB, 2.92 ms, 4.58 ms. 4 px shows single trees; 2 px
  adds little for three times the memory.
- Neo-Voxy (Voxy) splits at 64 px per 32-voxel node, about 2 px per voxel;
  Farfield takes the same kind of criterion (concept only, ADR-001) with a
  coarser default until GPU culling (ADR-007) lowers the per-section cost.
- The vanilla hand-off discards LOD fragments only where vanilla draws:
  within the render distance horizontally and vertically (Sodium culls
  sections farther than the render distance on either axis).

## ADR-024 — Default level of detail: 8 px per voxel

**Date**: 2026-10-15 · **Status**: accepted

Amends ADR-023. The default target is 8 px per voxel instead of 4. The
user compared both in the Fabric pack with the level colors: 4 px is very
good, 8 px is fine, and 8 px costs about a third of the memory and less
than half the render-thread time (Fabric dev client, 4K, over the lake:
62 MB of quads and 0.29 ms against 203 MB and 0.80 ms). Players choose the
detail in the in-game settings screen once it exists (plan item E.8); the
`/farfield detail <px>` command stays.
