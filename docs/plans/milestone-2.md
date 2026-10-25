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

- [~] B.1 Design ADR (ADR-068, proposed 2026-10-25, waits for the user): protocol (versioned, capabilities at join), what is
  sent (stored sections and preview tiles, surface only by default per
  ADR-013), order (nearest and in view first, as `WantOrder`), budgets,
  the client cache per server (the client-mode store of ADR-022), what a
  client without Farfield on the server keeps doing.
- [ ] B.2 LOD sections streamed to clients, with Neo-Voxy's defaults as
  the first settings, measured on a dev server with two clients.
- [ ] B.3 Noise preview built by the dedicated server around each player
  and streamed the same way.
- [ ] B.4 The user's NeoForge pack on a server: fill times and bandwidth
  from the log lines.

## M2.C — Shader packs

- [ ] C.1 Shader pack support (Iris on Fabric, Oculus or Iris on
  NeoForge): what the LODs need to show under a shader pack (G-buffer
  passes, shadow pass, depth), how Distant Horizons and Voxy do it, then
  an ADR.
