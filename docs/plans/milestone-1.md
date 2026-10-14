# Milestone 1 — Skeleton, core spikes, first LODs on 1.21.1

Goal: a project skeleton that builds and boots on both loaders, measured
answers to the core technical questions (storage, meshing, region import),
then the first LODs rendered in a 1.21.1 single-player world.

Legend: `[ ]` todo · `[~]` in progress (the text says what remains) ·
`[x]` done and verified · `[-]` dropped (with the reason).

Spike IDs match the analysis (`docs/analysis/lod-engine-state-of-the-art.md`,
§16). Each spike ends with numbers in its progress log and, when it settles a
choice, an ADR.

## M1.A — Bootstrap

- [x] A.1 Private repository `JordanOlivet/farfield`, MultiLoader skeleton
  (`api`, `core`, `common`, `neoforge`, `fabric`), Gradle wrapper 8.10
- [x] A.2 Platform service (`IPlatform`) on both loaders, one init log line
- [x] A.3 `core` unit tests run in the build (`LodKey`)
- [x] A.4 Docs: `CLAUDE.md`, `docs/VISION.md`, `docs/DECISIONS.md`
  (ADR-001 to ADR-016), this plan, the analysis, the first progress log
- [x] A.5 CI green on GitHub (`.github/workflows/build.yml`; first cold run ~15 min)
- [x] A.6 Dev servers boot on both loaders (`Farfield initialising`, `Done`)
- [x] A.7 Dev clients boot on both loaders (title screen, init log line)
- [x] A.8 Reference world corpus: six worlds documented in `docs/corpus.md`
  (pre-generated server map, large modded, played modded, main test world,
  generated amplified terrain, a 1.20.1 world with a Distant Horizons
  database), stored outside the repository. The user's large server world
  can be added later

## M1.B — Spike S5: region-file import (feeds S1 and S2 with real data)

- [x] B.1 Pure-Java Anvil reader in `core`: region header, chunk
  decompression (zlib, LZ4 and uncompressed), NBT parsing without Minecraft
  (`core.anvil`, `core.nbt`; also gzip and external `.mcc` chunks; LZ4
  checked against lz4-java in tests)
- [x] B.2 Decode 1.21.1 chunk sections: block-state palettes, packed indices,
  biomes, sky/block light (1.18+ format; every chunk of the corpus and of the
  user's server decodes with no error or warning)
- [x] B.3 Parallel import pipeline with backpressure, resumable, throttled
  (basis of the "build LOD for an existing world" command): `RegionImporter`,
  unit tested (checkpoint resume, throttle, pause, sink failure). Not wired
  to a game command yet
- [x] B.4 JMH benchmark on the corpus. Exit: > 2000 chunks/s on 8 cores
  (33,800 to 46,800 full chunks/s on 8 threads with a warm cache, 31,400 on
  the user's 17.5 GB server cold from disk; numbers in
  `docs/progress/2026-10-14.md`)

## M1.C — Spike S2: meshing

- [x] C.1 Brick representation from S5 output (paletted bricks, occupancy
  masks): `LodSection`, 32³ of 4x4x4 bricks of 8³, one palette per section,
  uniform bricks without data, 1/2/4/8/16-bit packed indices; built from
  decoded chunk sections (6.6 to 12 µs per chunk section). Occupancy masks
  are built by the mesher from the bricks, not stored
- [x] C.2 Binary greedy meshing (64-wide bit operations), packed quads
  (target 8 bytes per quad), per-direction face groups:
  `BinaryGreedyMesher`, `Quad` (8 bytes, 16 bits reserved for light/AO),
  `MeshBuffer`; checked against a brute-force face visibility oracle on
  random sections
- [x] C.3 JMH benchmark. Exit: < 200 µs per 32³ section on one core
  (122 to 139 µs on real terrain, no allocation; numbers in
  `docs/progress/2026-10-14.md`). Light and AO are not in it yet

## M1.D — Spike S1: storage format

- [x] D.1 Encoders: dense + zstd (baseline), paletted bricks + zstd,
  paletted bricks + zstd with trained dictionaries, + parent-mip residuals,
  + brick deduplication; plus two store-time transforms (hidden voxels,
  face light) that turned out to be the main gain (`StorageBenchmark`)
- [x] D.2 Containers: custom paged file vs LMDB (write, read, crash safety,
  file size): `LogStore` (append-only log) vs LMDB, 20 process kills each,
  no durable record lost
- [x] D.3 Measurements on the corpus: bytes per chunk, read/write speed.
  Exit: at least 4x smaller than Neo-Voxy's database for the same area, reads
  at least as fast. Met where Neo-Voxy covered the whole world: 5.65x on the
  user's server (4.69 GB vs 26.5 GB), 6.1x on `server-pregen` (zstd 3). Not
  met on `modded-large` (3.3x), `modded-played` (2.1x), `main-install`
  (1.9x), whose Voxy databases (0.6x to 1.0x the region files) most likely
  cover less ground than the region files. Reads 2x faster than the dense
  Voxy-style layout (not measured against Voxy itself)
- [x] D.4 ADR choosing the format: ADR-019 (proposed)

## M1.E — First LODs in single player (spike S4)

- [~] E.1 Mip building (downsampling rules of analysis §5.3): `MipBuilder`
  with block shapes, weighted occupancy and the material seen from above
  (ADR-020), unit tested against a voxel-by-voxel reference. Remains: the
  look check in game (E.5)
- [x] E.2 Ingest from loaded chunks (snapshots on the owner thread):
  `LodWorld` engine (ADR-021) and the server side (ADR-022): snapshots after
  load and after block changes, workers, maintenance thread,
  `/farfield import` from region files. Checked on the dev servers of both
  loaders (live chunks, a block change through the mixin, import, unchanged
  sections not rewritten across restarts); not yet in single player or in
  the user's install (E.5). Store-time transforms of ADR-019 not applied yet
- [x] E.2b Side-neutral LOD pipeline (ADR-022): snapshot scheduling,
  workers and maintenance out of `LodServer` into a pipeline that works on
  any `Level` (`io.lakio.farfield.lod.LodPipeline`); a provider gives the
  renderer the `LodWorld` of the current dimension (`LodWorldProvider`);
  identity and store folder of a remote world (`RemoteWorldId`, seed hash
  through `BiomeManagerAccessor`). No client-side ingest yet. Server mode
  checked again on both dev servers; the remote branch of the provider
  has not run (needs a client on a remote server, E.6)
- [x] E.3 OpenGL 4.6 RHI minimum: buffers, shaders, indirect draws
  (`GlBuffer` with DSA storage, `GlProgram`, `glMultiDrawArraysIndirect`
  with `gl_BaseInstance`). Runs in the dev clients of both loaders; Fabric
  needed `WindowMixin` to get a 4.6 context (vanilla asks for 3.2;
  NeoForge's early window already creates 4.6)
- [x] E.4 Render the LOD meshes beyond the vanilla render distance:
  `LodRenderer` (octree selection by `LodSelector`, meshing on two
  threads, first-fit quad arena, map colors, sky brightness, mixin after
  the sky). Checked in the dev clients of both loaders on `main-install`
  (936 to 985 sections drawn up to 4,096 blocks, 49 MB of quads, about
  1.4 ms of render-thread time per frame). Known gaps, for later items:
  water and grass without biome tints (water reads lavender through the
  fog), a visible seam at the vanilla distance (ADR-011 transition band),
  no light or AO in the LOD faces, cracks between levels not checked
- [~] E.5 In-game check in the user's real install on both loaders,
  including the CPU share of LOD work (import, meshing) in the ~400-mod
  pack (ADR-018). Done in the 398-mod pack on NeoForge (three passes): no
  crash, LOD CPU 0 to 1.8 % of the process in steady state and 12 to 21 %
  during an import, LOD cost per frame small after the renderer rework
  (273 to 320 fps with LODs against 319 without), holes from fast
  exploration fixed (0 of 10,930 chunks). Remains: the Fabric pass
  ("Cobblemon Custom Modpack"), and the look of the downsampling (ADR-020)
  with biome tints
- [ ] E.6 Client mode (ADR-022): a client with Farfield on a server
  without it builds and renders LODs from the chunks it receives, stored
  per remote world; command to force or rename the world identity.
  Checked with the dev client on a vanilla 1.21.1 server jar, then on a
  real server
