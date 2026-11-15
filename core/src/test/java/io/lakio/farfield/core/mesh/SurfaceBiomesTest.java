package io.lakio.farfield.core.mesh;

import io.lakio.farfield.core.lod.LodSection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurfaceBiomesTest {

    private static final MaterialClassifier CLASSES = m -> m == 0 ? MaterialClassifier.AIR : MaterialClassifier.OPAQUE;

    @Test
    void highestCellWithMatterGivesTheColumnBiome() {
        LodSection section = new LodSection(0);
        section.fillBiome(5);
        // Column (0, 0): ground up to voxel y 9 (cell 2), a cave biome below.
        for (int y = 0; y < 10; y++) {
            section.set(1, y, 2, 1);
        }
        section.setBiome(0, 0, 0, 7);
        section.setBiome(0, 2, 0, 3);
        // Column (1, 0): one voxel at the very top, unknown biome there.
        section.set(5, 31, 0, 1);
        section.setBiome(1, 7, 0, -1);
        // Column (7, 7): ground in the lowest cell only.
        section.set(31, 0, 31, 1);
        section.compact();

        int[] columns = SurfaceBiomes.columns(section, CLASSES, null);
        assertEquals(3 + 2, columns[0]);
        assertEquals(-1 + 2, columns[1]);
        assertEquals(5 + 2, columns[63]);
        assertEquals(0, columns[2], "an all-air column has no surface");
    }

    @Test
    void theMeshersRowsGiveTheSameColumnsAsTheClassifier() {
        // Material m has class m % 6: air, opaque, translucent, partial, shaped and plant voxels.
        MaterialClassifier classes = m -> m % 6;
        java.util.Random random = new java.util.Random(42);
        BinaryGreedyMesher mesher = new BinaryGreedyMesher();
        for (int round = 0; round < 40; round++) {
            LodSection section = new LodSection(0);
            int voxels = round % 4 == 0 ? 0 : 1 + random.nextInt(round % 4 == 1 ? 8 : 3000);
            for (int i = 0; i < voxels; i++) {
                section.set(random.nextInt(32), random.nextInt(32), random.nextInt(32), random.nextInt(18));
            }
            if (round % 5 == 0) {
                for (int y = 0; y < 8; y++) {
                    for (int z = 0; z < 8; z++) {
                        for (int x = 0; x < 8; x++) {
                            section.set(8 + x, 16 + y, z, 1 + round % 5); // a uniform brick
                        }
                    }
                }
            }
            for (int c = 0; c < 64; c++) {
                section.setBiome(c % 8, random.nextInt(8), c / 8, random.nextInt(20));
            }
            section.compact();
            mesher.mesh(section, classes, null, new MeshBuffer());
            assertEquals(java.util.Arrays.toString(SurfaceBiomes.columns(section, classes, null)),
                    java.util.Arrays.toString(SurfaceBiomes.columns(section, mesher, null)), "round " + round);
        }
    }
}
