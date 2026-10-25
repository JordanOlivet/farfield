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

## ADR-025 — Textured LOD faces by distance

**Date**: 2026-10-15 · **Status**: accepted

Implements the near half of ADR-010.

- Each block state's faces are baked into 16x16 tiles from the model's
  quads (positions and UVs from the vertex data, layers in drawing order),
  as an untinted tile and a biome-tinted tile, in one sRGB atlas with five
  mip levels built per tile (nearest filtering, no bleeding between tiles)
  and identical tiles stored once. The textured color is `untinted +
  tinted * biome tint`; the far mean color is the mean of the same tiles,
  so the fade between them shows no step.
- Textures repeat once per block at every LOD level and fade into the mean
  color over the last quarter of a distance in blocks, by default 512, set
  with `/farfield textures <0..4096>` (0: off) and later in the settings
  screen (E.8). Distance rather than LOD level: what decides whether a
  texture shows is its size on screen, and beyond about 500 blocks a block
  covers two or three pixels on a 4K screen, so the mips already reduce the
  texture to its mean.
- Measured in the Fabric dev client with Sodium (4K, 1,219 sections):
  2.05 ms GPU without textures, 2.10 at 128 blocks, 2.13 at 512, 2.11 at
  2,048. The distance is a matter of look, not of cost, on this hardware.
- Opaque materials with holes (leaves) have their holes filled with their
  own color at 45 % brightness, as vanilla's fast leaves: textured cubes
  near, darker foliage far. Translucent materials keep their texture's
  alpha.

## ADR-026 — Textures on every LOD by default

**Date**: 2026-10-15 · **Status**: accepted

Amends ADR-025. The default texture distance is "all": every LOD face
shows its texture, whatever its distance (the mips reduce far textures to
their mean anyway). The measured GPU cost of textures was within noise
(2.05 ms without, 2.10 to 2.13 ms with). `/farfield textures all|<blocks>`
(0: off) stays for cases where it hurts, and moves into the settings
screen (E.8).

## ADR-027 — Default level of detail: 6 px per voxel

**Date**: 2026-10-15 · **Status**: accepted

Amends ADR-024. After playing with textures on every LOD, the user found
8 px "a bit short in some cases": the default is 6 px per voxel (about
100 MB of quads on the middle-distance test view, between the 62 MB of
8 px and the 203 MB of 4 px). `/farfield detail <px>` and the settings
screen (E.8) keep the choice open.

## ADR-028 — Fill height of coarse voxels

**Date**: 2026-10-15 · **Status**: accepted

Amends ADR-020 (downsampling rules).

- Coarse voxels (levels 1 and up) store a **fill**: how high their content
  stands in the voxel, in sixteenths (4 bits per voxel, in bricks like the
  light). Per child column, the upper child's height above a full lower
  half, or the lower child's; the highest of the four columns, rounded up.
  Level-0 children take their block's height (snow layer 2/16, slab 8/16,
  cube 16/16); coarser children their fill. The highest column keeps
  trunks, walls and cliff edges.
- Quads carry the fill in eighths (3 bits). Opaque voxels that are not full
  mesh as partial (they hide only the top of the voxel below), and their
  top edges come down to the fill. The sea now lies at its own height on
  every level: the one-block lowering of coarse translucent tops is gone.
- Quad layout: material 16 bits (6,460 block states in a 398-mod pack),
  biome 9 bits (127 biomes in the same pack), sky and block light 4 bits
  each, fill 3 bits. Quads stay 8 bytes.
- Section format version 2 adds the fill map and bricks; version 1 reads as
  full. A world built with older mip rules rebuilds its coarse levels once
  from level 0 when opened (`mips.version`); 27,432 level-1 sections in
  15 s on the test world. Store size unchanged at the MB level
  (L1 152 MB, L2 31 MB on the test world).
- Level 0 keeps no fill: there the block state gives the height
  (`MaterialClassifier.PARTIAL`, drawn at its height by `lod.vert`).

## ADR-029 — Client settings: file, screen and key

**Date**: 2026-10-16 · **Status**: accepted

Implements plan item E.8.

- The LOD drawing settings (LOD drawing on or off, detail in pixels per
  voxel, texture distance, level colors) are saved on the physical client
  in `config/farfield-client.properties`: a commented properties file,
  written through a temporary file, read by `RenderConfig` in `core`
  (missing or invalid keys take their default, out-of-range values are
  clamped). A dedicated server never writes it.
- One settings screen in `common`, built from vanilla options widgets
  (`OptionsSubScreen`), so it looks like the game's own pages on both
  loaders. Values apply at once; the detail slider applies on release, as
  each value re-selects the LOD sections. The file is written when the
  screen closes and after each `/farfield render|detail|textures|debug
  levels` command.
- Openers: the Mods screen's "Config" button on NeoForge
  (`IConfigScreenFactory`), Mod Menu's button on Fabric (optional
  dependency, `modmenu` entrypoint), and a key binding, unbound by default
  (Controls > Key Binds > Farfield), for Fabric installs without Mod Menu.

## ADR-030 — Biome blending of LOD tints

**Date**: 2026-10-16 · **Status**: accepted

Grass, foliage, water and the tints of mods changed color in hard steps at
biome borders, on the 4-voxel biome cells. LODs now follow vanilla's
Biome Blend option, as the user asked: at 0 nothing changes (one tint per
quad, the quad's biome); otherwise each fragment takes the mean tint, in
sRGB as vanilla, over a square of 2r + 1 blocks around it (r the option's
radius in blocks), read live every frame.

- Each section slot holds the surface biome of its 8x8 columns of 4x4
  voxels (`SurfaceBiomes`: the biome of the highest cell with a non-air
  voxel), 128 bytes per slot, uploaded with every mesh. Blending is
  horizontal like vanilla's, and LODs mostly show surfaces: one biome per
  column keeps a cave biome under the surface of the same section from
  bleeding into it.
- The fragment shader weighs the columns the window covers by their
  overlap, across same-level neighbor sections through the slot table of
  ambient occlusion. Unknown columns (neighbor not resident at that level,
  air columns) and the fragment's own column take the quad's biome.
- The window slides continuously instead of block by block, and biomes
  come from the LOD's 4-voxel cells, not from vanilla's per-block fuzzy
  biome lookup: the ramps sit where vanilla's do, without vanilla's
  per-block jitter.
- Measured in the Fabric dev client with Sodium (4K, top-down view over
  the ocean of the test world): 3.40 ms GPU at 0, 3.55 ms at 2 (vanilla's
  default), 4.22 ms at 7.

## ADR-031 — Release versions and channel

**Date**: 2026-10-16 · **Status**: accepted

- Releases are SemVer tags `v<major>.<minor>.<patch>[-<pre-release>]`,
  pre-releases as `alpha.N`, `beta.N` or `rc.N` (first: `v0.1.0-alpha.1`).
  The tag gives the version: Gradle builds with `-Pversion=<tag without v>`,
  so the jar names and the mod metadata carry it; `version=` in
  `gradle.properties` holds the core of the next release and must match the
  tag's core, or the release stops before publishing. Local and CI builds
  keep the plain core version. The `0.1.0-pre-alpha` tag made by hand
  before this ADR stays as it is.
- A pushed tag runs `.github/workflows/release.yml`, which calls the
  reusable `publish-mod.yml` of `JordanOlivet/ci-workflows` (shared with the
  user's other mods): one build, then one Modrinth and CurseForge version
  per loader jar (`Kir-Antipov/mc-publish` v3.3.1, maintained), version
  numbers suffixed `+neoforge` / `+fabric` to stay unique on Modrinth, and
  one GitHub Release with both jars. The version type follows the label:
  alpha, beta (beta, rc), release.
- The changelog is the list of commit subjects since the previous tag,
  without docs, chore, ci and test commits.
- No dry run on Modrinth's staging server: both projects are unlisted, and
  the user can delete test uploads.

## ADR-032 — Client mode, as built

**Date**: 2026-10-16 · **Status**: accepted

Implements the client mode of ADR-022 (plan item E.6).

- `LodClient` runs one `LodPipeline` per connection to a remote server
  (never in single player, nor for the host of a LAN game), fed by the
  loaders' client chunk load and unload events, the block-change mixin and
  the client tick. Stores live in
  `<game dir>/farfield/servers/<address>/<seed hash>/<dimension>/`.
- Light: vanilla applies the light the server sends through the client
  level's light update queue, a few ticks after the chunk, and never marks
  a client chunk's light correct. The pipeline takes a light predicate:
  the server keeps `isLightCorrect`, the client waits for an empty light
  update queue (then the usual 10 s fallback).
- A dimension change closes that level, leaving the server closes the
  pipeline, both in the background: waiting chunks are snapshotted on the
  client thread, the store is written and closed on the maintenance
  thread, and opening the same store again (going back, reconnecting)
  waits for that close. When the game quits, the stores are written before
  it exits (at most 30 s).
- Until the protocol of ADR-013 exists, the client builds LODs on every
  remote server, Farfield or not; a server with Farfield builds its own
  LODs in parallel without sending them.
- The command to force or rename a remote world's identity is not built
  yet.

## ADR-033 — LODs in thick dimension fog

**Date**: 2026-10-16 · **Status**: accepted

Amends ADR-011 (hand-off with vanilla fog).

- In the Nether, vanilla draws a thick fog (from 5 % of the far plane to
  `min(far, 192) / 2`, sphere-shaped). The fog mixin left it alone while
  the LODs kept their own far fog: near terrain sank into the fog color
  and distant LODs stood out sharp and dark behind it, and the columns of
  sections vanilla draws (fogged) cut light bands through the LOD ceiling
  (user's pack pass in the Nether).
- By default, as the user chose, the LODs now take the game's terrain
  fog (start, end and shape, read right after vanilla sets it up) when the
  dimension fog is thick: the Nether keeps its haze, and little of the
  distant terrain shows. The same applies when the camera is in water,
  lava or powder snow.
- A setting, Dimension Fog (Vanilla / Pushed Back, file key
  `extend_dimension_fog`, default Vanilla), pushes thick fog back to the
  LOD horizon instead, like the normal fog of ADR-011.
- Blindness and fog another mod changed are left alone, as before.

## ADR-034 — Fog distances chosen by the player

**Date**: 2026-10-16 · **Status**: accepted

Amends ADR-011 and ADR-033; asked by the user, so each player places the
fog where it looks best: none in the Overworld, a little in the Nether.

- Two settings, as sliders in the settings screen and in the file: Fog
  Distance for normal dimensions (`fog_distance`, 256 to 4,096 blocks by
  128, or off; default 4,096, the look before) and Thick Fog Distance for
  dimensions with thick fog (`thick_fog_distance`, vanilla, 256 to 4,096,
  or off; default vanilla). It replaces ADR-033's Vanilla / Pushed Back
  switch; a file with `extend_dimension_fog=true` reads as 4,096.
  `/farfield fog [thick] off|<blocks>` and `/farfield fog thick vanilla`
  set them in single player.
- The fog is opaque at the chosen distance (never closer than the game's
  far plane) and starts at 60 % of it; normal fog never starts before
  vanilla's own fog start, so near terrain stays as clear as without LODs.
  Off sets the fog beyond anything drawn: the edge of the LODs shows.
- Nothing is selected beyond the fog, which hides it anyway: a closer fog
  lightens the GPU and frees meshes (Fabric dev client, 4K, ocean view:
  512 blocks drew 1,298 sections and kept 58 MB of quads, against 1,981
  and 126 MB at 4,096). With vanilla thick fog nothing is drawn beyond the
  game's terrain. Water and lava fog do not limit the selection: they come
  and go, and would evict every mesh.

## ADR-035 — Naming a remote world

**Date**: 2026-10-17 · **Status**: accepted

Completes ADR-022's "command to force or rename the identity" (plan item
E.6).

- A client command with a root of its own, `/farfieldclient`, so it never
  clashes with a server's `/farfield` (each loader registers it with its
  client command source): `world` shows the server address, seed hash,
  name and store folder; `world name <name>` names the current remote
  world (the rest of the line, sanitized to lower-case letters, digits,
  `.`, `-` and `_`, at most 64); `world reset` goes back to the automatic
  identity.
- A named world's LODs live in `<servers>/named/<name>/` instead of
  `<servers>/<address>/<seed hash>/`; names are kept in
  `<servers>/identities.properties`, keyed `<address>/<seed hash>`
  (`RemoteWorldNames`, core).
- Changing the identity closes the stores first (stop and wait), then: a
  target folder that does not exist receives the current one (the LODs
  follow: rename), an existing one is used as is and the current folder
  stays (force); an emptied address or `named` folder is removed. The
  pipeline starts again on the next tick from the new folder.

## ADR-036 — Shaped blocks drawn as their boxes

**Date**: 2026-10-18 · **Status**: accepted

Amends ADR-020 and ADR-028 for the finest level; plan item E.10. Fences,
walls and panes were invisible in LODs and stairs were full cubes.

- A fifth mesher class, `MaterialClassifier.SHAPED`: a block that is not a
  full cube and either covers its whole footprint without standing on the
  bottom over a lower height (stairs, top slabs; `PARTIAL` keeps bottom
  slabs, snow layers, carpets), or has a collision shape (fences, walls,
  panes, doors, cactus, lily pads, lanterns). Blocks without a collision
  shape (plants, torches, rails, signs) stay out of the LODs as before.
- Its boxes are the block's outline shape (`VoxelShape.toAabbs`) in
  sixteenths, at most 8 (the largest by volume), resolved once per block
  state into the world's `MaterialTable` (`ShapeBoxes`, 30 bits per box).
- On level 0 each box gives one unit quad per face, not merged; the box
  index rides in the quad's fill bits, unused on level 0. A face that
  reaches the voxel's side is lit by the voxel in front and hidden by a
  full opaque neighbor; an inner face is lit by the shaped voxel itself.
  A shaped voxel hides no neighbor face. `lod.vert` reads the box from
  the material entry (stride 19 to 27 ints) and moves the corners.
- Coarser levels treat shaped blocks as opaque, and the mip rules do not
  change: stairs and top slabs weigh as covers, fences, walls and panes as
  sparse blocks (ADR-020). No store rebuild.
- The opaque pass now discards textured texels below half coverage, so
  glass panes and iron bars show their frames as vanilla's cutout;
  untextured (far) faces stay whole.
- Cost: a stair is up to 12 quads instead of a share of merged faces;
  bamboo forests and villages mesh larger level-0 sections.

## ADR-037 — Terrain not generated yet: spike S6 and proposal

**Date**: 2026-10-18 · **Status**: proposed

Plan item E.11, state-of-the-art open question 8 and spike S6. Numbers from
`/farfield spike generation` (`GenerationSpike`) on the NeoForge dev
server, vanilla 1.21.1 Overworld, the user's PC (16 logical threads), two
runs of 256 and 1,024 chunks per step, fresh areas 20,000 to 50,000
chunks from spawn.

- **Generation by the game**, with Farfield's own chunk tickets: full
  chunks at 50 to 75 chunks/s (13 to 20 ms of wall time per chunk, about
  5 cores busy). Stopping at noise or surface is not cheaper through the
  chunk system: 33 to 51 chunks/s on about 1.2 cores (it runs lower
  statuses on few threads). A 4,096-block radius (206,000 chunks) takes
  45 to 70 minutes. Vanilla saves the chunks: this is pregeneration.
- **Noise preview**: the generator's `getBaseHeight` builds a `NoiseChunk`
  per column, 2.3 ms per column and thread: a 1,000-chunk radius at one
  column per chunk takes about 15 minutes on 8 threads. Vanilla's
  preliminary surface level (`initialDensityWithoutJaggedness` scanned
  down by cell height, as `NoiseChunk` does for aquifers) costs 0.25 ms:
  32,000 columns/s on 8 threads, so the 4,096-block LOD range in about
  6.5 s and a 1,000-chunk radius in about 100 s (S6 target: 5 s, out of
  reach). It lies 12.5 blocks below the real ocean floor on average (max
  61): no jaggedness (peaks) and cell resolution. Biome lookup adds
  almost nothing.

Proposal, to be confirmed by the user:

1. Noise preview first: no world change, seconds for the current LOD
   range. One column per chunk, nearest first; surface color from the
   biome, water at sea level; stored apart from real LODs, replaced as
   soon as a real chunk is ingested, and never drawn over real data.
2. Then generation for the near ring: through the chunk system with
   Farfield's tickets at full status, nearest first, throttled (cores and
   TPS budget), chunks saved by vanilla like a pregenerator. LOD-only
   generation without saving (drop the chunks after ingest) is deferred:
   it needs to keep vanilla from saving them, which is risky with modded
   world generation.
3. Before building: the same spike in the user's pack (modded world
   generation costs differ), and a bias correction of the preliminary
   height measured there.

## ADR-038 — Noise preview of terrain not generated yet

**Date**: 2026-10-18 · **Status**: accepted

Decides ADR-037. The user: noise preview first, and the player must be
able to turn it off; a pregenerator comes later, as an option, since
pregenerating is not the mod's goal; the spike in the user's large pack
comes later too.

- **Height**: the noise router's final density (`finalDensity`) scanned
  down from the top by cell height, then bisected to the block. Measured
  on the dev server over 1,024 columns: 1.0 block from the generator's
  ocean floor on average, 98 % within 4 blocks, max 26, 0.73 ms per
  column and thread. The preliminary surface level of ADR-037 lay 13
  blocks low; the density without jaggedness at threshold 0, refined,
  still 7.
- **Data**: one column per chunk, so one level-4 voxel column (16 blocks).
  Solid up to the surface, the top voxel filled to it in sixteenths
  (ADR-028); a surface block guessed from the biome (sand, red sand,
  mycelium, snow, stone, end stone, else grass block; sand or gravel under
  water), stone below; water up to the sea level over lower ground; the
  biome per cell for the tints. Stored in the dimension's LOD store as
  sections of their own level (`LodWorld.PREVIEW_LEVEL`, level-4
  coordinates), so they share its block state and biome ids, and never
  enter the mip levels.
- **Builder**: the integrated server only (single player, LAN host): no
  protocol sends LODs to clients yet (ADR-013). Dimensions with a noise
  generator and no ceiling. Every 2 s, the section columns (512 x 512
  blocks) within the LOD range of a player, nearest first, on two
  low-priority threads; a column already stored is skipped.
- **Drawing**: the client draws stored preview sections within the
  selection distance at level 4, beside the octree selection, and masks
  every chunk column where the real level-4 data holds a voxel: a chunk
  once ingested replaces its preview at once (the preview section is
  meshed again when the real one is written). Preview never covers real
  data; the vanilla hand-off applies as to all LODs.
- **Setting**: `noise_preview` in `farfield-client.properties` (default
  on), a toggle in the settings screen and `/farfield preview on|off`. Off
  stops building and drawing; the stored preview stays.
- Not in the preview: trees, structures, caves, overhangs, surface rules
  (the surface block is a guess). A seam of a few blocks may show where it
  meets real terrain. Not on servers (protocol), nor in the Nether.

## ADR-039 — Noise preview as height tiles, drawn at every level

**Date**: 2026-10-18 · **Status**: accepted

Amends ADR-038 after the user's pass in the Fabric pack: the preview
loaded and came back after a reconnect, but read as stacked 16-block
boxes, with ugly transitions to the real LODs. The user asked for a finer
silhouette and smoother transitions first (step 1), approximate trees
later (step 2).

- **Data**: tiles of 512 x 512 blocks (`PreviewTile`) in
  `<dimension store>/preview/t.<x>.<z>.ffp`, zstd: the surface every 4
  blocks (129 x 129 samples, the edges shared with the next tiles), the
  top block and the filler under it per 4-block cell, the biome per
  chunk, the ground block and the sea. The level-4 sections of ADR-038 are
  gone (their `L8` folder is deleted at open).
- **Heights**: the final density as in ADR-038, each sample's search
  starting from its neighbor's height (up or down by cell height, then
  bisected); a result with ground right above it (cave, overhang) is
  searched again from the top. Vanilla interpolates its density over
  4-block cells, so heights are interpolated bilinearly in between.
- **Surface blocks**: from the biome as before, plus bare stone where two
  samples 4 blocks apart differ by 8 blocks or more, terracotta in
  badlands, sandstone under desert sand, dirt under grass, snow over snow
  in mountains.
- **Sections**: built by the client from the tiles at any level, on
  demand (`PreviewVoxels`): a voxel column stands at the highest
  interpolated block of its footprint (finer than 4 blocks) or the highest
  sample in it (from 4 blocks); only a skin is built, one voxel under the
  lowest neighbor. The renderer runs a second octree selection over the
  tiles (`PreviewLayer`), so the preview is as fine as real terrain near
  the camera and meets it at the same level.
- **Mask**: per tile, the chunks whose real level-4 column holds a voxel;
  a tile's sections enter the selection once its mask is read, and real
  level-4 writes in it rebuild the mask and remesh its preview sections.

## ADR-040 — Trees in the noise preview

**Date**: 2026-10-18 · **Status**: accepted

Step 2 of the preview (ADR-039). The user: approximate trees and other
features, not the real ones but "des trucs random aux endroits où il y en
aura".

- **What grows**: each biome's vegetal decoration features, followed
  through random selectors (weighted, simple, boolean) down to tree
  features: trunk and leaves blocks, average trunk height, crown radius and
  height from the trunk and foliage placers (8 samples), cone crowns for
  spruce and pine placers. Read once per biome (`PreviewTrees`), logged.
- **How many**: the average count per chunk from the placement modifiers,
  read through their codec form (count with its int provider, rarity,
  noise threshold and noise based counts), so modded biomes built from the
  vanilla modifiers work. At most 48 per chunk.
- **Where**: at random cells of the chunk, seeded by the world seed and
  the chunk, on grass or snow above the sea, at the interpolated surface.
  Stored in the tile (format 2: tree kinds, then per chunk the trees);
  tiles of format 1 fail to decode, are deleted at open and built again.
- **Drawing** (`PreviewVoxels`): trunk and crown block by block on level
  0; on coarser levels a voxel takes the tree's material when trees fill a
  quarter of it (two full children of eight in the real mip rule), so
  forests stay as canopy far away and lone trees fade. Tree tops raise the
  tile summary of their chunk and its neighbors. Trees of real chunks, and
  crown blocks over real chunks, are left out.
- Not yet: huge mushrooms, cactus, bamboo and other non-tree features,
  structures (villages); the exact tree shapes (mega trunks, acacia).

## ADR-041 — Plants, huge mushrooms and villages in the noise preview

**Date**: 2026-10-18 · **Status**: accepted

Extends ADR-040. The user: "Rajoute d'abord champignons géants, cactus,
bambou et structures comme les villages".

- **Plants**: `PreviewTrees` also follows huge mushroom features (stem and
  cap; red caps as domes over a 5-block stem, brown ones as one wide layer
  of radius + 1), bamboo (12-block columns), block columns (cactus, as the
  sum of their layers' mean heights), single blocks (pumpkins, melons) and
  random patches (tries x 0.15). Blocks without a collision shape (grass,
  flowers, sugar cane, berry bushes) are left out, as the LODs leave them
  out. Each kind needs its ground: grass or snow (trees, bamboo), grass or
  mycelium (huge mushrooms), sand (cactus).
- **Tree kinds** carry a shape (blob, cone, flat, column) instead of the
  cone flag; tile format 3, older tiles are built again.
- **Villages**: `PreviewVillages` takes the world's structure sets that
  hold structures of the `#minecraft:village` tag with a random spread
  placement (vanilla's, and modded sets made the same way), asks the
  placement for each region's candidate chunk with the game's own
  frequency and exclusion rules (`isStructureChunk`), and keeps it when a
  village of the set accepts the biome at its center. That is where the
  game will try a village; the jigsaw may still fail there.
- **Houses**: not the game's pieces. Ten to seventeen boxes around the
  center (within 56 blocks, eight tries per house so a village by a lake
  spreads along the shore), seeded by the world and the center so every
  tile lays out the same ones, apart from each other, on land (not in
  water, not on slopes over 6 blocks): walls 4 to 5 blocks over the
  center's ground, a floor one block under the lowest corner, one roof row.
  Materials by style from the structure's name: plains (oak walls, spruce
  roof), desert (smooth and cut sandstone), savanna (acacia), taiga
  (spruce, dark oak roof), snowy (spruce, snow roof). Stored in the tile
  whose area holds the house's corner; no trees grow within the village
  radius.
- Not yet: other structures (outposts, temples, ruins), paths and farms,
  the exact village layout.

## ADR-042 — Machine-independent dev setup

**Date**: 2026-10-19 · **Status**: accepted

The user works from a second PC and wants the setup to hold on any dev
machine: "il faut que ça soit assez générique pour que cela s'applique sur
n'importe quel pc de dev". Paths of one machine were written in tracked
files, the dev run dirs were hand-made, and each server had a random seed.

- **`local.properties`** at the repository root, gitignored, from the
  tracked `local.properties.example`: the machine's paths as keys
  (`farfield.corpus.dir`, `farfield.testJars.dir`, `farfield.gameLogs.dir`).
  `settings.gradle` makes every key a project property of every project;
  `-P` wins. Tracked files cite the key, never a path outside the
  repository. Progress logs and skill evidence keep the paths they recorded
  (history is not rewritten).
- **Run dirs from `dev/`**: `prepareRuns` (each loader, before `runClient`
  and `runServer`) copies the files of `dev/client` and `dev/server` missing
  from the run dirs and never overwrites one. The templates hold
  `options.txt`, `server.properties`, `eula.txt`, `ops.json` (offline `Dev`
  operator) and one test world as a bare `level.dat`: seed
  `-6818606149209278498`, spawn 48, 75, 32, noon without daylight cycle,
  weather or mob spawning, spectator for new players. Chunks generate on
  first visit, so every machine has the same terrain and coordinates are
  shared (`docs/test-worlds.md`).
- **`runVanillaServer`** (root project): downloads the vanilla server jar of
  `minecraft_version` from Mojang's piston-meta manifest (SHA-1 checked)
  into `runs/vanilla-server`, copies `dev/server`, runs it on JDK 21 with
  stdin, for client mode checks.
- **`copyTestJars`**: builds both loader jars and leaves them alone in
  `farfield.testJars.dir`, replacing the older Farfield jars there.
- **Project skills** in `.claude/skills/` are tracked; autoharness usage
  counters (`.ledger.jsonl`, `.sidecar.json`) and the rest of `.claude/`
  stay local. User-level Claude settings, memory and tools stay per machine.
- Not covered: other operating systems (the scripted checks rely on
  PowerShell and Win32), the reference corpus itself (private worlds, 3.5
  GB; `docs/corpus.md` says what kinds of worlds to use).

## ADR-043 — Depth pre-pass for opaque LOD faces

**Date**: 2026-10-20 · **Status**: accepted

Measured on the test world (Fabric dev client, RTX 3070 Laptop, 2582x1550,
793 sections): the LOD GPU time was mostly per pixel. Sending 43 % fewer
quads (E.12) saved 3 %, textures off 8 %, 2.4 times less geometry 30 %.
`lod.frag` discards fragments (vanilla hand-off, cutout holes) in a pass
that writes depth, which keeps the GPU from testing depth before the
shader: every face behind another one paid the whole shader (ambient
occlusion, biome blending, textures).

- **Opaque faces draw twice**: a depth-only pre-pass (`lod.frag` built with
  `DEPTH_ONLY`: only the hand-off and cutout discards, color writes off),
  then the shaded pass (`EARLY_Z`: `layout(early_fragment_tests)`), with
  `GL_EQUAL` and no depth writes, without the two discards: the pre-pass
  depth already holds those holes. `lod.vert` declares `invariant
  gl_Position` so the two programs compute the same depth.
- **Translucent faces** use the `EARLY_Z` program too, with their discards:
  they write no depth, so early tests change nothing but skip the water
  behind terrain.
- `GlProgram` builds variants from one source with `#define` lines; each
  variant has its own uniform locations (`LodRenderer.Shader`).
- The stat line splits the GPU time into pre-pass, opaque and translucent.
  `/farfield debug prepass on|off` switches the pre-pass for comparisons,
  on at every start, not saved, not in the settings screen.
- Measured, same view and run: 3,421 us per frame without, 2,554 us with
  (pre-pass 796, opaque 1,553, translucent 204), -25 %; captures with and
  without identical over the LOD part (4 pixels of 4 million off by more
  than 2 levels). Remaining costs to look at next: the pre-pass itself
  (vertex and raster work, about 800 us) and the shaded opaque pass.

## ADR-044 — Depth pre-pass without discard where nothing can be cut

**Date**: 2026-10-20 · **Status**: accepted (amends ADR-043)

The pre-pass of ADR-043 cost about 800 us. Measured in one dev client run
by switching variants: vertex work alone (rasterizer discard) 345 us, the
pre-pass without any discard 456 to 474 us, without the cutout test 560
us. A `discard` anywhere in the depth shader costs about 320 us even when
it rarely runs: the GPU then tests and writes depth after the shader. A
lighter vertex shader (no color, light or occlusion outputs) changed
nothing: the driver already dropped the unused outputs.

- **Two pre-pass lists**: sections that cannot discard (outside the grid of
  sections the game may draw, and no opaque face with holes) draw first
  with a depth shader without any discard (`DEPTH_ONLY` + `SOLID`, early
  tests); the others then draw with the discarding one, mostly behind
  depth already written. The shaded opaque pass draws both lists in one
  call, as before.
- **Faces with holes**: `MaterialColors.CUTOUT`, the top bit of a face's
  tinted color word, set when its baked texture has a texel under half
  coverage (the opaque pass cuts out below 0.5). Opaque materials have
  their holes filled first (leaves), so few faces carry it (panes, bars).
  The discarding shaders read the texture only for those faces. Per
  material, `MaterialColors.cutout` gives the flag to the mesher threads,
  true while the material's look is not built; a section meshed then is
  meshed again once the table is complete.
- **Hand-off test only near the game's terrain**: `lod.vert` flags a
  section that overlaps the compiled-sections grid (`v_handoff`); the
  fragment shader skips the grid lookup elsewhere.
- The stat line gives the drawn sections that may discard.
- Measured, same view and run (793 sections, 171 may discard): 3,630 us
  per frame without pre-pass, 2,497 us with (pre-pass 521, opaque 1,768,
  translucent 208), -31 % (ADR-043's version: -25 %); captures with and
  without identical over the LOD part. Absolute times move between runs
  on this laptop (frame rate capped near 118 fps, GPU clocks follow the
  load): compare within one run.

## ADR-045 — Frozen seas in the noise preview

**Date**: 2026-10-20 · **Status**: accepted (extends ADR-039)

The user saw, at the border between real LODs and the noise preview, open
preview water beside real ice: "le seedgen devrait mettre de la glace à la
place de la première couche d'eau dans les biomes gelés". The preview had
one water material up to the sea level and nothing froze.

- **Per cell of 4 blocks**, a frozen bit (tile format 4; older tiles are
  built again): the cell is under the sea and vanilla's freeze_top_layer
  rule holds there, `!biome.warmEnoughToRain(pos)` at the sea's top block,
  with the cell's own biome at that height. A first version took the
  chunk's biome (sampled at the sea floor) and left whole chunks of open
  water in a frozen sea. The frozen-ocean temperature noise leaves open
  water as vanilla does.
- **Voxels**: the voxel holding the sea's top block is the tile's ice
  (`Blocks.ICE`, water seas only), on every level, at the sea's fill.
- Cost: tiles took 2,462 ms each instead of 2,220 in the test world
  (one more biome sample per sea cell).
- Not covered: icebergs and other frozen-ocean features; snow on ice
  (vanilla puts none).

## ADR-046 — GPU gains of E.12 and E.13 measured without a frame cap

**Date**: 2026-10-20 · **Status**: accepted (completes ADR-043 and ADR-044)

The measurements of ADR-043 and ADR-044 ran at vanilla's default 120 fps
cap, where the laptop GPU lowered its clocks. Measured again uncapped
(`maxFps:260`, vsync off), Fabric dev client, RTX 3070 Laptop,
2582x1550, `farfield-test` at `/tp @s 48 180 32 45 25`, 1,417,837 quads
in the drawn sections. One run; a temporary switch turned the face
directions off (every direction drawn); each value is the second stat
line after a switch, two rounds:

| Pre-pass | Face directions | GPU per frame | Frames in 30 s |
|---|---|---|---|
| Off | Off (before E.12) | 4,930 to 4,977 us | 5,506 to 5,559 |
| Off | On | 4,666 to 4,732 us | 5,767 to 5,851 |
| On | Off | 3,374 to 3,384 us | 7,790 to 7,818 |
| On | On | 2,875 to 2,889 us | 8,955 to 8,989 |

Both together: GPU time -42 %, frame rate +62 % (about 184 to 299 fps,
vanilla terrain included). Face directions alone: -5 %, or -15 % with
the pre-pass, whose vertex work they halve (pre-pass 935 to 720 us).
Render thread: 413 to 462 us per frame in every case.

## ADR-047 — GPU occlusion culling tried, not kept; sections the game draws are skipped

**Date**: 2026-10-20 · **Status**: accepted (amends ADR-007's "GPU culling")

The user asked for GPU culling of hidden sections. Built and measured on
branch `experiment/occlusion-culling` (commit `7fa2d7b`): two phases (the
depth pre-pass of the sections visible last frame, a max-depth pyramid of
it by compute shader, every draw command tested against it and compacted,
the other visible sections drawn with `glMultiDrawArraysIndirectCount`),
tight per-section quad bounds (a level-3 section is 256 blocks tall and its
whole box always reached the sky over a ridge), and the LOD sections the
game draws entirely as near occluders (finest level, no see-through faces).
Images unchanged, no popping by construction.

Measured in the Fabric dev client, uncapped: 2 to 6 % of the draws culled
in the test world, 3 to 7 % in an amplified world, GPU time unchanged or
worse, render thread +60 to +100 us. The terrain that hides the horizon is
the game's own near terrain, drawn after the LODs and outside their depth;
the few LOD sections fully under it make poor occluders (most hold leaves).

- **Not kept**: occlusion culling, the depth pyramid, the tight bounds.
  Worth another look only if the LODs ever draw after the game's terrain
  (its depth available) or for a far tier where LOD terrain hides LOD.
- **Kept**: a LOD section whose every chunk section the game draws (the
  rule of `vanillaDraws` in `lod.frag`, run on the CPU over the same
  compiled-sections grid; levels 0 and 1; one block of margin on Sodium's
  float distance) is not drawn at all, since every fragment would be
  discarded. Measured in the amplified world, three views: GPU -4 to -6 %
  (2,833 to 2,722, 2,690 to 2,526, 3,602 to 3,428 us), 35 to 44 sections
  skipped, images unchanged over the LODs. The stat line gives the
  sections left to the game.

## ADR-048 — Indexed quads, a section cache, thread settings, coarser far preview

**Date**: 2026-10-20 · **Status**: accepted

After the occlusion culling trial (ADR-047) the user asked for other gains,
in the order proposed: rendering, LOD building, the noise preview, then a
measure of the shaded pass. All measured in the Fabric dev client,
frame rate uncapped, RTX 3070 Laptop, 2582x1550, `farfield-test`.

- **4 vertices per quad**: indexed indirect draws
  (`glMultiDrawElementsIndirect`, 5-int commands) over one shared index
  buffer of 131,072 quads (indices `4q, 4q+1, 4q+3, 4q, 4q+3, 4q+2`); the
  base vertex is the group's start times 4, `lod.vert` reads the quad at
  `gl_VertexID / 4` and the corner at `gl_VertexID % 4`. A longer run takes
  several commands. Same view, same sections: GPU 2,874-2,896 to
  2,310-2,336 us per frame (-19 %), about 300 to 360 fps, same image.
- **Decoded section cache** for the mesher threads (`SectionCache`, 1,024
  sections, least recently used out): a mesh reads its section and six
  neighbors. The store's write listener invalidates an entry and stamps
  the key; a read that started before a write is not kept. Reading fell
  from 62 to 14 us of a 1.4 ms job: a small gain, larger when reads are
  slow (934 us per job at a cold start). A "LOD meshing" stat line gives
  jobs, threads, time per job, read time and cache hits.
- **Thread settings**: `mesh_threads` and `preview_threads`, Auto by
  default (one per four logical processors, 1 to 4: 2 on 8, 4 on 16) or 1
  to 8; two sliders with Auto first; both pools resize live.
- **Far preview tiles computed coarser**: heights every 4 blocks up to
  1,024 blocks from a player, 8 up to 2,048, 16 beyond, the samples between
  filled bilinearly; a tile is built again finer when a player comes
  closer (tile format 5 stores the grid, in the summary too). Neighbor
  border layers of preview sections compute only their three rows of
  columns and leave trees out. The 233 tiles within 4,096 blocks: about
  2 minutes with 4 threads, against about 6 with 2 threads and the full
  grid; no seam seen.
- **Shaded pass, measured** (temporary switches, removed): of 2,330 us,
  ambient occlusion costs 228, biome blending 65, textures 73; with all
  three off the opaque pass still takes 1,322 us. The rest is geometry
  paid in both the pre-pass and the shaded pass, so the next GPU lever is
  fewer quads, not the shader.

Open: preview section meshing still costs 2.5 to 3.7 ms per job without
any store read (whole-section builds, tree stamping); a vanilla section
waiting for its neighbors (`hasAllNeighbors`) is compiled but not drawn
and the LODs give way there (one transient hole seen at the vanilla edge).

## ADR-049 — Snow on preview tree tops; vanilla spruce and pine crowns

**Date**: 2026-10-21 · **Status**: accepted (extends ADR-040, ADR-045)

The user saw no snow on the noise preview's trees in snowy biomes, the
same gap the ice was (ADR-045).

- **Which trees**: vanilla's freeze_top_layer lays a snow layer on the
  highest block of each column, leaves included. A tree kind carries a
  snow material (`Blocks.SNOW`, one layer; -1 for none) when the block
  above its crown is cold enough to snow (`coldEnoughToSnow`, the chunk's
  biome, as the snowy ground). Columns (cactus, bamboo) take none. Tile
  format 6; older tiles are built again.
- **Drawing** (`PreviewVoxels`): on the finest level a snow layer over
  each crown block that no row above covers, laid after every tree so it
  never takes the place of another tree's leaves; on coarser levels a tree
  voxel holding such a block shows the snow, as the real mip rule's view
  from above. Counted from areas on coarse levels like the leaves
  (ADR-048), checked against block by block in a unit test. Snow raises
  the tile summary by one block.
- **Crowns as vanilla's**, found by comparing preview and real snowy
  taiga side by side (snow made the gaps plain): spruces take tiers
  (`TIERED`: radius 0, 1, 0, 1, 2, 1, 2, 3, ... from the top, vanilla's
  spruce foliage); pines stay cones but no wider than their rows allow
  (vanilla's pine widens one block per row; the sampled radius gave 4,
  a 9-block wedding cake of snow); cone and tier rows leave out their
  corners from radius 1, as vanilla's spruce and pine do (a plus on top).
- **Placer types by class** (`instanceof` on the vanilla spruce, pine and
  mega pine placers), modded placers by class name: the name test alone
  saw intermediary names on Fabric outside the dev client, so its
  spruces and pines drew as blobs there.
- Cost, `PreviewBenchmark` whole section, 10 spruces per chunk: level 2
  232 to 266 us with snow, level 4 630 to 730 us (+15 %); level 0 the same.

Not covered: snow on the noise preview's village roofs (already snow
blocks for snowy villages), icebergs.

## ADR-050 — Partial blocks keep whole faces on coarse levels; preview snow by vote

**Date**: 2026-10-21 · **Status**: accepted (amends ADR-049)

The user, in the Fabric dev client right after ADR-049, through a
spyglass: "comme si les feuilles étaient transparentes". Far snowy preview
forests (level 1) showed holes down to the ice and sky.

- **Cause**: coarse levels draw a partial block (snow layer, slab) as a
  full cube, but its face textures were baked from its model: a snow
  layer's sides cover 2 of 16 rows, the rest flagged as cutout holes. The
  cube's sides were cut away, and as an opaque voxel it hid its
  neighbors' faces. Real coarse voxels that take a snow layer (snowy
  trees, snow on steps) had the same holes.
- **Fix** (`MaterialColors`): a partial block's faces get the rest of
  their tile filled with their own mean color, not darkened (opaque
  materials keep the 45 % fill of ADR-025's leaves). The finest level
  draws only the shape's part, whose texels did not change.
- **Preview snow on coarse levels by vote**: a tree voxel shows the snow
  when the snow layers lying in it (at their own height, one block above
  the open leaves) cover half its columns or more, as the real mip rule's
  most frequent top of four columns; ADR-049's "any open block" turned
  far forests into white cubes once the holes were gone. Far forests now
  show green and white like the real ones beside them.

## ADR-051 — Preview sections mesh by their draw level

**Date**: 2026-10-21 · **Status**: accepted (completes ADR-050)

After ADR-050 the user still saw, through a spyglass, preview spruces
and pines as hollow rings with floating snow plates. Reproduced up close
in the Fabric dev client at render distance 2 (`/tp 2960 84 -490 -142
11.6`, a preview pine at 3018, 64, -566): every leaf in a horizontal
layer that also held snow layers was missing.

- **Cause**: `LodRenderer.mesh` chose the classifier from
  `LodKey.level(key)`, which for a preview key carries
  `PreviewLayer.KEY_LEVEL_OFFSET`. Level-0 preview sections were meshed
  with the coarse classifier, where partial and shaped blocks count as
  opaque cubes: a snow layer hid the side faces of the leaves beside it,
  while the shader drew it 2/16 high. The preview had no partial block
  before ADR-049, so the bug stayed hidden.
- **Fix**: the draw level (`drawLevel(key)`) picks the classifier, as
  everywhere else that reads a level from a key that may be a preview one.
- Checked: the stored tiles and their level-0 meshes were complete (an
  offline scan of 81 sections: every face of an opaque voxel next to air
  emitted), which pointed at the renderer; after the fix the same pine
  is whole, and the view of the user's screenshots shows no ring.

## ADR-052 — Vanilla hand-off from its visible sections

**Date**: 2026-10-21 · **Status**: accepted (amends the vanilla side of ADR-011's hand-off)

The user, Fabric dev client without Sodium: the vanilla-edge flicker was
back, a chunk gone for one or two frames while moving ("j'ai l'impression
qu'on y retombe toujours dessus").

- **Cause**: LODs gave way to every section compiled for two passes. Vanilla
  draws only the compiled sections of `LevelRenderer.visibleSections`, its
  occlusion graph (rebuilt in the background after the camera moves 8
  blocks) cut by the frustum. A temporary counter in the dev client: 15 to
  31 non-empty compiled sections in view missing from that list in almost
  every frame of a flight, so neither side drew them for a frame or two.
- **Fix** (vanilla only): an accessor on `visibleSections`; the
  compiled-sections grid is rebuilt each frame from that list. Sodium keeps
  the compiled rule of 2026-10-16 (its own lists, not read here).
- Checked by the user: "Le flicker a disparu". Left: thin lines at the
  vanilla edge over sea ice that appear late (2026-10-21 progress log).

## ADR-053 — Sky light fades through preview seas

**Date**: 2026-10-21 · **Status**: accepted (extends ADR-039)

The user: LOD sea floors lit as if no water were above. The real LODs
carry vanilla's light (a scan of the stored sections: 14 under the
surface, one less per block, 0 from 15 blocks down); the noise preview set
none, so its floors kept full sky light.

- `PreviewVoxels` gives each water voxel the sky light of its highest
  block, `15 - (sea - y)`, full when the voxel also holds the air above the
  sea; the floor's top face takes the light of the water in front of it.
- Checked in the Fabric dev client at the user's view (2394, 126, -162):
  the preview patch that stood out pale among the real frozen ocean is as
  dark as the real floor around it. Unit test `skyLightFadesThroughTheSea`.

## ADR-054 — Frozen ocean icebergs in the noise preview

**Date**: 2026-10-21 · **Status**: accepted (extends ADR-039, ADR-045)

The user: the preview grows no icebergs, a sharp cut beside the real ones.

- **Source**: the tall jagged icebergs of frozen oceans are not a feature
  but vanilla's surface rule `SurfaceSystem.frozenOceanExtension`, column by
  column from three noises (`iceberg_pillar`, `iceberg_pillar_roof`,
  `iceberg_surface`); the preview reads the same noises from the world's
  `RandomState` and computes the same top (without the rule's random holes,
  less 2 where the biome melts them slightly).
- **Tiles**: each computed height sample under the sea of a frozen ocean
  (its biome at the sea's top) rises to its iceberg's top; cells touching a
  raised sample take packed ice, with snow on top from sea + 22 (vanilla:
  a few snow blocks from 18 to 27, random). The tile stores the iceberg
  material (format 7); `PreviewVoxels` draws such cells in it down to their
  foot instead of the tile's stone under the filler. Far tiles (8 or 16
  blocks between samples) raise only their computed samples.
- Not covered: the smaller round and prism icebergs of the `iceberg`
  features (one in 16 chunks), the underwater part of icebergs (the
  preview column is ice from its top down to the floor beside it).
- Checked in the Fabric dev client at the user's view (2394, 126, -162,
  facing south): the icebergs go on past the real terrain with their spires
  and snow caps; `/farfield preview off` leaves only the real ones. Tiles
  built in 264 ms each (4,096-block radius). Unit test
  `icebergsAreIceToTheirFoot`.

## ADR-055 — Translucent layers as vanilla, sorted far to near

**Date**: 2026-10-24 · **Status**: accepted (amends the translucent rule of ADR-008's mesher)

The user (Fabric without Sodium): a band one block wide, lighter than the
ice around it, stays along one side of a chunk that just turned vanilla,
and goes away further on. With `/farfield debug levels` the band is not
red: vanilla geometry, with the LOD terrain showing through it.

- **Cause**: vanilla draws two translucent layers over a frozen sea seen
  from above, the ice's top and the water's top under it (8/9 high), and
  more toward the ice's bottom from below. The LOD mesher culled every face
  between two translucent voxels, so LOD ice was one layer, lighter than
  vanilla's. At the vanilla edge, a ray entering the last vanilla column's
  ice crosses into the LOD chunk before it reaches the water: it misses
  vanilla's water top and finds no LOD one, hence the lighter band on the
  far side of the edge. It moves with the edge, which is why it "turns
  vanilla" further on (F3+A rebuilt the edge and moved it too).
- **Mesher**: a translucent voxel hides only translucent neighbors of its
  own kind, fluid or not (`MaterialClassifier.isFluid`, set for
  `LiquidBlock` and the preview's fluid mask): water against water and ice
  against ice stay culled, ice against water shows both faces. Neighbor
  sections' solid layers still hide both kinds. Two different non-fluid
  materials (ice against glass) still show no face. The extra planes are
  built only in sections holding both kinds.
- **Draw order**: with two layers, the translucent sections are drawn far
  to near by their center's distance, as vanilla's. In selection order, a
  ray through one section's ice could reach a neighbor's water drawn
  later, blended over the ice: dark one-block lines along section borders.
- Checked in the Fabric dev client at -193.6, 120, -13.5 facing south
  (pitch 37), before and after: the brightness step across the vanilla
  edge falls from about 15 levels (with a band 13 above vanilla) to about
  7 with no band, and no line along section borders; with Sodium, no band
  and no mixin error. Unit test `iceOverWaterShowsTheIceBottomAndTheWaterTop`,
  the brute-force check covers both kinds.
- Not done: the LOD ice stays slightly lighter than vanilla's (about 5
  levels on that view); quads inside one section are not sorted.

## ADR-056 — LOD meshes and preview tiles nearest and in front first

**Date**: 2026-10-24 · **Status**: accepted (amends the request order of the LOD renderer and of ADR-038's preview)

The user felt LOD building and the noise preview had become slower. The
logs said otherwise: in the user's pack, the preview built the 4,096-block
range in about 2 min on 2026-10-24 against about 5 min on 2026-10-18, and
the mesher sat mostly idle (`0 meshing`). The user's reading: the work is
spread evenly around the player, so what is in view and near arrives no
sooner than what is behind.

- **Client**: `LodSelector` walks the grid from one corner and the
  renderer took the first 64 sections it wanted, often far and behind
  the camera. The wanted sections (and the stale ones) are now ordered by
  `WantOrder.score`: distance to the nearest point of the section, counted
  up to 4 times farther behind the camera (out of the frustum on the
  client), then requested in that order. The selection also runs again
  when the view turns by about 25 degrees.
- **Server**: the preview tiles are ordered by the same score, with the
  cosine between the player's horizontal view and the tile's direction,
  and scheduled every 10 ticks instead of 40: with 8 tiles in flight and
  tiles of about 0.5 s on 4 threads, the threads sat idle between updates.
- **Fill lines**: `LOD view fill after <joining|a teleport>` (sections
  missing in view at 0 to 60 s) and `Noise preview fill after <joining|a
  jump>` (tiles within 1,024 blocks, in front within 60 degrees, all).
- Measured in the Fabric dev client, the test world restored from one copy
  before each run, the player teleported by the server to 12000, 160, 8000
  (never generated, 236 preview tiles, render distance 4, facing south):

  | Run | Tiles within 1,024 | In front | All |
  |---|---|---|---|
  | Before (nearest only, every 2 s) | 16.0 s | 68.0 s | 68.0 s |
  | After, 64 in flight | 20.0 s | 26.5 s | 34.0 s |

  The tiles in front are done 2.5 times sooner and all of them in half the
  time; the near tiles behind come 4 s later, as intended. The client's
  sections missing in view after the teleport stay at 0 to 2 in every run:
  the preview is what the view waits for, not the mesher.
- **Mesh jobs in flight**: 32, 64, 96 and 128 gave the same times (26.5,
  26.5, 25.5 and 26.5 s in front; 34 to 33 s for all). It stays at 64.
- Not measured: the order's effect when joining a world with stored LODs
  (the first 5 to 10 s are dominated by cold jobs of 10 to 24 ms); NeoForge;
  the user's pack.

## ADR-057 — Per-column caches in the preview's density

**Date**: 2026-10-24 · **Status**: accepted (amends the surface search of ADR-038)

After ADR-056 a teleport waits for the preview tiles. Per-stage timers in
the minute line (`heights`, `cells`, `biomes`, `villages`, `trees`,
`write`) and a JFR recording around a teleport (Fabric dev client, 12000,
160, 8000, 236 tiles over frozen sea) showed where a tile goes:

- heights 499 of 569 ms per tile; 82 % of the preview threads' samples in
  `PerlinNoise.getValue`, 77 % under the terrain splines, 45 % in the
  shift noises, 7.6 % in the 3D `BlendedNoise`.
- The surface search reads `finalDensity` with a `SinglePointContext` about
  eight times per column. That context ignores vanilla's cache markers, so
  every read recomputed continentalness, erosion, ridges, their shift
  noises and the splines over them, all `flat_cache` or `cache_2d`: 2D
  functions that `NoiseChunk` computes once per column.

**Decision**: `ColumnDensity` maps the router's density once per preview
thread, replacing `flat_cache` and `cache_2d` markers by a cache of the
last column and `cache_once` ones by a cache of the last point, as
`NoiseChunk` does (samples are 4-block aligned, the quart corners vanilla's
flat cache reads). The 3D noise is still read at every point.

- Same run, same world copy: heights 499 to 191 ms, a tile 569 to 259 ms;
  after the teleport, tiles within 1,024 blocks 21.5 to 9.7 s, in front
  28.0 to 14.7 s, all 35.5 to 20.7 s. The 558 tile files of both runs are
  byte-identical.
- Left: the biome lookups (31 % of the samples with the cache), mostly the
  per-cell frozen-sea check over cold seas, and the 3D noise (25 %).

## ADR-058 — Shared climate caches and one sea biome per quart in the preview

**Date**: 2026-10-24 · **Status**: accepted (extends ADR-057)

With ADR-057 the biome lookups were 31 % of the preview threads' samples,
most of them two lookups of the same quart: the iceberg check per sample
and the frozen-sea check per cell both ask the biome at the sea's top, and
a sample and the cell it starts share their quart.

- A tile keeps the sea-level biome of each quart it looked up; the iceberg
  and frozen checks share it.
- The climate sampler's six functions are mapped once per preview thread
  by `ColumnDensity`, with one cache per distinct marked function (equal
  records share it, as in `NoiseChunk.wrap`): the shift noises under
  temperature, humidity, continentalness, erosion and weirdness, and the
  splines under depth, are computed once per lookup instead of once per
  function. `finalDensity` gets the same sharing.
- Same run as ADR-057's: a tile 259 to 202 ms (heights 191 to 140, which
  hold the iceberg lookups; cells 53 to 45); after the teleport, tiles
  within 1,024 blocks 9.7 to 7.8 s, in front 14.7 to 12.8 s, all 20.7 to
  18.8 s.
- Not byte-identical this time: 2 of 558 tiles differ by one frozen-sea
  cell each, all else equal (heights, materials, biomes, trees, decoded
  and compared). Vanilla's `Climate.RTree.search` starts from the thread's
  last result and keeps it on an exact distance tie (`i > k`), so the
  order of lookups breaks ties; changing that order moved two ties. The
  same holds in vanilla's own generation.

## ADR-059 — Plants as crossed quads on the finest level

**Date**: 2026-10-25 · **Status**: accepted (amends ADR-041's "plants without a collision stay out")

The user: plains in the far view look "affreusement vides", next to
vanilla's grass. Every LOD left blocks without a collision out (grass,
flowers, ferns, saplings, crops), real terrain and preview alike. The
user chose crossed quads on the finest level and a ground tint farther
(this ADR is the first part; the tint and the preview's plants follow).

- **Class**: `MaterialClassifier.PLANT` for blocks without a collision
  that are a `BushBlock` (grass, flowers, ferns, saplings, crops, tall
  plants, mushrooms, berry bushes) or sugar cane. Mips keep them sparse
  (unchanged), coarse levels mesh them as air. Level-0 sections already
  store them: no import needed.
- **Mesh**: each plant voxel gives two unit quads, directions 6 and 7 of
  `Quad` (the voxel's two diagonals), in `MeshBuffer.PLANTS`; a plant
  hides nothing and nothing hides it. Lit like its own voxel, biome as
  any tinted material.
- **Shader**: `lod.vert` stands each diagonal inset as vanilla's cross
  (0.05 to 0.95) and winds it toward the camera, so back-face culling
  keeps one quad per diagonal; no shade, no ambient occlusion.
  `lod.frag` cuts the texture's holes and drops plants beyond the texture
  distance (they would be solid rectangles).
- **Look**: `MaterialColors` lays the sprite of the model's first quad
  whole on face 0 (height along the columns, width along the rows), its
  biome tint kept: grass takes the biome's color.
- **Draw**: plants have their own group and their own draw list, drawn
  once after the opaque pass (depth test and write, holes cut), outside
  the depth pre-pass. Measured in the Fabric dev client over plains
  (-1264, 107, 882 facing east, the plains 110 blocks ahead, render
  distance 4): GPU 2,255 us without plants; with plants in the sections'
  own passes 3,688 us (a section with one plant moved to the cutout
  pre-pass, and the thin quads were drawn twice); with their own pass
  2,468 us (+213 us, 48,000 plant quads in view).

## ADR-060 — Grounds covered by plants on coarse levels; plants in the preview

**Date**: 2026-10-25 · **Status**: accepted (extends ADR-059; amends ADR-020's material rule and ADR-040)

The second and third parts of the user's choice (ADR-059): beyond the
finest level the plants' crosses stop, so the ground there takes their
color; and the noise preview, whose plains the user found empty first,
gets its plants.

- **Covered materials**: `CoveredKey` (`farfield:covered|<quarters>|<plant>|<ground>`)
  names a ground seen from above with a plant over 1 to 4 quarters of it.
  It is its ground everywhere (`BlockKeys.state` returns the ground: class,
  shape, height, boxes); `MaterialColors` speckles its top face with texels
  of the plant's sprite, on 45 % of the pixels at four quarters, tinted
  plants in the face's tinted part (grass on grass: one biome color). The
  far, untextured levels see the mean of that face.
- **Mips** (`MIPS_VERSION` 3, coarse levels rebuilt once at world open):
  a column whose ground has a plant on it counts four quarters on level 1;
  coarser columns pass their covered child's quarters on. The parent of a
  ground takes the mean over the columns showing ground, rounded, and the
  most frequent plant (`MipBuilder.Shapes.covered`, interned by
  `MaterialTable` and persisted with the other keys).
- **Preview** (tile format 8): `PreviewTrees` keeps the single blocks
  without collision drawn as plants, with their mean count per chunk
  (patch tries of plants succeed 80 %, against 15 % for trees); a tile
  stores per chunk the kinds and counts, and the ground plants grow on
  (grass). On level 0 a plant stands on that ground where the block's hash
  falls under the chunk's cover `1 - exp(-count / 256)`, its kind drawn by
  count; coarser levels give the top voxel the ground covered by the most
  common plant over that cover in quarters. Plains: 216 plants per chunk
  of 5 kinds, 57 % covered.
- Fabric dev client, the plains of ADR-059 looked at nearly level: tufts
  and flowers over the real terrain and the preview beyond it to the
  distance; 8,858 coarse sections rebuilt at open; GPU 2,326 us.

## ADR-061 — Map colors until a look is built; the preview's plants by the renderer's rule

**Date**: 2026-10-25 · **Status**: accepted (amends ADR-010's budgeted build and ADR-060)

The user's pass of ADR-060 in the Fabric pack, with screenshots:

- **Black at the first load after the game starts**: leaves and part of the
  terrain drawn black until everything had loaded, not after leaving and
  joining again. `MaterialColors` builds looks under 3 ms per frame and a
  material not built yet had color 0: black, for seconds over the pack's
  thousands of states. Every new id now gets a placeholder at once, its map
  color on every face without texture, with its height and boxes; the
  budgeted build replaces it. The renderer uploads up to the placeholders
  when new ones came (`changedEnd`). Plants without tiles stay hidden
  (`lod.frag`), as before.
- **Blue-green cubes all over a savanna's preview**, absent from the real
  terrain: the preview kept `minecraft:seagrass` and
  `cobblemon:galarica_nut_bush` as plants (no collision, a bush), while the
  renderer draws the first as water (waterlogged) and the second as a cube
  (full outline). The preview now asks `BlockKeys.isPlant`, the renderer's
  own rule; tile format 9 builds the old tiles again.
- Checked: Fabric dev client captures 3, 6 and 12 s after the renderer is
  ready, colored, no crash (vanilla states only: the pack is the real
  check); the user's savanna tile decoded: acacia, oak, pumpkin kinds,
  plants dandelion, short grass, mushrooms, seagrass, medicinal leek,
  galarica nut bush, tall grass.

## ADR-062 — Sections wait for their looks; more time for looks while many wait

**Date**: 2026-10-25 · **Status**: accepted (replaces ADR-061's map-color placeholders)

The user on ADR-061's placeholders: correct, but "le premier affichage
avec seulement la couleur est vraiment très moche", and asked for no
provisional color, and for more time to build looks the more are
waiting, at join, after a teleport or anywhere, without the game falling
under 60 fps when it is above.

- No placeholder: a material without its look keeps color 0, and a
  section whose highest material has no look yet is not drawn
  (`Resident.maxMaterial`; looks are built in id order). Terrain appears
  section by section with its final look; the sky shows meanwhile. The
  faces' mean colors stay: they draw the far levels beyond the texture
  distance, the fade into it, and faces whose atlas tile is missing.
- Budget: 3 ms per frame, or, while more than 64 looks wait, what the
  previous frame left before 60 fps (its interval minus the looks' own
  time, up to a whole 60 fps frame): a game under 60 fps keeps the 3 ms
  and loses no more than before. `LOD looks: N built in T ms` closes each
  backlog.
- Fabric dev client, game start then the test world: `LOD looks: 1836
  built in 3001 ms`; captures 3, 6 and 12 s after the renderer was ready
  show the final looks only. Not measured: the old budget on the same
  start (no log line then), the user's pack (thousands of states).

## ADR-063 — Spyglass detail in view only; no section kept empty for want of room; waterlogged blocks are fluid

**Date**: 2026-10-25 · **Status**: accepted (amends the selector of ADR-022, the arena's out-of-room rule, ADR-055)

The user's pass of ADR-062 in the Fabric pack (looks: `8671 built in 3838
ms`, then about 1 s on later joins), with screenshots:

- **LOD sections missing for minutes** after going near and back. The
  user had looked through a spyglass: the narrow projection raised the
  detail from 4.6 to 62.6 section sizes, every section around split to
  level 0 (126,760 resident, 1,023 of 1,024 MB of quads), and a mesh that
  found no room was kept as an empty resident "until evictions free
  space": ready to the selection, never meshed again, while eviction
  frees only sections unused for 10 s. Two fixes:
  - `LodSelector.setOutsideView`: sections outside the frustum split at
    the detail of the options' field of view at most; the zoomed detail
    serves what the zoomed view shows.
  - Room before residence: the quads of a mesh are allocated first; on
    failure the arena drops the sections the last selection did not use
    and tries again; still nothing, and the result waits (an existing
    section keeps its old mesh and turns stale) instead of an empty one.
- **Dark squares in the water around kelp**: kelp, seagrass and other
  waterlogged blocks are drawn as water but were a translucent non-fluid,
  so since ADR-055 their faces against the water around showed (water
  over water). They are a fluid now (`MaterialTable.packFluid`).
- Checked: core tests with `sectionsOutsideTheViewKeepTheUsualDetail`;
  Fabric dev client boot, `LOD looks: 2083 built in 1644 ms`, 1,562
  sections drawn, no error. Not checked: the spyglass and the kelp in the
  user's pack.

## ADR-064 — The kelp's body is water

**Date**: 2026-10-25 · **Status**: accepted (amends ADR-063's waterlogged rule)

ADR-063 made waterlogged blocks a fluid, but the user's dark squares
around kelp stayed. `/farfield render off` at the spot (a spyglass view)
showed only sky: LOD geometry. A dev client bench over a cold ocean,
logging the class `BlockKeys` gave each kelp and seagrass state, found
`minecraft:kelp_plant` (the kelp's body) a translucent non-fluid: its
outline is a full block, so the full-block test took it before the
waterlogged one, and its look was the kelp texture on a cube. Each kelp
column was a translucent textured cube with faces against the water
around (ADR-055), dark through the sea.

- A block with water in it is a fluid unless it is an opaque full block,
  tested before the full-block rule; `lookOf` gives it the water's look.
- `MaterialTable.covered` refuses translucent grounds, and
  `MaterialColors` leaves covered translucent grounds plain: the same log
  showed mips covering seagrass with plants (`farfield:covered|1|
  minecraft:short_grass|minecraft:seagrass`).
- Checked: the bench before (dark kelp columns through the water) and
  after (clean water, `kelp_plant` a fluid). Kelp is now invisible in
  LODs, as seagrass: drawing it as crossed quads under water is left open.

## ADR-065 — Kelp and seagrass are water with crossed quads

**Date**: 2026-10-25 · **Status**: accepted (amends ADR-064)

ADR-064 made kelp water, so it vanished from LODs. The user asked for it
back as crossed quads, like the plants of ADR-059.

- A plant without collision standing in its fluid (`BushBlock` or
  `GrowingPlantBlock` with a fluid state: kelp, seagrass, a waterlogged
  propagule) packs as `MaterialTable.packSubmerged()`: a translucent fluid,
  as before, with a flag. `MaterialClassifier.hasPlant` reads the flag,
  `fluidOf` names its fluid (the resolver's `fluid(key)`, the state's
  legacy fluid block). Every rule that treats it as water stays as it was:
  culling, neighbor planes, mips, the preview's masks.
- The mesher gives its translucent faces the fluid's material (water over
  kelp merges with the water around) and, on the finest level, the two
  diagonals of `PLANT` with the plant's own material. Coarse levels keep
  only the water (the coarse classifier has no `hasPlant`, but passes
  `fluidOf`), so stored coarse voxels holding kelp draw as water: no
  `MIPS_VERSION` bump.
- `MaterialColors` builds such a material as a plant, and `lookOf` keeps
  the plant's state: its look is its sprite, never the water's.
- The plants' pass draws before the translucent one, so the water blends
  over the kelp.

## ADR-066 — The repository is public

**Date**: 2026-10-25 · **Status**: accepted (amends ADR-002; ADR-003 still open)

After release 0.1.0 and the logo, the user made `JordanOlivet/farfield`
public: "maintenant qu'on a une première version et qu'on travaille le
branding, c'est ok".

- Checked before: no token or key in the git history (Modrinth `mrp_`,
  GitHub `ghp_` and `github_pat_`, JWT, AWS keys, private keys, `token=`
  or `password=` lines: none); `local.properties` never committed. The
  history holds local paths (`C:\Users\Lakio\...`) and the skills'
  autoharness notes of earlier commits: nothing secret.
- The license is still "All rights reserved" (`LICENSE`, mod metadata):
  ADR-003 meant to choose one before the first public release, and that
  choice is still the user's to make (options in the analysis, §2). Until
  then the code is visible, not reusable.
- Listing images can now link to the repository; the banners are hosted
  in the Modrinth gallery anyway, whose URLs work on both platforms.

## ADR-067 — License: LGPL-3.0-only

**Date**: 2026-10-25 · **Status**: accepted (closes ADR-003)

The repository is public (ADR-066). The user chose LGPL-3.0 among the
options of the analysis (§2): "Partons sur LGPL-3.0".

- **Why**: the license of Distant Horizons, Iris and Nvidium, known to
  players and pack makers; improvements to Farfield that are distributed
  stay open; mods that only use Farfield and modpacks keep any license;
  LGPL code of Iris or Distant Horizons may be reused (shader packs,
  M2.C). The code is ours alone (clean room, ADR-001), so the choice was
  free. Not legal advice; the analysis suggested a short outside check.
- `LICENSE` holds the LGPL-3.0 text, `COPYING` the GPL-3.0 it supplements
  (both from gnu.org); every jar carries them as `LICENSE_farfield` and
  `COPYING_farfield`. `license=LGPL-3.0-only` in `gradle.properties`
  (SPDX) feeds both loaders' metadata; the copyright holder stays "Lakio",
  as in the former `LICENSE` and `mod_author`.
- Modrinth's license is set from `gradle.properties` by
  `docs/listing/modrinth.py push-license`; CurseForge's is set by the user
  in the project settings.
- Versions up to 0.1.0 were published "All rights reserved"; the next one
  is the first under LGPL-3.0. Relicensing later needs the agreement of
  every contributor whose code is in.

## ADR-068 — Server streaming of LODs and the noise preview

**Date**: 2026-10-25 · **Status**: accepted (M2 B.1; details ADR-013, ADR-022). Proposed,
then accepted with the user's "go B.2"; the client storage below was
rewritten before acceptance, from the first implementation.

With Farfield on the server, the server builds the LODs and the noise
preview and sends them to its players. Today a dedicated server builds
LODs that nobody receives, the client builds its own from what it has
seen (client mode, ADR-022), and the preview needs single player
(ADR-038). The user: "il faut que le server génère les LOD et la preview
et l'envoi aux clients (comme on l'avait fait sur neo-voxy)". Neo-Voxy's
concepts are reused, not its code (ADR-001).

### What Neo-Voxy taught

- It pushed rings of sections around each player every 40 ms and cleared
  its "sent" map on a teleport: about 16 MB/s on the game connection,
  ping at 1 to 3 bars, input delayed, a 1 s stall of its own scheduler
  (`neo-voxy/docs/diagnostics/lod-streaming-network-flood-analysis.md`).
  Fixed by a per-player and global byte budget, small packets, nearest
  first, no clear-and-burst on teleport.
- What worked and is kept: a shared cache of serialized sections (one
  serialization per section version for all players), serialization on a
  small worker pool, a per-player and a global bandwidth cap, client AIMD
  congestion control (start at 1 MB/s, +50 KB/s per second, back off on
  congestion), a mapping table from the server's block and biome ids to
  the client's, a capability handshake, and a per-client "already sent"
  state persisted under a cache epoch, so a rejoin streams only changes.
- Its defaults, the first values here: 1,024 KB/s per player, 10,240 KB/s
  for all players, serialization threads min(4, cpus / 2), 4,096 cached
  serialized sections, debug logs off.

### Design

- **Pull, not push.** The client asks; the server answers. The renderer
  already knows what it lacks and in what order (`WantOrder`: nearest and
  in view first, reselected when the view turns). The client sends those
  keys with the version it holds (none, or the one in its cache); the
  server answers each with the section, "unchanged", or "empty". At most
  N requests in flight per player (first value 64, as `MAX_IN_FLIGHT`),
  so a teleport costs a new request order, never a burst: Neo-Voxy's
  flood cannot happen by construction.
- **Version of a section**: the position of its record in the server's
  append-only store (`LogStore`): every rewrite moves it, a compaction too
  (a harmless resend). No extra state on the server.
- **Changes near the player**: when a section within a player's radius is
  rewritten, the server sends a small "changed: key, version" notice; the
  client requests it again when its renderer still wants it.
- **What travels**: a LOD section as its stored record (`SectionCodec`,
  zstd), its palette ids translated on the client through the server's
  key table (sent at join and as keys are added). Noise preview tiles as
  their files (`PreviewTile`, zstd, a few KB): the client builds the
  preview voxels itself, as in single player. Surface only by default
  (ADR-013): sections entirely below the surface of their columns are not
  sent unless the operator allows full data.
- **Client storage**: a mirror of the server's store per server and
  dimension (`farfield/servers/<server>/<seed hash>/stream/<dimension>/`),
  the same format and the same key ids as the server's (the client takes
  the server's key tables in id order and empties the mirror when they
  disagree, or when the server's store has another epoch). Received
  records go in as they are, never rebuilt into mips; the versions held
  are kept beside them (`versions.bin`), so a rejoin only checks them.
  The client builds nothing itself while the server streams: a client
  building level 0 next to coarse levels received from the server would
  rebuild those from its own few chunks and blank them. A server whose
  settings turn streaming off declines the hello (session -1), and the
  client falls back to client mode.
- **The noise preview on a dedicated server**: built around each player
  on low-priority threads, nearest and in front first (as in single
  player), and served like sections. An operator switch turns it off.
- **Transport**: Minecraft custom payloads on the game connection, at
  most 32 KB each, at most a byte budget per tick per player, inside the
  per-player and global caps; gameplay packets are never queued behind a
  burst. A side channel stays a later option (ADR-013).
- **Handshake**: protocol version and capabilities (sections, preview,
  surface only, compression) at join; a client or server without
  Farfield, or with another protocol version, falls back to client mode.
- **Server settings** (`config/farfield-server.properties`): streaming on
  or off, radius in blocks (first value 4,096, the renderer's range; the
  client's selection asks fine levels near and coarse ones far, so the
  far sections are few), the two bandwidth caps, serialization threads,
  the preview on or off and its threads, full data on or off.

### Steps (M2 B.2 to B.4)

1. Handshake, key table, request and answer of LOD sections, budgets;
   two dev clients on a dev server: fill time, bandwidth, ping.
2. Change notices and the persisted client cache (rejoin cost).
3. The preview built on a dedicated server and streamed.
4. The user's NeoForge pack on a server: fill time and bandwidth from the
   log lines.

## ADR-069 — Vanilla columns drawn whole at the hand-off

**Date**: 2026-10-25 · **Status**: proposed (amends ADR-052; vanilla renderer only)

The user, Fabric dev client without Sodium, walking along the vanilla
edge over a shallow sea: "de petites portions de terrain/eau disparaître
puis réapparaître très vite". Not seen with Sodium. The frames of their
video show, for one frame, vanilla kelp, seagrass and sea floor with no
water over them.

- **Cause**: vanilla builds and lists a new chunk's sections one at a time
  and draws each once listed. A sea floor section (kelp, sand) can be drawn
  a frame before the section above it that holds the water's surface. The
  LODs give way per section (ADR-052), so the LOD water of that upper
  section is drawn, but the LODs draw before the game's terrain and clear
  the depth after: the vanilla floor covers that water. A second video with
  level colors showed it plainly: a chunk switching from LOD to vanilla
  showed its dry floor for one frame (frames 111, 372, 494 and 517 of
  `flicker2.mp4`).
- **Decision**: before vanilla draws, the hand-off goes down each column it
  draws from the camera's height. Once a section with blocks, in view, is
  not drawn by vanilla (not built, or not in its list yet), the sections
  below it leave vanilla's list for that frame and the LODs draw them. The
  first version held back only sections below one listed but not built,
  and missed the water section not listed yet. Vanilla rebuilds the list
  only at a frustum update (a turn of the camera, a graph update), so the
  sections held back go back into it at the next frame and wait again only
  while their column is not whole. The stat line counts them.
- Sodium keeps its own lists; the user did not see the effect there.
- To check: the user's walk along the edge with the stat line's count
  above 0, and no column of LODs left in place of built vanilla terrain.
