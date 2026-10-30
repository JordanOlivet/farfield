package io.lakio.farfield.core.preview;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.world.LodWorld;

import java.io.IOException;

/**
 * The real ground of a chunk as the preview beside it needs it: the lowest
 * column top over the chunk. The preview's columns next to a real chunk go
 * down to its ground; the coarsest level's single voxel per chunk stands near
 * its highest columns, so a preview column beside a pit, a cave mouth or a
 * ravine stopped above it, and the view passed over the low real ground into
 * the hollow under the preview's skin, the sky showing (ADR-109).
 */
public final class RealGround {

    private RealGround() {
    }

    /**
     * The first free block above the lowest solid column top of a chunk,
     * read at the finest level below {@code maxLevel} that holds the chunk;
     * fluids are not ground. {@link Integer#MIN_VALUE} when no such level
     * holds solid voxels there.
     */
    public static int lowest(LodWorld w, int chunkX, int chunkZ, int minY, int maxY, int maxLevel)
            throws IOException {
        int air = w.air();
        MaterialClassifier classes = w.materials();
        for (int level = 0; level < maxLevel; level++) {
            int voxel = 1 << level;
            int size = LodSection.SIZE << level;
            int columns = 16 >> level;
            int bx = chunkX * 16;
            int bz = chunkZ * 16;
            int sx = Math.floorDiv(bx, size);
            int sz = Math.floorDiv(bz, size);
            int vx0 = Math.floorMod(bx, size) >> level;
            int vz0 = Math.floorMod(bz, size) >> level;
            int[] top = new int[columns * columns];
            java.util.Arrays.fill(top, Integer.MIN_VALUE);
            int left = top.length;
            for (int y = Math.floorDiv(maxY - 1, size); y >= Math.floorDiv(minY, size) && left > 0; y--) {
                long key = LodKey.pack(level, sx, y, sz);
                LodSection s = w.exists(key) ? w.read(key) : null;
                if (s == null) {
                    continue;
                }
                for (int z = 0; z < columns; z++) {
                    for (int x = 0; x < columns; x++) {
                        int c = z * columns + x;
                        if (top[c] != Integer.MIN_VALUE) {
                            continue;
                        }
                        for (int vy = LodSection.SIZE - 1; vy >= 0; vy--) {
                            int m = s.material(vx0 + x, vy, vz0 + z);
                            if (m == air || classes.classOf(m) == MaterialClassifier.TRANSLUCENT) {
                                continue;
                            }
                            // The fill says how high the content stands in the voxel, in sixteenths.
                            int fill = (s.fill().get(vx0 + x, vy, vz0 + z) + 1) * voxel;
                            top[c] = (y * LodSection.SIZE + vy) * voxel + (fill + 15) / 16;
                            left--;
                            break;
                        }
                    }
                }
            }
            if (left < top.length) {
                int low = Integer.MAX_VALUE;
                for (int t : top) {
                    if (t != Integer.MIN_VALUE) {
                        low = Math.min(low, t);
                    }
                }
                return low;
            }
        }
        return Integer.MIN_VALUE;
    }
}
