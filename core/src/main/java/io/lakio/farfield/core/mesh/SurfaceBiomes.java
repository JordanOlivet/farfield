package io.lakio.farfield.core.mesh;

import io.lakio.farfield.core.lod.LodSection;

/**
 * The surface biome of each 4x4-voxel column of a section, for biome
 * blending: the biome of the highest biome cell of the column that holds a
 * non-air voxel. Biome blending is horizontal (vanilla averages the tints of
 * the blocks around, at the same height), and LODs mostly show surfaces, so
 * one biome per column is enough; a cave biome under the surface of the same
 * section does not bleed into it.
 */
public final class SurfaceBiomes {

    private static final int CELLS = LodSection.BIOME_CELLS_PER_AXIS;
    /** Voxels per biome cell along each axis. */
    private static final int CELL = LodSection.SIZE / CELLS;

    private SurfaceBiomes() {
    }

    /**
     * Fills {@code out} (64 entries, index {@code cellZ * 8 + cellX}) with
     * each column's surface biome id plus 2, so that unknown biomes (-1)
     * stay distinct from 0, which marks a column without any non-air voxel.
     */
    public static int[] columns(LodSection section, MaterialClassifier classifier, int[] out) {
        int[] columns = out != null ? out : new int[CELLS * CELLS];
        for (int cz = 0; cz < CELLS; cz++) {
            for (int cx = 0; cx < CELLS; cx++) {
                columns[cz * CELLS + cx] = 0;
                for (int cy = CELLS - 1; cy >= 0; cy--) {
                    if (hasMatter(section, classifier, cx, cy, cz)) {
                        columns[cz * CELLS + cx] = section.biome(cx, cy, cz) + 2;
                        break;
                    }
                }
            }
        }
        return columns;
    }

    private static boolean hasMatter(LodSection section, MaterialClassifier classifier, int cx, int cy, int cz) {
        for (int y = cy * CELL; y < cy * CELL + CELL; y++) {
            for (int z = cz * CELL; z < cz * CELL + CELL; z++) {
                for (int x = cx * CELL; x < cx * CELL + CELL; x++) {
                    if (classifier.classOf(section.material(x, y, z)) != MaterialClassifier.AIR) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
