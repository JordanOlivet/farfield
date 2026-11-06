package io.lakio.farfield.core.mesh;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomeBlendTest {

    private static final int STRIDE = 8;
    private static final int KINDS = 3;
    private static final int SLOTS = 6;

    /** Tints per biome slot and kind: distinct, as BiomeTints' table. */
    private static int[] tints() {
        int[] t = new int[SLOTS * STRIDE];
        Random random = new Random(7);
        for (int slot = 0; slot < SLOTS; slot++) {
            for (int kind = 0; kind < STRIDE; kind++) {
                t[slot * STRIDE + kind] = kind < KINDS ? random.nextInt() | 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return t;
    }

    /** Nine sections of random columns (some unknown), one neighbor missing. */
    private static int[][] sections(long seed) {
        Random random = new Random(seed);
        int[][] sections = new int[9][];
        for (int s = 0; s < 9; s++) {
            if (s == 2) {
                continue; // no neighbor at (-1, +1)
            }
            int[] columns = new int[BiomeBlend.COLUMNS];
            for (int i = 0; i < columns.length; i++) {
                columns[i] = random.nextInt(8) == 0 ? 0 : 1 + random.nextInt(SLOTS);
            }
            sections[s] = BiomeBlend.pack(columns);
        }
        return sections;
    }

    /** biome_tint.glsl's blendedTint, channel by channel in 0..1, sRGB. */
    private static float[] shader(int[][] sections, int[] tints, float px, float pz, int face, int kind, int level,
                                  int radius) {
        float h = BiomeBlend.halfWindow(radius, level);
        float loX = px - h, hiX = px + h, loZ = pz - h, hiZ = pz + h;
        int ownX = (int) Math.floor(px / 4), ownZ = (int) Math.floor(pz / 4);
        float[] sum = new float[3];
        float total = 0;
        for (int z = (int) Math.floor(loZ / 4); z <= (int) Math.floor(hiZ / 4); z++) {
            float wz = Math.min(hiZ, z * 4 + 4) - Math.max(loZ, z * 4);
            for (int x = (int) Math.floor(loX / 4); x <= (int) Math.floor(hiX / 4); x++) {
                float w = wz * (Math.min(hiX, x * 4 + 4) - Math.max(loX, x * 4));
                if (w <= 0) {
                    continue;
                }
                int b = x == ownX && z == ownZ ? 0 : BiomeBlend.column(sections, x, z);
                int tint = tints[(b == 0 ? face : b - 1) * STRIDE + kind];
                for (int c = 0; c < 3; c++) {
                    sum[c] += (tint >>> (8 * c) & 0xFF) / 255f * w;
                }
                total += w;
            }
        }
        for (int c = 0; c < 3; c++) {
            sum[c] /= total;
        }
        return sum;
    }

    /** What the shader makes of one column's blended entry, for a face of biome slot {@code face}. */
    private static float[] fromEntry(int entry, int[] tints, int face, int kind) {
        float alpha = (entry >>> 24) / 255f;
        int tint = tints[face * STRIDE + kind];
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) {
            c[i] = (entry >>> (8 * i) & 0xFF) / 255f + (tint >>> (8 * i) & 0xFF) / 255f * (1 - alpha);
        }
        return c;
    }

    @Test
    void columnCentresMatchTheShadersBlend() {
        int[] tints = tints();
        for (int radius : new int[]{2, 4, 7}) {
            for (int level = 0; level <= 1; level++) {
                if (!BiomeBlend.needed(radius, level)) {
                    continue;
                }
                int[][] sections = sections(radius * 10L + level);
                int[] out = new int[BiomeBlend.COLUMNS * STRIDE];
                BiomeBlend.blend(sections, tints, SLOTS, KINDS, STRIDE, level, radius, out);
                for (int cz = 0; cz < BiomeBlend.AXIS; cz++) {
                    for (int cx = 0; cx < BiomeBlend.AXIS; cx++) {
                        for (int kind = 0; kind < KINDS; kind++) {
                            for (int face = 0; face < SLOTS; face++) {
                                float[] want = shader(sections, tints, cx * 4 + 2, cz * 4 + 2, face, kind, level, radius);
                                float[] got = fromEntry(out[(cz * BiomeBlend.AXIS + cx) * STRIDE + kind], tints, face, kind);
                                for (int c = 0; c < 3; c++) {
                                    // Two 8-bit roundings (the sum and its share).
                                    assertEquals(want[c], got[c], 1.6f / 255f,
                                            "radius " + radius + " level " + level + " column " + cx + "," + cz);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void oneBiomeAroundGivesItsOwnTint() {
        int[] tints = tints();
        int[] columns = new int[BiomeBlend.COLUMNS];
        java.util.Arrays.fill(columns, 3 + 1);
        int[][] sections = new int[9][];
        for (int s = 0; s < 9; s++) {
            sections[s] = BiomeBlend.pack(columns);
        }
        int[] out = new int[BiomeBlend.COLUMNS * STRIDE];
        BiomeBlend.blend(sections, tints, SLOTS, KINDS, STRIDE, 0, 7, out);
        for (int i = 0; i < BiomeBlend.COLUMNS; i++) {
            float[] got = fromEntry(out[i * STRIDE + 1], tints, 3, 1);
            int tint = tints[3 * STRIDE + 1];
            for (int c = 0; c < 3; c++) {
                assertEquals((tint >>> (8 * c) & 0xFF) / 255f, got[c], 1.6f / 255f);
            }
        }
        assertEquals(0, out[5 * STRIDE + KINDS], "kinds past the blended ones stay empty");
    }

    @Test
    void narrowWindowsStayInTheShader() {
        assertFalse(BiomeBlend.needed(0, 0));
        assertFalse(BiomeBlend.needed(1, 0), "a 3-block window spans 2x2 columns at most");
        assertTrue(BiomeBlend.needed(2, 0));
        assertTrue(BiomeBlend.needed(7, 1));
        assertFalse(BiomeBlend.needed(3, 1));
        assertFalse(BiomeBlend.needed(7, 2));
    }

    /** Between centres the shader interpolates; how far that is from its own per-fragment blend. */
    @Test
    void interpolationStaysCloseToThePerFragmentBlend() {
        int[] tints = tints();
        // Two biomes split along x = 16 voxels: a border inside the section.
        int[] columns = new int[BiomeBlend.COLUMNS];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = (i % BiomeBlend.AXIS < 4 ? 1 : 2) + 1;
        }
        int[][] sections = new int[9][];
        for (int s = 0; s < 9; s++) {
            int[] c = columns.clone();
            if (s / 3 == 0) {
                java.util.Arrays.fill(c, 1 + 1);
            } else if (s / 3 == 2) {
                java.util.Arrays.fill(c, 2 + 1);
            }
            sections[s] = BiomeBlend.pack(c);
        }
        int[] out = new int[BiomeBlend.COLUMNS * STRIDE];
        BiomeBlend.blend(sections, tints, SLOTS, KINDS, STRIDE, 0, 7, out);
        float worst = 0;
        for (float px = 2; px <= 30; px += 0.25f) {
            float pz = 13.5f;
            int face = px < 16 ? 1 : 2;
            float qx = px / 4 - 0.5f, qz = pz / 4 - 0.5f;
            int x0 = (int) Math.floor(qx), z0 = (int) Math.floor(qz);
            float tx = qx - x0, tz = qz - z0;
            float[] want = shader(sections, tints, px, pz, face, 0, 0, 7);
            for (int c = 0; c < 3; c++) {
                float v00 = fromEntry(out[(z0 * 8 + x0) * STRIDE], tints, face, 0)[c];
                float v10 = fromEntry(out[(z0 * 8 + x0 + 1) * STRIDE], tints, face, 0)[c];
                float v01 = fromEntry(out[((z0 + 1) * 8 + x0) * STRIDE], tints, face, 0)[c];
                float v11 = fromEntry(out[((z0 + 1) * 8 + x0 + 1) * STRIDE], tints, face, 0)[c];
                float got = (v00 * (1 - tx) + v10 * tx) * (1 - tz) + (v01 * (1 - tx) + v11 * tx) * tz;
                worst = Math.max(worst, Math.abs(got - want[c]));
            }
        }
        // A 15-block window over a straight border: the interpolation follows its ramp
        // within a small share of the two biomes' difference (measured, not a contract).
        float step = 0;
        for (int c = 0; c < 3; c++) {
            step = Math.max(step, Math.abs((tints[STRIDE] >>> (8 * c) & 0xFF) - (tints[2 * STRIDE] >>> (8 * c) & 0xFF)) / 255f);
        }
        System.out.println("BiomeBlend interpolation over a border: worst " + worst * 255 + " of 255, "
                + Math.round(worst / step * 100) + " % of the step between the biomes");
        assertTrue(worst < 0.1f * step, "worst " + worst * 255 + " of 255 for a step of " + step * 255);
    }
}
