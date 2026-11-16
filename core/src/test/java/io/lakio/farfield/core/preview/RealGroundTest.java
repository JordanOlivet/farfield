package io.lakio.farfield.core.preview;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.anvil.ChunkData;
import io.lakio.farfield.core.anvil.SectionData;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.core.world.MaterialTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealGroundTest {

    @TempDir
    Path dir;

    /** Chunk (cx, cz): stone under y 0, dirt from 0 to 7, and one column {@code (3, 5)} dug down to 0 when {@code pit}. */
    private static ChunkData chunk(LodWorld world, int cx, int cz, boolean pit) {
        int stone = world.blockStates().id("minecraft:stone");
        int dirt = world.blockStates().id("minecraft:dirt");
        int plains = world.biomes().id("minecraft:plains");
        ChunkData chunk = new ChunkData();
        chunk.beginLive(cx, cz);
        SectionData below = chunk.addLiveSection(-1);
        below.setBlockPalette(new int[]{stone}, 1);
        below.setBiomePalette(new int[]{plains}, 1);
        SectionData ground = chunk.addLiveSection(0);
        ground.setBlockPalette(new int[]{world.air(), dirt}, 2);
        short[] indices = ground.blockIndicesForWrite();
        for (int i = 0; i < SectionData.BLOCK_COUNT; i++) {
            int y = i >> 8;
            int z = (i >> 4) & 15;
            int x = i & 15;
            boolean dug = pit && x == 3 && z == 5;
            indices[i] = (short) (y < 8 && !dug ? 1 : 0);
        }
        ground.setBiomePalette(new int[]{plains}, 1);
        return chunk;
    }

    @Test
    void aPitInTheChunkIsItsGround() throws IOException {
        try (LodWorld w = LodWorld.open(dir, MaterialTable.SIMPLE)) {
            w.ingest(chunk(w, 0, 0, false));
            w.ingest(chunk(w, 1, 0, true));
            w.flush(0);
            assertEquals(8, RealGround.lowest(w, 0, 0, -64, 320, 4));
            // One column dug to the stone: the level-4 voxel stands at 8, the preview beside must reach 0.
            assertEquals(0, RealGround.lowest(w, 1, 0, -64, 320, 4));
            // A chunk the store does not hold.
            assertEquals(Integer.MIN_VALUE, RealGround.lowest(w, 5, 5, -64, 320, 4));
        }
    }

    /** The rule of {@link RealGround#lowest}, voxel by voxel. */
    private static int reference(LodWorld w, int chunkX, int chunkZ, int minY, int maxY, int maxLevel)
            throws IOException {
        int air = w.air();
        for (int level = 0; level < maxLevel; level++) {
            int voxel = 1 << level;
            int size = LodSection.SIZE << level;
            int columns = 16 >> level;
            int sx = Math.floorDiv(chunkX * 16, size);
            int sz = Math.floorDiv(chunkZ * 16, size);
            int vx0 = Math.floorMod(chunkX * 16, size) >> level;
            int vz0 = Math.floorMod(chunkZ * 16, size) >> level;
            int low = Integer.MAX_VALUE;
            boolean found = false;
            for (int z = 0; z < columns; z++) {
                for (int x = 0; x < columns; x++) {
                    int top = Integer.MIN_VALUE;
                    for (int y = Math.floorDiv(maxY - 1, size); y >= Math.floorDiv(minY, size)
                            && top == Integer.MIN_VALUE; y--) {
                        LodSection s = w.read(LodKey.pack(level, sx, y, sz));
                        if (s == null) {
                            continue;
                        }
                        for (int vy = LodSection.SIZE - 1; vy >= 0; vy--) {
                            int m = s.material(vx0 + x, vy, vz0 + z);
                            if (m == air || w.materials().classOf(m) == io.lakio.farfield.core.mesh.MaterialClassifier.TRANSLUCENT) {
                                continue;
                            }
                            int fill = (s.fill().get(vx0 + x, vy, vz0 + z) + 1) * voxel;
                            top = (y * LodSection.SIZE + vy) * voxel + (fill + 15) / 16;
                            break;
                        }
                    }
                    if (top != Integer.MIN_VALUE) {
                        found = true;
                        low = Math.min(low, top);
                    }
                }
            }
            if (found) {
                return low;
            }
        }
        return Integer.MIN_VALUE;
    }

    @Test
    void skippingBricksOfAirOrFluidFindsTheSameGround() throws IOException {
        MaterialTable.Resolver resolver = key -> key.equals("minecraft:air") ? MaterialTable.pack(
                io.lakio.farfield.core.mesh.MaterialClassifier.AIR, MaterialTable.EMPTY)
                : key.equals("minecraft:water") ? MaterialTable.packFluid()
                : MaterialTable.pack(io.lakio.farfield.core.mesh.MaterialClassifier.OPAQUE, MaterialTable.FULL);
        try (LodWorld w = LodWorld.open(dir, resolver)) {
            int stone = w.blockStates().id("minecraft:stone");
            int water = w.blockStates().id("minecraft:water");
            java.util.SplittableRandom random = new java.util.SplittableRandom(5);
            for (int level = 0; level < 4; level++) {
                for (int sy = -2; sy <= 1; sy++) {
                    for (int sxz = 0; sxz < 2; sxz++) {
                        if (random.nextInt(3) == 0) {
                            continue; // some levels and sections absent
                        }
                        LodSection s = new LodSection(w.air());
                        int ground = random.nextInt(48) - 16;
                        int sea = ground + random.nextInt(24);
                        for (int z = 0; z < 32; z++) {
                            for (int x = 0; x < 32; x++) {
                                int top = ground + random.nextInt(5);
                                for (int y = 0; y < 32; y++) {
                                    int at = sy * 32 + y;
                                    if (at < top) {
                                        s.set(x, y, z, stone);
                                        s.fill().set(x, y, z, random.nextInt(16));
                                    } else if (at < sea) {
                                        s.set(x, y, z, water);
                                    }
                                }
                            }
                        }
                        s.compact();
                        w.putSection(LodKey.pack(level, sxz, sy, sxz), s);
                    }
                }
            }
            int checked = 0;
            for (int cz = 0; cz < 8; cz++) {
                for (int cx = 0; cx < 8; cx++) {
                    int expected = reference(w, cx, cz, -64, 64, 4);
                    assertEquals(expected, RealGround.lowest(w, cx, cz, -64, 64, 4), cx + "," + cz);
                    checked += expected == Integer.MIN_VALUE ? 0 : 1;
                }
            }
            org.junit.jupiter.api.Assertions.assertTrue(checked > 10, "chunks with ground: " + checked);
        }
    }

    @Test
    void aChunkHeldOnlyOnACoarseLevelReadsItsFill() throws IOException {
        try (LodWorld w = LodWorld.open(dir, MaterialTable.SIMPLE)) {
            int stone = w.blockStates().id("minecraft:stone");
            LodSection s = new LodSection(w.air());
            // Level 1, chunk (0, 0): 8 x 8 columns of 2-block voxels; one voxel at y 4..5 half full.
            for (int z = 0; z < 8; z++) {
                for (int x = 0; x < 8; x++) {
                    s.set(x, 3, z, stone);
                }
            }
            s.set(2, 2, 6, stone);
            s.set(2, 3, 6, w.air());
            s.fill().set(2, 2, 6, 7);
            w.putSection(LodKey.pack(1, 0, 0, 0), s);
            assertEquals(5, RealGround.lowest(w, 0, 0, -64, 320, 4));
        }
    }
}
