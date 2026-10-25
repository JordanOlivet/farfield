package io.lakio.farfield.core.preview;

import io.lakio.farfield.core.util.ByteReader;
import io.lakio.farfield.core.util.ByteWriter;

/**
 * The noise preview of 512 x 512 blocks (ADR-039): the surface height every
 * {@link #GRID} blocks, interpolated in between; per {@link #GRID}-block
 * cell the block seen on top and the one just under it; per chunk the
 * biome and its trees (ADR-040: positions, each of a {@link TreeKind});
 * the buildings of villages (ADR-041); per cell whether the sea's top
 * block freezes; for the whole tile the ground's material, the sea and its
 * ice; per chunk its plants without collision, as kinds and mean counts,
 * and the ground they grow on (ADR-060).
 *
 * <p>A far tile computes its heights every {@link #grid()} blocks only (8
 * or 16 instead of {@link #GRID}) and fills the samples between them
 * bilinearly ({@link #fillBetweenSamples}), so readers never see the
 * difference; it is built again finer when a player comes closer.
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
    /**
     * Format 2 adds the trees, 3 their shapes and the buildings, 4 the ice,
     * 5 the grid the heights were computed on, 6 the snow on tree tops and
     * tiered spruces, 7 the icebergs, 8 the plants, 9 only the plants the
     * renderer draws as plants, 10 a quarter as many plants (measured
     * against generated chunks); older tiles are built again.
     */
    private static final int FORMAT = 10;
    /** Most plant kinds a tile holds (the kind rides in 8 bits of a chunk's plant entry). */
    public static final int MAX_PLANT_KINDS = 255;
    /** Most tree kinds a tile holds (the kind rides in 8 bits of a tree). */
    public static final int MAX_KINDS = 255;

    private final short[] heights = new short[SAMPLES * SAMPLES];
    /** Blocks between the heights computed from the noise: {@link #GRID}, 8 or 16. */
    private int grid = GRID;
    private final int[] top = new int[CELLS * CELLS];
    private final int[] filler = new int[CELLS * CELLS];
    private final int[] biomes = new int[CHUNKS * CHUNKS];
    private int below;
    private int water = -1;
    private int seaLevel;
    private int ice = -1;
    /** Material of frozen ocean icebergs: a cell whose filler it is shows it all the way down; -1 for none. */
    private int iceberg = -1;
    /** One bit per cell, {@code cz * CELLS + cx}: the sea's top block is ice there. */
    private final long[] frozen = new long[CELLS * CELLS / 64];
    private final java.util.List<TreeKind> kinds = new java.util.ArrayList<>();
    /** Per chunk, its trees: x | z << 4 (in the chunk) | kind << 8. */
    private final int[][] trees = new int[CHUNKS * CHUNKS][];
    /** Materials of the plant kinds. */
    private final java.util.List<Integer> plantKinds = new java.util.ArrayList<>();
    /** Per chunk, its plants: kind | mean count per chunk in sixteenths << 8. */
    private final int[][] plants = new int[CHUNKS * CHUNKS][];
    /** The top material plants grow on; -1 for none. */
    private int plantGround = -1;

    /** Crown shapes of a {@link TreeKind}. */
    public static final int BLOB = 0;
    /** A cone narrowing to a point: spruces and pines. */
    public static final int CONE = 1;
    /** One wide layer on top: brown huge mushrooms. */
    public static final int FLAT = 2;
    /** No crown: cactus, bamboo, single blocks. */
    public static final int COLUMN = 3;
    /** Tiers widening downward, each narrow again at its top: spruces. */
    public static final int TIERED = 4;

    /**
     * A tree or plant shape: its trunk and crown materials, the trunk's
     * height, and a crown of {@code crownHeight + 1} rows around the trunk's
     * top, of radius {@code radius}, shaped by {@code shape}: {@link #BLOB}
     * (rounded rows narrowing at the top), {@link #CONE}, {@link #TIERED},
     * {@link #FLAT} or {@link #COLUMN}. {@code snow}, -1 for none, lies on the top of each
     * crown column (vanilla's freeze_top_layer, where the crown is cold
     * enough to snow); columns take none.
     */
    public record TreeKind(int trunk, int leaves, int height, int radius, int crownHeight, int shape, int snow) {

        /** A kind without snow. */
        public TreeKind(int trunk, int leaves, int height, int radius, int crownHeight, int shape) {
            this(trunk, leaves, height, radius, crownHeight, shape, -1);
        }
    }

    /**
     * A building of a village: walls of {@code wall} over blocks [x, x +
     * width) x [floor, top) x [z, z + depth) of the tile (x and z may leave
     * the tile by a few blocks), and a roof layer of {@code roof} at {@code top}.
     */
    public record Building(int x, int z, int width, int depth, int floor, int top, int wall, int roof) {
    }

    private final java.util.List<Building> buildings = new java.util.ArrayList<>();

    public void addBuilding(Building b) {
        buildings.add(b);
    }

    /** The buildings whose corner lies in this tile. Do not modify. */
    public java.util.List<Building> buildings() {
        return buildings;
    }

    /** The index of a kind, added when new; -1 when the tile holds {@link #MAX_KINDS} already. */
    public int kind(TreeKind kind) {
        int i = kinds.indexOf(kind);
        if (i >= 0) {
            return i;
        }
        if (kinds.size() >= MAX_KINDS) {
            return -1;
        }
        kinds.add(kind);
        return kinds.size() - 1;
    }

    public TreeKind treeKind(int index) {
        return kinds.get(index);
    }

    /** Adds a tree at block (bx, bz) of the tile. */
    public void addTree(int bx, int bz, int kind) {
        int c = (bz >> 4) * CHUNKS + (bx >> 4);
        int[] list = trees[c];
        int[] grown = list == null ? new int[1] : java.util.Arrays.copyOf(list, list.length + 1);
        grown[grown.length - 1] = (bx & 15) | (bz & 15) << 4 | kind << 8;
        trees[c] = grown;
    }

    /** The trees of a chunk of the tile, each {@code x | z << 4 | kind << 8}; never null. */
    public int[] trees(int chunkX, int chunkZ) {
        int[] list = trees[chunkZ * CHUNKS + chunkX];
        return list == null ? NO_TREES : list;
    }

    private static final int[] NO_TREES = new int[0];

    /** The index of a plant material, added when new; -1 when the tile holds {@link #MAX_PLANT_KINDS} already. */
    public int plantKind(int material) {
        int i = plantKinds.indexOf(material);
        if (i >= 0) {
            return i;
        }
        if (plantKinds.size() >= MAX_PLANT_KINDS) {
            return -1;
        }
        plantKinds.add(material);
        return plantKinds.size() - 1;
    }

    public int plantMaterial(int kind) {
        return plantKinds.get(kind);
    }

    /** Sets a chunk's plants, each {@code kind | sixteenths << 8} ({@link #plantEntry}). */
    public void setPlants(int chunkX, int chunkZ, int[] entries) {
        plants[chunkZ * CHUNKS + chunkX] = entries.length == 0 ? null : entries.clone();
    }

    /** The plants of a chunk of the tile, each {@code kind | sixteenths << 8}; never null. */
    public int[] plants(int chunkX, int chunkZ) {
        int[] list = plants[chunkZ * CHUNKS + chunkX];
        return list == null ? NO_TREES : list;
    }

    /** A chunk's plant entry: a kind and its mean count per chunk. */
    public static int plantEntry(int kind, double perChunk) {
        return kind | (int) Math.min(0xFFFFFF, Math.round(perChunk * 16)) << 8;
    }

    public void setPlantGround(int material) {
        plantGround = material;
    }

    /** The top material plants grow on; -1 for none. */
    public int plantGround() {
        return plantGround;
    }

    public int grid() {
        return grid;
    }

    public void setGrid(int blocks) {
        grid = blocks;
    }

    /**
     * Fills every sample between those on multiples of {@code step} (in
     * samples; {@code SAMPLES - 1} must be a multiple of it) bilinearly,
     * rounded to the nearest block.
     */
    public void fillBetweenSamples(int step) {
        if (step <= 1) {
            return;
        }
        for (int k = 0; k < SAMPLES; k++) {
            int k0 = k / step * step;
            int k1 = Math.min(k0 + step, SAMPLES - 1);
            for (int i = 0; i < SAMPLES; i++) {
                if (i % step == 0 && k % step == 0) {
                    continue;
                }
                int i0 = i / step * step;
                int i1 = Math.min(i0 + step, SAMPLES - 1);
                float u = i1 == i0 ? 0 : (i - i0) / (float) (i1 - i0);
                float v = k1 == k0 ? 0 : (k - k0) / (float) (k1 - k0);
                float top = sample(i0, k0) * (1 - u) + sample(i1, k0) * u;
                float bottom = sample(i0, k1) * (1 - u) + sample(i1, k1) * u;
                setHeight(i, k, Math.round(top * (1 - v) + bottom * v));
            }
        }
    }

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

    /** The material of the frozen sea's top block; -1: none. */
    public void setIce(int material) {
        ice = material;
    }

    public int ice() {
        return ice;
    }

    public void setIceberg(int material) {
        iceberg = material;
    }

    /** The material of icebergs: cells whose filler it is take it down to the bottom, not {@link #below()}. */
    public int iceberg() {
        return iceberg;
    }

    /** Whether the sea's top block is {@link #ice()} over a cell (vanilla's freeze_top_layer). */
    public void setFrozen(int cx, int cz, boolean value) {
        int i = cz * CELLS + cx;
        if (value) {
            frozen[i >> 6] |= 1L << i;
        } else {
            frozen[i >> 6] &= ~(1L << i);
        }
    }

    public boolean frozen(int cx, int cz) {
        int i = cz * CELLS + cx;
        return ice >= 0 && (frozen[i >> 6] >>> i & 1) != 0;
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
        for (int cz = 0; cz < CHUNKS; cz++) {
            for (int cx = 0; cx < CHUNKS; cx++) {
                for (int t : trees(cx, cz)) {
                    TreeKind k = kinds.get(t >>> 8);
                    // The first free block above the crown, or above its snow.
                    int topY = height(cx * 16 + (t & 15), cz * 16 + (t >> 4 & 15)) + k.height()
                            + (k.snow() >= 0 ? 2 : 1);
                    for (int nz = Math.max(0, cz - 1); nz <= Math.min(CHUNKS - 1, cz + 1); nz++) {
                        for (int nx = Math.max(0, cx - 1); nx <= Math.min(CHUNKS - 1, cx + 1); nx++) {
                            int i = nz * CHUNKS + nx;
                            max[i] = (short) Math.max(max[i], topY);
                        }
                    }
                }
            }
        }
        for (Building b : buildings) {
            for (int cz = Math.max(0, (b.z() >> 4) - 1); cz <= Math.min(CHUNKS - 1, ((b.z() + b.depth()) >> 4) + 1); cz++) {
                for (int cx = Math.max(0, (b.x() >> 4) - 1); cx <= Math.min(CHUNKS - 1, ((b.x() + b.width()) >> 4) + 1); cx++) {
                    int i = cz * CHUNKS + cx;
                    max[i] = (short) Math.max(max[i], b.top() + 1);
                    min[i] = (short) Math.min(min[i], b.floor());
                }
            }
        }
        return new Summary(min, max, water >= 0 ? seaLevel : Integer.MIN_VALUE, grid);
    }

    /**
     * What decides which sections of a tile hold something: per chunk the
     * lowest and highest surface; the sea level, or {@code Integer.MIN_VALUE}
     * without sea; the grid its heights were computed on ({@link #grid()}).
     */
    public record Summary(short[] chunkMin, short[] chunkMax, int sea, int grid) {

        /**
         * Whether blocks [y0, y1) may hold voxels over chunks [cx0, cx1] x
         * [cz0, cz1] of the tile: between one voxel ({@code voxel} blocks)
         * under the lowest surface nearby (a chunk around) and the highest
         * surface or the sea.
         */
        public boolean spans(int cx0, int cz0, int cx1, int cz1, int y0, int y1, int voxel) {
            return spans(cx0, cz0, cx1, cz1, y0, y1, voxel, Integer.MAX_VALUE);
        }

        /**
         * Like {@link #spans(int, int, int, int, int, int, int)}, the columns
         * going down to {@code lowest} at least (a preview column beside a
         * real sea goes down to the real floor).
         */
        public boolean spans(int cx0, int cz0, int cx1, int cz1, int y0, int y1, int voxel, int lowest) {
            int lo = lowest;
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
        out.writeVarInt(grid);
        out.writeVarInt(below);
        out.writeVarInt(water + 1);
        out.writeInt(seaLevel);
        out.writeVarInt(ice + 1);
        out.writeVarInt(iceberg + 1);
        for (long bits : frozen) {
            out.writeLong(bits);
        }
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
        out.writeVarInt(kinds.size());
        for (TreeKind k : kinds) {
            out.writeVarInt(k.trunk());
            out.writeVarInt(k.leaves());
            out.writeVarInt(k.height());
            out.writeVarInt(k.radius());
            out.writeVarInt(k.crownHeight());
            out.writeVarInt(k.shape());
            out.writeVarInt(k.snow() + 1);
        }
        for (int[] list : trees) {
            int n = list == null ? 0 : list.length;
            out.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                out.writeVarInt(list[i]);
            }
        }
        out.writeVarInt(buildings.size());
        for (Building b : buildings) {
            out.writeInt(b.x());
            out.writeInt(b.z());
            out.writeVarInt(b.width());
            out.writeVarInt(b.depth());
            out.writeInt(b.floor());
            out.writeInt(b.top());
            out.writeVarInt(b.wall());
            out.writeVarInt(b.roof());
        }
        out.writeVarInt(plantGround + 1);
        out.writeVarInt(plantKinds.size());
        for (int material : plantKinds) {
            out.writeVarInt(material);
        }
        for (int[] list : plants) {
            int n = list == null ? 0 : list.length;
            out.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                out.writeVarInt(list[i]);
            }
        }
    }

    public static PreviewTile decode(ByteReader in) {
        int format = in.readVarInt();
        if (format != FORMAT) {
            throw new IllegalArgumentException("unknown preview tile format " + format);
        }
        PreviewTile t = new PreviewTile();
        t.grid = in.readVarInt();
        t.below = in.readVarInt();
        t.water = in.readVarInt() - 1;
        t.seaLevel = in.readInt();
        t.ice = in.readVarInt() - 1;
        t.iceberg = in.readVarInt() - 1;
        for (int i = 0; i < t.frozen.length; i++) {
            t.frozen[i] = in.readLong();
        }
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
        int kinds = in.readVarInt();
        for (int i = 0; i < kinds; i++) {
            t.kinds.add(new TreeKind(in.readVarInt(), in.readVarInt(), in.readVarInt(), in.readVarInt(),
                    in.readVarInt(), in.readVarInt(), in.readVarInt() - 1));
        }
        for (int c = 0; c < t.trees.length; c++) {
            int n = in.readVarInt();
            if (n > 0) {
                int[] list = new int[n];
                for (int i = 0; i < n; i++) {
                    list[i] = in.readVarInt();
                }
                t.trees[c] = list;
            }
        }
        int buildings = in.readVarInt();
        for (int i = 0; i < buildings; i++) {
            t.buildings.add(new Building(in.readInt(), in.readInt(), in.readVarInt(), in.readVarInt(), in.readInt(),
                    in.readInt(), in.readVarInt(), in.readVarInt()));
        }
        t.plantGround = in.readVarInt() - 1;
        int plantKinds = in.readVarInt();
        for (int i = 0; i < plantKinds; i++) {
            t.plantKinds.add(in.readVarInt());
        }
        for (int c = 0; c < t.plants.length; c++) {
            int n = in.readVarInt();
            if (n > 0) {
                int[] list = new int[n];
                for (int i = 0; i < n; i++) {
                    list[i] = in.readVarInt();
                }
                t.plants[c] = list;
            }
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
