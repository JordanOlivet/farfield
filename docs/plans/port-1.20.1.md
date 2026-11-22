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

- [ ] D.1 Loaders: **Forge 47.4 and Fabric** (recommended), no NeoForge
  node. Forge is a third loader project (`forge/`, about the size of
  `neoforge/`: 4 files, 200 lines), with nodes for 1.20.1 only; the
  `neoforge` branch has none for 1.20.1 (done in `settings.gradle`).
- [ ] D.2 `api` and `core` at Java 17 bytecode for every version (amends
  ADR-165, "Java 21 bytecode while a supported version runs Java 21"):
  one build of them serves all nodes. Cost: no Java 21 API in `core`
  (none is used today).
- [ ] D.3 Shader packs: Iris 1.7.6 on Fabric (with Sodium 0.5.13), Oculus
  1.8.0 and Embeddium on Forge. Both are far from the Iris 1.8.14 the
  mixins target, and Sodium 0.5 has other packages
  (`me.jellysquid.mods.sodium`). Recommended: first release without the
  shader path on 1.20.1 (LODs off under a shader pack, as beside an
  unsupported Iris), then a second step.
- [ ] D.4 A new ADR records D.1 to D.3 and the version list.

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
- [ ] A.4 `forge/` project: `multiloader-loader` with the legacy plugin
  (`legacyForge { version = "1.20.1-${forge_version}" }`), `mods.toml`,
  reobfuscated jar (SRG), a mixin refmap (Forge 1.20.1 needs one, unlike
  NeoForge), MixinExtras nested with `jarJar` (Forge does not bundle it),
  aircompressor nested.
- [ ] A.5 `multiloader-common`: `expandProps` without the NeoForge keys on
  a version that has none (the spike sets them empty), `mods.toml` in
  `filesMatching`, `copyJarToOutput` on Forge's `reobfJar`.
- [ ] A.6 Fabric node: Loom 1.19 with Minecraft 1.20.1 on a Java 17
  toolchain; `fabric.mod.json` without the `sodium:config_api_user`
  entrypoint on 1.20.1.
- [ ] A.7 Dev tasks: run dirs `runs/1.20.1/`, `runVanillaServer -Pmc=1.20.1`
  on JDK 17, `-PquickPlay` (1.20.1 has `--quickPlaySingleplayer`).
- [ ] A.8 Rehearsal needs a 1.20.1 node on both loaders before any
  minimized client run.

## P.B — `common`

- [ ] B.1 The 50 errors of the table above, `common` first (the skill's
  order): renames as replacements under `!atLeast('1.20.5')`, shape
  changes through `Mc` and `McClient`, whole files in
  `src/versioned/1.20.1/`.
- [ ] B.2 `src/versioned/1.20.1/`: `farfield.mixins.json` at `JAVA_17`
  (done), `LevelRendererMixin` and `FogRendererMixin` rewritten for
  1.20.1 (the spike copied 1.21.1's: the first does not compile, the
  second compiles and its targets are not checked), `StreamPayload`,
  `FarfieldSettingsScreen`.
  1.21.1 gets no folder change: it keeps its own.
- [ ] B.3 The three files with Java 21 syntax (`BlockKeys`,
  `FeatureSpike`, `SurfaceSpike`) and `compatibilityLevel` of the Iris
  and Sodium mixin configs (`JAVA_21` in shared resources: per version,
  or `JAVA_17` for all).
- [ ] B.4 Mixin targets checked against 1.20.1's sources, one by one:
  `LevelChunk`, `ChunkGenerator`, `BiomeManager`, `ClientLevel`,
  `LevelRenderer` accessors, `SpriteContents`, `Window` (1.20.1 asks for
  OpenGL 3.2; which context Forge 47's early window makes is to check,
  ADR-166 covers the case where it is under 4.6). `DedicatedServerMixin`
  is not needed (no pause of an empty server before 1.21.2).
- [ ] B.5 World generation paths of the noise preview on 1.20.1's
  generator (`NoiseChunk`, `RandomState`, surface rules, features):
  compiles is not enough, `N features failed` must be 0 on the dev server
  (skill, item 12). Stand-in with the preview off if it does not hold
  (item 10).

## P.C — Loaders

- [ ] C.1 Fabric: networking through `FriendlyByteBuf` receivers (Fabric
  API 0.92 has no `PayloadTypeRegistry`), `canSend` on the channel id.
- [ ] C.2 Forge: `FarfieldForge`, `FarfieldForgeClient`, `ForgePlatform`,
  `ForgeNetwork` on `MinecraftForge.EVENT_BUS`; an optional
  `SimpleChannel` or event channel that servers and clients without
  Farfield accept (ADR-068); `ConfigScreenHandler` for the Mods screen;
  `TickEvent` phases in place of `Post` events.
- [ ] C.3 Dedicated-server safety on Forge (client classes in their own
  class, as on NeoForge).

## P.D — Run time

- [ ] D.1 Dev servers, both loaders: boot to `Done`, no `Mixin apply
  failed`, clean stop with the store counters.
- [ ] D.2 Importer on 1.20.1's own chunks (DataVersion 3465): `LOD import
  of ... done: N chunks ... 0 failed` on a world 1.20.1 wrote, and on the
  corpus world `dh-baseline-1.20.1`. The decoder already reads this
  format (ADR on region compression); the test world is 1.21.1's and
  cannot be opened by 1.20.1, so 1.20.1 needs a test world of its own
  (same seed).
- [ ] D.3 Dev clients, both loaders (`farfield-dev-client-check`): LODs
  drawn, depth and fog joined to vanilla's (1.20.1's fog and its
  `renderLevel` order differ from 1.21.1's).
- [ ] D.4 Client mode against the vanilla 1.20.1 server; streaming between
  a 1.20.1 server and client on each loader.
- [ ] D.5 LOD stores written by 1.20.1 read by 1.21.1 after a world
  upgrade (text keys, ADR-165).
- [ ] D.7 The Forge jar on NeoForge 47.1.106 (a 1.20.1 NeoForge instance
  of a launcher): boots, LODs drawn. If it holds, the listing tags the
  Forge 1.20.1 file for NeoForge too.
- [ ] D.6 The user's test in a real 1.20.1 pack (Forge), with Embeddium,
  and with Oculus once D.3 of the decisions is settled.

## P.E — Shipping

- [ ] E.1 `minecraft_versions` gains 1.20.1; `build` makes its two jars.
- [ ] E.2 `release.yml`: two targets (`forge` and `fabric`, game version
  1.20.1, Java 17); the shared `publish-mod` workflow must know the
  `forge` loader. `build.yml` boots a dev server per node.
- [ ] E.3 Listing and README: the where-it-works table, Forge.
- [ ] E.4 `CLAUDE.md`: versions, the `forge` project and its commands.
