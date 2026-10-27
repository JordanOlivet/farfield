---
name: farfield-lod-hole-triage
description: Use when LOD terrain shows holes, missing chunks, white or black squares under water, or a stepped line or seam at the vanilla render edge.
category: minecraft-modding
---

# Farfield LOD hole triage

Snapshot a chunk from the object the load or block-change event handed you, never from a fresh `getChunkNow` lookup. A loaded chunk the player walks away from becomes a border chunk: complete and in memory, but `getChunkNow` no longer returns it, and walking back fires no load event. A lookup-based queue loses that chunk for good.

Before telling the user a hole is fixed:

1. Copy the dev world (`neoforge/run/world/region` and `neoforge/run/world/farfield`) into an empty scratch directory. Never run diagnostics on the live store.
2. Run `./gradlew.bat :core:storeHoles --no-daemon -q --args="$(cygpath -m "$D")"` from Git Bash, where `$D` is the scratch copy. Convert the path with `cygpath -m` first. Expect `N full chunks, 0 without any LOD voxel`. Any count above 0 is a hole.
3. Reproduce deterministically with the forceload script in `references/forceload-repro.md`. The minute log line must show border-chunk snapshots, and the close line must show `0 not snapshotted`.
4. Only then ask the user to retest. Say what stays unverified (for example, the user's own flight).

Stale vanilla mesh test, before blaming LOD: reload the chunks with F3+A (QWERTY). On AZERTY the same key is F3+Q, because Minecraft reads the physical key position, so F3+A does nothing there. If the edge moves or vanishes after the reload, the vanilla mesh was stale; if it stays, keep measuring.

Known residue, not a regression: a chunk outside the forced area that finishes generating during the server stop is taken on its next load. One such hole after a stop test is expected; re-visit that chunk to confirm it fills.

Log vocabulary: the `dropped` counter no longer exists. A `N of border chunks` count above 0 proves the border path ran, which the CLAUDE.md rule requires for any path you claim to have verified.

Holes that appear only after a zoom (spyglass) or during a long walk, with `storeHoles` at 0: read the `LOD renderer` stat line before anything else. A `quads used` figure at its budget (1,023 of 1,024 MB) plus a detail far above the default (62.6 against 4.6 section sizes) means the selector and the arena are starved, not the data. Fix in `farfield-lod-renderer` (zoomed view, quad room before residence), then prove it with the stat line and a user retest.

Old worlds keep their holes. Re-import with `/farfield import` or re-visit the area, then repeat step 2.

A hole near the camera that is not a store hole (`storeHoles` reports 0) is a render cull, not missing data. Check the vertical reach first: vanilla's Sodium draws sections only within the render distance on both axes, so LOD fragments discarded on the horizontal distance alone leave a hole under a high player (seen at y=245 over a lake). Compare with `/farfield render off` at the same spot: if the frame is only sky with LODs off, the LOD path is what fills the gap; fix the discard in `farfield-lod-renderer`.

A stepped line that looks like the vanilla edge may be a border between LOD levels. Identify it before fixing anything:
1. Sample the line's pixels from a full-resolution capture. Pure gray (125,125,125 by day) is the lower sky seen through a slit, not a face.
2. `/farfield render off` at the same spot: if the line's area turns to sky, it is LOD geometry.
3. `/farfield debug levels on`: a line exactly on a color border (red 0, orange 1, ...) is a level crack: coarse voxels must stand at their fill height (ADR-028), not at the top of their cell.
Seen on 2026-10-15: two fixes aimed at vanilla's edge (fluid side faces toward unloaded chunks) never triggered, because Sodium builds a section only once its neighbors are loaded. Border skirts left a darker line. Measure first.

A 1-pixel near-black line exactly on the vanilla chunk polygon over water is a height mismatch, not a missing face: vanilla still water tops at 8/9 of a block, and any LOD surface at another height lets a grazing ray pass between the two down to the unlit sea bed. LOD fluid tops are lowered to 8/9 on every level (`MaterialColors.height`, `lod.vert`). Measure such lines before and after, at the same view, with the `screenshot-artifact-measure` skill (its slit detector counts the 1-2 px line).

A band lighter than the ice around it, one block wide, that stays along one side of a chunk turned vanilla, over frozen sea, is a missing translucent layer, not a light or stale-mesh problem. Check in this order: `/farfield debug levels` (uncolored band = vanilla geometry, LOD terrain shows through it, so the LOD layer is missing); then the brightness step across the edge (profile method in `screenshot-artifact-measure`). Ruled out first: vanilla compiling before its neighbors' light, and stored light. The fix is in the translucent mesher, see `farfield-lod-renderer` (ADR-055). Do not patch the light sampling for this symptom.

Black rectangles over new sea, with the stored light right: sections the client drew were fetched while one of their chunks was still generating, and never refreshed. Check with `/farfieldclient render off` at the same spot (the rectangles vanish with the LOD geometry) and `/farfieldclient debug levels` (they sit on level 0). Then compare the mirror's water voxels per section with the server store: fewer in the mirror means an outdated fetch. The fix is in `farfield-lod-streaming`, not the renderer. A pale patch with straight edges on a sea floor is a section border face that took full light from a missing neighbor: see `farfield-lod-renderer`.

White squares on a sea floor under coarse water: a coarse water voxel keeps its highest block's light, in the mips (ADR-071) and in the noise preview (`PreviewVoxels`, ADR-072), so floors under it took the light of the water's top. On a streamed server most of the far sea is preview: ADR-071 alone left the squares there (user pass of `c158067`). Several squares of one exact colour do not prove holes: sand at full light under the same water is one colour too. Growing quads to close T-junctions did not move them. To look at one, lock the level with `/farfieldclient debug level <n>` (the whole displayed distance, the preview locked the same way) and walk toward it from far away.

Sampling is ruled out for these squares (A/B in one dev session, same preview view, 2026-10-27): they vanish with `/farfieldclient textures 0` and stay with mip bias 1 or 2 and with a linear minification filter, which only adds moiré. Do not spend another run on mip bias or the filter. Open lead, unverified: at the coarsest mip a block takes its tile's 1x1 average, alpha included, while the untextured path takes the material's mean colour and alpha; a lower water alpha with textures would let the floor show through per block. Test it by comparing textured and untextured water and floor colour and alpha at the coarsest mip.

White squares under the sea on the preview's level 0, gone once real LODs replace it (2026-10-27): the sky seen through the sea floor along edges and corners. A preview column only went down to under its four direct neighbors, so a column one block thick beside a much lower diagonal neighbor touched the water only by an edge or a corner, and a one-pixel crack there showed the hollow under it, then the sky. Found by a probe that builds the area as one grid of blocks across section borders and counts water touching a hollow under ground by face, edge and corner (0 by face, 26,321 by edge or corner before the fix; a per-section, face-only probe had found 0). The column now goes down to under the lowest of its eight neighbors (`PreviewVoxels`); test `aRuggedSeaFloorShowsNoHollowToTheWater`. Ruled out on the way: light (ADR-071, ADR-072 are right but were not this), textures, mip choice, the depth pre-pass, quad growth.

Before probing a square, read the user's annotated screenshots (files suffixed `-annoted`): they are the ground truth for where the squares are. On the 2026-10-27 pass they showed the squares on the sea-floor blocks, not on the water. After the 26-neighbour fix, the same area had 0 water faces against a hollow under ground, so the squares are not a data hole. Their faces take full sky light (15) but face solid ground hidden under the terrain. Open lead, unverified: a dotted straight line of squares along a level border (level 0 beside level 1), where the coarse column leaves a slit. Confirm with two in-game captures at the same spot: `/farfieldclient debug levels`, then `/farfieldclient debug level 0`. A square that disappears at level 0 is a level crack; one that stays drops the lead.

Measure specks with care: an exact-colour count of the user's squares stayed at 10 in every textured variant while the bright-pixel count moved from 109 to 9,838, so one count proves nothing. Compare stacked same-crop images (NearestNeighbor) instead. Temporary switches go in as chat commands, A/B in one run; afterwards `git checkout` their files, check `git status`, and never commit them.

Specks that stay after that, in a straight dotted line, are a seam between two levels of detail: check with `/farfieldclient debug levels` and the water hidden (`debug water off`) whether they sit exactly on a colour border. Seen on 2026-10-27 on the red/orange border: a coarse preview column stands at the highest block of its footprint, so the finer terrain beside it lay lower than the coarse column's floor and the view passed into its hollow, whose hidden faces take full light (sand-coloured squares). Fix: a preview column reaches under the lowest block of its neighbors' footprints (`PreviewVoxels`, test `aCoarseSectionShowsNoHollowToAFinerOneBeside`). `debug level 0` cannot rule a seam out: the lock is a floor, so level 0 changes nothing.
