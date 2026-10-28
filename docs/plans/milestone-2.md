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
- [ ] B.4 The user's NeoForge pack on a server: fill times and bandwidth
  from the log lines. The highest ping during a fill (server stat line)
  needs a player away from the server: the user's server is on their
  home network, where it reads 0 ms as on the dev runs.

## M2.C — Shader packs

- [ ] C.1 Shader pack support (Iris on Fabric, Oculus or Iris on
  NeoForge): what the LODs need to show under a shader pack (G-buffer
  passes, shadow pass, depth), how Distant Horizons and Voxy do it, then
  an ADR.

## M2.D — Mod features in the noise preview

- [~] D.1 Features that come from mods in the preview. The user
  (2026-10-28): plains in the preview turned out, flown over, to be full
  of giant trees from a mod; the same for vegetation; handle every type
  generically, with a way to turn some off. ADR-079: the world's own
  placed features grown on the tile's ground in a stand-in world, stored
  as 2-block voxels and plant counts; server settings `preview_features`
  and `preview_feature_exclude`. Spike: 88 to 94 % of real tree columns
  found in three biomes, 0 failures; tiles 3 to 6 s, 59 to 113 KB.
  Remains: a look in a client and in the pack, the amethyst rainforest's
  34 %, the tile size.
- [ ] D.2 Real surface rules on the preview's ground (the top block is
  guessed from the biome: Biomes O' Plenty's white sand, Terralith's
  surfaces), with the same stand-in world.
- [ ] D.3 A client toggle per kind of feature (trees, plants, rocks).
