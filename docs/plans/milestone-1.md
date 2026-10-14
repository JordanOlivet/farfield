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

- [ ] D.1 Encoders: dense + zstd (baseline), paletted bricks + zstd,
  paletted bricks + zstd with trained dictionaries, + parent-mip residuals,
  + brick deduplication
- [ ] D.2 Containers: custom paged file vs LMDB (write, read, crash safety,
  file size)
- [ ] D.3 Measurements on the corpus: bytes per chunk, read/write speed.
  Exit: at least 4x smaller than Neo-Voxy's database for the same area, reads
  at least as fast
- [ ] D.4 ADR choosing the format

## M1.E — First LODs in single player (spike S4)

- [ ] E.1 Mip building (downsampling rules of analysis §5.3)
- [ ] E.2 Ingest from loaded chunks (snapshots on the owner thread)
- [ ] E.3 OpenGL 4.6 RHI minimum: buffers, shaders, indirect draws
- [ ] E.4 Render the LOD meshes beyond the vanilla render distance
- [ ] E.5 In-game check in the user's real install on both loaders
