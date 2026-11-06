package io.lakio.farfield.core.mesh;

import io.lakio.farfield.core.lod.LodSection;

/**
 * Biome blending once per 4x4-voxel column instead of once per fragment.
 *
 * <p>{@code biome_tint.glsl}'s {@code blendedTint} averages the tints of
 * every column under a square window of {@code 2h} voxels around the
 * fragment ({@code h = (radius + 0.5) / 2^level}), the fragment's own column
 * and the unknown ones taking the face's biome. With vanilla's widest Biome
 * Blend that is up to 25 columns per tinted fragment, every frame. This
 * computes the same sum at each column's centre, when a section or one of
 * its neighbors arrives; the shader then interpolates the four nearest
 * centres. The face's biome stays the shader's: the result holds the other
 * columns' tints, premultiplied by the share of the window they cover, and
 * that share, so the shader adds its own biome's tint for the rest.
 *
 * <p>Only windows of {@link #MIN_HALF_WINDOW} voxels or more go this way
 * (finer levels, wide blends): a narrower window spans at most 2x2 columns,
 * cheap in the shader, and its sharp transitions would blur between centres.
 */
public final class BiomeBlend {

    /** Columns per section axis ({@link LodSection#BIOME_CELLS_PER_AXIS}). */
    public static final int AXIS = LodSection.BIOME_CELLS_PER_AXIS;
    /** Columns per section. */
    public static final int COLUMNS = AXIS * AXIS;
    /** Voxels per column side. */
    private static final int CELL = LodSection.SIZE / AXIS;
    /** The half window, in voxels, from which a section's columns are blended here. */
    public static final float MIN_HALF_WINDOW = 2.0f;

    private BiomeBlend() {
    }

    /** The half window of the shader's blend, in voxels of the level. */
    public static float halfWindow(int radius, int level) {
        return (radius + 0.5f) / (1 << level);
    }

    /** Whether sections of this level are blended here at this radius. */
    public static boolean needed(int radius, int level) {
        return radius > 0 && halfWindow(radius, level) >= MIN_HALF_WINDOW;
    }

    /**
     * Blends a section's columns.
     *
     * @param sections the packed surface biome columns of the section and its
     *                 horizontal same-level neighbors, index
     *                 {@code (dx + 1) * 3 + (dz + 1)} (4 is the section), null
     *                 where the shader sees no neighbor; two 16-bit entries per
     *                 int, index {@code z * AXIS + x}, biome slot + 1 or 0 when
     *                 unknown
     * @param tints    ABGR8 tint per biome slot and kind, {@code stride} kinds per slot
     * @param slots    valid biome slots in {@code tints}
     * @param kinds    kinds to blend (the others are left at 0)
     * @param stride   kinds per biome slot in {@code tints} and per column in {@code out}
     * @param level    the section's level
     * @param radius   vanilla's Biome Blend, in blocks
     * @param out      per column {@code z * AXIS + x} and kind: premultiplied ABGR8,
     *                 alpha the share of the window those tints cover
     */
    public static void blend(int[][] sections, int[] tints, int slots, int kinds, int stride, int level,
                             int radius, int[] out) {
        float h = halfWindow(radius, level);
        // The window spans the same number of columns from every centre: at most this many per axis.
        int span = (int) Math.ceil(2 * h / CELL) + 1;
        int[] biome = new int[span * span];
        float[] weight = new float[span * span];
        float[] sum = new float[3];
        for (int cz = 0; cz < AXIS; cz++) {
            for (int cx = 0; cx < AXIS; cx++) {
                float px = cx * CELL + CELL / 2f;
                float pz = cz * CELL + CELL / 2f;
                float loX = px - h;
                float hiX = px + h;
                float loZ = pz - h;
                float hiZ = pz + h;
                int x0 = (int) Math.floor(loX / CELL);
                int x1 = (int) Math.floor(hiX / CELL);
                int z0 = (int) Math.floor(loZ / CELL);
                int z1 = (int) Math.floor(hiZ / CELL);
                int n = 0;
                float total = 0;
                float known = 0;
                for (int z = z0; z <= z1; z++) {
                    float wz = Math.min(hiZ, z * CELL + CELL) - Math.max(loZ, z * CELL);
                    for (int x = x0; x <= x1; x++) {
                        float w = wz * (Math.min(hiX, x * CELL + CELL) - Math.max(loX, x * CELL));
                        if (w <= 0) {
                            continue;
                        }
                        total += w;
                        if (x == cx && z == cz) {
                            continue; // the fragment's own column takes the face's biome
                        }
                        int b = column(sections, x, z);
                        if (b == 0 || b - 1 >= slots) {
                            continue; // unknown: the face's biome too
                        }
                        known += w;
                        biome[n] = b - 1;
                        weight[n] = w;
                        n++;
                    }
                }
                int base = (cz * AXIS + cx) * stride;
                for (int kind = 0; kind < stride; kind++) {
                    out[base + kind] = 0;
                }
                if (total <= 0) {
                    continue;
                }
                int alpha = Math.round(known / total * 255f);
                for (int kind = 0; kind < kinds; kind++) {
                    sum[0] = sum[1] = sum[2] = 0;
                    for (int i = 0; i < n; i++) {
                        int tint = tints[biome[i] * stride + kind];
                        float w = weight[i];
                        sum[0] += (tint & 0xFF) * w;
                        sum[1] += (tint >>> 8 & 0xFF) * w;
                        sum[2] += (tint >>> 16 & 0xFF) * w;
                    }
                    out[base + kind] = Math.round(sum[0] / total)
                            | Math.round(sum[1] / total) << 8
                            | Math.round(sum[2] / total) << 16
                            | alpha << 24;
                }
            }
        }
    }

    /** A column's biome slot + 1, in columns from the section's corner (-AXIS to 2 * AXIS - 1); 0 when unknown. */
    static int column(int[][] sections, int x, int z) {
        int ox = Math.floorDiv(x, AXIS);
        int oz = Math.floorDiv(z, AXIS);
        int[] s = sections[(ox + 1) * 3 + (oz + 1)];
        if (s == null) {
            return 0;
        }
        int i = (z - oz * AXIS) * AXIS + (x - ox * AXIS);
        return s[i >> 1] >>> ((i & 1) * 16) & 0xFFFF;
    }

    /** Packs per-column entries ({@code z * AXIS + x}) two per int, as the renderer uploads them. */
    public static int[] pack(int[] columns) {
        int[] packed = new int[COLUMNS / 2];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = columns[2 * i] | columns[2 * i + 1] << 16;
        }
        return packed;
    }
}
