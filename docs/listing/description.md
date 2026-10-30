![Farfield](https://cdn.modrinth.com/data/3ToF1nXi/images/51830b7991218d6e6fca326bdf0fa9cfaac1a44e.png)

**Farfield** shows your world up to 4,096 blocks away. Mountains, coasts,
forests and seas beyond your render distance are drawn as levels of detail
(LOD), for a fraction of what real chunks would cost, and the terrain your
world has not generated yet is drawn too, so the horizon is never empty.

Minecraft 1.21.1 · NeoForge and Fabric · single player and servers

![Vanilla at 8 chunks against Farfield, the same view](https://cdn.modrinth.com/data/3ToF1nXi/images/8fb598c574cfe1c5523934b431dd147eee863783.png)

## Far terrain that blends in

- The LODs start exactly where the game's own terrain ends, with its block
  colors and textures (modded blocks included), biome tints and Biome
  Blend, smooth lighting, day and night, and its fog.
- Up close they keep the details that read from afar: water at vanilla's
  height, grass and flowers, kelp, and the shapes of fences, walls, panes
  and stairs. Farther away, coarser levels take over where a block would
  be smaller than a few pixels on screen (the Detail setting).
- Works with Sodium.

![Vanilla at 8 chunks against Farfield, the same view](https://cdn.modrinth.com/data/3ToF1nXi/images/b7cb5ac50ffadb76540871cea8efb5a3ce013630.png)

## A horizon that is never empty

The terrain your world has not generated yet is previewed from its seed:

- relief, rivers, seas, ice and icebergs, and villages;
- the ground the world's own surface rules lay there: sand, snow,
  terracotta, the blocks of biome mods;
- trees, plants and rocks grown where the game will place them, from every
  mod that adds them;
- the sea floor's kelp, seagrass and coral reefs.

Real terrain replaces the preview chunk by chunk as you explore. The
preview is built in the background, on threads you set. More biomes in
the gallery.

![A world just created: almost all of it is the preview](https://cdn.modrinth.com/data/3ToF1nXi/images/3e7dd680ff8564ae8bd461fc657e2a700ebe926c.png)

![Badlands and a warm sea, in the preview](https://cdn.modrinth.com/data/3ToF1nXi/images/cc881c2cf1673a67bf6e434146a6355f6d4c48ed.png)

![A desert coast, coral reefs under the warm sea](https://cdn.modrinth.com/data/3ToF1nXi/images/a0b75614bd43233673702da379226ca782965374.png)

## Single player and servers

| Where you play | What you get |
|---|---|
| Single player | LODs of every chunk your world has, and the preview |
| Server with Farfield | The server builds the LODs and the preview and sends them to you, nearest first |
| Server without Farfield | Your client builds the LODs from the chunks it receives, kept per server; no preview (it needs the seed) |

`/farfield import` builds the LODs of every chunk a world already has, in
the background: in single player, or from the server console.

## For server owners

Install Farfield on the server and on the players' clients; both need the
same Farfield version (0.2.0) to stream. Settings in
`config/farfield-server.properties`:

- `streaming_radius`: how far around a player LODs are sent (default
  4,096 blocks).
- `player_kbps` and `total_kbps`: bandwidth caps per player and for all
  players (defaults 1,024 and 10,240 KB/s); the game's own packets always
  pass first.
- `full_data`: off by default, so only what is seen from the sky down is
  sent. **Caves and ores stay out of the LODs** your players receive.
- `preview` and `preview_features`: the preview around each player, with
  the world's features, built on low-priority threads (on by default).
  `preview_feature_exclude` leaves out features by id
  (`biomesoplenty:*`, `*:patch_*`).

## Compatibility

Tested with Sodium 0.8, Iris installed (shader pack off), C2ME, ModernFix,
Lithium, FerriteCore and Sinytra Connector, in a NeoForge pack of 386 mods
and a Fabric pack of 76, with Biomes O' Plenty and Terralith in the
preview. Other LOD mods (Distant Horizons, Voxy) are not tested beside it:
use one at a time.

![Levels of detail: finer near, coarser far](https://cdn.modrinth.com/data/3ToF1nXi/images/2724da186657ca00d678ebbf5888423d808e91ef.png)

## Settings

Mod Menu on Fabric, the mods list on NeoForge, or a key you bind in
Controls > Key Binds > Farfield: distant terrain on or off, detail,
textures, fog distances, the preview, building threads, level colors and
performance logs.

Commands: `/farfield render`, `detail`, `textures`, `fog`, `preview`,
`import`, `debug levels` in single player; on a server, players use
`/farfieldclient` for the drawing ones.

## Requirements

- A graphics card with **OpenGL 4.6**. macOS stops at OpenGL 4.1, so
  Farfield draws nothing there.
- Fabric: Fabric API. Mod Menu is optional.

## Not there yet

- **Shader packs** (Iris, Oculus): planned next.
- **Light from blocks in the preview** (glowing plants, sea pickles): with
  shader pack support; the real LODs have it already.
- **Beyond 4,096 blocks**: planned.

## Feedback

Bugs and ideas: [GitHub issues](https://github.com/JordanOlivet/farfield/issues).
Source: [github.com/JordanOlivet/farfield](https://github.com/JordanOlivet/farfield).
