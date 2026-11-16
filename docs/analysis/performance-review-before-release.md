# Performance review before the next release

Date: 2026-11-15. Status: steps 1 to 8 built and benched, not committed
("Built and measured"); step 10 (client/server run) done ("Step 10").
Branch `main` at 1838fbd.

The user (2026-11-15), after the generation to the world border: "j'aimerais
que tu repasses tout le code et que tu analyses pour trouver les moindres
améliorations de performances [...] livrer la version la plus optimisé
possible tout en gardant le rendue identique".

## Method

- Read: `LodRenderer`, the LOD shaders, `GlBuffer`, `StagingRing`,
  `SectionCache`, `PreviewLayer`, `PreviewVoxels`, `PreviewStore`,
  `LodSelector`, `WantOrder`, `BinaryGreedyMesher`, `LodSection`,
  `NoisePreview` (schedule, heights, cells, quarters), `ColumnDensity`,
  `UncutDensity`, `CoarseGround`, `SurfaceColumns`, `PreviewFeatures` (ground
  and stand-ins), `StreamClient` (update, tiles, existence), `LodWorld`
  (ingest, mips), and the per-frame parts of `MaterialColors`, `BiomeTints`,
  `FaceTextures`, `RenderSettings`, `FrameProbe`.
- Second pass, at the user's request ("Lis en profondeur ce que tu n'as pas
  lu"): the ingest of real chunks (`LodPipeline`, `ChunkSnapshot`,
  `LodLevel`, `BlockKeys`, `ChunkData`, `SectionData`, `MipBuilder`,
  `NibbleBricks`, the rest of `LodSection` and `LodWorld`), the storage
  (`SectionStore`, `LogStore`, `SectionCodec`, `KeyFile`, `KeyRegistry`,
  `MaterialTable`), the streaming (`StreamServer`, `SurfaceIndex`,
  `RegionChanges`, `FetchWindow`, the rest of `StreamClient`), the rest of
  the mesher (`greedy`, shaped blocks, plants, border planes and lights,
  `BiomeBlend`, `SurfaceBiomes`, `MeshBuffer`, `Quad`), `PreviewTile`
  (heights, views, summaries), `PreviewPyramid`, the biome samples,
  `FeatureSandbox`, `FeatureBudget`, `PreviewVillages`, the rest of
  `PreviewFeatures`, the per-frame parts of the shader pack path
  (`IrisLink`, `IrisLod`, the Iris mixins), the importer (`RegionImporter`,
  `ChunkDecoder`), `LodClient`, `LodServer`, `RangeAllocator`, `CpuUsage`,
  the game mixins and both loaders' client hooks. Its findings are F6 to F8,
  R7, the larger tables of the server and of the streaming, and the pack
  path.
- Every proposal keeps the image as it is, unless its row says otherwise.
- Figures come from one saved run, recomputed by a script over the log:
  `fabric/runs/prod/logs/latest.log` of 2026-11-14 17:03, Fabric pack, a new
  world, draw distance 33,554,432 blocks, 12 chunks of game view, 3840x2019,
  Sodium, Biome Blend 7, RTX 4070 Ti SUPER, 5 mesher threads, 3 to 5 preview
  threads. It is
  the run of the W2 trial (`join-as-a-wave.md`): its tile order is not the
  shipped one, its costs per frame, per job and per quarter are.
- "Read" in a row means the cause is certain from the code and the gain is
  an estimate. "Measured" means the log shows the cost itself. No gain below
  was measured: each row says how to measure it first.

## Where the time goes (measured)

Render thread, steady view (17:05:07, 22,995 sections drawn, 273 frames a
second, 3.66 ms a frame):

| Step | us per frame | What it is |
|---|---|---|
| colors | 287 | the compiled-sections grid (`updateDrawnSections`); the colors themselves return at once |
| commands | 248 | the CPU command loop over 1,255 sections and the translucent order |
| draw | 61 | the draw calls |
| blends, selection, wanted, eviction | 16, 15, 6, 4 | |
| All | 641 | |

GPU, same line: 3,333 us a frame; depth pre-pass 1,147, opaque 2,108, plants
14, translucent 62.

The join (17:04:41, 60 s): mesher 163.1 s of processor, noise preview 164.1 s,
preview selection 17.4 s; 5.76 cores in all. The meshers never idle from 20 to
100 s after the join, and they take as much processor as the terrain
generation they wait for.

| Phase | Figure |
|---|---|
| Mesh jobs, far sections only (17:04:27) | 12,288 jobs in 10 s, 2,895 us each (2,630 on the processor), all preview sections of level 6 and up |
| Coarse quarters (17:05:14) | 239 ms each: heights 154, cells 77, biomes 2, write 3 |
| All quarters (17:04:14) | 332 ms each: heights 190, features 82, cells 39, ground 13 |
| Longest LOD frames | 120.5 ms (uploads 110.2 ms for 7 meshes), 26.0 ms (selection 24.8), 7.3 ms (a buffer grown 5.3) |

## Findings: how fast the view fills

### F1. A far preview section looks up its tile once per column (read)

`PreviewLayer.build` gives `PreviewVoxels.buildFar` a `Tiles` that answers by
level-0 place (512 blocks): `PreviewStore.coarseSource` (a
`ConcurrentHashMap` read with a boxed key per level tried), then
`PreviewStore.read` (a second map read, then a lock shared by every mesher
thread around an access-ordered `LinkedHashMap`). `PreviewVoxels.TileCache`
keeps the last place only, and the per-build `SEEN` map is keyed by place.

From draw level 9 a voxel column is 512 blocks or more: every column is a
place of its own, and each of the 34 x 34 columns of a build pays both
lookups. Levels 5 to 8 share a place between 16, 8, 4 and 2 columns.

A section lies whole in one quarter of any tile it can read (the comment of
`PreviewLayer.touched` says so), and `coarseSource` answers the same for
every place of one quarter of the finest level asked. So a build needs at
most 9 lookups (its own quarter and the 8 around, for the columns of the
margin), not 1,156.

- Proposal: key the lookup by quarter of the level asked (`place >> (level -
  3)`), with the last quarter kept in front of the map. `missing(x, z)` stays
  per place.
- Gain: the 2.9 ms far job is mostly these lookups (3,876 column evaluations
  a job with F2, each two map reads and a contended lock). Estimate: far jobs
  of level 9 and up 3 to 5 times faster. They are the whole mesher load from
  60 s after the join.
- Image: identical (the same tile per column).
- Measure first: a temporary counter of `coarseSource` calls per job and the
  `LOD meshing` line of the new-world bench, before and after; a
  `PreviewTest` case that counts source reads for a level-10 section, as
  `aSectionLooksUpItsTilesAFewTimesNotOncePerColumn` does near.
- Effort: 1 h, plus the bench.

### F2. A preview mesh job computes its columns three times (read)

A job is one `PreviewVoxels.build` of the section and six of its neighbors'
border layers (`PreviewLayer.solidNeighbors`). The layers above and below
walk the same 34 x 34 columns again (`columnHeight`, the mask, the lakes):
1,156 + 2 x 1,156 + 4 x 102 = 3,876 column evaluations a job.

- Step a: the layers above and below filled from the columns of the main
  build (the same footprint, another `oy`). 3,876 down to 1,564.
- Step b: a margin of two columns instead of one (36 x 36 = 1,296), and the
  four side layers filled from it too. 1,296 a job, a third of today.
- A layer no column reaches (above the highest ground, sea and mask of the
  footprint, or below the lowest floor) is null without a walk: in a world
  of vanilla height, for a section of level 4 and up one of the two is
  always so.
- Gain: with F1, the column work of a job divided by about 3; on near
  sections (levels 0 to 4) this is the larger part, their lookups are
  already cached (`NearTiles`).
- Image: identical (same code per voxel, same columns).
- Measure first: the mesh bench (`core` JMH, `PreviewBenchmark`) on one
  level-0 and one level-10 section, and the `LOD meshing` line.
- Effort: step a 2 h, step b 2 h more.

### F3. The surface search computes the 2D terrain functions three times a sample (read)

A height sample reads three densities at one column: vanilla's estimate
(`columnInitial`), the search (`columnUncut`), the check for a cave mouth
(`columnDensity`). Each tree was wrapped by its own `ColumnDensity.of`, so
each has its own caches: continentalness, erosion, ridges, their shifts and
the offset, factor and jaggedness splines are computed three times per
column. In a pack those splines are the cost (Terralith; the noise preview
skill notes 0.5 ms a climate sample under C2ME).

- Proposal: one `Caches` per thread for the three trees
  (`ColumnDensity.of(Caches, root)`): equal marked functions already share a
  cache inside one tree, by the records' equality.
- Gain: heights are 154 of the 239 ms of a coarse quarter (36 us a sample).
  If the 2D part is half of a sample, a third of the heights goes: about 20 %
  of a quarter, on 164 s of processor a minute. To measure: the share is not
  known.
- Image: identical (the same function at the same column gives the same
  value).
- Measure first: `farfield spike tile at X Z` and a coarse spike on the dev
  server, then on the pack server, heights stage before and after; the tiles
  compared with the probe of the noise preview skill (two builds of one
  commit already differ on a few feature columns: only the heights count).
- Effort: 1 h, plus the two spikes.

### F4. The preview selection forgets its existence cache once per frame of changes (read, cost measured)

`PreviewLayer.applyExistenceOps` runs `forgetExistence` once per queued
operation, and each walks the whole existence map (every preview section the
selection ever visited) and tests each key against the written tiles. One
operation is queued per frame in which a tile came. A coarse tile written
forgets every section under it, though only those that read it change.

Measured: 17.4 s of processor over the join's second minute (0.29 core); the
longest selection 254 ms, against 6 ms once settled. A mesh is drawn only
after the next selection: a slow one delays what shows.

- Proposal: merge the queued operations into one walk; then forget a far
  section only when `coarseSource` now answers the written tile for it (the
  rule `touched` uses for meshes since ADR-155).
- Gain: most of the 0.29 core, and the 100 to 254 ms selections.
- Image: identical once settled; meshes show a few frames sooner during a
  join.
- Measure first: `preview-selection` on the `LOD CPU usage` line and `the
  longest preview selection` of the new-world bench.
- Effort: 1 to 2 h.

### F5. The coarse tiles' cache holds 64 tiles for 16 levels (to measure)

`PreviewStore.CACHED_COARSE` is 64, sized when the preview stopped at 32,768
blocks ("about 20 a level"). The pyramid to the border was 960 quarters in
this run, some 240 tiles. A miss reads the file, inflates it and decodes the tile, on a mesher
thread. Not known whether it misses: the meshes go by distance, which keeps
a ring's tiles together.

- Measure first: a temporary hit and miss counter on `coarse(long)` over the
  new-world bench. Raise the size only if it misses, and by the tiles'
  decoded size: about 0.5 to 0.9 MB each whatever the level (the cell arrays
  and the 272 x 272 feature columns are allocated for a ground-only tile
  too).
- The level-0 views (`PreviewStore.cache`, 64 too) have the same question,
  and two more costs to count with it: every quarter written drops the
  views under its tile (up to 1,024 places), and a miss is built outside the
  lock, so several mesher threads can build the same view at once (a file
  read, a decode, for a coarse source an `expand`). A miss in flight shared
  between the threads would build it once.

### F6. The meshes of real sections the game draws itself come first (read, counts measured)

The wanted sections are ordered by distance (`WantOrder.score` in
`finishApply`), and a drawn section's new mesh the same. The nearest
sections are those under the player, inside the game's own render distance:
the shader discards every fragment of theirs there, and `gameDrawsAll` skips
them whole. At a detail of 7.5 a level-0 section is wanted within 240
blocks; the game drew to 192 (12 chunks). At 32 chunks, levels 0 and 1 lie
wholly inside the game's distance.

Measured: 7,264 of the 20,414 mesh jobs of the join's first 20 s were real
sections of level 0 (2,617 then 4,647); the study of the wave counted 4.2
meshes a real section as the spawn's chunks came (`join-as-a-wave.md`). ADR-156 ordered the stale
sections not drawn "where they show"; a section drawn in name and covered
by the game is not in that case.

- Proposal: a section every chunk section of which the game draws
  (`gameDrawsAll`, and the same test for coarser sections inside the
  compiled grid less a margin of a few chunks) is ordered as not shown: at
  the game's render distance, with the refinement ratio. It is still meshed,
  after what shows.
- Gain: up to a third of the mesher's first 20 s of a new world goes to the
  preview instead. On a server whose terrain is already built, less (the
  real sections arrive meshed once).
- Image: the same once settled. It is a change of order, like W1 and W4:
  where the game's chunks are slow to build (a teleport, a fast flight),
  the LODs standing in for them come later. To judge on captures, not only
  on counts.
- Measure first: a counter in `MeshJobs` of the jobs whose section the game
  draws whole at the time they are asked.
- Effort: 2 h, plus the bench and the user's eye.

### F7. The surface biomes of a mesh read every voxel through the classifier (read)

`SurfaceBiomes.columns` runs for every mesh job: per 4 x 4 column, from the
top cell down, `hasMatter` reads 64 voxels with `section.material` and
`classifier.classOf` until one is not air. A section half empty costs about
16,000 such reads, an empty top up to 32,768. The mesher has just built the
same answer as bits (`rowsOpaque`, `rowsTranslucent`, `rowsPartial`,
`rowsShaped`, `rowsPlant`): a cell is 16 masked ints.

- Gain: estimate 0.1 to 0.25 ms a mesh job, 5 to 10 %.
- Image: identical (the same classes, read from the rows).
- The same habit in `borderPlane`, `addEmptyColumns` and `borderHashes`
  (real sections: 6 neighbors read, then 6 planes hashed, one
  `classOf(material(x, y, z))` per voxel): a class per palette index,
  computed once per section, as `borderLight` does for water.
- Measure first: the `core` mesh bench (JMH) with the columns in it.
- Effort: 1 to 2 h.

### F8. A mesh job's neighbors are read through decoded sections that are validated voxel by voxel (read, small)

`SectionCodec.decode` unpacks every voxel of every packed brick to check its
palette index, on each read (up to 32,768 unpacks a section), although
`LogStore.get` has just checked the record's CRC. `LogStore.get` also
allocates the record twice (header and payload, then the payload alone).

- Proposal: the check kept for records that come from the network
  (`decodeRecord`), dropped for records read back from the store.
- Gain: small per read (the log shows 49 to 56 us a job reading sections in
  the first 20 s, at a 70 % cache hit); it also runs under every mip build
  (8 children read per parent) and every mask read.
- Image: identical.

## Findings: the frame

### R1. The compiled-sections grid asks the terrain renderer about every section of its outer ring, every frame (measured)

`updateDrawnSections` with Sodium calls `isSectionCompiled` for the 3 outer
chunks of the grid every frame and for the whole grid every 8 frames. At 12
chunks: 9,915 calls a frame on average, 287 us, 29 ns each: 45 % of the LOD
render-thread time of a steady frame. At 32 chunks the same arithmetic gives
34,635 calls, about 1 ms a frame, and 107,736 calls on each eighth frame.

- Proposal: a column whose sections were all compiled at the last pass is
  tested by one of them; the others are read again only when that one is no
  longer compiled. A column still building keeps the full test.
- Gain: about 270 us a frame at 12 chunks, about 1 ms at 32.
- Image: identical if a loaded column's sections leave the compiled state
  together (unload, renderer reload). That is Sodium's and Embeddium's own
  rule, to confirm in their source before building
  (`verify-external-contract-first`); the vanilla path does not use this
  loop.
- Also: `uploadDrawnSections` copies the grid (`Arrays.copyOf`) and uploads
  it every frame; upload it when a bit changed, without the copy.
- Measure first: the `colors` step of the stat line, standing and flying, at
  12 and 32 chunks; the hand-off checks of `farfield-lod-renderer` (one-frame
  flicker while flying forward).
- Effort: 2 h with the contract check.

### R2. The command loop looks up every section before culling it (measured step, read cause)

248 us a frame for 1,255 CPU sections plus the GPU-culled sections with
translucent faces. Per section: a hash lookup of the resident, then the
frustum test. Most sections of the list are outside the view.

- Proposal: `visibleFaces` first (it needs the key only), the lookup after;
  the resident kept beside the key in `cpuList` and `gpuTranslucent` at
  apply time (an evicted flag on `Resident`), and in `frameTranslucent`
  instead of a second lookup after the sort.
- Gain: estimate half of the step, 100 to 150 us a frame.
- Image: identical (the same tests, another order).
- Measure first: the `commands` step of the stat line.
- Effort: 1 h for the reorder, 2 h with the kept residents.

### R3. `finishApply` is not sliced (measured once)

`continueApply` stops every millisecond; `finishApply` then orders every
wanted and stale section, sorts, removes freed slots, uploads the
candidates, and during a disc runs `handOffHole` (per stale real section up
to 256 chunk columns, each `columnDrawn`: about 100 set lookups). The
longest `selection` step was 24.8 ms (17:03:47, the disc running), about
1 ms on the other lines.

- Proposal: time its parts in the worst-frame line first. Then the wanted
  sections scored on the selection's thread (it has the camera and the
  frustum), and `handOffHole` bounded per frame.
- Image: identical; the disc may grow one selection later.
- Effort: 30 min for the timers, 2 to 3 h for the rest.

### R4. Buffers cleared that need no clear (measured)

- At start, with the 65,536 slots the last session remembered, `occupancy`
  (256 MB) and `blendedTints` (128 MB) are created cleared. The first frame
  with uploads took 110.2 ms in `uploads` for 7 meshes, 2.0 ms of it sending
  quads: the trap ADR-144 removed for the quad buffer. Both are read only
  where written: a slot's occupancy by its `info.z` and its neighbors'
  table, a slot's blend by its stamp, written at every first mesh.
- `gpuCommands` grown during a selection: 5.3 ms of a 7.3 ms frame
  (17:03:57). The culling pass writes every command the draws read.
  `cullCandidates`, `indirect` and `scatterPairs` are the same case.
- Proposal: `new GlBuffer(size, false)` for those, and `grow` without the
  clear.
- Image: identical if the read-where-written claim holds; check `lod.frag`
  (`occluder`, `blendReady`, `cellBlend`) once more before building, and
  keep `previewHidden`, `drawnSections` and `neighborSlots` cleared.
- Measure first: the `uploads` split of the first frames, as ADR-144 did.
- Effort: 30 min.

### R5. A tile written walks every resident section (measured)

`applyRewrites` walks all residents (32,297) and calls `touched` on each
when a tile changed; `touched` loops the drained coarse tiles. The longest
`rewrites` steps: 2.1 to 3.0 ms a frame during the join, 30 us on average.

- Proposal: walk `previewResident` only (no gain alone on a new world), or
  per written tile compute the sections that can read it (two levels of
  keys around it) and look those up.
- Low priority: a 3 ms frame at 273 frames a second does not show.

### R6. Small items on the render thread (read)

| Item | Where | Proposal |
|---|---|---|
| GPU timestamps taken and polled with the performance logs off | `readGpuTimers`, `draw` | only with the logs on (part of the 16 us of the `blends` step) |
| One buffer call per evicted slot, up to 512 a frame | `releaseSlot` | through the scatter pass, as the neighbor ints (ADR-148) |
| Two arrays and two buffer calls per mesh | `writeSection` | reused arrays |
| Three sets cloned per selection (32,297 residents) | `startPreviewSelection` | kept on the selection's thread, fed by a queue as `existenceOps` |
| Result sets grown from empty at each selection | `PreviewSelection` | sized from the last result |
| The fingerprint of a result computed on the render thread | `takePreviewSelection` | on the selection's thread |

### R7. Sections are blended again where no blend is read (measured step, read cause)

The `blends` step took 831 and 774 us a frame in the join's first 20 s, then
91 to 99 us while far sections filled, against 16 us settled.
`markBlendNeighbors` queues the 8 neighbors of every uploaded mesh, at every
level, and a new mesh of a section queues itself. For a level the radius
does not blend (`BiomeBlend.needed`: level 0 alone at vanilla's default
radius of 2; levels 0 and 1 at 7, the game's largest and this run's),
`blendSection` then writes one int, the slot's stamp, 0 over 0: one buffer
call per queued section. Of the 21,308 jobs asked from 17:03:37 to 17:03:47,
16,616 were of level 3 and up, where no radius blends; the step took 0.26 s
over that window, 0.21 s more than a settled one.

- Proposal: no neighbor queued for a level that needs no blend, and no
  stamp written when the slot's is already 0 (a new slot still gets its 0).
- Then, for the levels that blend, where each section is blended up to 9
  times as its neighbors arrive (the 0.8 ms a frame of the first 20 s, the
  step's whole budget): `BiomeBlend.blend` on a mesher thread from the neighbors'
  column arrays, the upload alone on the render thread. It also allocates
  three arrays per call today.
- Gain: the 91 to 99 us a frame at once; up to 0.8 ms a frame of a join's
  first seconds with the second step.
- Image: identical (the shader reads the same blends; the fallback per
  fragment covers a slot not blended yet, as today).
- Effort: 30 min for the first step, 2 to 3 h for the second.

## Findings: the GPU

### G0. The stat line does not count the quads of GPU-culled sections (read)

`quadsSubmitted` and `quadsInView` count the CPU list only. From 17:03:47 to
17:05:07 the sections drawn went from 4,124 to 22,995, the pre-pass from 647
to 1,147 us and the opaque pass from 1,215 to 2,108 us, while "quads sent"
stayed at 358,604 and 362,083. The far geometry costs 1.4 ms a frame and no
figure shows it.

- Proposal: sum the quads of the GPU slots at apply time, in the stat line.
  Needed to judge G1. 30 min.

### G1. The far sections are drawn twice, in the selection's order (to measure)

The pre-pass costs 1,147 us and the shaded pass 2,108: geometry is about as
much of the GPU time as shading. The GPU-culled sections never give way to
the game's terrain, and most cannot discard.

- Step a, no code: `/farfield debug prepass off` at the largest distance,
  the second stat line after the switch, without and with a shader pack.
- Step b: the selection's thread gives the draw list near to far (opaque
  order is free; translucent faces keep their own sort), so the pre-pass and
  the shaded pass reject more.
- Step c: the far solid list drawn once, depth written, early tests, near to
  far, outside the pre-pass; kept in the pre-pass under a pack (its shading
  is the cost there, ADR-043).
- Gain: up to the far share of the pre-pass, about 500 us of 3,333; less
  what overdraw gives back. Not known before step a.
- Image: identical (opaque faces, the same depth).
- Effort: step a 15 min, b 1 h, c half a day with the A/B.

### G2. Not kept: finer savings that change the image

- The detail rule is by distance alone: far ground seen at a grazing angle
  from near the ground projects to less than a pixel of height and is still
  drawn at 6 px a voxel of width. A rule by angle would cut most of the far
  geometry, and change which level draws where: out of this review's scope.
- The `pow` round trip of `lod.frag` (colors to linear, back to sRGB) is the
  identity for faces with one part only; removing it changes the rounding.

### G3. Under a shader pack (read, not measured)

No pack ran in the log. Per frame, `IrisLink` clears two full-size depth
textures and up to four RGBA16 targets, copies the opaque depth into the
translucent one, and `bindTarget` asks the driver for the bound framebuffer
and the viewport (`glGetInteger`, `glGetIntegerv`) at each of its two binds:
a query that makes a threaded driver wait for its queue. `beginTranslucent`
splits and upper-cases the blend strings of the contract every frame.

- Proposal: time the pack path's steps in the stat line before anything;
  then the framebuffer and viewport taken from the game's own state
  tracking, and the blend factors parsed once per pack load.
- Image: identical.

## Findings: the server and the streaming, smaller

Preview:

| Item | Where | Note |
|---|---|---|
| A `SinglePointContext` allocated per density read (over ten a sample) | `NoisePreview.solid`, `above` | one reusable context per thread; small |
| `Method.invoke` with boxed arguments per column and depth | `SurfaceColumns.column` | method handles; the cells stage (77 ms a coarse quarter, 19 us a cell) is mostly its one biome sample a cell, which stays |
| A tile decoded again from the bytes just written | `PreviewStore.write` | under 1 % of a quarter; leave |
| Every stand-in chunk holds plains away from its ground, so plains' features are tried in every chunk of every biome (their biome filter then rejects them); the biome set is read from the 24 sections of 9 chunks per chunk decorated | `PreviewFeatures.plains`, `FeatureSandbox.decorate` | features are 82 of the 332 ms of a quarter on average, all in level-0 quarters; measure with the tile spike's feature costs in a biome without plains; a set of the 16 ground biomes kept per stand-in removes both. The tile may differ where a plains feature placed far from the ground: compare the tiles |
| One hash map allocated per 2-block feature voxel | `PreviewFeatures.collect` | one map keyed by voxel and material |
| The planner walks every quarter of 16 levels around each player twice a second on the server thread, a boxed key and an array per candidate | `NoisePreview.schedule` | a few thousand candidates: about a millisecond, left as it is |

Real chunks (11.4 s of processor for 1,238 snapshots in the join's first
minute, 9.2 ms a chunk; no split of it in the log):

| Item | Where | Note |
|---|---|---|
| A chunk section of air is walked block by block (4,096 `get` calls), then ignored by the ingest | `ChunkSnapshot.fillBlocks` | skip the sections the game counts as only air: often more than half of a chunk's 24 |
| A mip's bricks are written voxel by voxel, a palette lookup each, the bricks repacked each time the palette passes a power of two | `MipBuilder.writeOctant`, `LodSection.set` | palette indexes per brick, packed once, as `writeChunkSection` does |
| 8 children read and decoded per parent built, validated voxel by voxel (F8) | `LodWorld.buildParent` | F8 |
| `KeyFile.persist` (synchronized, twice) and `KeyRegistry.size` (synchronized) before every section and tile written; `size` also several times a frame on the render thread | `SectionStore.writeEncoded`, `LodRenderer.uploadColors` | a volatile size, and the compare outside the lock |
| An object and a map merge per block changed on the server | `LevelChunkMixin`, `LodPipeline.onBlockChanged` | skip when the chunk is already due |

A profile of the workers (JFR, or stage timers as the preview has) comes
before any of these: the 9.2 ms are not split.

Streaming (a dedicated server; none in the log):

| Item | Where | Note |
|---|---|---|
| With `full_data=false` (the default), each key of a fetch lists its whole region (every key and version, boxed) to find the cached set of hidden sections: up to 64 listings a message | `StreamServer.underSurface`, `hiddenKeys` | one listing per region per message, or a write counter per region as the fingerprint |
| A tile received is decoded, encoded and compressed again, then decoded again for the cache, on the mirror's one writer thread, ahead of the sections waiting there | `StreamClient.tile`, `PreviewStore.write` | when its ids need no remap, the file written as received, as sections are (`putRecord`) |
| A tile is sent whole again at each of its quarters: a tile's bytes cross the network up to four times | `StreamServer.tileWritten`, `tileFetch` | a message per quarter would be a protocol change; measure the tiles' share of `player_kbps` first |
| One write lock per key of an index message | `StreamClient.index` | one per message |

The importer (`RegionImporter`, `ChunkDecoder`) has its stage timers and its
JMH benchmarks; one thing only: a block state's key is built as strings for
every palette entry of every section before the registry is asked.

## Memory, noted on the way

The per-slot GPU buffers at the 65,536 slots remembered: occupancy 256 MB,
blended tints 128 MB, the rest 21 MB: 405 MB for 32,297 residents. The
blended tints are read only where `BiomeBlend.needed` (levels 0 and 1 at the
usual radii) and every slot holds 2 KB of them. Not a speed matter; it
counts for the commit charge the pack runs out of (to weigh with the user).

## Read and left as they are

- `BinaryGreedyMesher`: bit rows, transposes, one pass per direction; the
  per-face work (`splitByMaterial`, `light`) is what a face needs.
- `StagingRing`, `WantOrder`, `SectionCache`: no cost in the log (reads 0 to
  56 us a job).
- `LodSelector`: two existence and two or three ready lookups per node; the
  real selection is not what the selection thread spends (F4 is).
- `MaterialColors.update`, `BiomeTints.update`, `FaceTextures.upload`:
  return at once when nothing is new.
- `LodWorld` mips: dirty parents per level, built once a batch, an unchanged
  encoding stops the climb.
- `StreamClient.askTiles` and `update`: a few thousand tests every scan.
- `PreviewFeatures`: already timed stage by stage; what is left is the
  game's own feature code under the cost guard.

## Read by role only

What runs once (a pack load, a world open), on demand, or only with a debug
switch, read for its place in the frame and not line by line:
`VoxyContract`, `DhMaterials`, `PackContract`, `GlProgram`, the baking in
`MaterialColors` and `FaceTextures` (budgeted per frame, ADR-062, and cached
by `LooksCache`), `PreviewPregen`, `PregenTasks`, `PreviewProfiles`,
`NbtReader`, `RegionFile`, the decompressors, `StreamProtocol`'s encoders,
the commands, the settings screen, the configuration, the spikes, and the
`debug` package (`FrameProbe`, `FlashScan`, `FlightRecorder`: off by
default, one static test per call).

## Order proposed

| Step | What | Estimate |
|---|---|---|
| 1 | F1, far tile lookups by quarter | 1 h |
| 2 | R4, buffers without a clear; R7 first step, no blend queued where none is read; G0, the quads of GPU-culled sections counted; the 405 MB of per-slot buffers looked at | 2 h 30 |
| 3 | F3, one cache for the three densities | 1 h and two spikes |
| 4 | R1, the compiled grid by column | 2 h |
| 5 | F4, existence forgotten in one walk | 1 to 2 h |
| 6 | F2, the neighbor layers from the section's columns; F7, the surface biomes from the mesher's rows | 3 to 6 h |
| 7 | F6, the game's own sections at low priority (accepted by the user; still checked by eye) | 2 h |
| 8 | R2, R3, R6, R7 second step | a day |
| 9 | G1, after its step a | half a day |
| 10 | The server's ingest and the streaming tables, after a profile and a client/server run, before the release | to size then |

One new-world bench before step 1 on the shipped order (the log used here is
the W2 trial's), then one after each of steps 1, 3, 5, 6 and 7: the `LOD
meshing` line, the two fill lines, `LOD CPU usage`.

## Limits

One run, one world, one position, 12 chunks of game view, single player, a
fast GPU. No figure for 32 chunks (R1's is arithmetic), for a dedicated
server, for a shader pack, or for a flight. The gains are estimates from the
code until measured.

## Built and measured (2026-11-15)

Not committed. Bench: `farfield-render-perf-probe`'s new-world scenario,
Fabric pack, seed 4242, 12 chunks, 3840x2019, spectator at (0, 200, 0)
facing 12 degrees down, a capture 100 s after the join. Each jar's figures
come from `bench.py` over its log (scratchpad). Three runs of the shipped
jar (`ref`, `ref2`, `ref3`) give the noise between runs.

| Run | Built | Mesh job (us, job-weighted) | Mesher CPU, join minute | View settled | Preview all built | Steady frame, render thread |
|---|---|---|---|---|---|---|
| ref | shipped | 2,780 | 164.9 s | 59.9 s | 91.6 s | 693 us |
| ref2 | shipped | 2,619 | 154.4 s | not settled at 60 s | 88.5 s | 655 us |
| ref3 | shipped | 2,638 | 153.3 s | 59.9 s | 87.6 s | 638 us |
| s123 | F1, R4, R7 step 1, G0, F3 | 1,956 | 118.3 s | 57.9 s | 85.0 s | 662 us |
| s1234 | + R1 (whole columns) | 1,852 | 118.1 s | 58.8 s | 88.0 s | 673 us |
| s7 | + F4, F6, F7, F2 | 1,663 | 94.1 s | 58.2 s | 84.7 s | 596 us |
| s8 | + R1 (empty columns) | 1,596 | 85.4 s | 55.8 s | 81.1 s | 564 to 588 us |
| s11 | + R1 told by Sodium, R2, R5, R6 (part), R3's timers | 1,608 | 80.5 s | 53.7 s | 80.3 s | 324 to 339 us |

- Mesh jobs: 37 % shorter (s7 against ref3), the mesher's processor time
  of the join minute 39 % lower.
- The first upload's stall is gone: the longest LOD frame of the shipped
  jar was 182.7, 89.3 and 140.9 ms (uploads 161.4, 80.1 and 130.6 ms); with
  R4 no upload step over 34 ms in any run.
- Image: F3 leaves the terrain as it was. Every tile of every level, 348
  tiles, has the same heights in `ref3` and `s7`, decoded and compared
  sample by sample (`tileheights.py`; the files differ as bytes, their
  block and biome ids being given in the order a run meets them). The
  settled captures differ by 2.2 % of pixels over 8 levels between `ref3`
  and `s7`, by 1.7 % between `ref2` and `ref3`, the same jar.
- Found on the way, not caused by this work: the snow on the preview's
  trees differs between two runs of the shipped jar (`ref2` and `ref3`,
  the snowy forest south-east of the spawn). It follows the order the
  quarters are built in. Not looked into.
- R1 as first built (a whole column tested by one section) changed nothing
  measurable: the `colors` step, split into `colors` (0 us) and `compiled`
  (260 us) in `s7`, holds the grid. Most of the grid's outer ring lies past
  the chunks the client holds, never whole. A column with no section built
  and no chunk held is now skipped: `compiled` 220 us in `s8`. A probe
  (`// TEMP`, removed) counted what is left, steady: 7,035 calls a frame,
  the loop 190 to 222 us, the upload 4 us; the columns tested each frame
  are 192 with some sections built and 100 with a chunk held and none
  built. Sodium builds only the sections its visibility walk reaches, so
  those stay partly built for good. The next step is to be told: an
  optional mixin on Sodium's section info update marking the column to
  test again (an external contract: `verify-external-contract-first`,
  Sodium 0.8.13 read), the user's call.
- R1 told by Sodium (the user: "vas y pour le mixin Sodium"): Sodium sets
  a section's built state only in `RenderSection.setInfo` and clears it
  only there and in `delete` (checked on Sodium 0.8.13). `RenderSectionMixin`
  (optional, `farfield.sodium.mixins.json`, applied only when Sodium's
  class is there) reports each to `LodRenderer.sodiumSectionChanged`; the
  grid then tests, every frame, only the columns reported and those whose
  drawn bits still follow their compiled ones. `compiled` went from 220 to
  7 us a frame (`s9`, `s11`); log line `LOD compiled-sections grid follows
  Sodium's section changes`. Inner columns now follow within a frame where
  they waited up to 8 before.
- The hand-off in flight, the east flight with the frame probe
  (`score-flashes.py`), three rounds a jar: render-edge flashes 9, 13 and
  10 with the shipped jar, 12, 10 and 13 with the new one; all LOD flashes
  23, 19 and 25 against 16, 19 and 20. The ranges overlap; the median of
  render-edge flashes is 2 higher. Not a clear change; the user's eye at
  the vanilla edge in flight is the check left.
- R2 (frustum test before the resident's lookup): `commands` 250 to 210 us.
  R5: only the preview's residents walked on a tile change (small on a new
  world). R6: `writeSection` reuses its arrays; no GPU timestamps with the
  performance logs off. R3: the worst-frame line now splits the selection
  step (preview selection taken, selected, applied, ordered, hand-off); the
  30 to 55 ms selection frames of earlier runs did not come back in `s11`
  (its longest: 9.4 ms selecting at the first frame).
- Found, not caused by this work: single `commands` frames of 24 to 57 ms,
  in the shipped jar too (`fr2` 56.6 ms, `fr1` 7.4 ms), no buffer grown in
  them. Not looked into.
- Not built, and why: R7 step 2 (0.7 ms a frame in a join's first 20 s
  only, against a race between neighbors' blends); the blend slots' 128 MB
  (a slot-to-blend index would be a ninth storage block in the vertex
  shader, past what some drivers give; a VRAM gain, no speed); G1 b and c;
  F5 and F8; R5's per-tile lookup; the rest of R6.
- G1 step a (`/farfield debug prepass off` once settled, `s8`): the GPU
  frame goes from 3,330 to 6,083 and 6,870 us without the pre-pass. The
  pre-pass halves the far sections' cost; steps b and c (one pass for the
  far solid list) are dropped.
- F8 dropped: the check runs only for a palette short of its bits' range,
  and reading sections took at most 83 us a job, at the join only.

## Step 10: the client/server run (2026-11-15)

A Fabric dev server on a fresh world of the test seed (view 12 chunks, the
pack server's Farfield settings: `streaming_radius=4096`, `player_kbps=1024`)
and a Fabric dev client joining it (4K, `fog_distance=33554432`): 25 s of
join, 60 s at the spawn, six 256-block jumps east over terrain never
generated, 10 s each, then settle; 228 s in all. JFR (`profile` settings)
attached to both game JVMs with `jcmd` (a `JAVA_TOOL_OPTIONS` recording breaks
Gradle's search for the Java 21 toolchain). Committed code (5d10013).

From the logs:
- The stream runs at its cap during the flight: 10,240 to 11,130 KB sent per
  10 s, the `player_kbps` budget, with up to 83 messages queued and sections
  pushed 1.3 to 2.0 s after their write (0.15 s before the flight). The
  stream threads take 6.7 s of processor in that minute: the budget, not the
  processor, sets the pace.
- The server's workers: 35.9 s of processor for 4,522 snapshots in the
  flight's minute, 7.9 ms a chunk (the review's 9.2 ms).
- The noise preview: 47 to 96 s of processor a minute, already split by its
  own minute line (heights and features).
- The client's mirror writer: 2.3 to 3.5 s a minute (mesher 59 to 91 s).

From the server's JFR (execution samples of Farfield's threads):

| Thread | Samples | Where |
|---|---|---|
| `farfield-server-worker` | 3,485 | `LodWorld.buildParent` 83 %: `MipBuilder.downsample` 63 %, reading the 8 children and the old parent 12 % (zstd and decode); the ingest itself (`ChunkSnapshot.fill` 9 %, `LodWorld.ingest` 8 %) 17 % |
| `farfield-stream` | 388 | `SurfaceIndex.hidden` 97 %, two thirds of it from `underSurface` per fetched key: a region's surface computed again whenever the region changed, which is every batch at the generation front |
| `farfield-server-maintenance` | 293 | `LodWorld.write`: zstd compression 66 %, `prunePalette` 18 % |
| `Server thread` | 6 of 1,155 | Farfield's hooks under 1 % of the game's own thread |

So the 9.2 ms a chunk of the review are mostly the coarse levels built
again, not the ingest. `buildMips` runs every 250 ms and rebuilds every dirty
parent from level 1 to 15, each from its 8 children read back from the store:
at the generation front the same coarse parents (levels 4 and up, a handful
a level) are rebuilt at every batch. 19,581 of the 67,211 sections built in
the run came out unchanged.

Leads, by size:

| Lead | Gain (estimate) | Image |
|---|---|---|
| S1. Coarse levels built less often while chunks keep coming: level L at most every `250 ms << (L - 3)` from level 4, the dirty set kept | most of the levels-4-and-up share, about a third of the workers' time (to measure) | identical once built; a far coarse section of new terrain reaches clients up to a few seconds later (the preview stands there meanwhile) |
| S2. The children just written kept decoded (a small cache per world) instead of read back and decoded | up to the 12 % read share | identical |
| S3. The stream's surface per region kept between batches: computed again only for the columns written | most of the stream's 6.7 s a minute | identical |
| S4. The review's own items (air sections in `fillBlocks`, `writeOctant` per voxel) | under 10 % of the workers each | identical |

The user: "go S1 S2 S3". Built, then measured on the same run:

- S3 built (`SurfaceIndex.hidden` with the floors kept per column and the
  fingerprint of its sections' versions, `StreamServer.regionFloors`): the
  stream threads' processor time in the flight's minute went from 6.7 s to
  1.6, 1.9 and 2.0 s in three runs; their samples from 388 to 97.
- S2 built (`LodWorld.written`: the sections written since their parents'
  last build kept decoded, at most 1,024, dropped once the level above is
  built): the children read back from the store went from 12.1 % to 7.6 %
  of the workers' samples (the siblings not written in the same batch are
  still read). A unit test builds the same parents, byte for byte, with the
  children kept and read back.
- S1 built, measured, and dropped. With temporary counters, the parents
  built in the flight's minute: 13,493 paced against 15,825 unpaced (levels 4
  and up 1,126 against 3,080), but the workers' time per chunk was 7.8 ms
  paced and 7.0 ms unpaced, inside the spread between runs (7.0 to 7.9 ms):
  the coarse parents are mostly air and uniform bricks, cheap to build. A
  delay of the coarse levels for no measured gain: removed.
- Seen in the counters, not built: level 1 is built 7,500 to 7,800 times a
  minute for 4,700 to 4,900 chunks, about 1.6 times a chunk: a level-1
  parent spans 4 x 4 chunks and is built again at every batch that brings
  one of them. The lead for a later pass (it is the near terrain, so any
  delay there shows).
- The workers' time per chunk over the four runs: 7.9 ms before, then 7.0,
  7.8 and 7.0 ms; the change is within the spread.
- Then, asked by the user ("est ce qu'on peut y faire quelque chose sans
  changer le rendu ?", then "go 1"): parents built from their changed
  octants only. Measured first with a temporary histogram: in the flight's
  minute 15,470 parents built, with 2.2 of their 8 children written since
  their last build on average (1 child: 5,440; 2: 5,678; 4: 3,451). Built:
  `LodWorld.changedOctants` marks the octant of each child written (before
  the parent is marked dirty), the build takes the mask before reading any
  child, and `MipBuilder.build(children, finest, stored, keep)` copies the
  kept octants from the stored parent (`copyOctant`, written through the
  same `writeOctant`) and downsamples the others; a rebuild of the coarse
  levels, a mip-rules upgrade, or a parent without a stored copy builds
  whole. A unit test builds the same parents byte for byte as whole builds
  (5 rounds, chunks on both sides of the origin, over 50 parents built in
  part). Measured: the workers' time per chunk in the flight's minute 5.1 ms
  (24.2 s for 4,736 snapshots) against 7.0 to 7.9 ms in the four runs
  before; profile samples 2,336 against 3,485 and 3,661 for the same chunk
  count. `copyOctant` is now 13.7 % of the workers, `downsample` 40.1 %.
- Then "explorer la copie en brique": `copyOctant` copies a kept octant
  brick by brick (`LodSection.copyBrick`: the stored brick's indices mapped
  to the parent's palette in voxel order, so the palette grows in the same
  order as voxel by voxel, then packed once; the light and fill bricks as
  they are). Parents still identical byte for byte (the unit test, now with
  palettes of 35 materials so they grow while copied; a deliberate error in
  the copy makes it fail). Measured: the workers' time per chunk in the
  flight's minute 4.6 ms (17.9 s for 3,881 snapshots) against 5.1 ms;
  `copyOctant` 5.7 % of the workers' samples against 13.7 %. The server's workers are
  not its main cost: the noise preview takes 47 to 96 s a minute against
  their 26 to 37 s.

## Measured leads (2026-11-16)

The user: "Mesure tout ce qui pourrait permettre de gagner des perfs". Two
runs of the step 10 bench with temporary counters (removed since), the
hot lines and callers of the profiles of both sides, and the pack server's
log of the last 30 hours. Nothing built; every lead keeps the image
identical unless said.

| Lead | Measured | Share now | Fix, image |
|---|---|---|---|
| L1. `RealGround.lowest` (client, a real chunk's lowest ground beside the preview, ADR-109) | up to 659,682,670 voxels read in 10 s, 95.5 % of them in bricks of air only; 11,875 scans of 507 chunks in 10 s, each chunk scanned again about 23 times | 11.9 % of the mesher's samples (`LodSection.material` from it: 1,073 of 1,867) | skip air bricks (8 x 8 x 8 at once) and keep each chunk's result until one of its sections is written; identical |
| L2. `LodSection.pack` divides by a variable per voxel (`v / perLong`, `v % perLong`), though `perLong` is a power of two | line 326 of `pack`: 124 samples on the server (from `writeChunkSection`, `copyBrick`, `repack`, `set`), 561 on the client (`set`, `repack`) | about 10 % of the server workers, 3.4 % of the mesher | shifts and masks; identical |
| L3. `MipBuilder.above` reads the brick above through `paletteIndex` | 75,904,987 calls in the brick and 24,115,730 into the brick above in the flight's minute; line 400 (into the brick above) 183 of 186 samples | about 14 % of the server workers | walk each child's bricks from the top and keep the bottom layer of the brick above, already unpacked; identical |
| L4. `ChunkSnapshot.fillBlocks` walks all 4,096 voxels of a uniform chunk section | 62,802 of 106,296 sections uniform (59 %) in the flight's minute; 23,986,036 state changes, 80,154,945 palette search steps (3.3 a change) | 12 % of the server workers (line 116: the palette search) | a section of one state filled at once (the container's own count); identical |
| L5. Level-0 sections ingested again unchanged | 11,582 of 30,456 level-0 writes in the flight's minute came out the same as stored (38 %) | the ingest and encoding of those chunks | find why those chunks are snapshotted again (block changes, light); to study |
| L6. `PreviewVoxels.build` writes the preview's columns voxel by voxel (`section.set`, line 503) and the sea's sky light voxel by voxel (`seaLight`) | 1,121 samples at line 503, 657 in `NibbleBricks.set` from `seaLight` | 6.7 % and 3.9 % of the mesher | indices per brick packed once, as `writeChunkSection` does; identical if the palette order is kept |
| L7. `PreviewLayer.mask` looked up in a `ConcurrentHashMap` | 798 samples at `mask:1148` | 4.8 % of the mesher | to read: likely a lookup per column where one per section would do |

The user: "go L1 L2 L3 L4 L6 et étudie L5 et L7". Built:
- L1: `RealGround.lowest` skips a brick of air or fluid in one step (a unit
  test compares it with the voxel-by-voxel rule on random sections, and
  fails on a wrong skip), and `PreviewLayer` keeps each chunk's result with
  the count of real writes over the chunk (levels under 4, counted in
  `realWritten`; read before the store, so a write meanwhile reads it
  again), dropped with the world, a change of build heights, or past
  262,144 chunks.
- L2: `LodSection.pack` with shifts and masks.
- L3: `MipBuilder.downsample` walks a child's bricks from the top and keeps
  the bottom layer of each brick (`aboveLayers`) for the brick under it.
  A golden test hashes the parents of fixed random children with plants on
  every brick border (`parentsOfPlantedChildrenKeepTheirEncoding`, hash of
  the code before L2 and L3); a wrong read of the brick above changes it.
- L4: `ChunkSnapshot.fillBlocks` takes a section whose container's palette
  holds one state (`PalettedContainer.maybeHas`, which reads the palette
  only, checked in vanilla's source) without walking its blocks.
- L6, reduced: `LodSection.set` remembers the last material and its palette
  index (forgotten by `remapIds` and `prunePalette`). The full rewrite into
  bricks packed once was left: the preview section is read and changed by
  later steps (plants, masks, light), and the palette's order, which sets
  the order of a section's quads, would have to be kept.
- Measured on the same flight: server workers 3.9 ms a chunk (18.7 s for
  4,796 snapshots) against 4.6 ms; the mesher 996 us of processor a job
  against 1,119 and 1,131 us in two runs before (job-weighted over the
  run), 131.7 s of processor in the two minutes against 157.6 and 151.8 s.
  Captures not compared (the runs differ more than the change; core paths
  checked by the tests above).

Studied, not built:
- L5: a probe of the flight's minute: 10,560 of 27,728 level-0 writes came
  out unchanged, 7,662 of them (73 %) sections of air only. A level-0
  section spans 2 x 2 chunks: the first chunk of an air section above the
  ground writes it, the three others write the same air again (decode,
  octant, encode, compare). The other 2,898 are chunks snapshotted again
  after block changes that left the section as it was. Skipping them needs
  to know, without reading, that the incoming octant (blocks, light,
  biomes) equals the stored one: a fingerprint per section octant. The cost
  is about 38 % of the ingest and encoding of level 0, some 5 % of the
  workers: not worth the state for now.
- L7: `maskOf(w)` makes one mask object per preview build, and each column
  asks it `real`, `ground`, `fluidTop`: each call computes the tile's key and
  looks it up in `masks` (725 of the 798 samples from `PreviewVoxels.build`
  line 399, the rest from features and trees). The tile changes only
  between tiles, so a one-entry cache in the mask object (last tile key and
  its array) removes nearly all of it, about 4 to 5 % of the mesher. In a
  race with a mask being replaced, a build would then use one mask for all
  its columns instead of possibly two: a timing difference, not an image
  rule. Ready to build.
- L7 built on "go L7": the mask object keeps the last four tiles asked and
  their masks (one was not enough: a neighbor layer along a section's edge
  goes from one tile to the other at every row). Mask lookups in the
  mesher's samples: 1,049 of 14,322 (7.3 %) before, 643 of 12,730 with one
  tile kept, 470 of 13,961 (3.4 %) with four. All that is left comes from
  `buildFar` (514 of 527 callers): a far section's columns each lie in
  another tile, so no per-build cache helps there; skipping them would need
  to know a far area holds no real data without asking its tiles, which
  coarse levels built later could contradict for a moment. The mesher's
  processor time a job read 996, 985 and 1,062 us over the three runs (141k
  to 154k jobs): the change is inside the spread between runs.

Measured, no identical gain found:
- Parents built again unchanged: 946 of about 15,000 in the flight's minute
  (6 %, levels 1 and up): no waste left there.
- The preview's surface search: 13.6 density evaluations per sample
  (descent 1.0, climb 12.4, check 0.2; 1,301,728 searches in a minute). The
  climb is the bisection (3) and the five probes for ledges and noodle caves
  (`SURFACE_PROBES`, 5 when none is solid): fewer probes would change the
  heights. Sea biomes: 993,641 climate samples a minute (one per underwater
  sample, for frozen oceans and icebergs), 10 % of the preview threads here;
  in the pack a climate sample costs about 0.5 ms (ADR-102), so this share
  is to measure there (`farfield spike tile` on the pack server).
- The pack server since the deploy: 1 to 10 chunks a minute, no preview
  tile built, Farfield under 0.2 s of processor a minute; nothing to
  measure until someone explores.

## The preview on the pack server (2026-11-16)

The user: "check le cout de l'aperçu". The pack server's log of the user's
client/server session (12:02 to 12:06) and three tile spikes there (no
player online, one at a time: `farfield spike tile at 2048 2048`,
`6000 1500`, `1500 7000`).

- The minute lines: 130 to 280 quarters a minute on 5 threads, 566 to
  770 ms a quarter (heights 183 to 242, ground 61 to 73, features 300 to
  442); 133 to 155 features a minute left out for their cost. The fill
  after a jump: 267 quarters, the area within 1,024 blocks done at 49.5 s,
  all at 97.5 s. In dev the heights led; in the pack the features do.
- The spikes, a whole tile (four quarters): 3,946, 4,666 and 6,983 ms.
  Heights 672 to 829 ms (12 to 17 %), ground 1,392 to 1,578 ms (22 to
  35 %), features 1,826 to 4,589 ms (46 to 66 %).
- In the features: the stand-in chunks take 452 to 645 ms a quarter, about
  half of it the surface rules run again on 289 chunks (186 to 334 ms a
  quarter); the placed features 1,138 to 3,701 ms a tile, trees 41 to 49 %
  of them (`biomesoplenty:trees_coniferous_forest` 607 ms,
  `terralith:snowy/trees_center` 1,465 ms in one tile), then Terralith's
  snow layers, Supplementaries' barnacles, `minecraft:freeze_top_layer`,
  `minecraft:amethyst_geode` (73 to 173 ms a tile, under the ground).

What could cut it, none built:
- The pack's own feature code is most of the cost: only placing fewer
  features (the cost budget, `FeatureBudget`) cuts it, and that changes the
  image (fewer far trees).
- The surface rules run twice, per 4-block cell for the tile's ground and per
  block in the stand-in chunks for the features (0.8 to 1.1 s a tile,
  14 to 23 %). One at block resolution feeding the other would be the same
  rules once; whether the cell values come out the same is to check before
  anything.
- Geodes and other features under the ground only: `PreviewFeatures` keeps
  only what reaches the surface (dug lakes, ADR-118); a feature whose kind
  never reaches it could be skipped, if its kind is known before it runs
  (the spike's kinds are measured after). To study.
- Not code: the pack server builds the spawn's tiles while no player is on
  (`preview_spawn_radius`, ADR-123); a larger radius moves this cost out of
  play time, at the price of disk and a longer first build.

The user answered (2026-11-16), quoted as given: "autoriser
preview_spawn_radius a aller à plus que 16384. Il devrait pouvoir aller
jusqu'au max, soit la bordure"; "si on exécute deux fois la même règles
autant ne l'exécutrer qu'une fois"; "pourquoi traiter des choses qui
n'arriveront jamais dans la vue ? On le fait aussi"; for the trees, "si le
gain est vraiment intéressant, pourquoi ne pas le mettre derrière une
option et la désactiver par défaut ?". Then "go 1 2 4 3". Done:

- 1, built (ADR-161): the radius goes past the border; every level within
  16,384 blocks as before, past it what a player at the center sees. A dev
  server with `preview_spawn_radius=29999984` on a fresh world: "4719 tiles
  within 29999984 blocks", 0.2 to 0.3 s a tile, about 20 minutes there.
- 2, nothing to build: the surface rules run once in a normal build (column
  by column, ADR-134). The second run seen above is the spike's own check
  (`compareGround`), which builds the old stand-in chunks too to count the
  cells that differ; the spike's ground figures are inflated by it, the
  minute lines' (61 to 73 ms a quarter) are not.
- 4, not built: the setting exists. `preview_features_distance` (default
  1,250, 0 to 4,096) is the distance within which the world's own features
  grow; past it the preview draws each biome's canopy from its profiles, and
  the features' cost goes with the square of that distance (625 blocks:
  about a quarter of the level-0 feature tiles). A new switch leaving costly
  trees out would also leave them out of the tiles the profiles are measured
  on, so those forests would lose their canopy at every distance. To expose
  the setting in single player's settings screen is a choice left to the
  user.
- 3, built (ADR-162): a feature placed in 32 chunks of a biome with none of
  its blocks kept is left out there whatever its cost. In the dev bench, 19
  features left out against 17 and 18 in two runs before; 4 of them geodes
  under the old half-millisecond threshold (0.17 to 0.34 ms a chunk), which
  only the new rule takes. The costlier unseen ones were already left out
  by ADR-131: a small gain.

## Answers (2026-11-15)

The user's words, quoted as given.

1. Which steps go into the next release, and is a bench fine for each?
   "On a pas d'impératife de livraison donc idéalement, j'aimerai y mettre
   tout ce qu'on peut. Et oui aucun problème à faire des bench pour tout ce
   qui est nécessaire." Every step of the order goes in, each benched.
2. Is 32 chunks of game view a case to tune for? "32 chunks n'est pas
   souvent utilisé, il faut que ça marche avec mais la plupart du temps, les
   gens jouent avec 12 ou 16 chunks en vanilla mais là avec notre mods, ils
   vont peut être même le réduire à 8 ou 10 car on leur permet de voir au
   delà avec un coups bien moins important." Tune for 8 to 16 chunks; 32
   must keep working, not tuned for. R1 then weighs less than its 32-chunk
   arithmetic said; it is still measured at 12.
3. Two draw paths for G1 step c? "Oui c'est ok".
4. The 405 MB of per-slot buffers, now or after? "Oui tu peux le regarder en
   même temps": with step 2.
5. F6, the game's own sections meshed later? "Oui c'est ok aussi, pas besoin
   de faire de calcul pour quelque chjose qui est déjà rendu par le vanilla.
   Ces sections peuvent être calculée avec un basse priorité". They go to
   the back of the queue, not out of it.
6. The dedicated server measured before or after the release? "On peut faire
   un run de test client/server avant oui." Step 10 runs before the release.

## Open questions

None left.
