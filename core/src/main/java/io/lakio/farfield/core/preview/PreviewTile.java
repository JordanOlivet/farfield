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
 * and the ground they grow on (ADR-060), and its plants under the sea
 * (kelp, seagrass), as kinds, columns and mean heights (ADR-105); the blocks the world's own
 * features set above the ground (ADR-079: trees, rocks, every mod's), as
 * {@link #FEATURE_VOXEL}-block voxels in runs per column, over the tile and
 * a {@link #FEATURE_MARGIN}-block margin around it (a crown spreading out
 * of the tile).
 *
 * <p>Tiles come in levels (the pyramid of the coarse-to-fine study,
 * ADR-126): a tile of {@link #level()} L covers {@code BLOCKS << L} blocks
 * a side with the same samples, cells and chunk entries, each {@code 1 << L}
 * times wider. Level 0 carries the world's own features; a coarser tile
 * carries a canopy per cell instead ({@link #canopyWord}), from what the
 * features grow in its biome. Readers see level-0 tiles only: a coarser
 * one is cut into them ({@link #expand}), its canopy turned into feature
 * runs.
 *
 * <p>A tile is built a quarter at a time (ADR-127), each 64 samples a side
 * (256 blocks at level 0), nearest the player first: {@link #quarters()}
 * tells which are built and which are complete (a coarse quarter waiting
 * for its biomes' canopy is built, not complete). Readers see a quarter
 * only once built; until then the coarser tile over it.
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
     * against generated chunks), 11 the world's own features instead of the
     * guessed trees, 12 vines-like plants as plants and nothing LODs leave
     * out among the features (ADR-080), 13 the trees of snowy forests
     * (ADR-086), 14 and 15 the ground from the surface rules (ADR-101,
     * ADR-104), 16 the plants under the sea (ADR-105), 17 the light sources
     * of the features (ADR-117), 18 the lakes they dig (ADR-118); older
     * tiles are built again; 19 the levels of the pyramid, the canopy, and
     * no far grid any more (ADR-126); 20 built by quarters (ADR-127).
     */
    private static final int FORMAT = 20;
    /** Blocks a side of a feature voxel. */
    public static final int FEATURE_VOXEL = 2;
    /** Blocks around the tile its features may reach. */
    public static final int FEATURE_MARGIN = 16;
    /** Feature columns a side: the tile and its margin. */
    public static final int FEATURE_SIDE = (BLOCKS + 2 * FEATURE_MARGIN) / FEATURE_VOXEL;
    /** Most plant kinds a tile holds (the kind rides in 8 bits of a chunk's plant entry). */
    public static final int MAX_PLANT_KINDS = 255;
    /** Most tree kinds a tile holds (the kind rides in 8 bits of a tree). */
    public static final int MAX_KINDS = 255;

    private final short[] heights = new short[SAMPLES * SAMPLES];
    /** The pyramid's level: the tile covers {@code BLOCKS << level} blocks a side. */
    private int level;
    /** Bits 0 to 3: quarter q built; bits 4 to 7: quarter q complete ({@link #quarters()}). */
    private int quarters = ALL_QUARTERS;
    /** Per cell, its canopy ({@link #canopyWord}), 0 for none. */
    private final int[] canopy = new int[CELLS * CELLS];
    /** The snow on canopies that carry it; -1 for none. */
    private int canopySnow = -1;
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
    /** Per chunk, its plants under the sea ({@link #seaPlantEntry}). */
    private final int[][] seaPlants = new int[CHUNKS * CHUNKS][];
    /** The top material plants grow on; -1 for none. */
    private int plantGround = -1;
    /**
     * Per feature column ({@code fz * FEATURE_SIDE + fx}), its runs bottom up,
     * two ints each: {@code voxelY << 8 | voxels} (voxelY in {@link
     * #FEATURE_VOXEL}-block units, may be negative), then the material; null
     * for none.
     */
    private final int[][] features = new int[FEATURE_SIDE * FEATURE_SIDE][];
    /** Light sources of the features (ADR-117): pairs of {@link #lightWord} and block y. */
    private int[] lights = new int[0];
    /** The columns the features dug (ADR-118): {@link #PIT_INTS} ints each, sorted by {@link #pitWord}. */
    private int[] pits = new int[0];

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

    /**
     * Sets the feature runs of column (fx, fz), its west and north side at
     * block {@code fx * FEATURE_VOXEL - FEATURE_MARGIN} of the tile; runs as
     * {@link #features} holds them, null or empty for none.
     */
    public void setFeatureColumn(int fx, int fz, int[] runs) {
        features[fz * FEATURE_SIDE + fx] = runs == null || runs.length == 0 ? null : runs.clone();
    }

    /** The feature runs of column (fx, fz) ({@link #setFeatureColumn}); never null. */
    public int[] featureColumn(int fx, int fz) {
        int[] runs = features[fz * FEATURE_SIDE + fx];
        return runs == null ? NO_TREES : runs;
    }

    /** A run's word: its first voxel's y (in voxels) and its length (1 to 255 voxels). */
    public static int featureRun(int voxelY, int voxels) {
        return voxelY << 8 | voxels;
    }

    /** The lowest block of a run. */
    public static int featureRunY(int word) {
        return (word >> 8) * FEATURE_VOXEL;
    }

    /** The blocks a run spans upward. */
    public static int featureRunBlocks(int word) {
        return (word & 0xFF) * FEATURE_VOXEL;
    }

    /**
     * Sets the features' light sources: pairs of {@link #lightWord} and
     * block y, one per block, with the block's light emission (1 to 15).
     */
    public void setLights(int[] pairs) {
        lights = pairs == null ? new int[0] : pairs.clone();
    }

    /** The features' light sources ({@link #setLights}); never null. Do not modify. */
    public int[] lights() {
        return lights;
    }

    /**
     * A light source's word: block (x, z) of the tile ({@code -FEATURE_MARGIN}
     * to {@code BLOCKS + FEATURE_MARGIN - 1}) and its emission (1 to 15).
     */
    public static int lightWord(int x, int z, int emission) {
        return (x + FEATURE_MARGIN) | (z + FEATURE_MARGIN) << 10 | emission << 20;
    }

    public static int lightX(int word) {
        return (word & 0x3FF) - FEATURE_MARGIN;
    }

    public static int lightZ(int word) {
        return (word >> 10 & 0x3FF) - FEATURE_MARGIN;
    }

    public static int lightEmission(int word) {
        return word >> 20 & 15;
    }

    /** Ints per dug column in {@link #pits()}: {@link #pitWord}, bottom, fluid top, fluid material. */
    public static final int PIT_INTS = 4;

    /**
     * Sets the columns the features dug into the ground (ADR-118), {@link
     * #PIT_INTS} ints each: {@link #pitWord}, the lowest dug block's y, the
     * first block above the fluid that fills it from the bottom (the bottom
     * itself without fluid), and the fluid's material (-1 for none). Above
     * the fluid the column is open up to the tile's surface. One entry per
     * column; any order.
     */
    public void setPits(int[] entries) {
        if (entries == null || entries.length % PIT_INTS != 0) {
            throw new IllegalArgumentException("pit entries come in " + PIT_INTS + " ints");
        }
        pits = sortedPits(entries.clone());
    }

    /** The dug columns ({@link #setPits}), sorted by {@link #pitWord}; never null. Do not modify. */
    public int[] pits() {
        return pits;
    }

    /**
     * The index in {@link #pits()} of block column (x, z) of the tile
     * ({@code -FEATURE_MARGIN} to {@code BLOCKS + FEATURE_MARGIN - 1}), or
     * -1 when the features dug nothing there.
     */
    public int pit(int x, int z) {
        if (pits.length == 0) {
            return -1;
        }
        int key = pitWord(x, z);
        int lo = 0;
        int hi = pits.length / PIT_INTS - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int word = pits[mid * PIT_INTS];
            if (word < key) {
                lo = mid + 1;
            } else if (word > key) {
                hi = mid - 1;
            } else {
                return mid * PIT_INTS;
            }
        }
        return -1;
    }

    /** A dug column's word: block (x, z) of the tile, {@code -FEATURE_MARGIN} to {@code BLOCKS + FEATURE_MARGIN - 1}. */
    public static int pitWord(int x, int z) {
        return (z + FEATURE_MARGIN) << 10 | (x + FEATURE_MARGIN);
    }

    public static int pitX(int word) {
        return (word & 0x3FF) - FEATURE_MARGIN;
    }

    public static int pitZ(int word) {
        return (word >> 10 & 0x3FF) - FEATURE_MARGIN;
    }

    private static int[] sortedPits(int[] entries) {
        int n = entries.length / PIT_INTS;
        long[] order = new long[n];
        for (int i = 0; i < n; i++) {
            order[i] = (long) entries[i * PIT_INTS] << 32 | i;
        }
        java.util.Arrays.sort(order);
        int[] sorted = new int[entries.length];
        for (int i = 0; i < n; i++) {
            int from = (int) order[i] * PIT_INTS;
            if (i > 0 && order[i] >>> 32 == order[i - 1] >>> 32) {
                throw new IllegalArgumentException("two pits in one column");
            }
            System.arraycopy(entries, from, sorted, i * PIT_INTS, PIT_INTS);
        }
        return sorted;
    }

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

    /** Sets a chunk's plants under the sea, each a {@link #seaPlantEntry}. */
    public void setSeaPlants(int chunkX, int chunkZ, int[] entries) {
        seaPlants[chunkZ * CHUNKS + chunkX] = entries.length == 0 ? null : entries.clone();
    }

    /** The plants under the sea of a chunk of the tile ({@link #seaPlantEntry}); never null. */
    public int[] seaPlants(int chunkX, int chunkZ) {
        int[] list = seaPlants[chunkZ * CHUNKS + chunkX];
        return list == null ? NO_TREES : list;
    }

    /**
     * A chunk's entry of plants under the sea: a kind (its material is the
     * column's lowest block), the columns it stands in per chunk, and their
     * mean height in blocks (kelp grows tall), {@code kind | height << 8 |
     * sixteenths << 14}.
     */
    public static int seaPlantEntry(int kind, double columnsPerChunk, double meanHeight) {
        int height = (int) Math.max(1, Math.min(63, Math.round(meanHeight)));
        return kind | height << 8 | (int) Math.min(0x3FFFF, Math.round(columnsPerChunk * 16)) << 14;
    }

    public static int seaPlantKind(int entry) {
        return entry & 0xFF;
    }

    public static int seaPlantHeight(int entry) {
        return entry >>> 8 & 63;
    }

    /** Columns per chunk of a {@link #seaPlantEntry}. */
    public static double seaPlantColumns(int entry) {
        return (entry >>> 14) / 16.0;
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

    /** The pyramid's level: the tile covers {@code BLOCKS << level} blocks a side, its samples {@code GRID << level} apart. */
    public int level() {
        return level;
    }

    public void setLevel(int level) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("tile level " + level);
        }
        this.level = level;
    }

    /** The coarsest level a tile may have: a level-15 tile spans 16,777,216 blocks. */
    public static final int MAX_LEVEL = 15;

    /** Every quarter built and complete. */
    public static final int ALL_QUARTERS = 0xFF;
    /** Samples a side of a quarter (it shares its last row and column with the next one). */
    public static final int QUARTER_SAMPLES = (SAMPLES - 1) / 2;

    /**
     * Which quarters are built (bit {@code q}) and complete (bit {@code q +
     * 4}); quarter {@code q = qz * 2 + qx} covers samples {@code qx * 64} to
     * {@code qx * 64 + 64} along X, cells and chunks likewise.
     */
    public int quarters() {
        return quarters;
    }

    public void setQuarters(int bits) {
        quarters = bits & ALL_QUARTERS;
    }

    public boolean built(int quarter) {
        return (quarters >> quarter & 1) != 0;
    }

    public boolean quarterComplete(int quarter) {
        return (quarters >> (quarter + 4) & 1) != 0;
    }

    /** Marks a quarter built, and complete or not. */
    public void setQuarter(int quarter, boolean complete) {
        quarters |= 1 << quarter;
        quarters = complete ? quarters | 1 << (quarter + 4) : quarters & ~(1 << (quarter + 4));
    }

    /** Every quarter built and complete. */
    public boolean complete() {
        return quarters == ALL_QUARTERS;
    }

    /** Every quarter built, and all complete or none. */
    public void setComplete(boolean value) {
        quarters = value ? ALL_QUARTERS : 0x0F;
    }

    /**
     * Copies quarter {@code q} of a level-0 tile (a view) into this one: its
     * samples (the shared edges included), cells, chunks with their plants
     * and trees by material, the feature columns over its blocks (and the
     * margin beside it at the tile's edges), the buildings, light sources
     * and dug columns whose place lies in it.
     */
    public void copyQuarter(PreviewTile from, int q) {
        int qx = q & 1;
        int qz = q >> 1;
        int s0x = qx * QUARTER_SAMPLES;
        int s0z = qz * QUARTER_SAMPLES;
        for (int k = s0z; k <= s0z + QUARTER_SAMPLES; k++) {
            for (int i = s0x; i <= s0x + QUARTER_SAMPLES; i++) {
                setHeight(i, k, from.sample(i, k));
            }
        }
        for (int cz = s0z; cz < s0z + QUARTER_SAMPLES; cz++) {
            for (int cx = s0x; cx < s0x + QUARTER_SAMPLES; cx++) {
                int i = cz * CELLS + cx;
                top[i] = from.top[i];
                filler[i] = from.filler[i];
                canopy[i] = from.canopy[i];
                frozen[i >> 6] = (frozen[i >> 6] & ~(1L << i)) | (from.frozen[i >> 6] & 1L << i);
            }
        }
        int c0x = qx * CHUNKS / 2;
        int c0z = qz * CHUNKS / 2;
        for (int cz = c0z; cz < c0z + CHUNKS / 2; cz++) {
            for (int cx = c0x; cx < c0x + CHUNKS / 2; cx++) {
                int c = cz * CHUNKS + cx;
                biomes[c] = from.biomes[c];
                plants[c] = remapPlants(from, from.plants[c], false);
                seaPlants[c] = remapPlants(from, from.seaPlants[c], true);
                trees[c] = remapTrees(from, from.trees[c]);
            }
        }
        int half = BLOCKS / 2;
        int bx0 = qx * half;
        int bz0 = qz * half;
        int bx1 = bx0 + half;
        int bz1 = bz0 + half;
        // The tile's margin beside an edge quarter is that quarter's.
        int mx0 = qx == 0 ? -FEATURE_MARGIN : bx0;
        int mz0 = qz == 0 ? -FEATURE_MARGIN : bz0;
        int mx1 = qx == 1 ? BLOCKS + FEATURE_MARGIN : bx1;
        int mz1 = qz == 1 ? BLOCKS + FEATURE_MARGIN : bz1;
        for (int fz = 0; fz < FEATURE_SIDE; fz++) {
            int bz = fz * FEATURE_VOXEL - FEATURE_MARGIN;
            if (bz < mz0 || bz >= mz1) {
                continue;
            }
            for (int fx = 0; fx < FEATURE_SIDE; fx++) {
                int bx = fx * FEATURE_VOXEL - FEATURE_MARGIN;
                if (bx >= mx0 && bx < mx1) {
                    int c = fz * FEATURE_SIDE + fx;
                    features[c] = from.features[c] == null ? null : from.features[c].clone();
                }
            }
        }
        buildings.removeIf(b -> b.x() >= bx0 && b.x() < bx1 && b.z() >= bz0 && b.z() < bz1);
        for (Building b : from.buildings) {
            if (b.x() >= bx0 && b.x() < bx1 && b.z() >= bz0 && b.z() < bz1) {
                buildings.add(b);
            }
        }
        java.util.function.IntPredicate inside = word -> {
            int x = lightX(word);
            int z = lightZ(word);
            return x >= mx0 && x < mx1 && z >= mz0 && z < mz1;
        };
        Ints kept = new Ints();
        for (int i = 0; i < lights.length; i += 2) {
            if (!inside.test(lights[i])) {
                kept.add(lights[i]);
                kept.add(lights[i + 1]);
            }
        }
        for (int i = 0; i < from.lights.length; i += 2) {
            if (inside.test(from.lights[i])) {
                kept.add(from.lights[i]);
                kept.add(from.lights[i + 1]);
            }
        }
        lights = kept.toIntArray();
        kept.clear();
        for (int i = 0; i < pits.length; i += PIT_INTS) {
            int x = pitX(pits[i]);
            int z = pitZ(pits[i]);
            if (!(x >= mx0 && x < mx1 && z >= mz0 && z < mz1)) {
                kept.addAll(pits, i, PIT_INTS);
            }
        }
        for (int i = 0; i < from.pits.length; i += PIT_INTS) {
            int x = pitX(from.pits[i]);
            int z = pitZ(from.pits[i]);
            if (x >= mx0 && x < mx1 && z >= mz0 && z < mz1) {
                kept.addAll(from.pits, i, PIT_INTS);
            }
        }
        pits = sortedPits(kept.toIntArray());
    }

    /** Takes the tile-wide values of another tile: its ground, sea, ice, plant ground, canopy snow. */
    public void copyTileValues(PreviewTile from) {
        below = from.below;
        water = from.water;
        seaLevel = from.seaLevel;
        ice = from.ice;
        iceberg = from.iceberg;
        plantGround = from.plantGround;
        canopySnow = from.canopySnow;
    }

    /** A chunk's plant entries of another tile with this tile's kinds; null for none. */
    private int[] remapPlants(PreviewTile from, int[] entries, boolean sea) {
        if (entries == null) {
            return null;
        }
        int[] out = new int[entries.length];
        int n = 0;
        for (int e : entries) {
            int kind = plantKind(from.plantMaterial(e & 0xFF));
            if (kind >= 0) {
                out[n++] = e & ~0xFF | kind;
            }
        }
        return n == 0 ? null : java.util.Arrays.copyOf(out, n);
    }

    private int[] remapTrees(PreviewTile from, int[] list) {
        if (list == null) {
            return null;
        }
        int[] out = new int[list.length];
        int n = 0;
        for (int t : list) {
            int kind = kind(from.treeKind(t >>> 8));
            if (kind >= 0) {
                out[n++] = t & 0xFF | kind << 8;
            }
        }
        return n == 0 ? null : java.util.Arrays.copyOf(out, n);
    }

    /**
     * Adds runs to column (fx, fz): where the column has runs already (a
     * crown a neighbor quarter spilled over), every voxel of both is kept,
     * the new run's material where both have one.
     */
    public void mergeFeatureColumn(int fx, int fz, int[] runs) {
        int c = fz * FEATURE_SIDE + fx;
        int[] old = features[c];
        if (old == null || runs == null || runs.length == 0) {
            setFeatureColumn(fx, fz, runs == null || runs.length == 0 ? old : runs);
            return;
        }
        java.util.TreeMap<Integer, Integer> voxels = new java.util.TreeMap<>();
        for (int[] list : new int[][]{old, runs}) {
            for (int r = 0; r < list.length; r += 2) {
                int y = list[r] >> 8;
                for (int v = 0; v < (list[r] & 0xFF); v++) {
                    voxels.put(y + v, list[r + 1]);
                }
            }
        }
        Ints out = new Ints();
        int runY = 0;
        int length = 0;
        int material = -1;
        for (var e : voxels.entrySet()) {
            if (length > 0 && e.getKey() == runY + length && e.getValue() == material && length < 255) {
                length++;
                continue;
            }
            if (length > 0) {
                out.add(featureRun(runY, length));
                out.add(material);
            }
            runY = e.getKey();
            length = 1;
            material = e.getValue();
        }
        out.add(featureRun(runY, length));
        out.add(material);
        features[c] = out.toIntArray();
    }

    /** Adds light sources ({@link #setLights} pairs); a place already there keeps the brighter. */
    public void addLights(int[] pairs) {
        java.util.Map<Long, Integer> all = new java.util.HashMap<>();
        for (int[] list : new int[][]{lights, pairs}) {
            for (int i = 0; i < list.length; i += 2) {
                long place = (long) (list[i] & 0xFFFFF) << 32 | (list[i + 1] & 0xFFFFFFFFL);
                all.merge(place, lightEmission(list[i]), Math::max);
            }
        }
        int[] out = new int[all.size() * 2];
        int n = 0;
        for (var e : all.entrySet()) {
            out[n++] = (int) (e.getKey() >>> 32) | e.getValue() << 20;
            out[n++] = (int) (long) e.getKey();
        }
        lights = out;
    }

    /** A growable list of ints. */
    private static final class Ints {
        private int[] data = new int[16];
        private int size;

        void add(int v) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, size * 2);
            }
            data[size++] = v;
        }

        void addAll(int[] from, int at, int n) {
            for (int i = 0; i < n; i++) {
                add(from[at + i]);
            }
        }

        void clear() {
            size = 0;
        }

        int[] toIntArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    /** Adds dug columns ({@link #setPits} entries); a column already there takes the new entry. */
    public void addPits(int[] entries) {
        if (entries == null || entries.length % PIT_INTS != 0) {
            throw new IllegalArgumentException("pit entries come in " + PIT_INTS + " ints");
        }
        java.util.Map<Integer, int[]> all = new java.util.HashMap<>();
        for (int[] list : new int[][]{pits, entries}) {
            for (int i = 0; i < list.length; i += PIT_INTS) {
                all.put(list[i], java.util.Arrays.copyOfRange(list, i, i + PIT_INTS));
            }
        }
        int[] out = new int[all.size() * PIT_INTS];
        int n = 0;
        for (int[] e : all.values()) {
            System.arraycopy(e, 0, out, n, PIT_INTS);
            n += PIT_INTS;
        }
        pits = sortedPits(out);
    }

    /**
     * A cell's canopy (ADR-126): the top of what the features grow there,
     * {@code top} blocks above the ground, {@code thickness} blocks of
     * {@code material} under that top (1 to 15), with the tile's {@link
     * #canopySnow()} over it when {@code snow}.
     */
    public static int canopyWord(int material, int top, int thickness, boolean snow) {
        if (material < 0 || material >= 0xFFFFF) {
            throw new IllegalArgumentException("canopy material " + material);
        }
        return (material + 1) | Math.max(1, Math.min(63, top)) << 20 | Math.max(1, Math.min(15, thickness)) << 26
                | (snow ? 1 << 30 : 0);
    }

    public static int canopyMaterial(int word) {
        return (word & 0xFFFFF) - 1;
    }

    public static int canopyTop(int word) {
        return word >>> 20 & 63;
    }

    public static int canopyThickness(int word) {
        return word >>> 26 & 15;
    }

    public static boolean canopySnowy(int word) {
        return (word >>> 30 & 1) != 0;
    }

    /** Sets a cell's canopy ({@link #canopyWord}), 0 for none. */
    public void setCanopy(int cx, int cz, int word) {
        canopy[cz * CELLS + cx] = word;
    }

    public int canopy(int cx, int cz) {
        return canopy[cz * CELLS + cx];
    }

    public boolean hasCanopy() {
        for (int w : canopy) {
            if (w != 0) {
                return true;
            }
        }
        return false;
    }

    /** Drops every cell's canopy: the features grow next. */
    public void clearCanopy() {
        java.util.Arrays.fill(canopy, 0);
    }

    /** Drops every chunk's plants: the features grow next. */
    public void clearPlants() {
        java.util.Arrays.fill(plants, null);
    }

    public void setCanopySnow(int material) {
        canopySnow = material;
    }

    /** The material of the snow over snowy canopies; -1 for none. */
    public int canopySnow() {
        return canopySnow;
    }

    /**
     * The level-0 tile at offset ({@code ox}, {@code oz}) inside this one, 0
     * to {@code (1 << level) - 1} each (this tile itself at level 0): its
     * samples interpolated from this tile's, its cells, chunks and canopy
     * those it lies in, the canopy turned into feature runs ({@link
     * #growCanopy}). Neither this tile nor its arrays are shared with it.
     */
    public PreviewTile expand(int ox, int oz) {
        int scale = 1 << level;
        if (ox < 0 || oz < 0 || ox >= scale || oz >= scale) {
            throw new IllegalArgumentException("offset " + ox + ", " + oz + " in a level-" + level + " tile");
        }
        PreviewTile t = new PreviewTile();
        int bx0 = ox * BLOCKS;
        int bz0 = oz * BLOCKS;
        expandHeights(t, bx0, bz0);
        t.below = below;
        t.water = water;
        t.seaLevel = seaLevel;
        t.ice = ice;
        t.iceberg = iceberg;
        t.plantGround = plantGround;
        t.canopySnow = canopySnow;
        t.plantKinds.addAll(plantKinds);
        int cellShift = Integer.numberOfTrailingZeros(GRID) + level;
        for (int cz = 0; cz < CELLS; cz++) {
            int from = ((bz0 + cz * GRID) >> cellShift) * CELLS;
            for (int cx = 0; cx < CELLS; cx++) {
                int c = from + ((bx0 + cx * GRID) >> cellShift);
                int i = cz * CELLS + cx;
                t.top[i] = top[c];
                t.filler[i] = filler[c];
                t.canopy[i] = canopy[c];
                if ((frozen[c >> 6] >>> c & 1) != 0) {
                    t.frozen[i >> 6] |= 1L << i;
                }
            }
        }
        int chunkShift = 4 + level;
        for (int cz = 0; cz < CHUNKS; cz++) {
            for (int cx = 0; cx < CHUNKS; cx++) {
                int c = ((bz0 + cz * 16) >> chunkShift) * CHUNKS + ((bx0 + cx * 16) >> chunkShift);
                int i = cz * CHUNKS + cx;
                t.biomes[i] = biomes[c];
                t.plants[i] = plants[c] == null ? null : plants[c].clone();
                t.seaPlants[i] = seaPlants[c] == null ? null : seaPlants[c].clone();
            }
        }
        if (level == 0) {
            t.kinds.addAll(kinds);
            for (int c = 0; c < trees.length; c++) {
                t.trees[c] = trees[c] == null ? null : trees[c].clone();
            }
            t.buildings.addAll(buildings);
            for (int c = 0; c < features.length; c++) {
                t.features[c] = features[c] == null ? null : features[c].clone();
            }
            t.lights = lights.clone();
            t.pits = pits.clone();
        }
        t.growCanopy();
        return t;
    }

    /**
     * The {@link #summary()} of the level-0 tile {@link #expand} would cut at
     * offset ({@code ox}, {@code oz}), without growing its canopy into runs:
     * a section's existence asks it on the render thread.
     */
    public Summary expandedSummary(int ox, int oz) {
        int scale = 1 << level;
        if (ox < 0 || oz < 0 || ox >= scale || oz >= scale) {
            throw new IllegalArgumentException("offset " + ox + ", " + oz + " in a level-" + level + " tile");
        }
        PreviewTile t = new PreviewTile();
        int bx0 = ox * BLOCKS;
        int bz0 = oz * BLOCKS;
        expandHeights(t, bx0, bz0);
        t.water = water;
        t.seaLevel = seaLevel;
        t.canopySnow = canopySnow;
        int cellShift = Integer.numberOfTrailingZeros(GRID) + level;
        for (int cz = 0; cz < CELLS; cz++) {
            int from = ((bz0 + cz * GRID) >> cellShift) * CELLS;
            for (int cx = 0; cx < CELLS; cx++) {
                t.canopy[cz * CELLS + cx] = canopy[from + ((bx0 + cx * GRID) >> cellShift)];
            }
        }
        return t.summary();
    }

    /** Sets the samples of {@code t} from this tile's, its corner at block (bx0, bz0) of this one. */
    private void expandHeights(PreviewTile t, int bx0, int bz0) {
        int span = GRID << level;
        int area = span * span;
        for (int k = 0; k < SAMPLES; k++) {
            int pz = bz0 + k * GRID;
            int ck = Math.min(pz / span, SAMPLES - 2);
            int fz = pz - ck * span;
            for (int i = 0; i < SAMPLES; i++) {
                int px = bx0 + i * GRID;
                int ci = Math.min(px / span, SAMPLES - 2);
                int fx = px - ci * span;
                int a = sample(ci, ck) * (span - fx) + sample(ci + 1, ck) * fx;
                int b = sample(ci, ck + 1) * (span - fx) + sample(ci + 1, ck + 1) * fx;
                t.setHeight(i, k, Math.floorDiv(a * (span - fz) + b * fz + area / 2, area));
            }
        }
    }

    /**
     * Turns the canopy of a level-0 tile into feature runs: every feature
     * column of the tile under a cell with a canopy and no runs of its own
     * gets one run of the canopy's material from {@code top - thickness} to
     * {@code top} blocks above the ground there, and a voxel of snow over it
     * when the canopy is snowy. The canopy stays.
     */
    public void growCanopy() {
        if (level != 0) {
            throw new IllegalStateException("a level-" + level + " tile grows its canopy once cut");
        }
        int first = FEATURE_MARGIN / FEATURE_VOXEL;
        int last = (FEATURE_MARGIN + BLOCKS) / FEATURE_VOXEL;
        for (int fz = first; fz < last; fz++) {
            int bz = fz * FEATURE_VOXEL - FEATURE_MARGIN;
            for (int fx = first; fx < last; fx++) {
                int bx = fx * FEATURE_VOXEL - FEATURE_MARGIN;
                int word = canopy[(bz / GRID) * CELLS + bx / GRID];
                int c = fz * FEATURE_SIDE + fx;
                if (word == 0 || features[c] != null) {
                    continue;
                }
                int ground = height(bx, bz);
                int topY = ground + canopyTop(word);
                int bottomY = Math.max(ground, topY - canopyThickness(word));
                int vy0 = Math.floorDiv(bottomY, FEATURE_VOXEL);
                int vy1 = Math.floorDiv(topY - 1, FEATURE_VOXEL) + 1;
                boolean snow = canopySnowy(word) && canopySnow >= 0;
                int[] runs = snow ? new int[4] : new int[2];
                runs[0] = featureRun(vy0, Math.max(1, vy1 - vy0));
                runs[1] = canopyMaterial(word);
                if (snow) {
                    runs[2] = featureRun(vy0 + Math.max(1, vy1 - vy0), 1);
                    runs[3] = canopySnow;
                }
                features[c] = runs;
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
        // Canopies raise the chunks they stand over: by their top, a voxel more for rounding, and the snow.
        for (int cz = 0; cz < CELLS; cz++) {
            for (int cx = 0; cx < CELLS; cx++) {
                int word = canopy[cz * CELLS + cx];
                if (word == 0) {
                    continue;
                }
                int h = Math.max(Math.max(sample(cx, cz), sample(cx + 1, cz)),
                        Math.max(sample(cx, cz + 1), sample(cx + 1, cz + 1)));
                int topY = h + canopyTop(word) + FEATURE_VOXEL + (canopySnowy(word) ? FEATURE_VOXEL : 0);
                int i = (cz >> 2) * CHUNKS + (cx >> 2);
                max[i] = (short) Math.max(max[i], topY);
            }
        }
        // Feature tops raise the chunks they stand over (columns of the margin raise none here).
        for (int fz = 0; fz < FEATURE_SIDE; fz++) {
            for (int fx = 0; fx < FEATURE_SIDE; fx++) {
                int[] runs = features[fz * FEATURE_SIDE + fx];
                if (runs == null) {
                    continue;
                }
                int bx = fx * FEATURE_VOXEL - FEATURE_MARGIN;
                int bz = fz * FEATURE_VOXEL - FEATURE_MARGIN;
                if (bx < 0 || bz < 0 || bx >= BLOCKS || bz >= BLOCKS) {
                    continue;
                }
                int last = runs[runs.length - 2];
                int topY = featureRunY(last) + featureRunBlocks(last);
                int i = (bz >> 4) * CHUNKS + (bx >> 4);
                max[i] = (short) Math.max(max[i], topY);
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
        return new Summary(min, max, water >= 0 ? seaLevel : Integer.MIN_VALUE, quarters);
    }

    /**
     * What decides which sections of a tile hold something: per chunk the
     * lowest and highest surface; the sea level, or {@code Integer.MIN_VALUE}
     * without sea; the quarters built and complete ({@link #quarters()}).
     */
    public record Summary(short[] chunkMin, short[] chunkMax, int sea, int quarters) {

        /** Every quarter built and complete. */
        public boolean complete() {
            return quarters == ALL_QUARTERS;
        }

        public boolean built(int quarter) {
            return (quarters >> quarter & 1) != 0;
        }

        public boolean quarterComplete(int quarter) {
            return (quarters >> (quarter + 4) & 1) != 0;
        }

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

    /**
     * Replaces every material and biome id with {@code materials[id]} and
     * {@code biomes[id]}: a tile moving from a server's store into a
     * client's mirror (ADR-068). -1 (none) stays -1.
     *
     * @throws IllegalArgumentException for an id past the end of its map
     */
    public void remapIds(int[] materials, int[] biomes) {
        below = mapId(materials, below);
        water = mapId(materials, water);
        ice = mapId(materials, ice);
        iceberg = mapId(materials, iceberg);
        plantGround = mapId(materials, plantGround);
        canopySnow = mapId(materials, canopySnow);
        for (int i = 0; i < top.length; i++) {
            top[i] = mapId(materials, top[i]);
            filler[i] = mapId(materials, filler[i]);
            if (canopy[i] != 0) {
                int w = canopy[i];
                canopy[i] = canopyWord(mapId(materials, canopyMaterial(w)), canopyTop(w), canopyThickness(w),
                        canopySnowy(w));
            }
        }
        for (int i = 0; i < this.biomes.length; i++) {
            this.biomes[i] = mapId(biomes, this.biomes[i]);
        }
        kinds.replaceAll(k -> new TreeKind(mapId(materials, k.trunk()), mapId(materials, k.leaves()), k.height(),
                k.radius(), k.crownHeight(), k.shape(), mapId(materials, k.snow())));
        buildings.replaceAll(b -> new Building(b.x(), b.z(), b.width(), b.depth(), b.floor(), b.top(),
                mapId(materials, b.wall()), mapId(materials, b.roof())));
        plantKinds.replaceAll(m -> mapId(materials, m));
        for (int[] runs : features) {
            if (runs != null) {
                for (int j = 1; j < runs.length; j += 2) {
                    runs[j] = mapId(materials, runs[j]);
                }
            }
        }
        for (int i = 3; i < pits.length; i += PIT_INTS) {
            pits[i] = mapId(materials, pits[i]);
        }
    }

    private static int mapId(int[] map, int id) {
        if (id < 0) {
            return id;
        }
        if (id >= map.length) {
            throw new IllegalArgumentException("no mapping for id " + id);
        }
        return map[id];
    }

    public void encode(ByteWriter out) {
        out.writeVarInt(FORMAT);
        out.writeVarInt(level);
        out.writeVarInt(quarters);
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
        for (int[] list : seaPlants) {
            int n = list == null ? 0 : list.length;
            out.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                out.writeVarInt(list[i]);
            }
        }
        // Feature runs: per column their count, then each run's rise from the last one's top
        // (small numbers for the compressor), its length and its material.
        for (int[] runs : features) {
            int n = runs == null ? 0 : runs.length / 2;
            out.writeVarInt(n);
            int y = 0;
            for (int r = 0; r < n; r++) {
                int word = runs[r * 2];
                out.writeVarInt(zigZag((word >> 8) - y));
                out.writeVarInt(word & 0xFF);
                out.writeVarInt(runs[r * 2 + 1]);
                y = (word >> 8) + (word & 0xFF);
            }
        }
        out.writeVarInt(lights.length / 2);
        for (int i = 0; i < lights.length; i += 2) {
            out.writeVarInt(lights[i]);
            out.writeVarInt(zigZag(lights[i + 1]));
        }
        out.writeVarInt(pits.length / PIT_INTS);
        for (int i = 0; i < pits.length; i += PIT_INTS) {
            out.writeVarInt(pits[i]);
            out.writeVarInt(zigZag(pits[i + 1]));
            out.writeVarInt(pits[i + 2] - pits[i + 1]);
            out.writeVarInt(pits[i + 3] + 1);
        }
        out.writeVarInt(canopySnow + 1);
        boolean anyCanopy = hasCanopy();
        out.writeVarInt(anyCanopy ? 1 : 0);
        if (anyCanopy) {
            for (int w : canopy) {
                out.writeVarInt(w);
            }
        }
    }

    public static PreviewTile decode(ByteReader in) {
        int format = in.readVarInt();
        if (format != FORMAT) {
            throw new IllegalArgumentException("unknown preview tile format " + format);
        }
        PreviewTile t = new PreviewTile();
        t.setLevel(in.readVarInt());
        t.quarters = in.readVarInt() & ALL_QUARTERS;
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
        for (int c = 0; c < t.seaPlants.length; c++) {
            int n = in.readVarInt();
            if (n > 0) {
                int[] list = new int[n];
                for (int i = 0; i < n; i++) {
                    list[i] = in.readVarInt();
                }
                t.seaPlants[c] = list;
            }
        }
        for (int c = 0; c < t.features.length; c++) {
            int n = in.readVarInt();
            if (n < 0 || n > 4096) {
                throw new IllegalArgumentException("bad feature run count " + n);
            }
            if (n > 0) {
                int[] runs = new int[n * 2];
                int y = 0;
                for (int r = 0; r < n; r++) {
                    int voxelY = y + unZigZag(in.readVarInt());
                    int voxels = in.readVarInt();
                    runs[r * 2] = featureRun(voxelY, voxels);
                    runs[r * 2 + 1] = in.readVarInt();
                    y = voxelY + voxels;
                }
                t.features[c] = runs;
            }
        }
        int lights = in.readVarInt();
        if (lights < 0 || lights > FEATURE_SIDE * FEATURE_SIDE * 64) {
            throw new IllegalArgumentException("bad light source count " + lights);
        }
        t.lights = new int[lights * 2];
        for (int i = 0; i < t.lights.length; i += 2) {
            t.lights[i] = in.readVarInt();
            t.lights[i + 1] = unZigZag(in.readVarInt());
        }
        int pits = in.readVarInt();
        int columns = (BLOCKS + 2 * FEATURE_MARGIN) * (BLOCKS + 2 * FEATURE_MARGIN);
        if (pits < 0 || pits > columns) {
            throw new IllegalArgumentException("bad dug column count " + pits);
        }
        int[] entries = new int[pits * PIT_INTS];
        for (int i = 0; i < entries.length; i += PIT_INTS) {
            entries[i] = in.readVarInt();
            entries[i + 1] = unZigZag(in.readVarInt());
            entries[i + 2] = entries[i + 1] + in.readVarInt();
            entries[i + 3] = in.readVarInt() - 1;
        }
        t.pits = sortedPits(entries);
        t.canopySnow = in.readVarInt() - 1;
        if (in.readVarInt() != 0) {
            for (int i = 0; i < t.canopy.length; i++) {
                t.canopy[i] = in.readVarInt();
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
