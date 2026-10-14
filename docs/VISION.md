# Farfield — Vision

Farfield renders Minecraft terrain far beyond the vanilla render distance,
using levels of detail (LOD), so that the world looks endless: hundreds to
thousands of chunks, chosen by the player or the server operator.

## What makes it worth building

- **Performance first**: a GPU-driven renderer, compact data, no stutter. The
  mod must be cheap enough that players leave it on.
- **Seamless**: no visible line where vanilla terrain ends and LOD terrain
  begins, with or without shader packs.
- **Small footprint**: little RAM, VRAM and disk; the LOD of a world must cost
  a fraction of the world itself.
- **Works everywhere**: single player, small servers, and large public
  servers with a robust streaming protocol that never hurts gameplay.
- **Full control**: our own code, publishable, on NeoForge and Fabric, ready
  for Minecraft's move to Vulkan.

## Product requirements

- Terrain beyond the vanilla distance, 256 to 512+ chunks, user-selectable.
- Unexplored (not yet generated) terrain shown, even coarsely.
- A command that builds the LOD of everything already generated in an
  existing world, throttled so it can run at night without hurting the
  server.
- Any shader pack works without pack-specific configuration, as far as
  technically possible.
- Server streaming safe by default: only the visible surface is sent unless
  the operator enables more.
- Presets from light to ultra; no announced minimum hardware.
- Diagnostics built in: debug overlay lines at a fixed, always-visible place
  on every loader, dumps, a diagnostic bundle command.

## Later (v2 and beyond)

- A world map showing each chunk's LOD state, with errors and logs.
- Importers for Distant Horizons, Voxy and Neo-Voxy data.
- A public API, a Paper/Folia server plugin, an optional side channel or HTTP
  tiles for LOD data.
- Minecraft 26.x with a native Vulkan backend.

## Non-goals (for now)

- Replacing Sodium for near terrain.
- Rendering entities or block entities as LOD.
- macOS (possible later through Vulkan/MoltenVK).
- Minecraft versions older than 1.21.1.

## Success metrics (targets to confirm with the spikes)

| Metric | Target |
|---|---|
| Frame time added at a 512-chunk LOD radius | < 2 ms on a mid-range GPU |
| Disk per explored area | < 25 % of the region files for the same area |
| Java heap held by the mod | < 300 MB at the default preset |
| Region-file import | > 2000 chunks/s on 8 cores |
| Coarse horizon after joining | < 5 s |
| Gameplay ping while streaming | unchanged |
| Seam at the hand-off band | not detectable in blind screenshot comparisons |
