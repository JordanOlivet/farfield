package io.lakio.farfield.core.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PreviewColumnsTest {

    private static final int AIR = 0;
    private static final int GRASS = 1;
    private static final int STONE = 2;
    private static final int SAND = 3;
    private static final int WATER = 4;

    @Test
    void columnsStandAtTheirHeightWithWaterUpToTheSea() {
        PreviewColumns columns = new PreviewColumns(AIR);
        columns.setBelow(STONE);
        columns.setWater(WATER, 63);
        for (int z = 0; z < PreviewColumns.SIZE; z++) {
            for (int x = 0; x < PreviewColumns.SIZE; x++) {
                // Land at x < 16 (ground top at y 69), sea floor elsewhere (top at y 39).
                columns.set(x, z, x < 16 ? 70 : 40, x < 16 ? GRASS : SAND, x < 16 ? 5 : 6);
            }
        }
        LodSection section = columns.build(0, -64);
        // Land: voxel 4 holds blocks 64..79, filled 6 blocks up; voxel 3 lies under it.
        assertEquals(GRASS, section.material(3, 4, 0));
        assertEquals(5, section.fill().get(3, 4, 0));
        assertEquals(STONE, section.material(3, 3, 0));
        assertEquals(15, section.fill().get(3, 3, 0));
        assertEquals(AIR, section.material(3, 5, 0));
        // Sea: sand in voxel 2 (32..47) up to 40, water in voxel 3 (48..63) up to 63.
        assertEquals(SAND, section.material(20, 2, 7));
        assertEquals(7, section.fill().get(20, 2, 7));
        assertEquals(WATER, section.material(20, 3, 7));
        assertEquals(14, section.fill().get(20, 3, 7));
        assertEquals(AIR, section.material(20, 4, 7));
        // Biomes per 4x4-column cell.
        assertEquals(5, section.biome(0, 0, 0));
        assertEquals(6, section.biome(7, 3, 7));

        // Below the world's bottom (-64) nothing is built.
        LodSection under = columns.build(-1, -64);
        assertEquals(STONE, under.material(3, 31, 0));
        assertEquals(STONE, under.material(3, 28, 0));
        assertEquals(AIR, under.material(3, 27, 0));
        assertNull(columns.build(1, -64), "nothing above y 512");
    }
}
