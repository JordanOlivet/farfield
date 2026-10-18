package io.lakio.farfield.core.preview;

import io.lakio.farfield.core.util.ByteReader;
import io.lakio.farfield.core.util.ByteWriter;

/**
 * The noise preview of 512 x 512 blocks (ADR-039): the surface height every
 * {@link #GRID} blocks, interpolated in between; per {@link #GRID}-block
 * cell the block seen on top and the one just under it; per chunk the
 * biome; for the whole tile the ground's material and the sea.
 *
 * <p>Heights are the first free block above the ground. Sample (i, k) lies
 * on the block corner (i * 4, k * 4) of the tile, so the last row and
 * column repeat the first ones of the next tiles and the surface is
 * continuous across tiles.
 */
public final class PreviewTile {

    public static final int BLOCKS = 512;
    public static final int GRID = 4;
    public static final int SAMPLES = BLOCKS / GRID + 1;
    public static final int CELLS = BLOCKS / GRID;
    public static final int CHUNKS = BLOCKS / 16;
    /** Blocks under the top one that show the filler (dirt under grass). */
    public static final int FILLER_DEPTH = 3;
    private static final int FORMAT = 1;

    private final short[] heights = new short[SAMPLES * SAMPLES];
    private final int[] top = new int[CELLS * CELLS];
    private final int[] filler = new int[CELLS * CELLS];
    private final int[] biomes = new int[CHUNKS * CHUNKS];
    private int below;
    private int water = -1;
    private int seaLevel;

    public void setHeight(int i, int k, int surfaceY) {
        heights[k * SAMPLES + i] = (short) surfaceY;
    }

    /** Height at sample (i, k), 0..{@link #SAMPLES} - 1. */
    public int sample(int i, int k) {
        return heights[k * SAMPLES + i];
    }

    /** Surface at the center of block (bx, bz) of the tile, bilinear between samples, rounded. */
    public int height(int bx, int bz) {
        // Positions in half blocks: the block center lies half a block past its corner.
        int fx = bx * 2 + 1;
        int fz = bz * 2 + 1;
        int i = fx / (GRID * 2);
        int k = fz / (GRID * 2);
        int tx = fx - i * GRID * 2;
        int tz = fz - k * GRID * 2;
        int span = GRID * 2;
        int h00 = sample(i, k);
        int h10 = sample(i + 1, k);
        int h01 = sample(i, k + 1);
        int h11 = sample(i + 1, k + 1);
        int a = h00 * (span - tx) + h10 * tx;
        int b = h01 * (span - tx) + h11 * tx;
        int v = a * (span - tz) + b * tz;
        return Math.floorDiv(v + span * span / 2, span * span);
    }

    public void setCell(int cx, int cz, int topMaterial, int fillerMaterial) {
        top[cz * CELLS + cx] = topMaterial;
        filler[cz * CELLS + cx] = fillerMaterial;
    }

    public int top(int cx, int cz) {
        return top[cz * CELLS + cx];
    }

    public int filler(int cx, int cz) {
        return filler[cz * CELLS + cx];
    }

    public void setBiome(int chunkX, int chunkZ, int biome) {
        biomes[chunkZ * CHUNKS + chunkX] = biome;
    }

    public int biome(int chunkX, int chunkZ) {
        return biomes[chunkZ * CHUNKS + chunkX];
    }

    public void setBelow(int material) {
        below = material;
    }

    public int below() {
        return below;
    }

    /** Fluid up to {@code seaLevel} (the first free block above it); -1: none. */
    public void setWater(int material, int seaLevel) {
        this.water = material;
        this.seaLevel = seaLevel;
    }

    public int water() {
        return water;
    }

    public int seaLevel() {
        return seaLevel;
    }

    /** Lowest and highest sample of each chunk (its 5 x 5 samples), as {@link Summary}. */
    public Summary summary() {
        short[] min = new short[CHUNKS * CHUNKS];
        short[] max = new short[CHUNKS * CHUNKS];
        for (int cz = 0; cz < CHUNKS; cz++) {
            for (int cx = 0; cx < CHUNKS; cx++) {
                int lo = Integer.MAX_VALUE;
                int hi = Integer.MIN_VALUE;
                for (int k = cz * 4; k <= cz * 4 + 4; k++) {
                    for (int i = cx * 4; i <= cx * 4 + 4; i++) {
                        int h = sample(i, k);
                        lo = Math.min(lo, h);
                        hi = Math.max(hi, h);
                    }
                }
                min[cz * CHUNKS + cx] = (short) lo;
                max[cz * CHUNKS + cx] = (short) hi;
            }
        }
        return new Summary(min, max, water >= 0 ? seaLevel : Integer.MIN_VALUE);
    }

    /**
     * What decides which sections of a tile hold something: per chunk the
     * lowest and highest surface; the sea level, or {@code Integer.MIN_VALUE}
     * without sea.
     */
    public record Summary(short[] chunkMin, short[] chunkMax, int sea) {

        /**
         * Whether blocks [y0, y1) may hold voxels over chunks [cx0, cx1] x
         * [cz0, cz1] of the tile: between one voxel ({@code voxel} blocks)
         * under the lowest surface nearby (a chunk around) and the highest
         * surface or the sea.
         */
        public boolean spans(int cx0, int cz0, int cx1, int cz1, int y0, int y1, int voxel) {
            int lo = Integer.MAX_VALUE;
            int hi = sea;
            for (int cz = Math.max(0, cz0 - 1); cz <= Math.min(CHUNKS - 1, cz1 + 1); cz++) {
                for (int cx = Math.max(0, cx0 - 1); cx <= Math.min(CHUNKS - 1, cx1 + 1); cx++) {
                    lo = Math.min(lo, chunkMin[cz * CHUNKS + cx]);
                    if (cx >= cx0 && cx <= cx1 && cz >= cz0 && cz <= cz1) {
                        hi = Math.max(hi, chunkMax[cz * CHUNKS + cx]);
                    }
                }
            }
            return y0 < hi && y1 > lo - 2 * voxel;
        }
    }

    public void encode(ByteWriter out) {
        out.writeVarInt(FORMAT);
        out.writeVarInt(below);
        out.writeVarInt(water + 1);
        out.writeInt(seaLevel);
        // Rows of height deltas: small numbers for the compressor.
        for (int k = 0; k < SAMPLES; k++) {
            int previous = k == 0 ? 0 : sample(0, k - 1);
            for (int i = 0; i < SAMPLES; i++) {
                int h = sample(i, k);
                out.writeVarInt(zigZag(h - previous));
                previous = h;
            }
        }
        for (int v : top) {
            out.writeVarInt(v);
        }
        for (int v : filler) {
            out.writeVarInt(v);
        }
        for (int v : biomes) {
            out.writeVarInt(v + 1);
        }
    }

    public static PreviewTile decode(ByteReader in) {
        int format = in.readVarInt();
        if (format != FORMAT) {
            throw new IllegalArgumentException("unknown preview tile format " + format);
        }
        PreviewTile t = new PreviewTile();
        t.below = in.readVarInt();
        t.water = in.readVarInt() - 1;
        t.seaLevel = in.readInt();
        for (int k = 0; k < SAMPLES; k++) {
            int previous = k == 0 ? 0 : t.sample(0, k - 1);
            for (int i = 0; i < SAMPLES; i++) {
                int h = previous + unZigZag(in.readVarInt());
                t.setHeight(i, k, h);
                previous = h;
            }
        }
        for (int i = 0; i < t.top.length; i++) {
            t.top[i] = in.readVarInt();
        }
        for (int i = 0; i < t.filler.length; i++) {
            t.filler[i] = in.readVarInt();
        }
        for (int i = 0; i < t.biomes.length; i++) {
            t.biomes[i] = in.readVarInt() - 1;
        }
        return t;
    }

    private static int zigZag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static int unZigZag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }
}
