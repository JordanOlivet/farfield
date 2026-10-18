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
 * ground; water fills up to the sea. Only a skin is built: a column goes
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
 * <p>Trees (ADR-040) stand on the interpolated surface: trunk and crown block
 * by block on the finest level; on coarser levels a voxel takes the tree's
 * material when trees fill a quarter of it, like the real mip rule's two
 * full children of eight. Trees of real chunks are left out.
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
     *                   (the border layer a neighbor section meshes against)
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
        for (int k = -1; k <= S; k++) {
            for (int i = -1; i <= S; i++) {
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
                        material = water;
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
        section = trees(section, level, ox, oy, oz, cache, mask, air, layerAxis, layerIndex);
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
            for (int y = ground; y < top; y++) {
                put(x, y, z, k.trunk(), chunkX, chunkZ);
            }
            int rows = k.crownHeight();
            for (int r = 0; r <= rows; r++) {
                int y = top - rows + r;
                int radius = k.cone()
                        ? Math.round(k.radius() * (rows - r) / (float) Math.max(1, rows))
                        : r == rows ? Math.max(0, k.radius() - 1) : k.radius();
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (!k.cone() && radius > 1 && Math.abs(dx) == radius && Math.abs(dz) == radius) {
                            continue; // rounded corners
                        }
                        if (dx == 0 && dz == 0 && y < top) {
                            continue; // the trunk
                        }
                        put(x + dx, y, z + dz, k.leaves(), chunkX, chunkZ);
                    }
                }
            }
        }

        private void put(int x, int y, int z, int material, int chunkX, int chunkZ) {
            int lx = x - ox;
            int ly = y - oy;
            int lz = z - oz;
            if (lx < 0 || ly < 0 || lz < 0 || lx >= size || ly >= size || lz >= size) {
                return;
            }
            if (mask != null && ((x >> 4) != chunkX || (z >> 4) != chunkZ) && mask.real(x >> 4, z >> 4)) {
                return; // a crown reaching over real terrain
            }
            int vx = lx >> level;
            int vy = ly >> level;
            int vz = lz >> level;
            if (layerAxis == 0 && vx != layerIndex || layerAxis == 1 && vy != layerIndex
                    || layerAxis == 2 && vz != layerIndex) {
                return;
            }
            if (level == 0) {
                if (section == null) {
                    section = new LodSection(air);
                }
                if (section.material(vx, vy, vz) == air) {
                    section.set(vx, vy, vz, material);
                }
                return;
            }
            int i = (vy * S + vz) * S + vx;
            counts[i]++;
            materials[i] = material;
        }

        LodSection finish() {
            if (level == 0) {
                return section;
            }
            int threshold = Math.max(1, s * s * s / 4);
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] < threshold) {
                    continue;
                }
                int vx = i % S;
                int vz = i / S % S;
                int vy = i / (S * S);
                if (section == null) {
                    section = new LodSection(air);
                }
                if (section.material(vx, vy, vz) == air) {
                    section.set(vx, vy, vz, materials[i]);
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
