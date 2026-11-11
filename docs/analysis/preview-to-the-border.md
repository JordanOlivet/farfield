# The preview to the world border: a terrain built from the seed, column by column (plan items E.1, E.2)

Date: 2026-11-11. Status: study, nothing built. Branch `feat/preview-pyramid`.
Follows `docs/analysis/preview-coarse-to-fine.md` (the pyramid, ADR-126).

The user (2026-11-11): the preview must reach the world border in a
reasonable time; today's generation is far too slow for it. Two mods show
that it can be done: Voxy-SeedGen and Distant Horizons SeedGen draw the
terrain out to the border almost at once, very coarse, then refine it in
waves. Study their ideas and adapt the ones that fit Farfield, or rethink
our generation where needed.

Credit: the approach below is inspired by the ideas of **Voxy-SeedGen** and
**Distant Horizons SeedGen** (modrinth.com/mod/voxy-seedgen,
modrinth.com/mod/distant-horizons-seedgen). This document describes
concepts and how Farfield would apply them; it contains no code from those
mods, and Farfield's implementation is its own.

## What we have today

- A pyramid of tiles (ADR-126): a level-L tile covers 512 x 2^L blocks with
  129 x 129 heights. Levels 0 to 2 within 4,096 blocks, coarsest first,
  built by quarters in rings (ADR-127).
- Heights (ADR-038, ADR-057): the noise router's final density, searched
  from a neighbor's height, else from the top of the world. About 0.5 to
  0.6 s a tile at every spacing (measure 1 of the coarse-to-fine study).
  Known defect: the search from the neighbor falls into caves and stays
  under overhangs, 1 to 2 % of the heights tens of blocks low.
- Ground (ADR-101, ADR-125): the generator's surface rules run on stand-in
  chunks. About 0.7 s of a level-0 tile in dev.
- Features (ADR-079): the world's own placed features run for real, chunk
  by chunk, in a stand-in world over `ProtoChunk`s, up to
  `preview_features_distance` (1,250 blocks). In the user's pack, 4.3 to
  4.6 s of a 6 to 7 s level-0 tile before the cost guard; about 1.6 s of
  a 4.0 s tile since (ADR-131, one place measured). Coarser tiles take a canopy from a
  profile per biome (ADR-126, ADR-130), measured on 4 x 4 sampled chunks
  for a biome not seen yet: 1.8 s of a 2.8 s coarse tile in the pack, on
  about 300 biomes.

## The ideas behind the SeedGen mods

Described as concepts, from their behavior and the way they are built.

1. **Every column is a pure function of (x, z).** The terrain of a column
   is computed without generating, or simulating, the chunk around it:
   height, ground, water, trees, all from the seed and the column's
   coordinates. A level of detail samples one column per voxel (1 block at
   the finest, tens of thousands at the coarsest), so the cost follows the
   number of voxels drawn, not the area covered.
2. **The coarse levels hold the ground only.** Far away the terrain is its
   shape, its surface material and its water; no trees, plants or
   structures. Trees appear only in the finer levels, where a tree is
   several voxels wide.
3. **Trees are shapes stamped at hashed places.** For each biome, the
   rate of trees per chunk and the mix of kinds are read once from the
   biome's own features. Each kind of tree, mods' included, is grown once
   or a few times, alone, in a tiny virtual world, and kept as a shape. A
   column then asks a jittered grid around it: each grid cell decides from
   a hash of its coordinates whether it holds a tree, which kind, which
   shape and which rotation; the column takes the highest canopy that
   covers it. No feature runs at draw time.
4. **The ground comes from the world's own surface rules, read once.** The
   surface rule tree is read as data and simplified per biome (the tests
   on the biome are decided once); what is left (depth, water, noise
   thresholds, steepness) is evaluated per column. No stand-in chunk.
5. **The surface is found from the generator's own estimate.** The search
   starts from the generator's cheap preliminary surface level, raised by
   a margin, climbs while solid, then bisects. It searches the density
   without the cave branch (so a cave never hides the surface), and checks
   the real density at the result. A column never depends on its
   neighbor.
6. **The density stops computing once its sign is known.** Only "solid or
   not" matters for the search: the octaves of a noise are summed coarse
   first, and the remaining ones are skipped when their largest possible
   amplitude can no longer change the sign.
7. **Features that do not fit a shape run in a light virtual world.** A
   feature that paints the ground (a mod's patches, for instance) runs for
   real, but in a world whose blocks come from the height function (stone
   below the surface, water below the sea), and whose writes go to a
   sparse map; no chunk is allocated.
8. **Rings of constant cost.** Each coarser level covers a ring twice as
   far as the one inside it with about the same number of sections, so
   every ring costs about the same and the border is a dozen rings away.
9. **Generated on the client from the seed**, the server only sharing the
   seed.

## What Farfield takes, adapted

| Idea | Farfield's version | Gain expected (to measure) |
|---|---|---|
| 2. Coarse levels hold the ground only | Tile levels from 3 up (draw level 5, 32-block voxels, and coarser) carry heights, ground and water only: no canopy, no plants, so no biome sampling for them. Measure 3 of the coarse-to-fine study already showed forests nearly invisible at 16-block voxels. | Takes the biome sampling off the waves that reach the border. |
| 5. Surface from the generator's estimate, without caves | Replaces the search from the neighbor: each column on its own, from the preliminary surface level plus a margin (ADR-037 measured it 12.5 blocks low on average), on the density without its cave branch, checked against the full density at the result. | Fixes the 1 to 2 % of heights tens of blocks low; cost to measure against today's 0.5 to 0.6 s a tile. |
| 4. Surface rules read once, evaluated per column | A compiled form of the generator's surface rule per biome, evaluated on the one column a cell keeps. Replaces the stand-in chunks of the ground (ADR-125) at every level. | The 0.7 s of ground of a level-0 tile; a right ground at coarse levels without the profile's guess. |
| 3. Trees as stamped shapes | Shapes **harvested from what our real-feature sandbox already grows** in level-0 tiles (the trees of each biome, mods' included, with their measured rate per chunk), so no parsing of features: ADR-079's lesson stands (mods place trees with their own feature classes and placements, which a reading of the definitions misses). A biome not harvested yet grows its tree features alone in the light virtual world (idea 7). Stamped on a hashed jittered grid in tile levels 1 and 2, and in level 0 beyond the real features' distance. Replaces the canopy cover of ADR-126. | Trees with their real silhouettes from 1,250 to 4,096 blocks, at the cost of a hash per column; lets the real features' distance shrink if the user wants it. |
| 7. A light virtual world | The sandbox's `WorldGenLevel` over the height function and a sparse map instead of `ProtoChunk`s, for the real features near and for growing shapes. | To measure against ADR-125's stand-in chunks. |
| 6. Stop the density at its sign | Later, only if heights still dominate once the rest is done: a compiled density with bounds per noise. Risky with C2ME, which compiles the density already in the user's pack. | Unknown. |

Not taken:

- **Generation on the client** (9): Farfield's server knows the mods and
  the seed, serves every client from one build, and the coarse data is
  small to send (2 to 3 MB at 16,384 blocks, coarse-to-fine study §5).
- **Tables written by hand** for vanilla's trees, grounds or structures:
  Farfield's rule is no list of mods or features (ADR-079); the harvest
  covers vanilla and mods alike.
- **Rings from near to far** (8): Farfield keeps the pyramid coarsest
  first (ADR-126 to ADR-128): the coarsest level covers the whole range in
  one wave, which is the "whole world at once, then refined" effect the
  user asked for. Each level asks about the same number of tiles, so
  every wave costs about the same, as with the rings.

## The budget to the border

From the pyramid's table (coarse-to-fine study §1): levels 0 to 15, about
340 tiles around a player, about 21 a level. With the coarse levels
holding the ground only, a coarse tile is its heights and its ground:

| | Today (pack) | With this study (estimate) |
|---|---|---|
| A coarse tile | 2.8 s while biomes were sampled (1.8 s of it, ADR-126) | 0.6 to 1 s (heights, compiled ground) |
| First image to the border (coarsest level, up to about 16 tiles, 4 threads) | not possible | a few seconds |
| Every level to the border (about 320 coarse tiles, 4 threads) | not possible | 1 to 2 minutes |
| A level-0 tile, real features to 1,250 blocks | about 4 s since the cost guard | less the stand-in ground (0.7 s in dev; the pack's share not measured) |
| A level-1 or 2 tile with trees | canopy cover from a profile | the same cost plus a hash per column |

The real features near the player stay the slowest part; their distance
is the user's call (question 1).

The data path to the border is the other half of the work, unchanged
from the coarse-to-fine study §6: tile levels to 15, draw levels to 17,
the store's mip levels above 4 for explored land, the keys' new layout (5
bits of level), the renderer's depth range, fog and sky.

## Steps

Each measured before the next.

1. **Spike** (done 2026-11-11, see Measures; (b) measured as the ground's
   share of a pack tile, the compiled rules being step 3): on the NeoForge
   dev server and the user's pack,
   for one tile: (a) heights from the estimate and the density without
   caves, against today's search and the generator's own height
   (`getBaseHeight`), time and error; (b) the compiled surface rule
   against the stand-in chunks (`SurfaceSpike`), time and match rate.
2. **Coarse levels hold the ground only** (2 to 3 h), with step 1's
   heights if they win. Done 2026-11-11 for the heights (ADR-133: every
   tile's samples searched from the estimate, tile format 21). The ground
   only rule waits for step 5: today's tiles stop at level 2 (16-block
   voxels), so no tile is at 32-block voxels or coarser yet; step 5 builds
   levels 3 and up without canopy, plants or biome sampling.
3. **The compiled surface rules** (about 1 day), for every level. Done
   2026-11-11 another way (ADR-134): not compiled into an evaluator of our
   own, the generator's own rule objects run on the one column a cell
   keeps, with one rule tree a tile; the same cells as the stand-in chunks
   (0 of 4,624 differ, three dev tiles), 10 to 20 times faster in dev, the
   spawn's 312 tiles in 1.6 min instead of 2.6. Coarse tiles keep the
   profile's ground until step 5 builds them with the rules.
4. **Harvested tree shapes on a hashed grid** for levels 1 and 2 and
   beyond the real features' distance (1 to 1.5 days), with the light
   virtual world for biomes not harvested yet. Put off 2026-11-11 after
   a visual spike: tile levels 1 and 2 draw at 8 and 16-block voxels,
   where a tree is one voxel at most and today's canopy (ADR-126) already
   fills each cell by a hash at the biome's measured share. NeoForge dev
   client, `farfield-test`, forests around 5,776 to 5,968, 0 to 320, seen
   from 1,500 and 3,000 blocks east (7,276, 220, 320 and 8,776, 260, 320,
   facing west), once with the default `preview_features_distance` (1,250)
   and once at 4,096 (real trees in every tile, the log's `every tile
   within 4096 blocks is built` before each capture): 1.1 % and 0.8 % of
   the pixels differ (854 x 480 window), scattered specks in the band
   beyond about 2,000 blocks, the nearer forests identical, the bands
   side by side indistinguishable by eye. Worth building only if the real
   features' distance comes down, or if far forests look wrong to the
   user.
5. **To the border** (2 to 4 days): tile levels to 15, the keys, the
   store's levels, the renderer (coarse-to-fine study §6). Split into
   lots E.3a to E.3e in `docs/plans/milestone-2.md` (2026-11-11).
   Built the same day: tile levels 3 and up with the ground only
   (ADR-135), the distance to 32,768 (ADR-136), real LODs to level 7
   (ADR-137), keys of five level bits and sixteen levels to the border
   (ADR-138). The renderer at the border is studied below (E.3e).
6. Only if heights still dominate: the density stopped at its sign.

## Measures (2026-11-11)

### Step 1a: heights from the estimate, on the density without caves

`/farfield spike heights at <x> <z>` now compares three searches on a
tile's 129 x 129 grid at seven spacings (4 to 131,072 blocks): from the
neighbor's height (today's), from the top of the world, and each column
on its own from vanilla's preliminary surface level
(`NoisePreview.surfaceFromEstimate`): the estimate searched from the
previous sample's, lowered by two cells, down to solid ground, then up by
cell height and bisected, on the density without its cave branch
(`UncutDensity`: the overworld's one `range_choice` whose in-range branch
is `min(input, ...)`, found through named references); then the full
density at the result, down again where a cave opens at the surface. Where
ground stands within 32 blocks above a result (probes at 4, 8, 16, 24, 32),
the climb goes on: a noodle cave under the surface, or a ledge over a gap.
Errors against the generator's own height (`getBaseHeight`) on the
samples where the searches differ and one in 64 of the others (610 to
1,010 samples a spacing). NeoForge dev server, PC free, three places
(20,000, 20,000; -35,000, 12,000; 400, -300).

| Search | Time a sample | Mean error | Samples off by more than 8 blocks | Worst |
|---|---|---|---|---|
| From the neighbor (today) | 23 to 38 us, growing with the spacing | 5.4 to 27.2 blocks | 104 to 528 | 73 to 215 |
| From the top | 76 to 92 us | 2.0 to 5.3 | 35 to 129 | 47 to 106 |
| From the estimate, without caves | 25 to 28 us at every spacing | 2.2 to 5.1 | 45 to 134 | 45 to 115 |

- The search from the estimate is as fast as today's and nearly as right
  as the one from the top, at every spacing to the border's: it replaces
  the search from the neighbor. Its columns are independent of each
  other.
- Without the probes it took 17 us a sample, but landed in noodle caves
  and under coastal ledges (thin solid bands between two cell-grid
  points); the probes cost about 10 us.
- What it still misses against the top's search: thin ledges more than
  32 blocks above a gap, mostly over the sea.
- In the user's pack (pack server, nobody online, three places: 20,000,
  20,000; 35,000, 12,000; 400, 300): Terralith rewrites the overworld's
  terrain as `min(max(sloped_cheese + beardifier, its own terrain),
  entrances)`; the first cut, which wanted `min(input, ...)`, found nothing
  and the search on the full density was 17 blocks off on average. The cut
  now keeps the side of the `min` that holds the choice's input
  (vanilla's result unchanged). C2ME's compiled density did not hide the
  structure.

| Search, pack | Time a sample | Mean error | Samples off by more than 8 blocks | Worst |
|---|---|---|---|---|
| From the neighbor (today) | 21 to 62 us, growing with the spacing | 3.8 to 34.1 blocks | 145 to 1,237 | 80 to 242 |
| From the top | 162 to 189 us | 1.3 to 2.6 | 30 to 81 | 38 to 105 |
| From the estimate, without caves | 27 to 42 us | 1.3 to 3.4 | 31 to 85 | 29 to 143 |

  The pack confirms dev: as right as the search from the top for a fifth
  of its cost, and far better than today's search, whose errors grow with
  the spacing (beyond 256 blocks, a third to a half of the checked samples
  more than 8 blocks off).

### Step 1b: the ground's share in the pack

`/farfield spike tile at` on the pack server, four level-0 tiles at three
places (20,000, 20,000; 35,000, 12,000; 200,000, 200,000 twice), the cost
guard's findings read from `feature-costs.bin`: 3.7 to 4.3 s a tile, of
which heights 0.6 to 0.7 s, **ground 1.2 to 1.5 s (31 to 36 %)**, features
1.6 to 2.1 s. The stand-in chunks' ground costs twice its dev share
(0.7 s): step 3 (compiled surface rules) targets a third of a level-0
tile in the pack, and is what a coarse tile's right ground needs.

## The user's answers (2026-11-11)

1. The real features' distance stays 1,250 blocks for now, "on ajustera
   si besoin plus tard".
2. Coarse levels from 32-block voxels up hold the ground and water only;
   trees come back later "si jamais je vois que c'est bizarre sans".
3. Coarsest first to the border, as today; adjusted later if needed.

## The renderer at the border (E.3e, 2026-11-11)

Measured in a NeoForge dev client on `farfield-test`, fog distance at its
maximum (2^25 blocks), window 854 x 480, after ADR-138.

| View | Sections drawn | Quads | GPU a frame | Render thread a frame |
|---|---|---|---|---|
| 32,768 blocks (ADR-136) | 329 | 10 MB | 2.0 ms | 0.20 ms |
| The whole world, from the spawn at y 600 | 2,963 | 40 MB | 4.1 ms | 0.56 ms |
| From 29,000,000, facing the border | 430 | 29 MB resident | 1.3 ms | 0.32 ms |

- **Depth.** The LODs write the game's depth buffer with their own
  perspective mapping, near 8, far twice the maximum (`lod.vert`). In 24
  bits (or a float near 1) a surface at distance d is told from one
  behind it from about d^2 / 1.3e8 blocks: 75 at 100,000, 7,500 at a
  million, 6.7 million at 30 million. Sections there are a million blocks
  with voxels of 32,768, and the far band is a few pixels high: no
  flicker or wrong ordering shows in the captures from the spawn or the
  edge. Options if it ever shows: a far pass drawn first with its own
  range and the depth cleared before the near LODs and the game's terrain
  (the near LODs' far halves could then cover far ridges, at the
  threshold only); a reversed float depth (`glClipControl`), which the
  game's own passes and shader packs do not expect; a logarithmic depth
  written per fragment, which loses the early depth test. None is worth
  its cost or risk for what the captures show.
- **Culling.** The frustum's far plane at 67 million blocks with a near
  plane at 1 was lost in float and every section was culled: fixed in
  ADR-138 (near plane at least far / 4,000,000).
- **Fog and sky.** The fog starts at 60 % of the distance: at the maximum,
  20 million blocks, so the terrain reaches the horizon unfogged and meets
  the vanilla sky there. Nothing to change unless the user wants a haze.
- **Shader packs (not tested).** The pack link gives packs a far plane of
  twice the maximum (67 million) and the drawn extent (ADR-113). Photon's
  `combined_far` and its depth reconstruction were tested only to a few
  thousand blocks: a run with Photon at a large distance is the open
  check before raising the default.
- **Building it.** About 40 tiles a level around a place for levels 6 to
  15; built in about 30 s around the spawn, about a minute at 29,000,000
  for every level; the preview store to the border 17 MB. The coarsest
  levels come after the nearer finer ones (the order's lag is 1,024
  blocks a level, the coarsest quarters millions of blocks away): a
  first image to the border sooner would order the coarsest levels first
  outright.

Recommendation: keep the depth as is; check a shader pack at a large
distance before the default goes past 4,096; the default stays 4,096.

