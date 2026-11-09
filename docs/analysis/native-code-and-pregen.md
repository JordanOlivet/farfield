# Native code and a pregenerator of our own (plan items G.2, G.3)

Date: 2026-11-09. Status: exploration, nothing decided.

The user's ideas (2026-11-09): a chunk pregenerator of our own "like
Chunky" with the heavy work in native code (Rust if possible), and native
code to speed up the LODs' build, the preview's build and their rendering;
"pour voir s'il n'est pas possible d'améliorer les perfs en pensant
différemment que juste un mod java classique".

## Where our time goes (measured, not guessed)

| Work | Measure | Source |
|---|---|---|
| Preview tile | 3.5 to 4.3 s (dev), 5 to 7 s (the user's pack) | minute lines, 2026-11-09 |
| of which the world's features (stand-in world) | 3.0 to 3.7 s, 85 to 90 % | same (`features 3747` of 4327 ms) |
| of which the heights (density functions) | 30 to 640 ms, under 15 % | same |
| Region import | 2,500 to 3,400 chunks a second | home server and dev, 2026-11-09 |
| Preview mesh job | 4.1 ms (2.7 ms on the processor) | ADR-118 fix, 2026-11-08 |
| Section mesh (binary greedy, one core) | 122 to 139 us | ADR-018 |
| LOD frame | 0.3 to 0.4 ms render thread, 0.7 to 1 ms GPU (1080p, pack) | stat lines, 2026-11-08 |
| Real chunk generation (vanilla, per core) | about 13 chunks a second | C2ME's benchmark, below |

What follows from it:

- The preview's cost is the features: the world's own placed features
  (trees, rocks, every mod's) run as the game's and the mods' Java code
  in a stand-in world. Native code cannot run them: a mod's feature is its
  Java class.
- Chunk generation is the game's and the mods' Java too (noise, surface
  rules, structures, features, light). A native pregenerator could only
  rewrite vanilla's generator, and would then differ from every modded
  world (Terralith, Biomes O' Plenty, Tectonic: the user's packs).
- The rendering is GPU-bound and GPU-driven already; native code on the
  CPU does not change GPU time.
- What native code could speed: our own pure computations, the meshing
  (2.7 ms of processor per job) and the region import's decoding (already
  2,500+ chunks a second, faster than any generation feeds it).

## What exists (2026-11)

- **C2ME** (Fabric, and a NeoForge port): parallel chunk generation in
  Java. Its author's benchmarks (Fabric 26.2, high-end Xeon, tmpfs, so
  upper bounds): about 29 chunks a second on one thread against 13 for
  vanilla Chunky, about 459 on 16 threads against 42, and 1,300 to 1,400
  at high thread counts. Its **density function compiler** turns the
  world's density functions (vanilla's and datapacks') into JVM bytecode:
  up to 30 % faster (0.3.0), "90 % faster" on Tectonic (0.4.x, the
  author's figure), with an OpenCL C backend in the 0.4 rewrite.
  [C2ME on Modrinth](https://modrinth.com/mod/c2me-fabric),
  [0.4.1 notes](https://modrinth.com/mod/c2me-fabric/version/0.4.1-beta.1.0+26.2),
  [CurseForge](https://www.curseforge.com/minecraft/mc-mods/c2me/files/8294509).
- **Chunky** and its relatives pace generation rather than speed it up:
  Chunky's own FAQ says single-thread speed matters; Chunksmith throttles
  against tick health so it runs with players online; Chunker caps chunks
  a second. [Chunky FAQ](https://github.com/pop4959/Chunky/wiki/FAQ),
  [Chunksmith](https://modrinth.com/mod/chunksmith/version/ICRwewSa),
  [Chunker](https://modrinth.com/mod/chunker).
- **Rust servers**: Pumpkin (a Minecraft server in Rust) generates most
  vanilla chunk features and claims terrain 1:1 with vanilla (its
  author's claim); vanilla only, no mod content.
  [r/rust thread](https://redlib.hackliberty.org/r/rust/comments/1l41yau/pumpkin_minecraft_chunk_generation_fully_written).
- **Rust inside mods**: FerricOxide (NeoForge, Rust over JNI, eight native
  targets built in CI and packed in one jar), Oxidizium (Fabric, replaces
  some game methods with Rust, several binding backends), NativeLoader
  (Fabric, a JNI/JNA loader), KryptonFNP (tests Java 22's FFM on Fabric,
  "Forge/NeoForge won't be able to run them"). Speedups are the authors'
  claims. [FerricOxide](https://www.curseforge.com/minecraft/mc-mods/ferric-oxide),
  [Oxidizium](https://www.modpackindex.com/mod/117370/oxidizium),
  [NativeLoader](https://modrinth.com/mod/nativeloader).
- **Java side**: the Foreign Function & Memory API is final from Java 22
  (JEP 454); Minecraft 1.21.1 runs Java 21, where it is a preview a mod
  cannot turn on, so JNI is the only native path there. Minecraft 26.x
  runs a newer Java, where FFM is open. The Vector API (SIMD in pure
  Java) is still an incubator module that needs a JVM flag.
  [Java 22 released](https://www.infoq.com/news/2024/03/java22-released/).
- **Other LOD mods**: Voxy leaves generation to Chunky and ingests saved
  chunks; Nvidium uses NVIDIA mesh shaders (no shader packs). Neither
  ships native CPU code for its LODs.

## The cost of native code in a mod

- Six to eight builds (Windows, Linux, macOS; x64 and arm64), built and
  signed in CI, extracted at run time.
- A native crash kills the game with no Java stack trace: harder support
  for a public mod, and some players and hosts distrust native binaries.
- On 1.21.1 only JNI: each call crosses the boundary and copies arrays
  (a section's 32,768 voxels) unless the data lives in direct buffers.
- Two languages to maintain beside the MultiLoader layout.

## Ideas, ranked by expected gain for the cost

1. **The preview's features, in Java** (largest share): fewer feature runs
   where they do not show (coarse tiles far away keep a canopy cover, the
   E.2 plan), caching per biome and feature where the result does not
   depend on the position, skipping features invisible at a tile's grid.
   Expected: the 85 to 90 % share falls for far tiles; no native code.
2. **Density functions compiled, not interpreted**: our heights read the
   world's noise router through `ColumnDensity`; with C2ME installed and
   its compiler on, check whether we read its compiled router (a measure
   in the pack, no code), else compile our column reads the same way
   (JVM bytecode). Under 15 % of a tile today, so at most that.
3. **A real-chunk pregenerator in Farfield, in Java** (G.2 without native
   code): the pregeneration tasks of ADR-123 (radius, player rule,
   persistence, commands) driving the game's own chunk generation, so one
   mod covers what Chunky does for an LOD server, and generated chunks go
   straight into the LODs (already as they come, checked with Chunky on
   2026-11-09). Speed then comes from the game and C2ME if installed, as
   for Chunky; our gain is the integration, not chunks a second.
4. **Native meshing, later**: a spike of the binary greedy mesher in Rust
   (or C) over direct buffers, measured against the Java one per section;
   worth it only if meshing becomes the limit (today 2.7 ms of processor a
   job, behind the reads and the preview). Better on 26.x, where FFM
   removes JNI's copies.
5. **GPU work instead of native CPU work**: density functions or meshing
   on the GPU (C2ME's OpenCL backend shows density functions can be
   compiled for a GPU; Voxy-style GPU pipelines). A research project, not
   a step.
6. **Not worth it**: a native chunk generator (vanilla only, wrong for
   modded worlds), native rendering code (GPU-bound already).

## Open questions for the user

- Idea 3 (a real-chunk pregenerator in Java, sharing ADR-123's tasks):
  wanted, or keep Chunky (plus C2ME) and only document it?
- Idea 1: is the preview's build speed a priority before E.2, which
  needs a cheaper far preview anyway?
- Idea 4: a native meshing spike now on 1.21.1 (JNI), or after the move
  to 26.x (FFM)?
