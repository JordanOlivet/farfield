package io.lakio.farfield.core.preview;

import io.lakio.farfield.core.lod.LodSection;

/**
 * Turns preview tiles into LOD sections of any level (ADR-039), on demand.
 * A voxel column of a level-L section ({@code s = 2^L} blocks wide) stands
 * at the highest surface of its blocks (interpolated per block below 4
 * blocks, the highest sample of its footprint above, like the real mip
 * rule); the top voxel shows the top block of the cell where that highest
 * surface lies, the next
 * {@link PreviewTile#FILLER_DEPTH} blocks its filler, then the tile's
 * ground; water fills up to the sea, its top block ice over frozen cells
 * (the voxel holding it, on coarse levels). Only a skin is built: a column goes
 * one voxel below the lowest of its four neighbors, so cliffs stay closed
 * and nothing is buried; next to a chunk with real data the neighbor's
 * height is the real ground's, so a preview column beside a real sea goes
 * down to the real sea floor instead of leaving a gap under it.
 *
 * <p>Columns of chunks with real data hold what hides the preview's faces
 * toward them without being drawn (the renderer drops their quads):
 * {@code maskGround}, an opaque material, up to the real ground, and
 * {@code maskFluid}, a translucent one, up to the real fluid's top. So the
 * preview's water shows no wall toward real water, and its land shows its
 * cliff toward a real sea.
 *
 * <p>Trees and plants (ADR-040) stand on the interpolated surface, village
 * buildings (ADR-041) on their stored floor: block by block on the finest
 * level; on coarser levels a voxel takes their material when they fill a
 * quarter of it, like the real mip rule's two full children of eight.
 * Those of real chunks are left out. A snowy crown (ADR-049) takes a snow
 * layer over the top block of each of its columns on the finest level; on
 * coarser levels a tree voxel holding such a top block shows the snow, as
 * the real mip rule's view from above.
 */
public final class PreviewVoxels {

    /** Block-state key of the opaque mask material: real ground. */
    public static final String MASK_GROUND_KEY = "farfield:preview_mask";
    /** Block-state key of the translucent mask material: real fluid. */
    public static final String MASK_FLUID_KEY = "farfield:preview_mask_fluid";

    /** The stored tiles, by tile coordinates; null where none is stored. */
    @FunctionalInterface
    public interface Tiles {
        PreviewTile tile(int tileX, int tileZ);
    }

    /** Chunks that have real LOD data, which replaces the preview, and their real heights. */
    @FunctionalInterface
    public interface Mask {
        boolean real(int chunkX, int chunkZ);

        /** The first block above the real ground of a real chunk; {@link Integer#MIN_VALUE} when unknown. */
        default int ground(int chunkX, int chunkZ) {
            return NONE;
        }

        /** The first block above the real fluid over that ground, or the ground without fluid. */
        default int fluidTop(int chunkX, int chunkZ) {
            return ground(chunkX, chunkZ);
        }
    }

    private static final int S = LodSection.SIZE;
    private static final int NONE = Integer.MIN_VALUE;
    /** Tests only: count coarse tree voxels block by block, as the finest level does. */
    static boolean blockByBlockTrees;

    private PreviewVoxels() {
    }

    /**
     * The section at level {@code level} and section coordinates (sx, sy, sz),
     * or null when it holds nothing.
     *
     * @param minY       the world's lowest block
     * @param maskGround the material of real ground, -1 to leave real chunks empty
     * @param maskFluid  the material of real fluid
     * @param layerAxis  -1 for the whole section, else 0, 1 or 2: only voxels
     *                   whose coordinate on that axis is {@code layerIndex}
     *                   (the border layer a neighbor section meshes against),
     *                   without trees or buildings
     */
    public static LodSection build(int level, int sx, int sy, int sz, Tiles tiles, Mask mask, int air, int minY,
                                   int maskGround, int maskFluid, int layerAxis, int layerIndex) {
        int s = 1 << level;
        int size = S * s;
        int ox = sx * size;
        int oy = sy * size;
        int oz = sz * size;
        TileCache cache = new TileCache(tiles);
        int n = (S + 2) * (S + 2);
        int[] h = new int[n];
        int[] cells = new int[n];
        boolean[] real = new boolean[n];
        int[] ground = new int[n];
        int[] fluid = new int[n];
        int[] cell = new int[1];
        // A layer along X or Z needs only its own row of columns and the two beside it.
        int iMin = layerAxis == 0 ? layerIndex - 1 : -1;
        int iMax = layerAxis == 0 ? layerIndex + 1 : S;
        int kMin = layerAxis == 2 ? layerIndex - 1 : -1;
        int kMax = layerAxis == 2 ? layerIndex + 1 : S;
        for (int k = kMin; k <= kMax; k++) {
            for (int i = iMin; i <= iMax; i++) {
                int c = (k + 1) * (S + 2) + i + 1;
                int bx = ox + i * s;
                int bz = oz + k * s;
                h[c] = columnHeight(cache, bx, bz, s, cell);
                cells[c] = cell[0];
                real[c] = mask != null && mask.real(bx >> 4, bz >> 4);
                if (real[c]) {
                    ground[c] = mask.ground(bx >> 4, bz >> 4);
                    fluid[c] = mask.fluidTop(bx >> 4, bz >> 4);
                }
            }
        }
        LodSection section = null;
        for (int k = 0; k < S; k++) {
            if (layerAxis == 2 && k != layerIndex) {
                continue;
            }
            for (int i = 0; i < S; i++) {
                if (layerAxis == 0 && i != layerIndex) {
                    continue;
                }
                int c = (k + 1) * (S + 2) + i + 1;
                if (real[c]) {
                    if (maskGround >= 0 && ground[c] != NONE) {
                        section = mask(section, air, i, k, oy, s, ground[c], fluid[c], minY, maskGround, maskFluid,
                                layerAxis, layerIndex);
                    }
                    continue;
                }
                int height = h[c];
                if (height == NONE) {
                    continue;
                }
                int floor = Math.min(Math.min(lower(height, c - 1, h, real, ground), lower(height, c + 1, h, real, ground)),
                        Math.min(lower(height, c - S - 2, h, real, ground), lower(height, c + S + 2, h, real, ground)));
                floor = Math.max(floor - s, minY);
                int bx = ox + i * s;
                int bz = oz + k * s;
                PreviewTile tile = cache.tile(Math.floorDiv(bx, PreviewTile.BLOCKS), Math.floorDiv(bz, PreviewTile.BLOCKS));
                int cellX = cells[c] % PreviewTile.CELLS;
                int cellZ = cells[c] / PreviewTile.CELLS;
                int top = tile.top(cellX, cellZ);
                int filler = tile.filler(cellX, cellZ);
                int water = tile.water();
                int sea = tile.seaLevel();
                boolean frozen = tile.frozen(cellX, cellZ);
                for (int vy = 0; vy < S; vy++) {
                    if (layerAxis == 1 && vy != layerIndex) {
                        continue;
                    }
                    int bottom = oy + vy * s;
                    if (bottom + s <= floor) {
                        continue;
                    }
                    int material;
                    int blocks;
                    if (bottom < height) {
                        blocks = Math.min(s, height - bottom);
                        if (height - 1 < bottom + s) {
                            material = top;
                        } else if (bottom + s > height - 1 - PreviewTile.FILLER_DEPTH) {
                            material = filler;
                        } else {
                            material = tile.below();
                        }
                    } else if (water >= 0 && bottom < sea) {
                        blocks = Math.min(s, sea - bottom);
                        // The voxel holding the sea's top block shows it, as the real mip rule's view from above.
                        material = frozen && bottom + s >= sea ? tile.ice() : water;
                    } else {
                        break;
                    }
                    if (section == null) {
                        section = new LodSection(air);
                    }
                    section.set(i, vy, k, material);
                    if (level > 0) {
                        section.fill().set(i, vy, k, (blocks * 16 + s - 1) / s - 1);
                    }
                }
            }
        }
        if (layerAxis < 0) {
            // Layers only hide a neighbor's water faces: trees there are left out (a
            // water face against a trunk on a section border stays, behind the trunk).
            section = trees(section, level, ox, oy, oz, cache, mask, air, layerAxis, layerIndex);
        }
        if (section != null) {
            for (int cz = 0; cz < LodSection.BIOME_CELLS_PER_AXIS; cz++) {
                for (int cx = 0; cx < LodSection.BIOME_CELLS_PER_AXIS; cx++) {
                    int bx = ox + (cx * 4 + 2) * s;
                    int bz = oz + (cz * 4 + 2) * s;
                    PreviewTile tile = cache.tile(Math.floorDiv(bx, PreviewTile.BLOCKS),
                            Math.floorDiv(bz, PreviewTile.BLOCKS));
                    if (tile == null) {
                        continue;
                    }
                    int biome = tile.biome(Math.floorMod(bx, PreviewTile.BLOCKS) >> 4,
                            Math.floorMod(bz, PreviewTile.BLOCKS) >> 4);
                    for (int cy = 0; cy < LodSection.BIOME_CELLS_PER_AXIS; cy++) {
                        section.setBiome(cx, cy, cz, biome);
                    }
                }
            }
        }
        return section;
    }

    /** Stamps the trees whose blocks fall in the section (see the class comment). */
    private static LodSection trees(LodSection section, int level, int ox, int oy, int oz, TileCache cache, Mask mask,
                                    int air, int layerAxis, int layerIndex) {
        int s = 1 << level;
        int size = S * s;
        Stamp stamp = new Stamp(section, level, ox, oy, oz, size, mask, air, layerAxis, layerIndex);
        // Crowns reach less than a chunk: the chunks around the footprint are enough.
        for (int chunkZ = (oz >> 4) - 1; chunkZ <= ((oz + size - 1) >> 4) + 1; chunkZ++) {
            for (int chunkX = (ox >> 4) - 1; chunkX <= ((ox + size - 1) >> 4) + 1; chunkX++) {
                PreviewTile tile = cache.tile(Math.floorDiv(chunkX, PreviewTile.CHUNKS), Math.floorDiv(chunkZ, PreviewTile.CHUNKS));
                if (tile == null || mask != null && mask.real(chunkX, chunkZ)) {
                    continue;
                }
                for (int t : tile.trees(Math.floorMod(chunkX, PreviewTile.CHUNKS), Math.floorMod(chunkZ, PreviewTile.CHUNKS))) {
                    PreviewTile.TreeKind k = tile.treeKind(t >>> 8);
                    int bx = chunkX * 16 + (t & 15);
                    int bz = chunkZ * 16 + (t >> 4 & 15);
                    int ground = tile.height(Math.floorMod(bx, PreviewTile.BLOCKS), Math.floorMod(bz, PreviewTile.BLOCKS));
                    stamp.tree(k, bx, ground, bz, chunkX, chunkZ);
                }
            }
        }
        // Buildings stay within 16 blocks of their tile: the tiles around the footprint are enough.
        for (int tz = Math.floorDiv(oz - 16, PreviewTile.BLOCKS); tz <= Math.floorDiv(oz + size + 16, PreviewTile.BLOCKS); tz++) {
            for (int tx = Math.floorDiv(ox - 16, PreviewTile.BLOCKS); tx <= Math.floorDiv(ox + size + 16, PreviewTile.BLOCKS); tx++) {
                PreviewTile tile = cache.tile(tx, tz);
                if (tile == null) {
                    continue;
                }
                for (PreviewTile.Building b : tile.buildings()) {
                    int bx = tx * PreviewTile.BLOCKS + b.x();
                    int bz = tz * PreviewTile.BLOCKS + b.z();
                    if (mask != null && mask.real(bx >> 4, bz >> 4)) {
                        continue;
                    }
                    stamp.building(b, bx, bz);
                }
            }
        }
        return stamp.finish();
    }

    /** Writes tree blocks into a section, or counts them per voxel on coarse levels. */
    private static final class Stamp {
        private final int level;
        private final int s;
        private final int ox;
        private final int oy;
        private final int oz;
        private final int size;
        private final Mask mask;
        private final int air;
        private final int layerAxis;
        private final int layerIndex;
        private final int[] counts;
        private final int[] materials;
        /** Coarse levels: per voxel the snow layers in it and their material; null until a snowy crown. */
        private int[] snowBlocks;
        private int[] snow;
        /** Finest level: snow layers to lay once every tree is in, voxel index then material. */
        private int[] snowLayers = new int[0];
        private int snowCount;
        /** The crown being stamped ({@link #crown}): per row from the bottom, its radius and the widest row above. */
        private int[] radii = new int[17];
        private int[] widest = new int[17];
        private LodSection section;

        Stamp(LodSection section, int level, int ox, int oy, int oz, int size, Mask mask, int air, int layerAxis,
              int layerIndex) {
            this.section = section;
            this.level = level;
            this.s = 1 << level;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.size = size;
            this.mask = mask;
            this.air = air;
            this.layerAxis = layerAxis;
            this.layerIndex = layerIndex;
            counts = level > 0 ? new int[S * S * S] : null;
            materials = level > 0 ? new int[S * S * S] : null;
        }

        void tree(PreviewTile.TreeKind k, int x, int ground, int z, int chunkX, int chunkZ) {
            int top = ground + k.height();
            if (top + 1 < oy || ground >= oy + size) {
                return;
            }
            if (level > 0 && layerAxis < 0 && !blockByBlockTrees && !overReal(x, z, k.radius(), chunkX, chunkZ)) {
                treeByArea(k, x, ground, top, z);
                return;
            }
            for (int y = ground; y < top; y++) {
                put(x, y, z, k.trunk(), chunkX, chunkZ);
            }
            if (k.shape() == PreviewTile.COLUMN) {
                return;
            }
            int rows = crown(k);
            for (int r = 0; r <= rows; r++) {
                int y = top - rows + r;
                int radius = radii[r];
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (rounded(k, radius) && Math.abs(dx) == radius && Math.abs(dz) == radius) {
                            continue; // rounded corners
                        }
                        if (dx == 0 && dz == 0 && y < top) {
                            continue; // the trunk
                        }
                        put(x + dx, y, z + dz, k.leaves(), chunkX, chunkZ);
                    }
                }
            }
            if (k.snow() >= 0) {
                snowBlockByBlock(k, x, top, z, rows, chunkX, chunkZ);
            }
        }

        /**
         * Fills {@link #radii} and {@link #widest} for a crown; returns its
         * rows minus one. The widest row above a row holds every other row
         * above it: rows are squares around the trunk less their corners
         * ({@link #rounded}), and a narrower square lies inside a wider one.
         */
        private int crown(PreviewTile.TreeKind k) {
            int rows = k.shape() == PreviewTile.FLAT ? 0 : k.crownHeight();
            if (radii.length <= rows) {
                radii = new int[rows + 1];
                widest = new int[rows + 1];
            }
            // Vanilla's spruce foliage from the top: tiers one row wider each, each from radius 1 again.
            int tier = 0;
            int tierWidest = 1;
            int start = 0;
            int above = -1;
            for (int r = rows; r >= 0; r--) {
                int radius;
                if (k.shape() == PreviewTile.TIERED) {
                    radius = Math.min(tier, k.radius());
                    if (tier >= tierWidest) {
                        tier = start;
                        start = 1;
                        tierWidest = Math.min(tierWidest + 1, k.radius());
                    } else {
                        tier++;
                    }
                } else if (k.shape() == PreviewTile.CONE) {
                    radius = Math.round(k.radius() * (rows - r) / (float) Math.max(1, rows));
                } else {
                    radius = r == rows && rows > 0 ? Math.max(0, k.radius() - 1) : k.radius();
                }
                radii[r] = radius;
                widest[r] = above;
                above = Math.max(above, radius);
            }
            return rows;
        }

        /**
         * Whether rows of {@code radius} leave out their corners: from radius
         * 1 for cones and tiers (vanilla's spruces and pines, a plus on top),
         * from radius 2 for blobs and caps.
         */
        private static boolean rounded(PreviewTile.TreeKind k, int radius) {
            return radius > (k.shape() == PreviewTile.CONE || k.shape() == PreviewTile.TIERED ? 0 : 1);
        }

        /** Whether (dx, dz) from the trunk lies in a crown row of {@code radius}. */
        private static boolean inRow(PreviewTile.TreeKind k, int radius, int dx, int dz) {
            if (Math.abs(dx) > radius || Math.abs(dz) > radius) {
                return false;
            }
            return !rounded(k, radius) || Math.abs(dx) != radius || Math.abs(dz) != radius;
        }

        /**
         * The snow of a crown, block by block: over each block of a row that
         * no row above covers, a snow layer on the finest level, counted in
         * its voxel on coarser ones ({@link #finish}).
         */
        private void snowBlockByBlock(PreviewTile.TreeKind k, int x, int top, int z, int rows, int chunkX, int chunkZ) {
            for (int r = 0; r <= rows; r++) {
                int y = top - rows + r;
                int radius = radii[r];
                int above = widest[r];
                if (above >= radius) {
                    continue; // covered by a row above
                }
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (!inRow(k, radius, dx, dz) || above >= 0 && inRow(k, above, dx, dz)) {
                            continue;
                        }
                        int i = voxel(x + dx, y + 1, z + dz, chunkX, chunkZ);
                        if (i >= 0) {
                            snowOn(i, 1, k.snow());
                        }
                    }
                }
            }
        }

        /** {@code blocks} snow layers of {@code material} in voxel {@code i}. */
        private void snowOn(int i, int blocks, int material) {
            if (level == 0) {
                if (snowCount + 2 > snowLayers.length) {
                    snowLayers = java.util.Arrays.copyOf(snowLayers, Math.max(64, snowLayers.length * 2));
                }
                snowLayers[snowCount++] = i;
                snowLayers[snowCount++] = material;
                return;
            }
            if (snow == null) {
                snow = new int[S * S * S];
                snowBlocks = new int[S * S * S];
            }
            snowBlocks[i] += blocks;
            snow[i] = material;
        }

        /** Whether a crown of {@code radius} around (x, z) reaches a real chunk other than its own. */
        private boolean overReal(int x, int z, int radius, int chunkX, int chunkZ) {
            if (mask == null) {
                return false;
            }
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
                    if ((cx != chunkX || cz != chunkZ) && mask.real(cx, cz)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * The counts {@link #tree} gives on a coarse level, from areas instead
         * of blocks (a forest section of level 4 holds some 11,000 trees):
         * the trunk as a vertical run, each crown row as its square cut by the
         * voxel grid, less its rounded corners and the trunk under the top.
         * Same rows in the same order, so each voxel keeps the same last
         * material.
         */
        private void treeByArea(PreviewTile.TreeKind k, int x, int ground, int top, int z) {
            int lx = x - ox;
            int lz = z - oz;
            if (lx >= 0 && lz >= 0 && lx < size && lz < size) {
                for (int y = Math.max(ground, oy); y < Math.min(top, oy + size); ) {
                    int vy = (y - oy) >> level;
                    int end = Math.min(Math.min(top, oy + size), oy + (vy + 1) * s);
                    add(lx >> level, vy, lz >> level, end - y, k.trunk());
                    y = end;
                }
            }
            if (k.shape() == PreviewTile.COLUMN) {
                return;
            }
            int rows = crown(k);
            for (int r = 0; r <= rows; r++) {
                int y = top - rows + r;
                // Snow (see snowBlockByBlock) over the blocks no row above covers, in the
                // block above the row: none under a row as wide.
                int radius = radii[r];
                int above = widest[r];
                boolean snowy = k.snow() >= 0 && above < radius && y + 1 >= oy && y + 1 < oy + size;
                boolean leaves = y >= oy && y < oy + size;
                if (!leaves && !snowy) {
                    continue;
                }
                int vy = (y - oy) >> level;
                int snowVy = (y + 1 - oy) >> level;
                boolean rounded = rounded(k, radius);
                int x0 = Math.max(lx - radius, 0);
                int x1 = Math.min(lx + radius, size - 1);
                int z0 = Math.max(lz - radius, 0);
                int z1 = Math.min(lz + radius, size - 1);
                for (int vz = z0 >> level; vz <= z1 >> level; vz++) {
                    int a0 = Math.max(z0, vz * s);
                    int a1 = Math.min(z1, vz * s + s - 1);
                    for (int vx = x0 >> level; vx <= x1 >> level; vx++) {
                        int b0 = Math.max(x0, vx * s);
                        int b1 = Math.min(x1, vx * s + s - 1);
                        int blocks = (a1 - a0 + 1) * (b1 - b0 + 1);
                        if (rounded) {
                            blocks -= inside(lx - radius, lz - radius, b0, b1, a0, a1)
                                    + inside(lx + radius, lz - radius, b0, b1, a0, a1)
                                    + inside(lx - radius, lz + radius, b0, b1, a0, a1)
                                    + inside(lx + radius, lz + radius, b0, b1, a0, a1);
                        }
                        if (snowy) {
                            int open = blocks - (above >= 0 ? rowBlocks(k, above, lx, lz, b0, b1, a0, a1) : 0);
                            if (open > 0) {
                                snowOn((snowVy * S + vz) * S + vx, open, k.snow());
                            }
                        }
                        if (!leaves) {
                            continue;
                        }
                        if (y < top) {
                            blocks -= inside(lx, lz, b0, b1, a0, a1);
                        }
                        if (blocks > 0) {
                            add(vx, vy, vz, blocks, k.leaves());
                        }
                    }
                }
            }
        }

        /** Blocks of a crown row of {@code radius} around (lx, lz) inside [x0, x1] x [z0, z1]. */
        private static int rowBlocks(PreviewTile.TreeKind k, int radius, int lx, int lz, int x0, int x1, int z0, int z1) {
            int w = Math.min(x1, lx + radius) - Math.max(x0, lx - radius) + 1;
            int d = Math.min(z1, lz + radius) - Math.max(z0, lz - radius) + 1;
            if (w <= 0 || d <= 0) {
                return 0;
            }
            int blocks = w * d;
            if (rounded(k, radius)) {
                blocks -= inside(lx - radius, lz - radius, x0, x1, z0, z1)
                        + inside(lx + radius, lz - radius, x0, x1, z0, z1)
                        + inside(lx - radius, lz + radius, x0, x1, z0, z1)
                        + inside(lx + radius, lz + radius, x0, x1, z0, z1);
            }
            return blocks;
        }

        private static int inside(int x, int z, int x0, int x1, int z0, int z1) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1 ? 1 : 0;
        }

        private void add(int vx, int vy, int vz, int blocks, int material) {
            int i = (vy * S + vz) * S + vx;
            counts[i] += blocks;
            materials[i] = material;
        }

        /** Walls from the floor up, the roof layer on top. */
        void building(PreviewTile.Building b, int x, int z) {
            if (b.top() < oy || b.floor() >= oy + size) {
                return;
            }
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            for (int y = Math.max(b.floor(), oy); y <= Math.min(b.top(), oy + size - 1); y++) {
                int material = y == b.top() ? b.roof() : b.wall();
                for (int dz = 0; dz < b.depth(); dz++) {
                    for (int dx = 0; dx < b.width(); dx++) {
                        put(x + dx, y, z + dz, material, chunkX, chunkZ);
                    }
                }
            }
        }

        private void put(int x, int y, int z, int material, int chunkX, int chunkZ) {
            int i = voxel(x, y, z, chunkX, chunkZ);
            if (i < 0) {
                return;
            }
            if (level == 0) {
                setIfAir(i, material);
                return;
            }
            counts[i]++;
            materials[i] = material;
        }

        /**
         * The voxel index, {@code (vy * S + vz) * S + vx}, of block (x, y, z)
         * of a tree or building of chunk (chunkX, chunkZ); -1 outside the
         * section or its layer, or over a real chunk other than its own.
         */
        private int voxel(int x, int y, int z, int chunkX, int chunkZ) {
            int lx = x - ox;
            int ly = y - oy;
            int lz = z - oz;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= size || ly >= size || lz >= size) {
                return -1;
            }
            if (mask != null && ((x >> 4) != chunkX || (z >> 4) != chunkZ) && mask.real(x >> 4, z >> 4)) {
                return -1; // a crown reaching over real terrain
            }
            int vx = lx >> level;
            int vy = ly >> level;
            int vz = lz >> level;
            if (layerAxis == 0 && vx != layerIndex || layerAxis == 1 && vy != layerIndex
                    || layerAxis == 2 && vz != layerIndex) {
                return -1;
            }
            return (vy * S + vz) * S + vx;
        }

        private void setIfAir(int i, int material) {
            int vx = i % S;
            int vz = i / S % S;
            int vy = i / (S * S);
            if (section == null) {
                section = new LodSection(air);
            }
            if (section.material(vx, vy, vz) == air) {
                section.set(vx, vy, vz, material);
            }
        }

        LodSection finish() {
            if (level == 0) {
                // Snow last: a layer never takes the place of another tree's leaves.
                for (int j = 0; j < snowCount; j += 2) {
                    setIfAir(snowLayers[j], snowLayers[j + 1]);
                }
                return section;
            }
            int threshold = Math.max(1, s * s * s / 4);
            // The real mip rule's view from above: snow where it tops half the voxel's columns or more.
            int snowy = s * s / 2;
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] >= threshold) {
                    setIfAir(i, snow != null && snowBlocks[i] >= snowy ? snow[i] : materials[i]);
                }
            }
            return section;
        }
    }

    /**
     * The lower of a column's height and a neighbor's: a real neighbor's
     * ground, else its preview surface; a neighbor without either is ignored.
     */
    private static int lower(int height, int n, int[] h, boolean[] real, int[] ground) {
        int neighbor = real[n] ? ground[n] : h[n];
        return neighbor == NONE ? height : Math.min(height, neighbor);
    }

    /**
     * The column of a real chunk: opaque mask from one voxel under the real
     * ground up to it, translucent mask from there up to the fluid's top.
     */
    private static LodSection mask(LodSection section, int air, int i, int k, int oy, int s, int ground, int fluid,
                                   int minY, int maskGround, int maskFluid, int layerAxis, int layerIndex) {
        int floor = Math.max(ground - s, minY);
        int top = Math.max(ground, fluid);
        for (int vy = 0; vy < S; vy++) {
            if (layerAxis == 1 && vy != layerIndex) {
                continue;
            }
            int bottom = oy + vy * s;
            if (bottom + s <= floor) {
                continue;
            }
            if (bottom >= top) {
                break;
            }
            if (section == null) {
                section = new LodSection(air);
            }
            section.set(i, vy, k, bottom < ground ? maskGround : maskFluid);
        }
        return section;
    }

    /**
     * The surface a voxel column stands at: below 4 blocks, the highest
     * interpolated block of its footprint; from 4 blocks, the highest sample
     * on or inside its footprint. {@link #NONE} without a tile. {@code cell}
     * receives the tile cell of that highest point, {@code z * CELLS + x}.
     */
    static int columnHeight(TileCache cache, int bx, int bz, int s, int[] cell) {
        PreviewTile tile = cache.tile(Math.floorDiv(bx, PreviewTile.BLOCKS), Math.floorDiv(bz, PreviewTile.BLOCKS));
        if (tile == null) {
            return NONE;
        }
        int lx = Math.floorMod(bx, PreviewTile.BLOCKS);
        int lz = Math.floorMod(bz, PreviewTile.BLOCKS);
        int best = NONE;
        int last = PreviewTile.CELLS - 1;
        if (s < PreviewTile.GRID) {
            for (int dz = 0; dz < s; dz++) {
                for (int dx = 0; dx < s; dx++) {
                    int v = tile.height(lx + dx, lz + dz);
                    if (v > best) {
                        best = v;
                        cell[0] = (lz + dz) / PreviewTile.GRID * PreviewTile.CELLS + (lx + dx) / PreviewTile.GRID;
                    }
                }
            }
        } else {
            for (int k = lz / PreviewTile.GRID; k <= (lz + s) / PreviewTile.GRID; k++) {
                for (int i = lx / PreviewTile.GRID; i <= (lx + s) / PreviewTile.GRID; i++) {
                    int v = tile.sample(i, k);
                    if (v > best) {
                        best = v;
                        // A sample on the far edge belongs to the next tile's cell: keep the last one.
                        cell[0] = Math.min(k, last) * PreviewTile.CELLS + Math.min(i, last);
                    }
                }
            }
        }
        return best;
    }

    /** Remembers the last tile asked for: columns come in runs over one tile. */
    static final class TileCache {
        private final Tiles tiles;
        private int lastX = Integer.MIN_VALUE;
        private int lastZ;
        private PreviewTile last;

        TileCache(Tiles tiles) {
            this.tiles = tiles;
        }

        PreviewTile tile(int x, int z) {
            if (x != lastX || z != lastZ) {
                lastX = x;
                lastZ = z;
                last = tiles.tile(x, z);
            }
            return last;
        }
    }
}
