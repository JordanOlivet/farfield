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
