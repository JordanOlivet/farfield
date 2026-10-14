package io.lakio.farfield.core.lod;

import java.util.function.IntPredicate;

/**
 * Builds a parent {@link LodSection} (level N+1, one voxel per 2x2x2 voxels)
 * from its eight children (level N).
 *
 * <p><b>Provisional rule</b>, good enough to size the mip pyramid in spike S1;
 * the real downsampling rules are plan item E.1 (analysis §5.3): a parent
 * voxel is the most frequent non-air material of its eight children when at
 * least four are non-air, air otherwise; light is the maximum of the
 * children; a biome cell takes the biome of its first child cell.
 */
public final class MipBuilder {

    private final IntPredicate isAir;
    private final int airMaterial;
    private final int[] materials = new int[8];
    private final int[] counts = new int[8];

    public MipBuilder(int airMaterial, IntPredicate isAir) {
        this.airMaterial = airMaterial;
        this.isAir = isAir;
    }

    /**
     * @param children the eight children indexed {@code (oy * 2 + oz) * 2 + ox};
     *                 a null child counts as all air with full sky light
     */
    public LodSection build(LodSection[] children) {
        LodSection parent = new LodSection(airMaterial);
        for (int oy = 0; oy < 2; oy++) {
            for (int oz = 0; oz < 2; oz++) {
                for (int ox = 0; ox < 2; ox++) {
                    LodSection child = children[(oy * 2 + oz) * 2 + ox];
                    if (child != null) {
                        downsample(child, parent, ox * 16, oy * 16, oz * 16);
                    }
                }
            }
        }
        parent.compact();
        parent.compactBiomes();
        return parent;
    }

    private void downsample(LodSection child, LodSection parent, int baseX, int baseY, int baseZ) {
        // Whole uniform child: a uniform octant.
        boolean uniform = true;
        int first = child.isUniform(0) ? child.uniformIndex(0) : -1;
        for (int b = 0; b < LodSection.BRICK_COUNT && uniform; b++) {
            uniform = child.isUniform(b) && child.uniformIndex(b) == first;
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int material;
                    if (uniform) {
                        int m = child.paletteEntry(first);
                        material = isAir.test(m) ? airMaterial : m;
                    } else {
                        material = pick(child, x * 2, y * 2, z * 2);
                    }
                    if (material != airMaterial) {
                        parent.set(baseX + x, baseY + y, baseZ + z, material);
                    }
                    int sky = 0;
                    int block = 0;
                    for (int i = 0; i < 8; i++) {
                        int cx = x * 2 + (i & 1);
                        int cy = y * 2 + (i >> 2);
                        int cz = z * 2 + ((i >> 1) & 1);
                        sky = Math.max(sky, child.skyLight().get(cx, cy, cz));
                        block = Math.max(block, child.blockLight().get(cx, cy, cz));
                    }
                    parent.skyLight().set(baseX + x, baseY + y, baseZ + z, sky);
                    parent.blockLight().set(baseX + x, baseY + y, baseZ + z, block);
                }
            }
        }
        for (int cy = 0; cy < 4; cy++) {
            for (int cz = 0; cz < 4; cz++) {
                for (int cx = 0; cx < 4; cx++) {
                    parent.setBiome(baseX / 4 + cx, baseY / 4 + cy, baseZ / 4 + cz, child.biome(cx * 2, cy * 2, cz * 2));
                }
            }
        }
    }

    private int pick(LodSection child, int x, int y, int z) {
        int distinct = 0;
        int nonAir = 0;
        for (int i = 0; i < 8; i++) {
            int m = child.material(x + (i & 1), y + (i >> 2), z + ((i >> 1) & 1));
            if (isAir.test(m)) {
                continue;
            }
            nonAir++;
            int j = 0;
            while (j < distinct && materials[j] != m) {
                j++;
            }
            if (j == distinct) {
                materials[distinct] = m;
                counts[distinct] = 0;
                distinct++;
            }
            counts[j]++;
        }
        if (nonAir < 4) {
            return airMaterial;
        }
        int best = 0;
        for (int j = 1; j < distinct; j++) {
            if (counts[j] > counts[best]) {
                best = j;
            }
        }
        return materials[best];
    }
}
