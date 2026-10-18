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
