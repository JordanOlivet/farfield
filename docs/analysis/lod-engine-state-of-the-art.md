# A new LOD mod: state of the art, lessons learned and design space

> **Status**: analysis written in the Neo-Voxy repository on 2026-10-14
> (merged there as PR #30), copied here as Farfield's reference. The Q&A that
> followed is settled: the binding outcomes are the ADRs in
> `docs/DECISIONS.md` (ADR-001 to ADR-016). Where this document lists
> options, the ADRs say which one was chosen.
> **Date**: 2026-10-14.
> **Scope**: everything we could use to build our own Minecraft LOD (level of
> detail) mod from a blank page: the ecosystem as of October 2026, what Distant
> Horizons and Voxy actually do (read from their source), the research
> literature, the lessons from two years of Neo-Voxy, and the candidate
> architectures. Section 18 lists the questions we need to answer together.
>
> Conventions: **[verified]** = read in source code or an official/primary
> source during this analysis; **[reported]** = third-party or author claims
> not independently checked; **[ours]** = measured or learned on Neo-Voxy.

---

## Table of contents

0. [Executive summary](#0-executive-summary)
1. [Goals, non-goals and success metrics](#1-goals-non-goals-and-success-metrics)
2. [Legal and licensing](#2-legal-and-licensing)
3. [Lessons from Neo-Voxy](#3-lessons-from-neo-voxy)
4. [Ecosystem landscape (October 2026)](#4-ecosystem-landscape-october-2026)
5. [Data model](#5-data-model)
6. [Storage](#6-storage)
7. [Data acquisition](#7-data-acquisition)
8. [Meshing and geometry](#8-meshing-and-geometry)
9. [Rendering](#9-rendering)
10. [Shader packs and mod integration](#10-shader-packs-and-mod-integration)
11. [Networking and multiplayer](#11-networking-and-multiplayer)
12. [Performance engineering on the JVM](#12-performance-engineering-on-the-jvm)
13. [Software architecture](#13-software-architecture)
14. [Product and UX](#14-product-and-ux)
15. [Candidate architectures](#15-candidate-architectures)
16. [Validation spikes and roadmap sketch](#16-validation-spikes-and-roadmap-sketch)
17. [Risks](#17-risks)
18. [Open questions for the Q&A](#18-open-questions-for-the-qa)
19. [References](#19-references)

---

## 0. Executive summary

**Why now.** Neo-Voxy is a fork of an "All rights reserved, do not
redistribute" mod, so none of its code can be published, and it has drifted
far from upstream. Meanwhile the platform is moving under every LOD mod at
once:

- Minecraft Java switched its default graphics API to **Vulkan** in snapshot
  26.4 (September 2026); OpenGL stays as a fallback and will be removed once
  Mojang is satisfied **[verified, Mojang changelog + announcement]**. Mojang
  explicitly told modders to "move away from OpenGL usage".
- The rendering API was split out of Blaze3D into **Renderpearl**
  (`com.mojang.renderpearl`, 26.3) with OpenGL and Vulkan backends, shaders
  compiled to SPIR-V with shaderc **[verified, NeoForge 26.3 primer]**. Its
  public surface has **no compute pipelines, no storage buffers and no
  multi-draw indirect** **[reported, Voxyrium compatibility table]**: a
  GPU-driven LOD renderer cannot be built on the public API alone.
- **Iris does not run on Vulkan**; a successor named **Aperture** is in
  development **[reported]**. Shader-pack compatibility is therefore about to
  be reset for everyone.
- **Voxy upstream** (26.2, Fabric only) is still OpenGL 4.6 only; a community
  Vulkan PR (#614, July 2026) was closed unmerged **[verified, GitHub]**. It
  proved the key trick: adopt Minecraft's own `VkDevice` and record LOD draws
  into the game's frame command buffer.
- **Distant Horizons 3.x** added a **Blaze3D rendering backend** next to
  OpenGL, to prepare for Vulkan **[verified, DH API enums + changelog]**, and
  its `dh_terrain`/`dh_water`/`dh_shadow` Iris programs are the de facto
  shader-pack standard **[verified, Iris docs]**.

A new mod designed **Vulkan-first, GPU-driven, clean-room and publishable**
would arrive exactly when both incumbents have to re-architect.

**Preliminary recommendations** (to challenge in the Q&A):

1. **Fresh repository**: new history, no code copied as is. Voxy-derived
   parts are rewritten from their behavior; our own Neo-Voxy concepts are
   reused freely (§2).
2. **Engine core independent of Minecraft** (pure Java library: data model,
   storage, LOD selection, meshing, protocol), thin per-version and per-loader
   adapters, and a **rendering hardware interface (RHI) with tiers**: native
   Vulkan by adopting the game's device (26.x), OpenGL 4.6 (1.21.x), and a
   reduced Renderpearl/Blaze3D fallback for portability (§9.1, §13).
3. **Data model**: generic 3D **paletted bricks with a mip pyramid**, not
   columns, plus a 2.5D summary level for the extreme horizon; consider DAG
   deduplication at the storage layer (§5).
4. **Storage**: one paged file per dimension (or LMDB), **zstd with trained
   dictionaries** on top of domain transforms (palette, bit packing, run
   lengths, parent-mip residuals). Target: LOD storage a fraction of the
   region files. On our test server the Voxy database is **1.7x the size of
   the world's region files** **[ours]** (§6).
5. **Rendering**: GPU-driven rasterization of greedy quads in clusters, with
   per-direction face groups, two-pass Hi-Z occlusion, indirect-count draws,
   reverse-Z depth and screen-space-error LOD selection with hysteresis. Ray
   marching stays an option for the farthest ring and for AO/shadows (§9).
6. **Seamless by design**: the hand-off band with vanilla terrain is a
   first-class feature (matching lighting, AO, normals and fog, dithered
   cross-fade, exact occupancy instead of AABBs) (§9.5).
7. **Shader packs**: aim for compatibility with the existing `dh_*` program
   contract where possible, and engage early with Aperture to define a
   generic LOD contract for the Vulkan era (§10).
8. **Multiplayer done right**: versioned pull protocol with hashes, strict
   bandwidth budget so gameplay packets are never delayed (our 16 MB/s flood),
   optional separate transport or HTTP tiles, anti-x-ray surface-only mode,
   and a Paper plugin (§11).
9. **Acquisition**: fast direct region-file import, LOD-only world generation
   without saving chunks, coarse noise sampling for an instant horizon,
   importers from DH/Voxy/Bobby data (§7).
10. **Tooling from day 1**: benchmarks, golden-image tests, debug overlays and
    dumps, scripted dev clients (§13).

---

## 1. Goals, non-goals and success metrics

### 1.1 User answers (2026-10-14)

| Topic | Answer |
|---|---|
| Repository | New, empty repository, separate from Neo-Voxy |
| Reuse | Do not copy Neo-Voxy code as is, but reuse the concepts, many of which are our own (see §2) |
| Minecraft versions | **Both** 1.21.1 and 26.x, planned from the start; the user plays 1.21.1 today, 26.x matters as mods migrate |
| License | Undecided; the repository stays **private** until the mod is presentable |
| Name | Working idea "Horizon Voxels"; open to suggestions (§1.4) |
| Platforms | **Windows and Linux** first |
| Minimum hardware | No announced minimum; a wide configuration range lets players scale the cost up or down |
| Shader packs | Goal: **any shader pack works without pack-specific configuration**, if at all possible (§10.1) |
| Multiplayer | Expect **large servers**: streaming must be very robust and fast |
| Unexplored terrain | Show it, even coarsely |
| Distance | Feel of infinite view; 64 chunks was already good in Neo-Voxy; allow 256, 512 and more, the player or the server operator picks the limit |
| Schedule and team | No deadline, personal project done properly; one developer plus Claude |

### 1.2 Requirements added on 2026-10-14

- **Bulk LOD build for an existing world**: when the mod is added to a world
  that already exists, one command builds the LOD of everything already
  generated, without a player having to visit it. It must be schedulable or
  at least throttled (run at night) because it can be heavy. Region-file
  import (§7) is the natural implementation.
- **v2, not a priority: world map with LOD state**: a map of the world where
  each chunk shows its LOD state (color overlay or similar) to spot problems
  quickly, and where errors and logs can be shown.

### 1.3 Goals (as stated by the user)

- Ultra-performant, seamless far-terrain rendering.
- Low RAM, VRAM and disk usage.
- Full control of the code, publishable (Modrinth, CurseForge).
- NeoForge and Fabric from one codebase (we just did this for Neo-Voxy).
- Free to break with existing architectures when it buys a real advantage.

### 1.4 Candidate non-goals (to confirm)

- Minecraft versions before 1.21.1.
- Rendering entities or block entities as LOD (beyond simple, opt-in cases).
- Replacing Sodium for near terrain (possible long-term, see §9.5).
- macOS: not targeted at first (user answer: Windows and Linux). Mojang now
  ships Vulkan on macOS through MoltenVK, so a Vulkan-first engine keeps the
  door open at low cost.

### 1.5 Name ideas

"Horizon Voxels" is free on Modrinth (no project, slug `horizon-voxels`
unused). Its weakness: "Horizon" is strongly associated with Distant
Horizons, so players may assume a fork or an addon. Alternatives whose
Modrinth slug was unused on 2026-10-14:

| Name | Slug | Note |
|---|---|---|
| Horizon Voxels | `horizon-voxels` | User's idea; descriptive; close to Distant Horizons |
| Voxel Horizon | `voxel-horizon` | Same idea, same caveat |
| Skyline | `skyline` | Short, evokes far views; generic word |
| Farfield | `farfield` | The "far field" of a renderer; distinctive |
| Vantage | `vantage` | "Vantage point"; short |
| Evervista | `evervista` | Coined word, unique, "endless view" |
| Overlook | `overlook` | A viewpoint over a landscape |

Taken or crowded: Vista, Vistas, Panorama, Longview (a reverse-Z mod),
Sightline, Afar. A trademark and web search is still needed before going
public.

### 1.6 Success metrics (proposal)

Define them before writing code, and measure them in CI and with a fixed set
of reference worlds:

| Metric | Example target | How to measure |
|---|---|---|
| Frame time added at 512-chunk LOD radius | < 2 ms on a mid-range GPU (RTX 3060 / RX 6600 class) | GPU timer queries, built-in overlay |
| VRAM per million visible LOD columns | Fixed budget, scaled by preset | Allocator statistics |
| Disk per km² of explored terrain | < 25 % of the region files for the same area | Reference worlds |
| Java heap held by the mod | < 300 MB at the default preset | JFR, heap histograms |
| Ingest throughput | > 2000 chunks/s on 8 cores from region files | Import benchmark |
| Time to a full horizon after joining | < 5 s (coarse), progressive refinement after | Scripted client |
| Network | Gameplay ping unchanged (+0 bars), configurable cap | Server and client graphs |
| Seam visibility at the hand-off band | Not detectable in blind A/B screenshots | Golden images, user tests |

---

## 2. Legal and licensing

> Not legal advice. Worth a short consultation with someone who knows
> software licensing before the first public release.

### What we may and may not reuse

| Source | License | Reading for ideas | Copying code |
|---|---|---|---|
| Voxy (upstream) | All rights reserved, "do not redistribute" **[verified, LICENSE.md]** | Yes (ideas and algorithms are not copyrightable) | **No** |
| Neo-Voxy | Fork of Voxy, never published (private the whole time) | Yes, and **our own concepts are reusable** | Not as is: files derived from Voxy code stay derivative even unpublished; our original designs are ours to reimplement |
| Distant Horizons | LGPL-3.0-only **[verified]** | Yes | Possible, but the copied files stay LGPL-3.0 and impose its obligations |
| Sodium | PolyForm Shield 1.0.0 **[verified]** | Yes | No; using its API from a mod is fine, building a product that competes with Sodium is not |
| Iris | LGPL-3.0 **[verified]** | Yes | As for DH |
| Nvidium | LGPL-3.0 **[verified]** | Yes | As for DH |
| meshoptimizer `clusterlod.h` | MIT **[reported]** | Yes | Yes (C++, reference only for us) |

### Clean-room process (proposal)

0. Clarification from the user (2026-10-14): Neo-Voxy was never published,
   and a significant part of what we built is original (server streaming
   with cache epochs, bloom filters and congestion control, the debug
   tooling, the multi-loader layer, many fixes). Those concepts are ours and
   can be reused; the rule is only to not copy code as is.
1. New repository and new git history.
2. Before coding, write **behavioral specifications** from what we know
   (this document, our ADRs, the bug memories): formats, invariants, edge
   cases. Specs describe *what* and *why*, never paste code.
3. Implement from the specs. Voxy-derived parts are rewritten from their
   behavior, not ported; our own Neo-Voxy designs can be reimplemented
   freely.
4. Keep the trail (specs, ADRs, progress logs): it is our evidence of
   independent creation.

### License choice for the new mod

| License | Effect | Fit |
|---|---|---|
| MIT / Apache-2.0 | Anyone can reuse, including in closed forks | Maximum adoption, no protection |
| MPL-2.0 | File-level copyleft: modified files stay open, the rest is free | Good balance, friendly to modpacks and addons |
| LGPL-3.0 | Library copyleft (DH, Iris, Nvidium) | Same community as DH and Iris, allows reusing LGPL code |
| GPL-3.0 | Strong copyleft | Addons must be GPL too: friction |
| PolyForm Shield / source-available | Visible but not reusable to compete | Control, less community goodwill |
| All rights reserved | Voxy's choice | What we are trying to escape |

Also: pick a **distinctive name** without "Minecraft" in it (Mojang usage
guidelines), check Modrinth/CurseForge for collisions, reserve the slugs.

---

## 3. Lessons from Neo-Voxy

Each line: what happened, the lesson, the design rule for the new mod.

### Correctness and robustness

| What happened | Lesson | Design rule |
|---|---|---|
| A blockstate from a removed mod crashed server chunk loading (datafixer from version 0) | Persisted ids outlive registries | Persist **string keys + data version**; unknown entries decode to a placeholder, never throw; resave lazily |
| A `StampedLock` write stamp leaked when `createWorld` threw, the server hung with no log | Hand-rolled locking around fallible code | Prefer single-writer actors / message passing; any lock released in `finally`; watchdog that dumps threads |
| Static `serverSupportCached` never reset on disconnect (found 2026-10-14) | Implicit session state | Explicit **session state machine** (connecting, connected, dimension change, disconnected) owning all per-connection state; tests for every transition |
| `VoxelIngestService` called the vanilla light engine from the chunk-load event; its hash map is not thread-safe (found 2026-10-14) | MC internals are single-threaded | Read MC state only on its owner thread, snapshot what we need (palettes, light arrays), process the snapshot elsewhere |
| `initVoxyClient()` runs twice on NeoForge | Lifecycle hooks overlap between loaders | Idempotent init, one documented entry point per lifecycle stage |
| `glPixelStorei(PACK_ROW_LENGTH)` left set: another mod's `glReadPixels` crashed the driver | The GL context is shared with every mod | Never leak global GPU state; with Vulkan this class of bug mostly disappears |
| CPU model baking on the render thread froze the game | Render thread budget is sacred | Render thread does submission and uploads only, within a time budget |
| ~290 inherited TODOs, unsafe ZSTD usage, untuned RocksDB | Inherited code is unknown risk | Own every line; quality gates (static analysis, tests) from the start |

### Rendering

| What happened | Lesson | Design rule |
|---|---|---|
| Phantom occlusion: full 16³ AABBs of partially empty vanilla sections hid LOD terrain at the render-distance edge | Coarse occluders lie | Use exact occupancy (per-section masks or depth from real geometry) for the hand-off |
| Iris translucent seam: LOD has flat normals and no AO, vanilla has smooth normals and AO; packs amplify the difference | The G-buffer contract matters more than the color | LOD must emit the same attributes as vanilla terrain: normals, AO, sky/block light, material id |
| Default `voxy.json` with two color targets broke Photon and Complementary (mismatched attachment sizes) | Shader packs configure targets differently | Integrate through a contract the packs opt into, not by guessing their targets |
| Software rasterizer bakes: diagonal gaps, mip bleed from the atlas, leaves losing alpha | Texture summarization is its own problem | Decide the material representation explicitly (§8.3); never sample MC's atlas mips (cross-sprite bleed) |
| 16 px model textures clearly better than 8 px (user validated) | Texture detail is visible in LOD 0/1 | Quality presets: texture resolution per LOD level |

### Multiplayer

| What happened | Lesson | Design rule |
|---|---|---|
| Server-driven LOD streaming pushed ~16 MB/s through the game connection; ping stuck at 1-3 bars, delayed inputs | One TCP connection, head-of-line blocking | Hard bandwidth budget, small interleaved frames, client-driven pull, optional separate transport (§11) |
| Protocol version "1" hard-coded, no capability handshake | Every format will change | Version negotiation and capability flags from the first release |
| No network compression, fixed streaming radius regardless of client | Waste | Compress, and let the client declare what it can display |

### Debug UI

| What happened | Lesson | Design rule |
|---|---|---|
| On Fabric the Voxy F3 block is appended after vanilla's targeted-block lines, so it scrolls off screen whenever the player looks at a block (seen 2026-10-14) | Each loader inserts debug lines at a different place | Insert our F3 lines at a fixed, deliberate position on every loader (before the targeted-block section), keep them short, and offer a dedicated debug overlay/key for the detailed statistics |

### Process and tooling that paid off

- The F2 sidecar dump, `/voxy dumpAtlas`, `/voxy debugBounds`, the mixin audit
  (`-PauditMixins`) and the scripted dev clients (window capture + chat
  commands) found real bugs in minutes. Build the equivalent in from day 1.
- ADRs and mandatory progress logs keep context across sessions.
- The MultiLoader template, `ServiceLoader` platform services and a vanilla
  `common` project worked; reuse the *pattern*.
- Supporting two Sodium major versions (0.6 and 0.8) in one jar cost a lot:
  avoid hard dependencies on fast-moving internal APIs.
- Chunky integration (auto-ingest of pre-generated chunks) is a feature users
  want.

---

## 4. Ecosystem landscape (October 2026)

### 4.1 Minecraft platform

| Item | Status |
|---|---|
| Versions | 1.21.1 still common in modpacks (our reason for ADR-002); latest release 26.3, snapshots 26.4 |
| Obfuscation | Removed from 26.1 on: Mojang names everywhere, no mappings layer **[verified, Fabric docs]** |
| Java | 26.1 requires Java 25 **[reported]**; 1.21.1 runs Java 21 |
| Graphics | Vulkan default since 26.4-snapshot-1; Renderpearl API with OpenGL and Vulkan backends; OpenGL removal announced without a date **[verified]** |
| Renderpearl capabilities | Render passes, pipelines from SPIR-V, push constants, uniform buffers, `drawIndexedIndirect` (one command per call), `drawMultipleIndexed`, device limits, built-in OIT pipeline sets **[verified, 26.3 primer]**; no compute, no SSBO, no GPU draw count **[reported]** |
| Vibrant Visuals | Mojang ties Vulkan to bringing Bedrock's Vibrant Visuals to Java: a native deferred pipeline is coming, a future integration target for LOD |
| Mod list payload | 26.4 adds a `minecraft:mod_list` custom payload so servers learn client mods **[verified]**: free capability detection |

### 4.2 LOD and related mods

#### Distant Horizons (DH)

Read from `distant-horizons-core` (commit 534e3f2, 2026-10-13):

- **License** LGPL-3.0-only; MC 1.12.2 to 26.3; Fabric, Forge, NeoForge in
  one jar per version; 37 M downloads **[verified, Modrinth]**.
- **Data model: columns.** A *full data source* covers 64x64 columns; each
  column is a list of 64-bit data points, sorted top to bottom
  **[verified, `FullDataSourceV2`]**:

  | Bits | Field |
  |---|---|
  | 0-31 | id (index into a per-source map of blockstate + biome pairs) |
  | 32-43 | height of the run |
  | 44-55 | min Y of the run |
  | 56-59 | sky light |
  | 60-63 | block light |

  So DH stores vertical **runs**, not voxels: very compact for terrain
  heightfields, weaker for overhangs, caves and 3D detail at a distance.
- Per-column metadata: generation step reached (`EMPTY` .. `LIGHT`, plus
  `DOWN_SAMPLED`) and world compression mode (`MERGE_SAME_BLOCKS` or
  `VISUALLY_EQUAL`) **[verified]**.
- **Storage**: SQLite, one database per dimension; repositories for full data
  V1/V2, chunk hashes and beacon beams. Compression options with DH's own
  measurements **[verified, `EDhApiDataCompressionMode`]**:

  | Mode | Read ms/DTO | Write ms/DTO | Ratio |
  |---|---|---|---|
  | Uncompressed | 6.09 | 6.01 | 1.0 |
  | LZ4 | 3.25 | 5.99 | 0.45 |
  | Zstd (block) | 2.10 | 4.90 | 0.26 |
  | LZMA2 | "extremely slow" | | "very good" |

  Note that compressed reads are *faster* than uncompressed ones: I/O bound.
- **Generation modes**: `PRE_EXISTING_ONLY`, `SURFACE`, `FEATURES`,
  `INTERNAL_SERVER` **[verified]**; distant generation runs the vanilla
  generator up to a chosen step.
- **Network**: request/response *pull*: the client requests a section with
  its timestamp, the server answers with the data or the changes; session
  config message; split messages; partial updates **[verified]**.
- **Rendering**: CPU-built geometry, a quadtree of detail levels, rendering
  engines `OPEN_GL`, `BLAZE_3D`, and an `API` engine **[verified]**. Also
  renders beacon beams, clouds and generic objects through its API.
- **Public API** (version 7.x) used by other mods, and the `dh_*` program
  contract in Iris.

#### Voxy (upstream, MCRcortex)

- 26.2, Fabric only, OpenGL 4.6, Sodium 0.9 **[verified, gradle.properties]**;
  229 Java files, 31 k lines **[verified]**.
- 32³ sections, one 64-bit word per voxel (block id, biome, light), 5 LOD
  levels, mip pyramid **[ours, same in Neo-Voxy]**.
- GPU-driven: hierarchical traversal and Hi-Z culling in compute, multi-draw
  indirect count, own model bakery for per-face textures **[ours]**.
- Storage RocksDB or LMDB, zstd/LZ4 **[ours]**.
- Shader packs integrate through a `voxy.json` the pack ships.
- No server-side streaming (issue #188 open) **[reported]**.
- Recent work (2026): fog modes, dynamic far plane for shaders, JMH
  benchmarks, fixed-point barycentrics **[verified, git log]**.
- Vulkan: PR #614 (adopt MC's `VkDevice`, record into MC's frame command
  buffer, indexed-indirect-count with a fixed-count fallback on MoltenVK,
  single-source GLSL with `#ifdef VOXY_VULKAN`) **closed unmerged**; the author
  reported ~200 FPS gains on Linux at 260 chunks **[verified PR text,
  numbers reported]**.

#### Forks and others

| Project | What matters for us |
|---|---|
| Voxyrium (Voxy fork) | Native OpenGL, native Vulkan and an experimental Blaze3D renderer; documents exactly what Blaze3D lacks **[reported]** |
| voxy-forged, voxy-neoforge, Voxy backports | Demand for NeoForge and 1.21.1 exists |
| Voxy World Gen V2 | Background generation feeding Voxy, **LOD-only chunks not saved to disk**, TPS-aware throttling **[reported]** |
| Bobby | Client cache of received chunks to exceed the server render distance; not real LOD |
| FarPlaneTwo | LOD for Cubic Chunks era, heightmap and voxel render modes **[reported]** |
| Nvidium | Mesh-shader terrain backend for Sodium, NVIDIA-only (GL `NV_mesh_shader`), LGPL-3.0, still active (push 2026-09-23) **[verified]** |
| VulkanMod | Vulkan renderer mod (LGPL-3.0); less relevant now that vanilla ships Vulkan |
| Sodium 0.9 | MC 26.x, early Vulkan support, PolyForm Shield **[reported/verified]** |
| Iris / Aperture | Iris is OpenGL-only; Aperture targets Vulkan, unreleased **[reported]** |
| C2ME, ScalableLux, Moonrise, Lithium | Faster parallel world generation and lighting: what makes distant generation viable |
| Chunky, Chunksmith | Pre-generators; Chunksmith adds adaptive throttling with players online **[reported]** |

#### Outside Minecraft (inspiration, to dig further if useful)

- **Aokana** (I3D 2025): GPU-driven voxel renderer for open worlds, many
  shallow SVDAGs (one per chunk), CPU implicit octree for LOD selection and
  streaming, Hi-Z culling, visibility buffer; reports up to 9x less memory and
  4.8x faster rendering than prior work, ~5 % of the scene resident in VRAM
  **[reported, authors' numbers]**.
- **Unreal Nanite** and its open-source descendants (meshoptimizer
  `clusterlod`, NVIDIA `vk_lod_clusters`, Bevy virtual geometry): cluster
  hierarchies, two-pass occlusion culling, visibility buffer, software
  rasterization of tiny triangles.
- Voxel games with large view distances (Cubyz, Veloren, Teardown,
  Enshrouded, Vintage Story) use mixes of mip voxels, heightfields and ray
  marching; worth a targeted look if we pick a direction they share.

### 4.3 Gaps we could own

1. **Vulkan-native, GPU-driven LOD** on 26.4+, while Voxy is GL-only and DH
   relies on the reduced Blaze3D path.
2. **Seamless hand-off** with vanilla terrain, including under shader packs.
3. **Multiplayer streaming that never hurts gameplay**, with a Paper plugin.
4. **Storage efficiency**: DH data barely compresses (4.7 GB to 4.4 GB zipped,
   one user report); our Voxy DB is larger than the world it summarizes.
5. **Instant horizon** (coarse noise preview, then refinement) instead of
   empty sky until generation catches up.
6. **Both 1.21.1 and 26.x** from one engine.
7. **Data portability**: import DH, Voxy and Bobby data so switching costs
   nothing.

---

## 5. Data model

### 5.1 What must be represented

- Occupancy (what is solid, what is empty), per LOD level.
- Material identity (block state, mapped to a render material), biome (tints),
  sky and block light, fluids, transparency class (opaque, cutout,
  translucent), emissive.
- Enough to compute normals and ambient occlusion consistent with vanilla.
- Generation state (how complete the data is) and a version or hash for
  synchronization.

### 5.2 Options

| Option | Description | Strengths | Weaknesses |
|---|---|---|---|
| A. Columns of runs (DH) | Per (x, z) column, list of (id, top, bottom, light) runs | Very compact for terrain; trivial to downsample horizontally; fast to generate from heightmaps | Overhangs, caves and arches cost many runs; 3D mips are awkward; per-column variable-length data |
| B. Dense sections + mips (Voxy) | 32³ × 64-bit per section, 5 levels | Simple, GPU friendly, fast random access | 256 KiB raw per section; relies entirely on generic compression |
| C. Paletted bricks | Sections split in 4³ or 8³ bricks; per-section palette; bit-packed indices; empty/uniform bricks implicit | Compact in memory *and* on disk; uniform regions cost ~0; close to MC's own `PalettedContainer` | Indirection on access; palette management on edits |
| D. SVO / SVDAG / HashDAG | Octree with shared identical subtrees; attributes stored separately | Huge geometry compression; ray-tracing friendly; HashDAG allows edits | Attributes (block ids) do not deduplicate well; edits and streaming complexity; raster meshing still needed for a raster pipeline |
| E. Multiple shallow DAGs (Aokana) | One small DAG per chunk/region, LOD by aggregation | Streaming-friendly; bounded edit cost | Research-grade; attribute handling to design |
| F. Heightfield + layers (2.5D) | Height, top material, color, maybe water level per column | Tiny; perfect for the extreme horizon; trivial to render | Loses all 3D detail |
| G. Signed distance / smooth | SDF or density, dual contouring / Transvoxel meshing | Smooth LOD transitions, no cracks | Wrong look for a blocky game; costly |

**Leaning**: **C as the canonical model** (3D, generic, compact, editable),
**F as an extra summary level** for the farthest ring and for maps, and **D/E
evaluated as a storage-level deduplication** (identical bricks and subtrees
are extremely common: ocean, stone, air, deserts). A spike should compare C
vs C+DAG vs DH-style columns on real worlds (§16).

### 5.3 Downsampling (building the mip levels)

The rule that builds level N+1 from 2x2x2 cells of level N decides how far
terrain looks:

- **Majority vote** loses thin features (paths, rails, single-block walls)
  and makes forests look bald.
- **Surface-preserving**: prefer a non-air child if any child is visible from
  outside (silhouette), prefer the material that is visible from above.
- **Opacity-aware**: water over sand must stay water; glass and leaves
  shouldn't punch holes; leaves need a coverage value, not a binary choice.
- **Separate occupancy for culling vs material for shading**: conservative
  occupancy is useful for occlusion, representative material for color.
- **Color as data**: store an averaged color (or per-face color) per cell at
  coarse levels so distant forests and fields keep the right tone, instead of
  picking one block.
- DH offers `MERGE_SAME_BLOCKS` vs `VISUALLY_EQUAL` merging **[verified]**;
  Voxy issue #671 shows flicker between quality levels **[reported]**: the
  rule and the selection hysteresis (§9.2) interact.

### 5.4 Identity and materials

- A per-world **palette of string keys** (`namespace:block[props]`,
  `namespace:biome`) with the data version, mapped to compact ids; unknown
  keys survive as placeholders (lesson from the removed-mod crash).
- A **material layer** derived from the block state: render class (opaque,
  cutout, translucent, fluid, emissive), average color per face, tint source,
  a compact "material id" like DH's `dhMaterialId` (stone, wood, leaves,
  water, illuminated...) for shader packs.
- Resource-pack aware: materials are recomputed on resource reload, stored
  data keeps block keys, not colors.
- Overridable through JSON in resource packs/datapacks so modpack makers can
  fix modded blocks without code.

### 5.5 Light

Options: store sky and block light per voxel (Voxy, 8 bits), per run (DH,
4+4 bits), or per exposed face. For seamless shading the LOD needs the same
inputs vanilla uses: **per-vertex smooth light and AO** computed at meshing
time from neighbors, like vanilla's smooth lighting.

### 5.6 Coordinates and world shape

- Variable world heights (datapacks, modded dimensions) and cubic-chunks-like
  mods: avoid baking -64..320.
- Section position encoding with level, x, y, z in one 64-bit key; Morton
  order inside sections for cache locality and compression.
- Per-dimension stores, dimension identity by key + type.

---

## 6. Storage

### 6.1 Engines

| Engine | Used by | Pros | Cons |
|---|---|---|---|
| SQLite | DH | One file, robust, tooling, SQL for metadata | Blob updates rewrite pages; write amplification; JDBC overhead |
| RocksDB | Voxy (default in Neo-Voxy) | Fast writes (LSM), compression built in | Large natives per platform, compaction spikes, many files, tuning; our DB is 1.7x the region files **[ours]** |
| LMDB | Voxy option | mmap B-tree, very fast reads, tiny natives (LWJGL), crash safe | Single writer, map size to manage, file grows, no built-in compression |
| Custom paged file (region-like) | Minecraft's Anvil | Exactly fits fixed-size keyed blobs; zero dependencies; easy backup | We own crash safety and compaction |
| Append-only log + index (Bitcask-like) | | Simple, crash safe, sequential writes | Needs compaction |
| H2 MVStore, Xodus | | Pure Java | Less proven for this workload |

**Leaning**: a **custom paged format** (fixed-size regions of sections, a
header table, sector allocation like Anvil, write-ahead or copy-on-write for
atomicity) or **LMDB** if we want proven crash safety quickly. Both are small
and portable. Avoid RocksDB's native weight and compaction behavior.

### 6.2 Compression

Generic compressors are second; **domain transforms first**:

1. Palette + bit packing (indices of 1..12 bits per voxel).
2. Uniform and empty bricks encoded as one value.
3. Run lengths along Y (terrain is layered).
4. Separate streams (ids, light, biome) so each compresses well.
5. **Residuals against the parent mip**: store only what differs from the
   upsampled coarser level (pyramid coding); most children equal the parent.
6. Morton or Hilbert ordering.
7. Content hashing to deduplicate identical sections or bricks (DAG at the
   storage layer).

Then a byte compressor:

- **zstd with trained dictionaries**: the best fit for many small blobs of
  similar structure (dictionary trained per world or shipped as default);
  DH's own numbers show zstd at 0.26 ratio and faster reads than LZ4.
- LZ4 for hot caches in RAM.
- LZMA2/XZ only for archives and exports (too slow interactively).

### 6.3 Formats, versioning, integrity

- Format version in every file and every record; migrations tested.
- Checksums per record (xxHash/CRC32C); recovery by regeneration of a corrupt
  section.
- Atomic updates; no corruption on crash or kill.
- Per-section **version/hash** reused by the network sync (DH keeps a chunk
  hash table **[verified]**).
- Export/import of a world's LOD as a single archive (server owners can ship
  it, players can back it up).

### 6.4 Caching tiers

GPU-resident geometry → decompressed sections in RAM (LRU, bounded) →
compressed blobs in RAM → disk. Prefetch along the camera's movement; evict
by screen-space importance, not just distance.

---

## 7. Data acquisition

| Source | Description | Notes |
|---|---|---|
| Live chunk loads (client or server) | Snapshot palettes and light on the owner thread, convert off-thread | Our light-engine race shows why snapshots are required |
| Block changes | Incremental update, dirty propagation up the mips, batched | Coalesce per section and per tick |
| **Region-file import** | Parse Anvil `.mca` files directly, multi-threaded, without loading chunks in the server | Fastest way to cover an existing world or server map; Voxy has `/voxy import world`. Implements the user's "build LOD for everything already generated" command (§1.2): resumable, throttled, schedulable |
| Pre-generation | Chunky/Chunksmith integration, or our own throttled generator | Already valuable in Neo-Voxy |
| **LOD-only generation** | Run vanilla generation up to NOISE/SURFACE/FEATURES, ingest, **do not save the chunk** | DH modes and Voxy World Gen V2 do this; must be thread-safe with modded worldgen; C2ME helps |
| **Coarse noise preview** | Sample the density functions and biome source at a coarse resolution for the far ring; replace when real data arrives | Instant horizon in seconds; no trees or structures; must fade into real data without popping |
| Server streaming | §11 | |
| Importers | DH SQLite, Voxy DB, Bobby caches | Adoption: users keep their explored terrain |
| Client-side multiplayer cache | Like Bobby: everything received is kept | Works on vanilla servers |

---

## 8. Meshing and geometry

### 8.1 From voxels to quads

- **Face culling + greedy meshing**. The **binary greedy meshing** technique
  (occupancy as 64-bit masks, hidden faces culled 64 at a time with bitwise
  operations, then merged) reports **50-200 µs per 64³ chunk**
  single-threaded **[reported, cgerikj]** and packs a quad in 8 bytes.
- **GPU meshing** in compute is possible (no CPU cost, no upload of meshes),
  but needs compute (not available through Renderpearl) and makes debugging
  harder; CPU binary meshing may already be fast enough.
- **Vertex pulling**: store quads (position, size, face, material, light/AO),
  expand them in the vertex shader; no vertex buffers, 4-6x less memory.
- **Per-direction groups**: store the six face directions separately; from a
  given camera position at most three are visible for a distant region: cull
  half the geometry before drawing.

### 8.2 Non-cube blocks and transitions between levels

- At level 0 and 1, partial blocks (slabs, stairs, snow layers, fences,
  flowers) need shape: sub-voxel occupancy masks (for example 2x2x2 per
  block), or simplified boxes from the block's collision/visual shape.
- Fluids need surface height to avoid the flowing-water gaps several forks
  had to fix.
- **Cracks between levels**: with blocky voxels, render the boundary faces
  using the neighbor's coarser occupancy; skirts or slight overlap; avoid
  T-junction sparkles. Transvoxel and dual contouring solve this for smooth
  surfaces only.

### 8.3 Materials and textures

| Approach | Quality | Cost |
|---|---|---|
| Average color per face | Low at level 0, fine beyond | Tiny |
| Baked per-face tiles (Voxy bakery, 8 or 16 px) | Good; 16 px clearly better **[ours]** | Atlas memory, baking work, resource reload |
| Reference MC's block atlas directly | Best fidelity at level 0 | Mip bleed between sprites **[ours]**; ties us to the atlas layout; animated textures |
| Color + procedural detail noise | Cheap "texture feel" at distance | Not faithful |

Probably a mix per level: textures for the first levels, colors beyond.
Foliage needs coverage (alpha-to-coverage or dithering) rather than binary
cutout, the "leaf aeration" problem we never finished.

### 8.4 Vegetation and objects

Trees seen from far are noise; **impostors** (octahedral impostors, billboard
clouds) or volumetric coverage can look better and cost less than voxels at
mid distance. Optional.

### 8.5 Clusters

Grouping quads into clusters (meshlets) with bounding boxes and normal cones
enables fine culling (frustum, occlusion, back-facing) on the GPU. With voxel
mips the LOD hierarchy is natural; a Nanite-style cluster DAG
(meshoptimizer `clusterlod`) matters only if we simplify non-voxel geometry.

---

## 9. Rendering

### 9.1 Graphics API strategy (the most important decision)

| Tier | Where | Capabilities | Notes |
|---|---|---|---|
| **Native Vulkan** | MC 26.x on its Vulkan backend | Everything: compute, storage buffers, indirect count, mesh shaders (`VK_EXT_mesh_shader`, cross-vendor), ray queries, async compute | Adopt the game's `VkDevice` and queue, record into its frame command buffer (proved by Voxy PR #614); depends on internal Renderpearl classes, so pin per MC version |
| **OpenGL 4.6** | MC 1.21.x, and 26.x while the GL backend exists | Compute, SSBO, `glMultiDrawElementsIndirectCount`, persistent mapping, sparse buffers | What Voxy/Neo-Voxy use; shared context with every mod |
| **Renderpearl / Blaze3D** | Any MC 26.x, any backend | Raster passes, uniform buffers, single indirect draws | CPU-driven fallback (DH's choice); portable and supported, slower |

Design an **RHI** with capability flags so the engine picks the best tier;
write shaders once (GLSL compiled to SPIR-V with shaderc, like Renderpearl;
or Slang) with backend defines. MoltenVK lacks some features (no
`drawIndirectCount`: fixed-count fallback, PR #614).

### 9.2 Visibility and LOD selection

- **LOD selection by screen-space error**: pick the coarsest level whose
  voxel projects under ~1 pixel (tunable), not fixed distance rings; add
  **hysteresis** and temporal blending to kill flicker between levels.
- **Hierarchical traversal on the GPU** (what Voxy does in compute): nodes
  culled by frustum and Hi-Z, children enqueued, leaves emit draw commands.
- **Two-pass Hi-Z occlusion**: draw what was visible last frame, build the
  depth pyramid, test the rest, draw newly visible clusters (Nanite and
  Aokana) **[reported, REAC 2025, Aokana]**.
- Per-direction face culling (§8.1), small-feature culling, cluster culling.
- Vanilla terrain depth feeds the occlusion pyramid so LOD hidden behind near
  terrain costs nothing (with exact occupancy, §9.5).

### 9.3 Draw submission and memory

- Indirect draws with GPU-generated counts (GL `ARB_indirect_parameters`,
  Vulkan `vkCmdDrawIndexedIndirectCount`), one or a few draws per frame.
- Vertex pulling from storage buffers; quad index buffer shared.
- A GPU memory allocator with large arenas, suballocation, defragmentation;
  sparse residency where available (NVIDIA workaround already in Voxy).
- Mesh shaders: cross-vendor on Vulkan, NVIDIA-only on GL (Nvidium); Intel
  supports them only on Vulkan/DX12 **[reported]**. Nice-to-have, not
  required.
- Visibility buffer: helps with many small triangles and complex shading;
  less needed for large voxel quads.

### 9.4 Ray marching and ray tracing

- **Primary rays on voxels** can beat rasterizing meshes in some settings
  (NVIDIA reports 20x vs optimal meshes in their 2018 ray-box work
  **[reported]**), and practitioners often end with **hybrids**: raster for
  primary visibility, rays for shadows and GI **[reported]**.
- Options for us: ray-march brick maps or DAGs for the farthest ring (no
  meshing, tiny memory), or hardware ray queries (`VK_KHR_ray_query`) on LOD
  geometry for AO and shadows as a premium preset.
- Constraint: shader packs expect G-buffer outputs; a ray-marched pass must
  write the same depth and attributes.

### 9.5 The seamless hand-off with vanilla terrain

The visible seam is what users notice most. Ingredients:

- **Exact occupancy** where LOD and vanilla meet (lesson: phantom occlusion).
- **Same lighting inputs**: smooth per-vertex light and AO, same directional
  shading, same normals.
- **Same fog and sky**, including with fog-changing mods.
- **Dithered cross-fade** over a band, driven by distance, so neither side
  pops.
- **Depth**: reverse-Z with a float depth buffer and camera-relative
  coordinates for precision at tens of kilometers; a dynamic far plane (Voxy
  added one in 2026).
- Long-term radical option: render near terrain ourselves too (become the
  terrain renderer). Removes the seam by construction but means replacing
  Sodium's job (block entities, mod rendering, compatibility): not for v1.

### 9.6 Translucency

Water, ice and glass at distance: sorted translucent quads vs
order-independent transparency (weighted blended OIT, moment-based OIT).
Renderpearl 26.3 ships OIT pipeline sets **[verified]**: matching vanilla's
choice keeps the seam invisible. Simplify water to its surface at distance.

### 9.7 Lighting and atmosphere

Per-vertex sky/block light with time of day, baked voxel AO plus screen-space
AO (Neo-Voxy has an SSAO mode), emissive materials, shadows (cascaded shadow
maps extended to LOD, or ray-traced), atmospheric scattering for the horizon,
clouds. Vibrant Visuals on Java will set expectations here.

### 9.8 Stability and frame pacing

Temporal stability far away (shimmering voxels, specular aliasing), TAA or
pre-filtered colors, alpha-to-coverage for leaves. Uploads through staging
ring buffers within a per-frame time budget; no GPU stalls; GPU timer queries
in the debug overlay.

---

## 10. Shader packs and mod integration

### 10.1 Shader packs

| Path | Today | Vulkan era |
|---|---|---|
| DH contract in Iris (`dh_terrain`, `dh_water`, `dh_shadow`, `dhMaterialId`, `dhDepthTex0/1`, `dhProjection`...) | Contract documented by Iris **[verified]**; supported by major packs such as BSL, Complementary and Photon **[reported]** | Unknown until Aperture defines it |
| Voxy contract (`voxy.json` shipped by packs) | Fewer packs | Unknown |
| Our own contract | Nobody supports it at first | Could be defined with Aperture from the start |

Options: (a) feed Iris's DH programs with our geometry (needs Iris to accept
a non-DH provider: an upstream Iris change or a compatibility shim; check the
DH API license and Iris code paths), (b) propose a **generic LOD provider
contract** to Iris/Aperture, which benefits everyone and is the clean
long-term path, (c) bespoke patching like Voxy (fragile, our `voxy.json`
lesson). Recommendation: (b), with (a) as the bridge for OpenGL users.

**"Any pack works without pack-specific configuration"** (user goal). What it
takes:

- Packs that already ship `dh_*` programs (most popular ones): reaching them
  through route (a) gives zero-config support for those packs.
- Packs with no LOD program at all: the only zero-config route is to draw LOD
  geometry **through the pack's own `gbuffers_terrain`/`gbuffers_water`
  programs**, emitting the same vertex attributes as vanilla terrain
  (position, color, lightmap coordinates, normal, `mc_Entity`-like ids,
  texture coordinates into a valid atlas). Open problems: the projection and
  far plane (packs assume vanilla's), fog distance uniforms, the shadow pass
  distance, depth-dependent effects (SSAO, volumetrics), and texture
  coordinates for LOD materials. Expect "works, with artifacts" for some
  packs; a per-pack override file is the escape hatch, not the norm.
- The Vulkan era (Aperture) is the chance to make a generic contract
  standard; engaging early matters more than any shim.

Spike S7 (§16) must answer how far zero-config can go before we promise it.

### 10.2 Other mods

- **Sodium**: hooks for section visibility and chunk bounds (avoid drawing LOD
  under loaded chunks); keep the hook surface tiny (two Sodium majors cost us a
  lot).
- Fog, sky and weather mods; Immersive Portals and other multi-view mods;
  Vivecraft (stereo); Flashback/Replay (recording far terrain); world-gen
  datapacks (Terralith, Tectonic) for distant generation; Lithium, C2ME.
- **World maps**: LOD data is a top-down map for free (2.5D summary level):
  an integration or a built-in map is a differentiator.
- **Public API** (semver, separate artifact): data queries, custom data
  providers (generators), extra renderables (beacons, clouds: DH exposes
  these), material overrides.

---

## 11. Networking and multiplayer

### 11.1 Modes

1. Client-only (works anywhere, Bobby-like cache).
2. Server-assisted streaming (mod or Paper plugin on the server).
3. **Static LOD tiles over HTTP** generated by the server or offline: CDN
   friendly, zero game-connection traffic; the client downloads tiles for the
   server it joined (the server advertises a URL and a manifest).
4. LOD archives shipped with modpacks or maps.

### 11.2 Protocol (proposal)

- Capability handshake and version negotiation (use 26.4's
  `minecraft:mod_list` where available); session config message (DH does
  this).
- **Client-driven pull**: the client asks for sections in its priority order
  (screen-space importance), sending the version/hash it has; the server
  answers "unchanged" or the data (DH pulls with timestamps **[verified]**).
- Push only small invalidation notices for block changes.
- Compression per message with a shared zstd dictionary.
- **Bandwidth budget**: token bucket per player and global, small frames
  (a few KB) interleaved with gameplay, never more than N in flight; the
  keep-alive and movement packets must never wait (our flood lesson). Option:
  a separate TCP/QUIC connection on another port when the host allows it.
- Fairness across players; server CPU budget per tick for generation and
  serialization; hot-section cache shared by players.

### 11.3 Security and privacy

- **Anti-x-ray**: a "surface only" mode that never sends underground or
  enclosed data (bases, caves, ores); server-configurable exclusion regions.
- Quotas and validation of client requests (DoS).
- Cache key by server identity + world/dimension hash, invalidated on map
  reset.

### 11.4 Scale (large servers)

User expectation: if the mod succeeds it will run on large servers. Design
targets to set and test, for example:

- 100+ concurrent players streaming, server CPU for LOD under a fixed budget
  per tick, no effect on TPS.
- Bounded memory per connected player; shared cache of hot sections.
- Load tests with headless bot clients speaking the LOD protocol, in CI or on
  demand, with network shaping (latency, loss, low bandwidth).
- Graceful degradation: when the budget is exhausted, lower detail or slower
  refinement, never gameplay lag.
- Observability for operators: per-player and global bandwidth, queue
  depths, generation backlog (spark-friendly metrics).

### 11.5 Server platforms

NeoForge/Fabric server mod, and a **Paper/Folia plugin** (DH has one): many
public servers run Paper.

---

## 12. Performance engineering on the JVM

- **Java 21 (1.21.1) and Java 25 (26.x)**: the Foreign Function & Memory API
  is final since Java 22, so only on the 26.x side; on Java 21 keep LWJGL
  `MemoryUtil` or `Unsafe`. Multi-release jars or per-version modules.
- The **Vector API** is still incubating (needs `--add-modules`): not usable
  in a mod by default; rely on 64-bit bit tricks (binary meshing) and the
  JIT's auto-vectorization.
- Allocation-free hot paths, off-heap buffers, pooled arrays, primitive
  collections (fastutil ships with Minecraft). Generational ZGC (default in
  recent JDKs) and compact object headers (Java 25) help but are not a plan.
- **Job system**: prioritized work queues, cancellation when the camera moves
  away, backpressure, per-frame budgets; virtual threads for blocking I/O
  only.
- Natives: keep them small (LWJGL zstd and LMDB are already in the game's
  LWJGL family); avoid large JNI dependencies.
- Profiling: JFR, async-profiler, spark on servers, RenderDoc/Nsight on the
  GPU, built-in GPU timers.
- **JMH benchmarks** (upstream Voxy added them in 2026) and reference worlds
  in CI to catch regressions.

---

## 13. Software architecture

### 13.1 Layers

```
api/          public API (semver), no MC types where avoidable
core/         pure Java: data model, mips, storage, compression, LOD selection,
              meshing, job system, protocol codecs, math. No Minecraft imports.
              Fully unit-testable and benchmarkable.
render/       RHI + backends (vulkan, opengl, renderpearl) + shaders
mc-<version>/ adapters per Minecraft version: chunk snapshots, registries,
              world generation hooks, light, events, render hooks (mixins)
loader-<x>/   NeoForge / Fabric / Paper entry points and services
```

- The core is where 90 % of the value lives and it never breaks when
  Minecraft updates.
- Per-version adapters stay small; **Stonecutter** (comment preprocessing,
  used widely for multi-version mods) is an alternative for the glue code,
  separate modules for the rest.
- Multi-loader: the MultiLoader template and `ServiceLoader` services we
  already validated.

### 13.2 Principles

- Mixins: as few as possible, each documented (target, why, compatibility),
  audited in CI (`MixinEnvironment.audit()`, as in Neo-Voxy).
- Explicit lifecycles and state machines (client session, world, dimension).
- Typed config with schema, migrations, presets and hardware-tier detection.
- Structured logging, debug HUD, one-command **diagnostic bundle** (logs,
  config, GPU info, F2-style dumps). F3 lines at the same, always-visible
  position on every loader (see the Debug UI lesson in §3).

### 13.3 Testing

- Unit and property-based tests for the core (mip invariants, codecs,
  storage crash recovery, protocol fuzzing).
- **Golden-image tests**: headless rendering (Mesa lavapipe/llvmpipe in CI)
  of reference scenes, image diff with tolerance.
- Scripted dev clients (what we did on 2026-10-14) for end-to-end checks.
- GameTest framework for server behavior.
- CI matrix: Minecraft versions x loaders; automated publishing (Modrinth,
  CurseForge).

---

## 14. Product and UX

- Zero-config start: hardware detection, sensible preset, generation
  progress visible.
- Presets with explicit budgets (VRAM, disk, threads, bandwidth).
- Storage management screen: size per world, prune, export, import.
- Server admin: commands, permissions, bandwidth, exclusion regions, metrics.
- Built-in or integrated world map from LOD data.
- Screenshot/cinematic mode (huge distances on demand), VR, replay mods.
- Localization from the start.

---

## 15. Candidate architectures

| | A. "Voxy evolved" | B. "DH evolved" | C. Ray-marched DAG | D. Hybrid tiered |
|---|---|---|---|---|
| Data | 3D paletted bricks + mips | Columns of runs | SVDAG/HashDAG per region + attributes | Bricks (canonical) + 2.5D summary + optional DAG storage |
| Meshing | CPU binary greedy, quads | CPU columns to boxes | None | Quads near/mid, none far |
| Rendering | GPU-driven raster, Hi-Z | CPU-driven raster, Blaze3D | Compute ray marching | Raster near/mid, ray-march or heightfield far |
| API needs | Vulkan or GL 4.6 | Works on Renderpearl | Compute required | Vulkan/GL 4.6, degraded on Renderpearl |
| Visual fidelity | High, true 3D | Good for terrain, weak 3D | High, aliasing far away | High |
| Memory/storage | Good with transforms | Good on flat terrain | Excellent geometry, attributes costly | Best overall |
| Shader-pack fit | Standard G-buffer | Standard G-buffer | Hard (custom depth/attributes) | Standard for raster parts |
| Complexity | Medium-high | Medium | High, research risk | High, staged |
| Risk | Known territory | Known, but no edge vs DH | Unproven in MC | Manageable if staged |

**Leaning**: start with **A built on the layering of §13**, designed so the
far tier of **D** (2.5D summary, then ray marching or heightfield) plugs in
later. **B** gives no edge over a mature DH. **C** is a research bet; keep it
as a spike.

---

## 16. Validation spikes and roadmap sketch

Each spike answers one question with a measurable exit criterion, on the
same reference worlds (our test worlds and a large real server map).

| Spike | Question | Exit criterion |
|---|---|---|
| S1 Storage | Bytes per km² and read speed: dense+zstd vs paletted bricks+zstd-dict vs bricks+DAG vs DH columns | One format at least 4x smaller than Voxy's DB, reads at least as fast |
| S2 Meshing | Binary greedy meshing throughput in Java on 32³/64³ sections | < 200 µs per section on one core |
| S3 Vulkan | Adopt MC 26.4's `VkDevice`, draw an indirect-count test scene inside the frame | Works on NVIDIA, AMD, Intel, MoltenVK; survives resize and resource reload |
| S4 GL 4.6 | Same scene on 1.21.1 | Same output via the shared shaders |
| S5 Import | Anvil region parsing throughput | > 2000 chunks/s on 8 cores |
| S6 Instant horizon | Coarse noise sampling for a 1000-chunk radius | Horizon in < 5 s; acceptable look; no pop when real data arrives |
| S7 Shader packs | Can Iris's DH programs be fed by another provider? What would a generic contract need? | A written proposal and a prototype with one pack |
| S8 Network | Pull protocol under a budget on a 16 MB/s-capable map | Ping unchanged while streaming |

Roadmap sketch: decisions (Q&A) → spikes → core MVP (storage, ingest, mips,
meshing) with a debug renderer → Vulkan and GL backends → seamless hand-off →
multiplayer → shader packs → public alpha.

---

## 17. Risks

- **Scope**: this is a large project; staging and spikes are mandatory.
- **Renderpearl churn**: internal Vulkan classes change every version; the
  native-Vulkan tier needs per-version adapters and the Renderpearl tier as a
  safety net.
- **Shader ecosystem uncertainty** (Iris on GL only, Aperture unreleased).
- **Legal taint** if the clean-room discipline slips.
- **Version split**: 1.21.1 (GL, Java 21) vs 26.x (Vulkan, Java 25) doubles
  the test matrix.
- **Hardware fragmentation**: Intel iGPUs, old AMD drivers, MoltenVK limits.
- **Comparison with DH**: mature, multi-version, server support; we must be
  clearly better on at least performance, seams, storage and multiplayer.
- **Neo-Voxy maintenance** in parallel splits attention.

---

## 18. Open questions for the Q&A

Answered on 2026-10-14 (see §1.1): versions (both), license (later,
private repository for now), name (ideas in §1.5), platforms (Windows and
Linux), hardware (no minimum, wide presets), shader packs (zero-config if
possible), multiplayer (large servers), unexplored terrain (yes, coarse is
fine), distance (256-512+ chunks, user-selectable), team and time (solo, no
deadline). Still open:

1. **Minecraft versions**: answered (both). Which comes first for the
   prototype: 1.21.1 (what the user plays) or 26.x (Vulkan-first)?
2. **Loaders**: NeoForge and Fabric from day 1? A Paper plugin for servers?
3. **License and name**: which license (§2)? Monetization or donations? Name
   ideas?
4. **Minimum hardware**: GL 4.6 / Vulkan 1.2+? Intel iGPUs? macOS via
   MoltenVK (reverses ADR-004)?
5. **Shader packs**: required for v1? Which packs matter most (BSL,
   Complementary, Photon...)?
6. **Multiplayer**: how important is server streaming vs client-only? Public
   servers (anti-x-ray) or friends' servers?
7. **Visual target**: faithful textures up close, stylized colors far away?
   How far (1000, 4000 chunks)?
8. **Distant generation**: must unexplored terrain appear (LOD-only
   generation, noise preview), or only explored terrain?
9. **Budgets**: acceptable disk per world, RAM, VRAM at the default preset?
10. **Team and time**: who works on it, how many hours per week, AI-assisted
    development rules, open contributions?
11. **Neo-Voxy**: keep maintaining it for our modpacks until the new mod
    replaces it? Data migration (import of Neo-Voxy worlds)?
12. **Extras**: world map from LOD data? API for other mods? Screenshot mode?
13. **Open development**: public repository from the start, or private until
    alpha?

---

## 19. References

### Ecosystem

- Mojang, Vulkan announcement: https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition
- Mojang, 26.4 snapshot 1: https://www.minecraft.net/article/minecraft-26-4-snapshot-1
- NeoForge 26.3 migration primer (Renderpearl): https://docs.neoforged.net/primer/docs/26.3/
- NeoForge 26.2 migration primer: https://docs.neoforged.net/primer/docs/26.2/
- Fabric porting to 26.1 (unobfuscated): https://docs.fabricmc.net/develop/porting/
- GamingOnLinux on the Vulkan switch: https://www.gamingonlinux.com/2026/02/minecraft-java-is-switching-from-opengl-to-vulkan-for-the-vibrant-visuals-update/
- Distant Horizons (Modrinth): https://modrinth.com/mod/distanthorizons
- Distant Horizons core source: https://gitlab.com/distant-horizons-team/distant-horizons-core
- Distant Horizons 3.0.0 changelog (Blaze3D): https://modrinth.com/mod/distanthorizons/version/3.0.0-b-1.21.11
- Iris DH integration reference: https://shaders.properties/current/reference/mod-support/distant_horizons/
- Voxy upstream: https://github.com/MCRcortex/voxy
- Voxy Vulkan PR #614: https://github.com/MCRcortex/voxy/pull/614
- Voxy server streaming request #188: https://github.com/MCRcortex/voxy/issues/188
- Voxyrium (Vulkan and Blaze3D renderers, feature table): https://github.com/senyakudr2000-prog/Voxyrium
- Voxy World Gen V2: https://www.mintlify.com/kamp0010/voxy_worldgen_v2
- Sodium (license): https://github.com/CaffeineMC/sodium
- Nvidium: https://github.com/MCRcortex/nvidium
- VulkanMod: https://github.com/xCollateral/VulkanMod
- C2ME: https://modrinth.com/mod/c2me-fabric
- Chunky FAQ: https://github.com/pop4959/Chunky/wiki/FAQ
- Stonecutter: https://github.com/ThePandaOliver/stonecutter
- DH vs Voxy comparison (October 2026): https://minecrafthub.io/articles/distant-horizons-vs-voxy

### Research and techniques

- Fang, Wang, Wang. *Aokana: A GPU-Driven Voxel Rendering Framework for Open World Games*. I3D 2025. https://dl.acm.org/doi/10.1145/3728299
- Laine, Karras. *Efficient Sparse Voxel Octrees*. I3D 2010.
- Kämpe, Sintorn, Assarsson. *High Resolution Sparse Voxel DAGs*. SIGGRAPH 2013.
- Villanueva, Marton, Gobbetti. *Symmetry-aware Sparse Voxel DAGs*. 2016. https://www.researchgate.net/publication/316789108
- Careil, Billeter, Eisemann. *Interactively Modifying Compressed Sparse Voxel Representations* (HashDAG). 2020. https://github.com/Phyronnaz/HashDAG
- Molenaar, Eisemann. *Editing Compressed High-resolution Voxel Scenes with Attributes*. CGF 2023. https://onlinelibrary.wiley.com/doi/10.1111/cgf.14757
- Modisett et al. *Encoding Occupancy in Memory Location for Efficient and Compact High-Resolution Voxel Structures*. CGF 2025. https://onlinelibrary.wiley.com/doi/10.1111/cgf.70292
- Crassin et al. *GigaVoxels*. I3D 2009.
- Majercik et al. *A Ray-Box Intersection Algorithm and Efficient Dynamic Voxel Rendering*. JCGT 2018. https://research.nvidia.com/labs/rtr/publication/majercik2018raybox
- Amanatides, Woo. *A Fast Voxel Traversal Algorithm for Ray Tracing*. 1987.
- Lengyel. *Transvoxel*. 2010.
- Losasso, Hoppe. *Geometry Clipmaps*. SIGGRAPH 2004.
- Strugar. *Continuous Distance-Dependent Level of Detail (CDLOD)*. 2009.
- Ulrich. *Rendering Massive Terrains using Chunked Level of Detail*. 2002.
- Haar, Aaltonen. *GPU-Driven Rendering Pipelines*. SIGGRAPH 2015.
- Karis et al. *Nanite: A Deep Dive*. SIGGRAPH 2021.
- Capcom, REAC 2025 (meshlets, two-phase occlusion, visibility buffer): https://enginearchitecture.org/downloads/REAC_2025_Capcom.pdf
- Burns, Hunt. *The Visibility Buffer*. JCGT 2013.
- McGuire, Bavoil. *Weighted Blended Order-Independent Transparency*. JCGT 2013.
- Jimenez et al. *Practical Real-Time Strategies for Accurate Indirect Occlusion* (GTAO). 2016.
- Lysenko. *Meshing in a Minecraft Game* (greedy meshing). 0fps.net, 2012.
- Lysenko. *Ambient Occlusion for Minecraft-like Worlds*. 0fps.net, 2013.
- cgerikj, binary greedy meshing: https://github.com/cgerikj/binary-greedy-meshing
- meshoptimizer `clusterlod` and hierarchical LOD: https://zeux.io/2025/09/30/billions-of-triangles-in-minutes/
- Vulkan Guide, GPU-driven rendering: https://vkguide.dev/docs/gpudriven/gpu_driven_engines/

### Neo-Voxy internal (in the private `JordanOlivet/neo-voxy` repository)

- `docs/DECISIONS.md` (Neo-Voxy ADR-001 to ADR-010)
- `docs/analysis/port-analysis.md` (server protocol audit, §9.B)
- `docs/diagnostics/lod-streaming-network-flood-analysis.md`
- `docs/progress-logs/2026-10-14.md` (in-game session findings)
