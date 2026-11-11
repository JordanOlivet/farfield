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

1. **Spike** (about 2 h): on the NeoForge dev server and the user's pack,
   for one tile: (a) heights from the estimate and the density without
   caves, against today's search and the generator's own height
   (`getBaseHeight`), time and error; (b) the compiled surface rule
   against the stand-in chunks (`SurfaceSpike`), time and match rate.
2. **Coarse levels hold the ground only** (2 to 3 h), with step 1's
   heights if they win.
3. **The compiled surface rules** (about 1 day), for every level.
4. **Harvested tree shapes on a hashed grid** for levels 1 and 2 and
   beyond the real features' distance (1 to 1.5 days), with the light
   virtual world for biomes not harvested yet.
5. **To the border** (2 to 4 days): tile levels to 15, the keys, the
   store's levels, the renderer (coarse-to-fine study §6).
6. Only if heights still dominate: the density stopped at its sign.

## Open questions for the user

1. The real features' distance: keep 1,250 blocks (the most faithful),
   or less (500?) with the harvested shapes beyond, so the level-0 tiles
   cost less?
2. Coarse levels from 32-block voxels up: ground and water only, no tree
   cover at all (the forests' color then comes from the ground's top
   block)?
3. Order to the border: coarsest first as today (the whole world at once),
   confirmed?
