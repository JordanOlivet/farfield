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
