# The noise preview built coarse to fine (plan items E.1, E.2)

Date: 2026-11-10. Status: study, nothing decided yet. Branch
`feat/preview-pyramid`.

The user (2026-11-10), after Voxy-SeedGen and Distant Horizons SeedGen
(`docs/progress/2026-11-10.md`): build the preview in several steps, the
coarse levels first and the finer ones after, and draw each level as soon
as it exists, so something shows as early as possible. In a video of the
SeedGen mods the player stood very high right after the world's creation
and saw the terrain out to the world border at a very coarse detail, and
the distance slider went that far. Goal: the fastest and most faithful
preview, in several steps if needed.

## What exists

- One kind of tile (`PreviewTile`, ADR-039): 512 x 512 blocks, 129 x 129
  heights every 4 blocks, top and filler block per 4-block cell from the
  generator's surface rules (ADR-101), the world's own features grown in a
  stand-in world for each of its 1,024 chunks (ADR-079), stored as 2-block
  voxel runs.
- A far tile computes its heights every 8 blocks (beyond 1,024 blocks) or
  16 (beyond 2,048) and interpolates the rest (`NoisePreview.grid`); it is
  the same tile, the same file size, the same features, and it is built
  again when a player comes closer.
- The client turns tiles into sections of the five draw levels on demand
  (`PreviewVoxels`, `PreviewLayer`): voxels of 1 to 16 blocks, nothing
  beyond 4,096 blocks (`LodRenderer.MAX_DISTANCE`, `NoisePreview.RANGE`).
- Tiles are built nearest first, in front first; the pregeneration
  (ADR-123) builds every tile of its radius at the finest grid.

## Where the time goes

Dev server, 2026-11-09, one minute line: 45 tiles at 3,838 ms each,
heights 361 ms, features 3,433 ms (89 %), the rest 48 ms. The user's pack:
5 to 7 s a tile. The 4,096-block range holds about 236 tiles; 16,384
blocks about 3,200 (E.2).

Inside the features stage (measure 2 below, 2026-11-10, NeoForge dev
server, vanilla world generation, `/farfield spike tile at` on four tiles
20,000 to 40,000 blocks out, while five preview threads built the spawn's
tiles):

| Part of a tile | Time | Share of a 4.2 s tile |
|---|---|---|
| Surface rules (`layGround`, vanilla's `buildSurface` on 1,024 stand-in chunks) | 1,755 to 1,918 ms | about 43 % |
| Making the stand-in chunks (two per chunk: one for the rules, one for the features) | 820 to 1,000 ms | about 21 % |
| The features themselves (lakes, local modifications, vegetal decoration, top layer) | 550 to 860 ms | about 16 % |
| Heights | 360 to 600 ms | about 12 % |

In vanilla the features are not what costs: the ground under them is. Two
thirds of a tile go to laying the top blocks, which the tile keeps for one
column in sixteen (one per 4-block cell), through a whole-chunk call that
walks all 256 columns, and to building each chunk twice. Both can be made
cheaper with the same result (step 2 below).

The user's pack server (16 cores, idle, the user's own run of the same
command, one tile at 200,000, 200,000, 2026-11-10): 9,843 ms.

| Part of a tile, pack server | Time | Share |
|---|---|---|
| The features themselves (vegetal decoration 4,795 ms of them) | 5,187 ms | 53 % |
| Making the stand-in chunks | 2,074 ms | 21 % |
| Surface rules | 1,616 ms | 16 % |
| Heights | 689 ms | 7 % |

There the mods' vegetation is half of a tile (4.7 ms a chunk) and the
ground still 37 %. One tile, one area: other biomes will differ. Both
levers count: the cheaper ground everywhere, and no real features where
they are not seen.

So the preview pays the full price of a tile, features included, at every
distance. The cost of an area grows with its surface, whatever the detail
it is drawn at. That is the whole gap with the SeedGen mods: their
"N-sized worldgen" builds each area at the detail the renderer draws
there, and their headline number (13.2 M chunks in 40 s) is mostly made of
far chunks built very coarse. Their per-chunk speed up close is not
published.

## Proposal

### 1. A pyramid of tiles

A tile of level L covers 512 x 2^L blocks a side with the same 129 x 129
heights, every 4 x 2^L blocks. Level 0 is today's tile. A level's tiles
are wanted within 1,024 x 2^L blocks of a player (today's grid limits:
4 blocks to 1,024, 8 to 2,048, 16 beyond), so every level asks for about
the same number of tiles around a player, about 21.

| Range (blocks) | Levels | Tiles today | Tiles in the pyramid |
|---|---|---|---|
| 4,096 | 0 to 2 | 236 | about 63 |
| 16,384 | 0 to 4 | about 3,200 | about 105 |
| 32,768 (Voxy's maximum) | 0 to 5 | about 12,900 | about 126 |
| 3,000,000 | 0 to 12 | - | about 270 |
| 29,999,984 (the world border) | 0 to 15 | - | about 340 |

Doubling the distance adds one level, about 21 tiles, instead of four
times the work. The far grid of today (`grid`, 8 or 16 in a 512-block
tile) goes away: a level-1 tile is the same heights in a quarter of the
data.

### 2. Coarse first, drawn as soon as built

- Order: the coarsest level first, over the whole range, the player's
  area included; then each finer level, nearest and in front first. The
  first image of the whole range costs one level, about 21 tiles without
  features.
- The client draws a section from the finest tile built over it and falls
  back on a coarser one; a finer tile that arrives meshes its sections
  again (the tile listener of `PreviewLayer` already does this for a
  rewritten tile).
- A tile is built in two passes, each stored: the terrain (heights,
  ground, sea, ice), then its features. The terrain pass of a level-0 tile
  is about 10 % of its cost today, so the ground near the player shows
  about ten times sooner and its trees follow.

### 3. Features by level

| Draw level (voxel) | Distance at default detail | Features |
|---|---|---|
| 0 to 2 (1 to 4 blocks) | to about 1,250 blocks | the real sandbox (ADR-079), as today |
| 3 and coarser (8 blocks and more) | beyond | a canopy per cell, from a profile per biome |

- The profile of a biome is measured, not written by hand: for the
  chunks the sandbox decorates in that biome, the share of columns a
  feature covers, how high above the ground, and with which top material
  (trees, other features and plants apart, the three kinds of D.3). It is
  kept in the world and grows with every sandbox tile. No list of mods or
  of features: a mod's forest gets its canopy from its own trees (the
  user's rule of ADR-079).
- A coarse tile that meets a biome with too few measured chunks decorates
  a few sample chunks of that biome first (about 16 chunks, 0.1 to 0.2 s,
  once a biome and a world).
- The coarse tile stores, per cell, a canopy height and material: where
  the profile's cover is dense the cell is canopy, where it is sparse a
  hash of the cell decides, so the average color and roughness of the
  biome hold. Clients receive tiles only, never the profile.
- The distance the real features reach is a server setting (the user,
  2026-11-10), 1,250 blocks by default. Its cost follows the square of the
  distance: 2,500 blocks is about four times the feature work, 4,096
  about ten times (today's cost).
- Less faithful than today beyond about 1,250 blocks: no single tree is at
  its place there, the density inside a biome is its average, villages are
  left out from level 2. At 8-block voxels a tree is one voxel. The real
  trees replace the canopy when the player comes within the level-0
  range; that change will be visible.

### 4. The pregeneration (amends ADR-123)

A task builds the pyramid of its radius first (seconds to a minute), then
the level-0 tiles with their real features, nearest first, as today (the
long part, "un Chunky la nuit"). A player who joins early sees the whole
radius at once.

Its threads while nobody is online become a setting of their own (the
user, 2026-11-10); the default stays Auto, as `preview_threads` (one per
three logical processors, 1 to 6).

### 5. Storage and protocol

- `preview/L<level>/t.<x>.<z>.ffp`; a new tile format; the tile key of the
  stream protocol takes the level (protocol bump). Old tiles are deleted at
  open, as for every format change.
- A coarse tile holds no feature runs: about 10 to 15 KB against 59 to
  113 KB. The 16,384-block range is about 2 to 3 MB for a client instead
  of the 250 MB of E.2.

### 6. Beyond 4,096 blocks

The target is the world border (the user, 2026-11-10: "on prévoit pour
l'atteindre même si on ne le fait pas de suite"). The data is designed for
it from the start; the renderer gets there in steps.

- Draw levels: a tile of level L feeds the draw level L + 2 (heights as
  far apart as the voxels are wide). 16,384 blocks needs draw levels 5 and
  6 (voxels of 32 and 64 blocks), the border draw level 17.
- Explored terrain shows its real LODs at those levels too (the user,
  2026-11-10: "il suffit d'appliquer le même niveau de grossièreté aux LOD
  lointains"): the store's mip pyramid grows above level 4 with the rule
  that builds levels 1 to 4 (`LodWorld.buildMips`). Each level holds at
  most a quarter of the one under it. The preview fills only what was
  never generated, as today. This replaces the first draft's "explored
  land drawn from the seed". To do: `LodWorld.LEVELS` (5) is read at 30
  places in 6 files (`LodWorld`, `LodRenderer`, `PreviewLayer`,
  `StreamServer`, `StreamClient`, `RenderCommands`), to check one by one; existing worlds build the new levels once in the background
  (as for a change of `MIPS_VERSION`); a protocol bump.
- The mask at coarse levels: a 64-block voxel covers 4 x 4 chunks, some
  real and some preview. Today's mask decides per chunk; the rule for a
  voxel holding both is to write (proposed: real as soon as its real
  level-4 column holds a voxel, the preview's skin closing against it as
  it does today).
- Keys: `LodKey` holds 4 bits of level (0 to 15) and the preview adds 8 to
  it (`KEY_LEVEL_OFFSET`), so preview sections stop at level 7. For the
  border: 5 bits of level and a preview bit, taken from the 12 bits of y
  (10 are enough: 511 sections of 32 blocks either way at level 0). The
  section store writes packed keys in its records (`SectionStore`,
  `LogStore.put(key, ...)`), so a new layout is a new store format: stored
  LODs are converted at open, or imported again from the region files.
- Renderer, for the border only: the depth range (far plane at 8,192
  today), the fog and the sky. Studied apart, after the 16,384 blocks.

## Expected gain

A coarse terrain tile costs about what a level-0 tile's terrain does
(measure 1: the heights take the same time at every level), so 0.5 to 1 s
with its ground.

| | Today (dev, 4 threads) | Pyramid |
|---|---|---|
| First image of the whole 4,096 blocks | about 4 min (236 tiles) | 5 to 10 s (level 2) |
| 4,096 blocks complete | about 4 min | 30 s to 1 min, of which 25 to 36 real-feature tiles |
| 16,384 blocks, terrain | about 50 min | under 1 min |
| Ground near the player after a teleport | 4 s a tile | under 1 s a tile, trees after |

## Measures (2026-11-10)

The user's pack ran on the same PC during the dev runs of measures 1 and
2: their absolute times are high; the ratios inside one run hold. To run
again with the PC free.

### 1. The heights of a coarse tile

`/farfield spike heights at <x> <z>` (new, `NoisePreview.heightsSpike`):
a tile's grid of 129 x 129 samples at seven spacings, each sample searched
from its neighbor's height (what `build` does), then from the top of the
world. NeoForge dev server, pregeneration paused, two places.

| Samples every (tile level) | From the neighbor | From the top | Mean step between neighbors |
|---|---|---|---|
| 4 blocks (0) | 426 to 484 ms | 1,394 to 1,458 ms | 1.2 to 1.9 blocks |
| 16 blocks (2) | 428 ms | 1,440 to 1,445 ms | 4.2 blocks |
| 64 blocks (4) | 487 to 496 ms | 1,424 to 1,428 ms | 9.1 to 9.6 blocks |
| 256 blocks (6) | 522 to 537 ms | 1,407 to 1,760 ms | 14.7 to 15.6 blocks |
| 1,024 blocks (8) | 592 ms (1,144 ms once) | 1,428 to 1,460 ms | 19.8 to 21.3 blocks |
| 16,384 blocks (12) | 574 to 601 ms | 1,406 to 1,420 ms | 21.7 to 25.0 blocks |
| 131,072 blocks (15) | 590 to 598 ms | 1,419 to 1,465 ms | 21.2 to 21.3 blocks |

- The search from the neighbor stays 2.4 to 3.4 times faster than from the
  top at every spacing, the border's included: heights 131 km apart are
  still on average 21 blocks from each other, three steps of the search. A
  coarse tile keeps today's search, about 0.5 to 0.6 s of heights.
- To look at, apart from this study: 1 to 2.7 % of the samples differ
  between the two searches at every spacing (171 to 443 of 16,641), by up
  to 174 blocks. The search from the neighbor only checks 16 blocks above
  its result for ground (`surface`), so it may follow a floor under a high
  overhang or inside an open cave. Which side is wrong is not known yet:
  the spike now counts the samples the neighbor search puts lower (not run
  yet), and neither was compared with generated chunks.

### 2. Inside the features stage

Done in dev and on the pack server: the two tables of "Where the time
goes". The stage times in the parentheses of a `Feature spike:` line are
shared with the tiles other threads build meanwhile and are wrong while a
pregeneration runs; the `features:` part after them is one tile's.

Still open from it: how a coarse tile gets its ground (the rules on one
column per coarse cell, or the biome guess of `NoisePreview.guess`
first); it follows from step 2's check of vanilla's source.

### 3. What a canopy profile per biome would hold

A temporary probe (a core test, deleted) over the 232 tiles of the dev
world (`neoforge/run/world/farfield/preview`, vanilla, about 237,000
chunks), per biome of a chunk: the share of its 2-block columns a feature
tops, how that share varies from chunk to chunk, the height above the
ground, and the share of 16-block and 8-block voxel columns whose
features fill a quarter of one voxel (the rule `PreviewVoxels` draws
coarse levels by, counted here by volume from the runs, not checked
against a rendered frame).

| Biome | Chunks | Columns covered (sd between chunks) | Height above ground | 16-block voxels filled | 8-block voxels filled |
|---|---|---|---|---|---|
| plains | 30,251 | 1.6 % (5.9) | 6.9 | 0.0 % | 0.7 % |
| forest | 24,331 | 54.2 % (22.7) | 6.4 | 2.8 % | 49.2 % |
| savanna | 14,402 | 12.6 % (11.1) | 7.4 | 0.0 % | 2.9 % |
| jungle | 5,457 | 79.9 % (26.1) | 10.5 | 37.4 % | 72.3 % |
| old growth birch forest | 5,104 | 53.1 % (21.9) | 7.9 | 0.6 % | 46.1 % |
| birch forest | 4,836 | 52.3 % (23.1) | 6.3 | 0.3 % | 47.9 % |
| taiga | 4,657 | 50.4 % (22.7) | 6.9 | 1.5 % | 46.0 % |
| flower forest | 3,878 | 41.4 % (17.8) | 6.2 | 0.2 % | 30.4 % |
| dark forest | 2,036 | 72.5 % (28.0) | 8.6 | 44.2 % | 72.3 % |
| swamp | 1,602 | 24.8 % (16.5) | 7.2 | 0.1 % | 18.5 % |
| mangrove swamp | 753 | 68.4 % (23.9) | 14.4 | 73.8 % | 77.4 % |

- Biomes are told apart well by their cover (plains 1.6 %, savanna 13 %,
  forests 50 to 55 %, jungle 80 %): a profile per biome carries the
  information.
- At 16-block voxels (draw level 4, from about 2,500 blocks), the trees
  of ordinary forests are almost never drawn today: 0.2 to 2.8 % of the
  voxel columns of forests, birch forests, taigas and flower forests.
  Only the tall or thick canopies show (jungle 37 %, dark forest 44 %,
  mangrove 74 %). Beyond that distance the real features cost a full
  tile and leave next to nothing on screen: a canopy there loses nothing,
  and a cell that takes the canopy from half cover up would show more
  forest than today.
- At 8-block voxels (draw level 3, about 1,250 to 2,500 blocks), about
  half the voxel columns of a forest hold canopy, and the cover varies a
  lot from chunk to chunk (sd 18 to 28 points): there a canopy drawn by a
  hash at the biome's share gives the right average, not the clearings
  where they are. This is the range where the setting of the real
  features' distance matters.
- Snowy biomes show snow as the top of 67 to 93 % of the columns: the
  snow layer comes from a feature (`freeze_top_layer`). A coarse tile
  takes its snow from the ground's rule (a cell cold enough to snow, as
  `PreviewFeatures.readCells` does), not from the canopy.
- Not measured: the pack's biomes (the probe ran on the vanilla dev
  world), and how the real LODs draw the same forests at levels 3 and 4,
  which the preview should match.

## Steps

1. The measures above (done 2026-11-10, but for a run with the PC free
   and the pack's biomes).
2. A cheaper ground for the tiles of today, same result (from measure 2):
   the surface rules run on the one column per cell the tile keeps
   instead of whole chunks, and one stand-in per chunk instead of two.
   Expected: a tile from about 4.2 s to under 2 s in dev, from about
   9.8 s to about 7 s on the pack server, to measure;
   the tiles must come out identical (compare the files). To check first
   in vanilla's source: whether a rule can be asked for one column
   (`SurfaceRules.Context` is built inside `SurfaceSystem.buildSurface`).
3. Pyramid, coarse first, two passes, canopy, within 4,096 blocks (levels
   0 to 2). Shipped together: the pyramid without the canopy would leave
   far forests bare. If step 2 and the pack's measure make the real
   features cheap enough, their default distance can be more than 1,250
   blocks.
4. 16,384 blocks (E.1 and E.2): tile levels 3 and 4, draw levels 5 and 6
   for the preview and the real LODs (the store's mip levels 5 and 6),
   the keys' new layout, the distance settings.
5. To the world border: the remaining levels and the renderer work
   (depth, fog, sky), studied apart.

## The user's answers (2026-11-10)

- Real features to 1,250 blocks to start with, as a setting.
- Explored terrain far away: its real LODs at coarser levels, not the
  seed's terrain.
- A threads setting for the pregeneration with nobody online; the default
  stays Auto.
- The final target is the world border, planned for from the start, not
  built at once.
