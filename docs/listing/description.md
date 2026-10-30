![Farfield](https://cdn.modrinth.com/data/3ToF1nXi/images/51830b7991218d6e6fca326bdf0fa9cfaac1a44e.png)

**Farfield** draws the terrain beyond your render distance as levels of
detail (LOD): mountains, coasts and forests up to 4,096 blocks away, for a
fraction of what real chunks would cost.

Minecraft 1.21.1 · NeoForge and Fabric · works in single player and on
servers.

## What you get

- **Far terrain that blends in.** The LODs start exactly where the game's
  own terrain ends, with its colors and textures, biome tints (Biome Blend
  included), smooth lighting, day and night light, and its fog. Works with
  Sodium.
- **Terrain the world has not generated yet.** A coarse preview built
  from the world seed shows relief, water, ice, villages, and the ground
  the world's own surface rules lay there (sand, snow, terracotta, mods'
  blocks). Trees, plants and rocks grow where the game will place them,
  from every mod that adds them, and the sea floor gets its kelp,
  seagrass and coral reefs. So the horizon is never empty; real terrain
  replaces it as you explore.
- **Details that still read far away.** Water at vanilla's height, grass
  and flowers, kelp, and the shapes of fences, walls, panes and stairs up
  close.
- **Your whole world at once.** `/farfield import` builds the LODs of every
  chunk your world already has, in the background.
- **Servers with Farfield send the LODs and the preview.** The server
  builds the LODs of its world and the preview around each player, and
  streams both, nearest first, within a bandwidth cap per player and in
  total (`config/farfield-server.properties`). Both sides need Farfield
  0.2.0.
- **On any server.** On a server without Farfield, your client builds the
  LODs from the chunks it receives and keeps them per server.
- **Light on your machine.** The terrain nearest to you and in front of
  you is built first, the renderer is GPU-driven, and the number of threads
  is yours to set.

## Settings

Mod Menu on Fabric, the mods list on NeoForge, or a key you bind in
Controls > Key Binds > Farfield: LOD drawing, level of detail, texture
distance, fog distances, the terrain preview, threads, level colors and
performance logs.

Commands in single player: `/farfield render`, `/farfield detail`,
`/farfield textures`, `/farfield fog`, `/farfield preview`,
`/farfield import`, `/farfield debug levels`. On a server, the drawing
ones are `/farfieldclient ...` for each player, and operators run
`/farfield import` on the server.

## Requirements

- A graphics card with **OpenGL 4.6**. macOS stops at OpenGL 4.1, so
  Farfield draws nothing there.
- Fabric: Fabric API. Mod Menu is optional.

## Not there yet

- **Shader packs** (Iris, Oculus): not supported yet; planned.
- **Light from blocks in the preview** (glowing plants, sea pickles):
  planned with shader pack support; the real LODs have it.
- **Beyond 4,096 blocks**: planned.

## Feedback

Bugs and ideas: [GitHub issues](https://github.com/JordanOlivet/farfield/issues).
Source: [github.com/JordanOlivet/farfield](https://github.com/JordanOlivet/farfield).
