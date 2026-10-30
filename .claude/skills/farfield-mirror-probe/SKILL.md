---
name: farfield-mirror-probe
description: Use when a render bug needs stored LOD mirror data.
category: debugging
---

# Farfield mirror probe

Answer a render question from the stored data, not from a guess: copy the store, read the copy from a temporary test, print counts.

1. Copy the store folder to the scratchpad: on the client, the `stream/minecraft_overworld` folder under `farfield/servers`; on a save, its `farfield` store. Never open the live store.
2. Copy `scripts/MirrorLightProbe.java` to `core/src/test/java/io/lakio/farfield/core/world/`, adapt the counts to the question, then run: `FF_MIRROR=<copy> FF_X=<x> FF_Z=<z> ./gradlew.bat :core:test --tests "*MirrorLightProbe*" --no-daemon -q -i 2>&1 | grep -a PROBE`.
3. Read counts per level, not voxel dumps. Check the claim against the code path before changing code. For a question about how a face is lit or meshed, count the mesher's quads, not voxels: mesh the copied sections in the probe under the old rule and the new one (`BinaryGreedyMesher` with a `MaterialClassifier`), and count the top quads per sky light level. A voxel count cannot show which light a face takes from the block above it.
4. Preview tiles (the noise preview, level 0 of far sea floors) need the sidecar files too. Copy `preview`, `block_states.txt`, `biomes.txt` and `mips.version` from `stream/minecraft_overworld` into the scratch folder, then open it with `LodWorld.open(dir, MaterialTable.SIMPLE)` and read tiles through `w.preview().read(x, z)`. A copy with only the tiles fails to open.
5. To test whether a column has a top face, mesh its sections with `BinaryGreedyMesher` and check each column's top voxel against the top quads, instead of counting voxels. Print one summary `PROBE` line and no per-voxel dump.
6. Count only faces that can be seen. A quad is hidden when the voxel in front of it (its position plus the face's direction, read from the neighbour section when it lies outside) is not the medium you are measuring. Before calling a bright face a bug, split the count into faces the medium touches and hidden faces. Example from the client mirror: of 60,577 bright ground quads under the sea, 57,607 faced solid ground and only 2,970 faced water.
7. Locate the mirror and its tiles: on the client it is `farfield/servers/<host>_<port>/<hash>/stream/minecraft_overworld` in the Minecraft instance (about 114 MB for the test area). Preview tiles are named `t.<tileX>.<tileZ>.ffp`, one tile per 512 blocks (`PreviewTile.BLOCKS`), so block x maps to tile `Math.floorDiv(x, 512)`. Pass the copy with `FF_MIRROR="$(cygpath -w <copy>)"` from Git Bash.
8. For a leak test, build the area as one grid of blocks across section borders and count water touching a non-water hollow through all 26 neighbours, not just 6 faces. A per-section, face-only check reported 0 where the grid found the leak.
6. Delete the probe before any commit; `git status` must not list it.

9. Tell a feature missing from the LOD from one missing from the preview by counting its blocks on the same chunks: the server's region files, read by the temporary test, against the mirror's level 0. Equal counts mean the LOD holds the feature and the bare land is the preview: switch to `farfield-noise-preview` (spike section). Delete the probe afterwards, as in step 6.

Probe template: `scripts/MirrorLightProbe.java`.
