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
- [ ] A.5 CI green on GitHub (`.github/workflows/build.yml`)
- [x] A.6 Dev servers boot on both loaders (`Farfield initialising`, `Done`)
- [x] A.7 Dev clients boot on both loaders (title screen, init log line)
- [ ] A.8 Reference world corpus: pick and document 3-4 real worlds (a
  pre-generated server map, a built-up survival world, a modded world, an
  extreme-terrain world); stored outside the repository

## M1.B — Spike S5: region-file import (feeds S1 and S2 with real data)

- [ ] B.1 Pure-Java Anvil reader in `core`: region header, chunk
  decompression (zlib, LZ4 and uncompressed), NBT parsing without Minecraft
- [ ] B.2 Decode 1.21.1 chunk sections: block-state palettes, packed indices,
  biomes, sky/block light
- [ ] B.3 Parallel import pipeline with backpressure, resumable, throttled
  (basis of the "build LOD for an existing world" command)
- [ ] B.4 JMH benchmark on the corpus. Exit: > 2000 chunks/s on 8 cores

## M1.C — Spike S2: meshing

- [ ] C.1 Brick representation from S5 output (paletted bricks, occupancy
  masks)
- [ ] C.2 Binary greedy meshing (64-wide bit operations), packed quads
  (target 8 bytes per quad), per-direction face groups
- [ ] C.3 JMH benchmark. Exit: < 200 µs per 32³ section on one core

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
