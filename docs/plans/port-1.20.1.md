# Port to Minecraft 1.20.1

Goal: Farfield on Minecraft 1.20.1 (Java 17), from the same source tree as
the other versions (ADR-165). Starts once the current version is released
(the user, 2026-11-22: "une fois que j'aurai release la version actuelle,
on partira sur ça, donc prépare le terrain").

Legend: `[ ]` todo · `[~]` in progress (the text says what remains) ·
`[x]` done and verified · `[-]` dropped (with the reason).

Branch `worktree-port-1.20.1` holds the spike below. Build its node with
`-Pminecraft_versions=1.21.1,1.20.1` (1.20.1 is not in `gradle.properties`
yet); `-PallErrors` makes javac report types and signatures past the
unresolved imports.

## What the spike measured (2026-11-22)

- `api` and `core` (139 files, 28,000 lines) compile at `--release 17` with
  no error and no change. `:core:test` passes with the main classes at 17.
- 1.20.1 has no NeoForm (it starts at 1.20.2) and no NeoForge worth
  building: NeoForge for 1.20.1 is the 47.1 fork of Forge (`net.neoforged:forge`,
  last build 47.1.106, May 2024), which loads Forge 1.20.1 jars as they
  are. A Forge jar serves both; GeckoLib's 1.20.1 branch ships Forge and
  Fabric only (checked 2026-11-22). `common`
  compiles against MCP 1.20.1 in Mojang's names through ModDevGradle's
  legacy plugin (`net.neoforged.moddev.legacyforge` 2.0.148, vanilla mode,
  `mcpVersion`), on Gradle 9.9.1 and Java 25 as the other nodes.
- `:common:1.20.1:compileJava -PallErrors`: **50 errors in 14 files** out of
  114 files and 28,000 lines, after three renames as Stonecutter
  replacements and one excluded file. `:common:1.21.1`, `:fabric:1.21.1`
  and `:neoforge:1.21.1` still compile.
- Not measured: the Fabric 1.20.1 node (it waits for `common` to compile),
  a Forge node (it does not exist yet), anything at run time.

The 50 errors, by cause:

| Cause | Files | Fix |
|---|---|---|
| Custom payloads (`CustomPacketPayload`, `StreamCodec`, 1.20.5) | `net/StreamPayload` | Versioned file: a channel id and a `FriendlyByteBuf` |
| Options screen (`HeaderAndFooterLayout`, `OptionsList` as a widget, 1.20.5 and 1.21) | `client/FarfieldSettingsScreen`, `FarfieldRehearsal` | Versioned file for the screen |
| `DeltaTracker` (1.21), `renderLevel` signature | `mixin/client/LevelRendererMixin` (copied from 1.21.1) | Rewrite for 1.20.1's `renderLevel(PoseStack, float, long, ...)` |
| `SectionRenderDispatcher.RenderSection`, `CompiledSection`, `isSectionCompiled` (1.20.2) | `LodRenderer` (2), `McClient`, `LevelRendererAccessor` | `ChunkRenderDispatcher.RenderChunk`, `CompiledChunk`, through `McClient` |
| `ChunkResult` (1.20.5), `getPersistedStatus`/`setPersistedStatus`, `ChunkStatus.getName` (1.21) | `GenerationSpike`, `SurfaceColumns`, `FeatureSpike`, `PreviewFeatures` | `Either`, `getStatus`/`setStatus`, through `Mc` |
| `LevelAccessor.gameEvent` signature, one more override | `FeatureSandbox` | Stonecutter comment |
| `ResourceLocation.fromNamespaceAndPath` (1.21) | `StreamPayload` | `new ResourceLocation(ns, path)` through `Mc` |
| `ServerGamePacketListenerImpl.latency()` (1.20.2) | `StreamServer` | `player.latency` through `Mc` |
| `Thread.threadId()` (Java 19) | `CpuUsage` | `getId()` |

## Decisions to take before starting

- [x] D.1 Loaders: **Forge 47.4 and Fabric**, no NeoForge node (the user,
  2026-11-22: "Ok ça me va on fixe ça dans le plan"). Forge is a third
  loader project (`forge/`, about the size of `neoforge/`: 4 files, 200
  lines), with nodes for 1.20.1 only; the `neoforge` branch has none for
  1.20.1 (done in `settings.gradle`). The Forge jar is tagged for
  NeoForge on the stores only once it has run on NeoForge 47.1.106
  (run time item D.7). A NeoForge 1.20.1 jar of its own only if that run
  fails and a pack the user plays needs it. Precedent, GeckoLib's
  CurseForge files for 1.20.1: a `geckolib-neoforge-1.20.1` jar up to
  4.4.9, the `forge` jar tagged NeoForge and Forge at 4.7, Forge alone
  from 4.8 to 4.8.5.
- [x] D.2 `api` and `core` at Java 17 level for every version (amends
  ADR-165, "Java 21 bytecode while a supported version runs Java 21"):
  one build of them serves all nodes (the user, 2026-11-22: "Oui si pas
  de régression"). Cost: no Java 21 syntax or API in `core` (none is used
  today). Checked on the spike branch: `build` green with the five
  existing versions (`core` tests, 10 jars); the shipped jars are not
  changed by it, since each loader node compiles the `api` and `core`
  sources with its own toolchain (`LodKey.class` is class version 65 in
  the NeoForge 1.21.1 jar, 69 in the Fabric 26.3 jar). Not checked: the
  jars in game, the JMH benchmarks.
- [x] D.3 Shader packs: Iris 1.7.6 on Fabric (with Sodium 0.5.13), Oculus
  1.8.0 and Embeddium on Forge. Both are far from the Iris 1.8.14 the
  mixins target, and Sodium 0.5 has other packages
  (`me.jellysquid.mods.sodium`). The work goes in two steps, the port
  without the shader path first (P.A to P.D), then shader packs (P.S);
  **1.20.1 is released only with shader packs working** (the user,
  2026-11-22: "on peut faire en deux temps mais la 1.20.1 devra sortir
  avec les shader compatibles").
- [x] D.4 A new ADR records D.1 to D.3: ADR-177.

## P.A — Toolchain

- [x] A.1 `versions/1.20.1.properties`: Java 17, MCP 1.20.1, Parchment
  1.20.1 2023.09.03, Fabric API 0.92.12, Mod Menu 7.2.2, Forge 47.4.29,
  Iris 1.7.6 (Fabric), Oculus 1.20.1-1.8.0. Resolved by the spike build,
  except Forge and Iris (not on a classpath yet).
- [x] A.2 `common` picks its plugin by version: the legacy plugin when the
  node has `mcp_version`. Iris and Sodium's API only where the properties
  name them; Oculus (remapped by the legacy plugin) stands for Iris at
  compile time on 1.20.1 and resolves every Iris import.
- [x] A.3 `api` and `core` compiled with `options.release = 17`
  (`farfield-java.gradle`); tests keep Java 21.
- [x] A.4 `forge/` project: `multiloader-loader` with the legacy plugin,
  `mods.toml`, reobfuscated jar (`reobfJar`, SRG), a mixin refmap written
  by the annotation processor and named by each config (a `processResources`
  filter, Forge only), the `MixinConfigs` manifest attribute (Forge 47
  reads the configs there), MixinExtras 0.3.5 and aircompressor nested
  with `jarJar`. The `forge` branch has a 1.20.1 node only, and exists
  only when 1.20.1 is in the versions built. Checked in the jar: manifest,
  refmap (5 KB, SRG names), both nested jars, `mods.toml` expanded.
- [x] A.5 `multiloader-common`: the Forge keys read with `findProperty`
  (empty elsewhere), `META-INF/mods.toml` in `filesMatching`,
  `copyJarToOutput` on `reobfJar`, then `remapJar`, then `jar`. The
  shared Iris and Sodium mixin configs take `mixin_compatibility_level`
  (`JAVA_21` by default, `JAVA_17` for 1.20.1).
- [~] A.6 Fabric node: Loom 1.19 with Minecraft 1.20.1 on a Java 17
  toolchain, builds its remapped jar. Remains: `fabric.mod.json` still
  names the `sodium:config_api_user` entrypoint, whose class is left out
  on 1.20.1 (only Sodium 0.8 reads it, so nothing loads it; drop it from
  the 1.20.1 jar for tidiness).
- [~] A.7 Dev tasks: `runServer` on both loaders in `runs/1.20.1/` (Fabric
  `fabric/runs/1.20.1/server`, Forge `forge/runs/1.20.1/run`), with
  `level-name=world-1.20.1` set by hand (a fresh world of the test seed:
  the template world is 1.21.1's). Remains: `runClient` with `-PquickPlay`,
  `runVanillaServer -Pmc=1.20.1`, a 1.20.1 template world in `dev/`.
- [~] A.8 Rehearsal (its own checkout, `rehearsal.dir`): 1.20.1 nodes on
  Fabric and a new `forge` project, the two pattern switches of
  `RehearsalClient` turned into `instanceof` chains (Java 17), a `McCompat`
  for three calls, Forge 47's early window hand-off in `WindowMixin`.
  `common` compiles for 1.20.1. Remains: its full build, the jars in a
  dev client, and a commit there (this session cannot run git outside the
  Farfield worktree).

## P.B — `common`

- [x] B.1 The 50 errors, `common` first: renames as replacements under
  `!atLeast('1.20.5')` (chunk status package and its persisted accessors,
  the options and video screens, the player's ping), shape changes through
  `Mc` (`id`, `chunkLoaded`) and `McClient` (`sectionCompiled`), Stonecutter
  comments for `Thread.threadId`, `LevelAccessor.gameEvent` and
  `LodRenderer`'s walk of vanilla's visible sections. `:common:1.20.1`
  compiles with no error.
- [x] B.2 `src/versioned/1.20.1/`: `farfield.mixins.json` at `JAVA_17`,
  `LevelRendererMixin` rewritten (`renderLevel(PoseStack, float, long,
  ...)`, the pose stack's matrix as the frustum matrix, `renderChunkLayer`),
  `FogRendererMixin` kept as 1.21.1's (`setupFog` has the same signature and
  branches in 1.20.1), `LevelRendererAccessor` on `renderChunksInFrustum`
  with `RenderChunkInfoAccessor` (its entries are private to the package),
  `StreamPayload` (a channel id and the bytes), `FarfieldSettingsScreen`
  (tabs over plain options lists; the sliders applied on release in later
  versions apply when the screen closes). Not checked in a client.
- [x] B.3 Java 21 syntax: none left in `common` (it compiles at 17); the
  Iris and Sodium configs take the version's level (A.5).
- [ ] B.4 Mixin targets checked against 1.20.1's sources, one by one:
  `LevelChunk`, `ChunkGenerator`, `BiomeManager`, `ClientLevel`,
  `LevelRenderer` accessors, `SpriteContents`, `Window` (1.20.1 asks for
  OpenGL 3.2; which context Forge 47's early window makes is to check,
  ADR-166 covers the case where it is under 4.6). `DedicatedServerMixin`
  is not needed (no pause of an empty server before 1.21.2).
- [x] B.5 World generation paths of the noise preview on 1.20.1's
  generator: on both dev servers the preview builds tiles, grows the
  world's features (Forge, third minute: 180 ms of features a quarter,
  8 villages) with `0 features failed` in every minute line.

## P.C — Loaders

- [x] C.1 Fabric: a 1.20.1 `FabricNetwork` (in `fabric/src/versioned/
  1.20.1/`) on a byte channel, receivers that read the bytes on the
  network thread and handle the message on the game thread; the client
  receiver moved into `FabricNetwork.registerClient` for every version.
  Streaming not checked between two games yet (D.4).
- [x] C.2 Forge: `FarfieldForge`, `FarfieldForgeClient`, `ForgePlatform`,
  `ForgeNetwork` (an optional `SimpleChannel`: absent or vanilla accepted
  both ways, handlers on the game thread), `ConfigScreenHandler` for the
  Mods screen, `TickEvent` END phases. Compiles; the server half ran (D.1).
- [x] C.3 Dedicated-server safety on Forge: client setup in
  `FarfieldForgeClient`, reached only when `FMLEnvironment.dist` is the
  client; the Forge dev server booted without loading it.

## P.D — Run time

- [x] D.1 Dev servers, both loaders, on a fresh world of the test seed:
  `Done` with no mixin failure (Forge: MixinExtras initialised; the refmap
  warning is the dev run's, its names are Mojang's), stores opened, live
  ingest (Fabric 726 chunks, Forge 716), the preview running, clean stop
  with the store counters. Only the server mixins apply on a dedicated
  server: the client ones wait for D.3.
- [~] D.2 Importer on 1.20.1's own chunks (DataVersion 3465): Fabric dev
  server, second boot of its 1.20.1 world, `farfield import`: `LOD import
  of minecraft:overworld done: 529 chunks in 1 s (699/s), 4 regions (0
  already done, 0 failed)`. Remains: the corpus world `dh-baseline-1.20.1`.
- [~] D.3 Dev clients, both loaders (`farfield-dev-client-check`): LODs
  drawn, depth and fog joined to vanilla's (1.20.1's fog and its
  `renderLevel` order differ from 1.21.1's). Done: both runs configure
  (`--dry-run`, `:forge:1.20.1:prepareClientRun`, Rehearsal 1.20.1 on the
  Forge run). Remains: every client check, no client has run yet.
- [ ] D.4 Client mode against the vanilla 1.20.1 server; streaming between
  a 1.20.1 server and client on each loader.
- [ ] D.5 LOD stores written by 1.20.1 read by 1.21.1 after a world
  upgrade (text keys, ADR-165).
- [ ] D.7 The Forge jar on NeoForge 47.1.106 (a 1.20.1 NeoForge instance
  of a launcher): boots, LODs drawn. If it holds, the listing tags the
  Forge 1.20.1 file for NeoForge too.
- [ ] D.6 The user's test in a real 1.20.1 pack (Forge), with Embeddium,
  without a shader pack (shader packs: S.5).

## P.S — Shader packs (second step, before any 1.20.1 release)

- [ ] S.1 Fabric: the nine Iris mixins and the shader path compiled
  against Iris 1.7.6 (the spike only resolved the imports, against
  Oculus); each mixin target checked in Iris 1.7.6's sources. What Iris
  1.8 added and 1.7 lacks goes in versioned files.
- [ ] S.2 Forge: the same against Oculus 1.8.0, each target checked in
  its sources.
- [ ] S.3 Sodium 0.5.13 and Embeddium 0.3.31: `RenderSectionMixin` on
  their `RenderSection` (`me.jellysquid.mods.sodium...`), the mixin
  plugin's detection; no Sodium config page (the API starts at 0.8).
- [ ] S.4 Dev clients with a shader pack on each loader: LODs drawn in
  the pack's programs, depth joined, no `Mixin apply failed`; the
  packs of the 1.21.1 checks.
- [ ] S.5 The user's test in a real pack with Oculus and a shader pack.

## P.E — Shipping

- [ ] E.0 Gate: P.S done. No 1.20.1 jar is published before shader packs
  work on both loaders (decision D.3).
- [ ] E.1 `minecraft_versions` gains 1.20.1; `build` makes its two jars.
- [ ] E.2 `release.yml`: two targets (`forge` and `fabric`, game version
  1.20.1, Java 17); the shared `publish-mod` workflow must know the
  `forge` loader. `build.yml` boots a dev server per node.
- [ ] E.3 Listing and README: the where-it-works table, Forge.
- [ ] E.4 `CLAUDE.md`: versions, the `forge` project and its commands.
