package io.lakio.farfield.core.lod;

/**
 * The noise preview of one section column (ADR-038): 32 x 32 terrain
 * columns, one per chunk, turned into level-4 sections (16 blocks per voxel)
 * by {@link #build}. Each column has a surface height, a surface material and
 * a biome; the ground below the surface voxel is {@link #below}, and water
 * fills lower columns up to {@link #seaLevel} when {@link #water} is set.
 *
 * <p>Not thread-safe; reusable.
 */
public final class PreviewColumns {

    public static final int SIZE = LodSection.SIZE;
    /** Blocks per voxel of a preview section (level 4). */
    public static final int VOXEL_BLOCKS = 16;

    private final int[] surface = new int[SIZE * SIZE];
    private final int[] top = new int[SIZE * SIZE];
    private final int[] biome = new int[SIZE * SIZE];
    private final int air;
    private int below;
    private int water = -1;
    private int seaLevel = Integer.MIN_VALUE;

    /** @param air the material id of air */
    public PreviewColumns(int air) {
        this.air = air;
    }

    /**
     * @param surfaceY first free block above the ground (the ground's top is {@code surfaceY - 1})
     * @param material the material seen on top
     * @param biomeId  the biome id, -1 for unknown
     */
    public void set(int x, int z, int surfaceY, int material, int biomeId) {
        int i = z * SIZE + x;
        surface[i] = surfaceY;
        top[i] = material;
        biome[i] = biomeId;
    }

    public int surface(int x, int z) {
        return surface[z * SIZE + x];
    }

    /** The material under the surface voxel. */
    public void setBelow(int material) {
        below = material;
    }

    /** Water up to {@code seaLevel} (the first free block above it); -1 for none. */
    public void setWater(int material, int seaLevel) {
        this.water = material;
        this.seaLevel = seaLevel;
    }

    /**
     * The level-4 section at section Y {@code sectionY} (voxel Y 0 at block
     * {@code sectionY * 512}), or null when it holds only air. Voxels wholly
     * below {@code minY}, the bottom of the world, stay air.
     */
    public LodSection build(int sectionY, int minY) {
        int base = sectionY * SIZE * VOXEL_BLOCKS;
        LodSection section = null;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int i = z * SIZE + x;
                for (int y = 0; y < SIZE; y++) {
                    int bottom = base + y * VOXEL_BLOCKS;
                    if (bottom + VOXEL_BLOCKS <= minY) {
                        continue;
                    }
                    int material;
                    int height;
                    if (bottom < surface[i]) {
                        height = Math.min(VOXEL_BLOCKS, surface[i] - bottom);
                        // The surface voxel shows the top material, deeper ones the ground.
                        material = surface[i] - bottom <= VOXEL_BLOCKS ? top[i] : below;
                    } else if (water >= 0 && bottom < seaLevel) {
                        height = Math.min(VOXEL_BLOCKS, seaLevel - bottom);
                        material = water;
                    } else {
                        break;
                    }
                    if (section == null) {
                        section = new LodSection(air);
                    }
                    section.set(x, y, z, material);
                    section.fill().set(x, y, z, height - 1);
                }
            }
        }
        if (section != null) {
            for (int cz = 0; cz < LodSection.BIOME_CELLS_PER_AXIS; cz++) {
                for (int cx = 0; cx < LodSection.BIOME_CELLS_PER_AXIS; cx++) {
                    int id = biome[(cz * 4 + 2) * SIZE + cx * 4 + 2];
                    for (int cy = 0; cy < LodSection.BIOME_CELLS_PER_AXIS; cy++) {
                        section.setBiome(cx, cy, cz, id);
                    }
                }
            }
        }
        return section;
    }
}
