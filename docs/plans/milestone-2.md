# Milestone 2 — First release, server streaming, shaders

Goal: Farfield public and findable (a real logo, release 0.1.0), then LODs
and the noise preview on servers with Farfield on both sides, then shader
packs.

Legend: `[ ]` todo · `[~]` in progress (the text says what remains) ·
`[x]` done and verified · `[-]` dropped (with the reason).

## M2.A — Release

- [x] A.1 A real logo, in place of the word "farfield" in black on white:
  the icon of the jar (`farfield.png` of both loaders' metadata) and of the
  Modrinth and CurseForge pages. The user chose direction E of five (dusk
  ridges built from blocks of 16, 32 and 64 px, light joints): "Oui je
  valide E". Source `docs/brand/farfield-logo.svg`, rendered by
  `docs/brand/render_logo.py` to `common/src/main/resources/farfield.png`
  (512 px), referenced by `fabric.mod.json` (`icon`) and
  `neoforge.mods.toml` (`logoFile`); both jars hold it. Modrinth: icon,
  banner and description from `docs/listing/` (2026-10-25); CurseForge
  pasted by the user. The user: "Tout est bon". Not checked: the icon in
  the in-game mod lists (Mod Menu, NeoForge)
- [x] A.2 Release 0.1.0 (tag `v0.1.0`, release type, not alpha): an alpha
  is hidden from CurseForge search; Modrinth lists the project once it is
  approved. Checked before it: the user's Fabric pack (Cobblemon) and
  NeoForge pack with Farfield on the server and the client ("Le test est
  concluant", 2026-10-25). Published 2026-10-25, run 40195779172: five
  jobs green, both Publish jobs `Successfully published the assets to
  CurseForge, Modrinth`, GitHub Release `Farfield 0.1.0` (not a
  pre-release) with both jars. CurseForge search shows the project once
  the files were approved (the user, 2026-10-25). Not checked: Modrinth
  search (the project is still in review)

- [x] A.3 Release 0.2.0 (tag `v0.2.0`, release type): server streaming
  of LODs and the preview (B.2, B.3), mod features and surface rules in
  the preview (D.1, D.2, D.4, D.5). Published 2026-10-30, run
  41088514099: five jobs green, both Publish jobs `Successfully
  published the assets to CurseForge, Modrinth`, `version-type:
  release`, GitHub Release `Farfield 0.2.0` (not a pre-release) with
  both jars and a summary on top of the commit list. The listing
  rewritten (sections, a where-it-works table, server owners,
  compatibility) with ten screenshots from the dev client, pushed to
  Modrinth with its gallery (2026-10-30; the user: "on va faire avec ça
  pour commencer", their own screenshots later). CurseForge: text and
  images (JPEG under its 2 MB limit) put there by the user, who closed
  with "tout est bon".

## M2.B — Server streaming of LODs and the noise preview (ADR-013)

Today the noise preview is built by the integrated server only, and a
client on a dedicated server builds LODs only from the chunks it receives.
With Farfield on the server, the server builds both and sends them to its
players, as Neo-Voxy did for LODs (the user: "ça marchait très bien pour
les LOD, on doit pouvoir réutiliser les mêmes concepts et paramétrages").
Reuse the concepts, never the code (ADR-001).

Neo-Voxy pieces to study (`<neo-voxy repo>/common/src/main/java/me/cortex/voxy/`):

- `common/network/ChunkedLodSender`: sections split into 64 KB pieces, sent
  every tick within the player's bandwidth share.
- `common/network/SharedBandwidthLimit`: a global budget shared by players.
- `client/network/ClientCongestionControl`: the client lowers its rate.
- `common/network/BloomFilter`: what the client already holds, so the
  server skips it.
- `common/network/IdRemapper`: block-state and biome ids of the server
  mapped to the client's.
- `common/network/VoxyNetworkHandler`: capabilities negotiated at join.
- `server/VoxyServerConfig` defaults: streaming radius 64 chunks (1,024
  blocks), client hint capped at 64 chunks, 1,024 KB/s per player, 10,240
  KB/s for all, serialize threads auto (min(4, cpus / 2)), dirty queue 8,192
  sections, 5 Hz while rings expand and 1 Hz in steady state, a cache of
  4,096 serialized sections, debug logs off by default.

Items:

- [x] B.1 Design ADR (ADR-068, accepted 2026-10-25 with "go B.2"): protocol (versioned, capabilities at join), what is
  sent (stored sections and preview tiles, surface only by default per
  ADR-013), order (nearest and in view first, as `WantOrder`), budgets,
  the client cache per server (the client-mode store of ADR-022), what a
  client without Farfield on the server keeps doing.
- [x] B.2 LOD sections streamed to clients, with Neo-Voxy's defaults as
  the first settings, measured on a dev server with two clients. Done:
  handshake, key tables, region indexes, pull of sections with versions,
  per-player and shared budgets, the client mirror with its versions,
  the renderer fed by it; `config/farfield-server.properties`. Fabric dev
  server on a copy of the test world, one Fabric dev client: 10,188
  sections (about 25 MB) at 1,024 KB/s, the cap; view settled at 24.6 s;
  then nothing in flight. Surface only (ADR-013, `SurfaceIndex`,
  `full_data=false` by default): 7,706 sections and about 14.8 MB
  instead of 10,188 and 25 MB, view settled at 15.9 s instead of 24.6 s,
  the same image (captures identical to 8 levels of 255); a rejoin with
  the mirror kept fetched 16 sections and settled at 4.4 s. 2026-10-26
  (protocol 4): change notices (`CHANGED`: the server tells the players
  near a region its sections were written, once the region is quiet for
  3 s or at most every 20 s; the client asks for the index again and
  fetches what moved) and a fetch window sized by the answers' delay
  (AIMD, `FetchWindow`: 64 at most, halved when an answer is 1 s later
  than the quickest). Fabric dev server with an empty store, two clients
  (Dev at spawn, Dev2 at 1,500, 0), `farfield import` while both were
  connected: Dev2 went from 401 sections held to 690 through the
  notices, without reconnecting; its rejoin then fetched 19 of 690 and settled at
  1.4 s. At 64 KB/s per player the window went 64, 32, 16, 4 and the
  server's queue 144, 29, 20, 2 messages, then the window grew back to
  22. NeoForge dev server and client: streamed, notices, no error. The
  ping during a fill: the server's stat line now logs the highest ping
  and the longest queue; 0 ms on a local link, a real link is B.4
- [~] B.3 Noise preview built by the dedicated server around each player
  and streamed the same way. Done: `preview` (default true) in
  `farfield-server.properties` turns `NoisePreview` on for a dedicated
  server; tiles pulled as their files (`TileFetch`, `Tile`, CRC32C
  versions), nearest first, 16 in flight; the server tells the players
  near a tile once it is built (`TILE_CHANGED`); protocol version 2.
  Fabric dev server and client, empty mirror: the server built every
  tile within 4,096 blocks in 33 s, the client held all 232 within 10 s,
  view settled at 15.5 s, the horizon drawn from y 220. The user's
  NeoForge pack on the home server (deployed by SSH with the user's yes):
  236 tiles built in about 90 s and sent in 10 s, 4,988 sections held,
  view settled at 12.0 s; the user: "de mon côté ça a l'air de bien
  marcher". Not Farfield's (2026-10-26, from the logs): the welcome
  came 1 s after the pack finished joining (connect 23:25:57, joined
  23:26:58, welcome 23:26:59); the "Attempted to load class
  net/minecraft/client" traces (63) are ScalableLux's mixin in a block
  state's cache, built lazily by ModernFix, on whichever thread first
  asks (52 from AE2's creative tab, 9 from the surface filter).
  Remains: faint grid lines on frozen sea in a dev capture, not compared
  with single player. The tile requests have their own fetch window
  (1 to 16) since 2026-10-26: at 64 KB/s per player it went 16, 8, 6,
  5, 3 and the server's longest queue stayed at 7 to 10 messages (20 to
  31 before)
- [-] B.4 The user's NeoForge pack on a server: fill times and bandwidth
  from the log lines. The highest ping during a fill (server stat line)
  needs a player away from the server: the user's server is on their
  home network, where it reads 0 ms as on the dev runs. Set aside by the
  user (2026-11-09): "on verra si à l'usage, le besoin se fait ressentir
  de nouveau. Pour le moment, on va dire qu'on est bon sur ce sujet."

## M2.C — Shader packs

- [~] C.1 Shader pack support: what the LODs need to show under a shader
  pack, how Distant Horizons and Voxy do it, then an ADR. Done: the two
  contracts (`docs/analysis/shader-lod-contracts.md`) and ADR-113 (Voxy's
  contract first, then DH's, through mixins into Iris). Remains: C.1a to
  C.1e.
  - [x] C.1a Spike: opaque LODs and the preview through `photon-gcea`'s
    `voxy_opaque` in a pack client (Iris 1.8.14, NeoForge): the snippet
    linked into `lod.frag`, its draw buffers, `vxDepthTexOpaque` and the
    `vx*` matrices in the composites, the `VOXY` macro. Done: opaque LODs
    drawn and lit by Photon in the pack client (2026-11-03, run 7).
    Then: Fabric under Complementary (production runs, 2026-11-04),
    shaders off on both loaders (runs of 2026-11-07 and 2026-11-08), the
    user's eye at night in their NeoForge pack under photon-gcea
    (2026-11-09).
  - [x] C.1b Translucent LODs (`voxy_translucent`, `vxDepthTexTrans`,
    blending), then the other packs of the user's instance (BSL,
    Complementary, MakeUp, Photon 1.3b); a link error falls back. Done
    (code, built): the pass at the game's translucent stage, its target and
    depth, the blending per draw buffer, opaque game terrain in front
    hides it; render targets past Iris's 16 given by Farfield (Photon's
    colortex16). Verified under photon-gcea in the pack client (sea by day
    and night, 2026-11-03, run 12), then under the five other packs of
    the user's instance (BSL, Complementary Reimagined and Unbound, MakeUp,
    Photon 1.3b, 2026-11-04), drawn before the pack's deferred passes as
    Voxy's. The dark squares seen on the Fabric pack's sea were ours, not
    the pack path's: the preview's water discarded through an uninitialized
    GPU buffer, fixed by zero-filled buffers (8 clean runs against about 1
    in 3 before, 2026-11-04). The user's eye: the LOD sea under
    photon-gcea at night in their NeoForge pack (2026-11-09).
  - [x] C.1c `customId` from the pack's `block.properties`, TAA offset and
    render scale. Done (code, built): ids per material from Iris's block
    state map, the `taaOffset` jitter in every pack program (pre-pass
    included, built again without it if it fails), the viewport scaled by
    `renderScale`; `vxRenderDistance` covers the farthest LOD geometry.
    Verified running under photon-gcea (TAAU 0.75, 803 of 1,673
    materials with a pack id). The user (2026-11-09, photon-gcea): no
    shimmer on the far LODs while moving.
  - [x] C.1d D.6 block light in the preview, for the packs' lightmap.
    Done (ADR-117): the features' light sources kept in the tiles
    (format 17, protocol 8), the preview's voxels lit from them, the
    `preview_light` setting (default on). Verified on the Fabric pack, no
    pack and Complementary, warm ocean at night. The user's eye in their
    NeoForge pack on the home server (tiles streamed), a shader pack on
    (2026-11-08, confirmed 2026-11-09).
  - [x] C.1e DH's contract for packs without Voxy's (ADR-119). Done: the
    contract chosen per pack at load (one macro), the pack's DH programs
    run on the LOD quads over a bridge, `/farfield debug shaderpack`.
    Verified on the Fabric pack under Complementary Reimagined forced to
    DH (day, night, sea), then by the user in their NeoForge pack under
    photon-gcea forced to DH (2026-11-09): drawn and lit, but flatter than
    through Voxy's contract, no ambient occlusion (confirmed by turning it
    off under Voxy's). Closed with the user (2026-11-09): a pack forced to
    DH runs the same path as one that ships DH's contract only (the same
    macro, programs and bridge), so the one difference, the automatic
    choice, is unit-tested (`ContractChoiceTest`: a DH-only pack, both, a
    forced contract, neither, a zipped pack). Left out: the DH code of
    packs not tried (each pack's own, looked at when a player reports
    one), the GPU cost (about +200 us at 1080p, measured once). Put off by
    the user: the ambient occlusion under DH (the pack's shader gets one
    color per vertex and the quads are merged: it needs quads split where
    the occlusion changes, more geometry under DH only).
  - Later, outside C.1 (the user, 2026-11-03): packs with neither
    contract (LODs off with a message meanwhile), our own contract, LOD
    shadows.
  - LOD shadows, studied 2026-11-09 (Iris 1.8.14, the user's six packs,
    their Photon fork), kept as a possible improvement (the user: "pas
    dramatique", the pack's option fixes it): the shadow map covers the
    pack's `shadowDistance` only (Photon 128, Complementary 256); no pack
    of the user's ships `dh_shadow` (Photon sets `dhShadow.enabled =
    false`) and Voxy's contract has no shadow pass, so under real DH their
    LODs cast no shadow either. Photon shades past `shadowDistance` with
    screen-space shadows (`SHADOW_SSRT`), which read the LODs' depth; the
    gap is the band between the game's render distance and
    `shadowDistance` (64 to 128 blocks at render distance 4). Fixes, by
    reach: the player sets Photon's shadow distance to the render
    distance; their fork uses the screen-space shadows on LOD pixels;
    Farfield draws the LODs in that band into the shadow map through the
    pack's own `shadow` program (a bridge like ADR-119's, 1 to 2 days, GPU
    cost unmeasured).

## M2.D — Mod features in the noise preview

- [x] D.1 Features that come from mods in the preview. The user
  (2026-10-28): plains in the preview turned out, flown over, to be full
  of giant trees from a mod; the same for vegetation; handle every type
  generically, with a way to turn some off. ADR-079: the world's own
  placed features grown on the tile's ground in a stand-in world, stored
  as 2-block voxels and plant counts; server settings `preview_features`
  and `preview_feature_exclude`. Spike: 88 to 94 % of real tree columns
  found in three biomes, 0 failures; tiles 3 to 6 s, 59 to 113 KB.
  Remains: a look in a client and in the pack, the amethyst rainforest's
  34 %, the tile size. The user (2026-10-30), after their tests: "de tout
  mes tests rien ne m'est sauté au yeux donc je pense qu'on est bon";
  reopened if they see something. Left as they were: the amethyst
  rainforest's 34 %, the tile size.
- [x] D.2 Real surface rules on the preview's ground (the top block is
  guessed from the biome: Biomes O' Plenty's white sand, Terralith's
  surfaces), with the same stand-in world. ADR-101: the generator's own
  surface rules on one column per cell of each stand-in chunk; against
  real generation (8 biomes of vanilla, Biomes O' Plenty and Terralith,
  8 x 8 chunks each) the ruled column's top is right in 98.2 to 100 % of
  cells, the guess in 0 to 77 %; 2 to 2.4 s more a tile. Seen in a dev
  client (redwood forest, badlands). ADR-102 (biome per quart column,
  7.9 s a tile in the pack), ADR-104 (ModernFix's lookup bypassed: the
  grid). The user, on their server (2026-10-30): "D.2 c'est ok".
- [x] D.3 A client toggle per kind of feature (trees, plants, rocks).
  ADR-121: trees, plants, rocks and others, client settings in the
  settings screen's new Preview tab (the screen now in tabs, the user's
  pick) and `/farfield preview trees|plants|others`, applied when the
  preview is meshed. Verified on the Fabric pack: trees and others
  switched off and on over a snowy taiga (the crowns' snow follows the
  trees), the preview meshed again each time; plants by unit test only (at
  854x480 the plains view changed 0.5 % of pixels). The user, in their
  NeoForge pack on the home server (2026-11-09): "C'est nikel, le menu
  marche bien et le filtrage aussi" (the log: each kind switched off and
  on, 2,032 to 2,202 preview sections meshed again).
- [x] D.4 Vegetation without collision that grows like kelp or vines
  (`GrowingPlantBlock`: Biomes O' Plenty's `high_grass`) was drawn as
  nothing, in real LODs and in the preview. ADR-080: classed as plants,
  double plants too; the tiles keep out what LODs never draw and the
  plants under the sea; the preview store persists its key tables before
  each tile. Dev tile checked; the user found nothing wrong in the pack
  (2026-10-30, with D.1).
- [x] D.5 The sea floor in the preview: kelp and seagrass (ADR-105) and
  the features under the sea, coral blocks first (ADR-108), as the real
  LODs draw them. The user, on their server (2026-10-30): "c'est bon pour
  le corail". Left out as in the real LODs: coral fans and plants, sea
  pickles (water there).
- [x] D.7 The lakes the features dig, in the preview (ADR-118, tile format
  18, protocol 9): found missing by a light-source probe (2026-11-07,
  surface lava lakes under the tile's surface); the user picked drawing
  them. Verified on the Fabric pack, the lava lake at -925, 1558, day and
  night, then by the user's eye in their NeoForge pack on the home server
  (2026-11-08). The user ticked it (2026-11-09): "Pour le D.7 tu peux
  valider aussi". Left out, not checked: a mod's water lake.
- [~] D.6 Block light in the preview (ADR-117, see C.1d): the light sources the stand-in's
  features place (sea pickles among corals, glow lichen, sea lanterns,
  mods' glowing plants), kept per feature voxel with their emission and
  spread at draw time into the voxels' block light, as the real LODs show
  it. The user (2026-10-30): later, "avec possibilité de désactiver dans
  les options" (a client setting, on by default or not: their call then).
  When (2026-10-30): after release 0.2.0, with the shader pack version
  (C.1), "car la lumière sera importante à ce moment".

## M2.E — Distance up to 16,384 blocks

Put off by the user (2026-10-30): "c'est pas du tout prio". The order
from there: D.2, then the sea floor's plants and corals in the preview.

- [ ] E.1 The user (2026-10-28): raise the limit from 4,096 to 16,384
  blocks, 4,096 staying the default. A server maximum never exceeded
  (`streaming_radius`, capped at 16,384); a client wish (default 4,096),
  clamped by the server's maximum when connected, applied as is in single
  player or client-only rendering. Raise the hard-coded 4,096 caps
  (`LodRenderer.MAX_DISTANCE`, `NoisePreview.RANGE`, `RenderConfig` fog and
  texture maxima); update every tooltip and text (en_us, fr_fr).
- [ ] E.2 A cheap preview beyond 4,096 blocks: the area is 16 times larger
  (about 3,200 tiles instead of 236, 50 to 60 min of server work after a
  teleport at today's 5 to 7 s a tile, about 250 MB a world and as much
  for each client). Heights every 32 or 64 blocks there and no feature
  voxels (a canopy cover per chunk instead), measured before choosing.

## M2.F — Tooling

- [x] F.1 The user (2026-11-08): move the agent test harness (the agent
  inbox of ADR-095 and its verbs, window handling, screenshots, scripted
  teleports and waits) out of Farfield into a mod of its own, loaded only in
  test runs. Instrumentation and probes then go into that mod rather than
  into Farfield (unless they need Farfield's internals), a run without
  Farfield keeps the harness (today a baseline run has no inbox and no
  window handling), and the harness serves the user's other mods. Seen
  while measuring Farfield's memory against a pack run without it.
  ADR-122 (2026-11-09): Rehearsal, its own repository, replacing this
  harness and the user's `mc-test-harness`; Farfield keeps one extension
  class. Verified: the Fabric pack with and without Farfield (a JSON
  scenario), Farfield's NeoForge and Fabric dev clients, the user's
  NeoForge pack through `neoforge_pack.py` (shader switch, measures,
  replay); `mc-test-harness` archived.

## M2.G — Ideas to study (the user, 2026-11-09)

Raw ideas, written down so they are not lost; each needs a study (what
exists, what it would gain, measured) before any work. The user: "c'est
vraiment que des idées jetées en vrac". The native-code ones are about
thinking past a plain Java mod for speed; Rust is the user's first
thought, not a fixed choice.

- [~] G.1 Build the noise preview around the spawn ahead of time on a
  server (client-server mode), so the first player to join finds it
  ready. Today (B.3) the server builds it around each player once they
  are there. ADR-123: pregeneration tasks (the spawn's at start,
  `preview_spawn_radius`, up to 16,384 blocks; `/farfield pregen`
  start, spawn, pause, resume, cancel, list), saved in the world and
  resumed, paused while players are online by default
  (`pregen_when_players`). Remains: see the progress log of 2026-11-09.
- [~] G.1b The user (2026-11-09): import the LODs of a world that lived
  without Farfield at start, unattended, with the same player rule.
  Studied (`docs/analysis/auto-import.md`); the user picked: once, then the
  regions changed without Farfield (a stop mark), every dimension, on by
  default, the pregeneration held past 1,024 blocks while it runs.
  ADR-124, built and checked on the Fabric dev server (never imported, a
  clean restart, a region changed after the stop, a crash). Remains: the
  player hold in a run, NeoForge, the user's server.
- [ ] G.2 A chunk pregenerator of our own, like Chunky, with the heavy
  work offloaded to native code (Rust, if possible) for speed. Explored
  (`docs/analysis/native-code-and-pregen.md`, 2026-11-09): generation is
  the game's and the mods' Java, so native code cannot speed it for a
  modded world; a Java pregenerator sharing ADR-123's tasks is possible,
  its gain the integration. The user's call pending.
- [ ] G.3 Native code (Rust, if possible) to speed up the LODs' build, the
  preview's build, and the rendering of both. Explored (same file): the
  preview's cost is the features (85 to 90 %, mod Java code), the
  rendering is GPU-bound; ranked ideas there (the features in Java first,
  compiled density functions, a native meshing spike later on 26.x).
