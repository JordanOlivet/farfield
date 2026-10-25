package io.lakio.farfield.core.world;

import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.lod.MipBuilder;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.mesh.ShapeBoxes;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-material properties the engine needs, indexed by block-state id of a
 * world's {@link KeyRegistry}: the mesher's class, the downsampling shape,
 * the block's height in sixteenths and the boxes of
 * {@link MaterialClassifier#SHAPED} blocks. Resolved lazily from the key by
 * the Minecraft side on first use and cached; thread-safe.
 */
public final class MaterialTable implements MaterialClassifier, MipBuilder.Shapes {

    /** Computes the properties of a block-state key; see {@link #pack}. */
    @FunctionalInterface
    public interface Resolver {
        /**
         * @return {@link #pack(int, int, int)} of the mesher class, the
         *         downsampling shape and the height; called once per key,
         *         from any thread
         */
        int resolve(String key);

        /**
         * The boxes of a key {@link #resolve} classes as {@link MaterialClassifier#SHAPED}
         * (1..{@link ShapeBoxes#MAX}, packed by {@link ShapeBoxes#pack}); called once per
         * such key, from any thread. Null or empty: the whole voxel.
         */
        default int[] boxes(String key) {
            return null;
        }

        /**
         * The key of the fluid a key {@link #resolve} packs with
         * {@link #packSubmerged()} stands in (water for kelp); null drops the
         * plant and keeps the fluid. Called once per such key, from any thread.
         */
        default String fluid(String key) {
            return null;
        }
    }

    /** Fallback for engines without a game: air keys are empty, all else full opaque cubes. */
    public static final Resolver SIMPLE = key -> {
        String name = key.indexOf('[') < 0 ? key : key.substring(0, key.indexOf('['));
        boolean air = name.equals("minecraft:air") || name.equals("minecraft:cave_air")
                || name.equals("minecraft:void_air");
        return air ? pack(AIR, EMPTY) : pack(OPAQUE, FULL);
    };

    private static final int UNRESOLVED = -1;
    /** Packed flag of fluids, above the height bits. */
    private static final int FLUID = 1 << 13;
    /** Quarters of a {@link CoveredKey} material (0 for others), above the fluid flag. */
    private static final int COVER_SHIFT = 14;
    /** Packed flag of fluids holding a plant ({@link #hasPlant}), above the cover's quarters. */
    private static final int SUBMERGED = 1 << 17;
    private static final int[] WHOLE_VOXEL = {ShapeBoxes.pack(0, 0, 0, 16, 16, 16)};

    private final KeyRegistry keys;
    private final Resolver resolver;
    private volatile int[] packed = new int[0];
    /** Boxes per material, set before {@link #packed} publishes the material; null when not shaped. */
    private volatile int[][] boxes = new int[0][];
    /** Ground and plant per covered material, set before {@link #packed} publishes it. */
    private volatile int[] coverGround = new int[0];
    private volatile int[] coverPlant = new int[0];
    /** Fluid per material that {@link #hasPlant}, set before {@link #packed} publishes it. */
    private volatile int[] submergedFluid = new int[0];
    /** Covered materials by ground << 32 | plant << 3 | quarters. */
    private final ConcurrentHashMap<Long, Integer> covered = new ConcurrentHashMap<>();

    public MaterialTable(KeyRegistry keys, Resolver resolver) {
        this.keys = keys;
        this.resolver = resolver;
    }

    /** Class and shape, with the height of the shape (16 for full and cover, 0 otherwise). */
    public static int pack(int meshClass, int shape) {
        return pack(meshClass, shape, shape >= FULL ? 16 : shape == COVER ? 16 : 0);
    }

    /** @param height the block's height in sixteenths (0..16) */
    public static int pack(int meshClass, int shape, int height) {
        return meshClass | shape << 4 | height << 8;
    }

    /** {@link #pack(int, int)} of a {@link MaterialClassifier#TRANSLUCENT} fluid. */
    public static int packFluid() {
        return pack(TRANSLUCENT, FULL) | FLUID;
    }

    @Override
    public int classOf(int material) {
        return properties(material) & 15;
    }

    /** {@link #packFluid()} of a fluid holding a plant: see {@link #hasPlant}, {@link Resolver#fluid}. */
    public static int packSubmerged() {
        return packFluid() | SUBMERGED;
    }

    @Override
    public boolean isFluid(int material) {
        return (properties(material) & FLUID) != 0;
    }

    @Override
    public boolean hasPlant(int material) {
        return (properties(material) & SUBMERGED) != 0;
    }

    @Override
    public int fluidOf(int material) {
        if (!hasPlant(material)) {
            return material;
        }
        int[] f = submergedFluid;
        return material < f.length ? f[material] : material;
    }

    @Override
    public boolean isPlant(int material) {
        return classOf(material) == PLANT;
    }

    @Override
    public int covered(int ground, int plant, int quarters) {
        if (classOf(ground) == TRANSLUCENT) {
            return ground; // plants on a fluid or glass: nothing to speckle (seen over seagrass)
        }
        int q = Math.max(1, Math.min(CoveredKey.MAX_QUARTERS, quarters));
        return covered.computeIfAbsent((long) ground << 32 | (long) plant << 3 | q,
                k -> keys.id(new CoveredKey(q, keys.key(plant), keys.key(ground)).key()));
    }

    @Override
    public int coverOf(int material) {
        return properties(material) >> COVER_SHIFT & 7;
    }

    @Override
    public int groundOf(int material) {
        if (coverOf(material) == 0) {
            return material;
        }
        int[] g = coverGround;
        return material < g.length ? g[material] : material;
    }

    @Override
    public int plantOf(int material) {
        if (coverOf(material) == 0) {
            return -1;
        }
        int[] p = coverPlant;
        return material < p.length ? p[material] : -1;
    }

    @Override
    public int shapeOf(int material) {
        return properties(material) >> 4 & 15;
    }

    @Override
    public int heightOf(int material) {
        return properties(material) >> 8 & 31;
    }

    @Override
    public int boxCount(int material) {
        return classOf(material) != SHAPED ? 0 : boxesOf(material).length;
    }

    @Override
    public int box(int material, int index) {
        return classOf(material) != SHAPED ? 0 : boxesOf(material)[index];
    }

    /** Boxes of a material known to be shaped. */
    private int[] boxesOf(int material) {
        int[][] b = boxes;
        if (material < b.length && b[material] != null) {
            return b[material];
        }
        // Read without the lock before the resolving thread published them.
        synchronized (this) {
            return boxes[material];
        }
    }

    private int properties(int material) {
        int[] p = packed;
        if (material < p.length) {
            int value = p[material];
            if (value != UNRESOLVED) {
                return value;
            }
        }
        return resolve(material);
    }

    private synchronized int resolve(int material) {
        int[] p = packed;
        if (material >= p.length) {
            int size = Math.max(material + 1, Math.max(64, p.length * 2));
            p = Arrays.copyOf(p, size);
            Arrays.fill(p, packed.length, size, UNRESOLVED);
        }
        if (p[material] == UNRESOLVED) {
            String key = keys.key(material);
            int value;
            try {
                value = resolver.resolve(key);
            } catch (RuntimeException e) {
                value = pack(OPAQUE, FULL);
            }
            CoveredKey cover = CoveredKey.parse(key);
            if (cover != null) {
                // Resolved like its ground; the shapes' side gets its parts.
                int[] g = coverGround.length > material ? coverGround : Arrays.copyOf(coverGround, p.length);
                int[] q = coverPlant.length > material ? coverPlant : Arrays.copyOf(coverPlant, p.length);
                g[material] = keys.id(cover.ground());
                q[material] = keys.id(cover.plant());
                coverGround = g;
                coverPlant = q;
                value = value & ~(7 << COVER_SHIFT) | Math.min(CoveredKey.MAX_QUARTERS, cover.quarters()) << COVER_SHIFT;
            }
            if ((value & SUBMERGED) != 0) {
                String fluid;
                try {
                    fluid = resolver.fluid(key);
                } catch (RuntimeException e) {
                    fluid = null;
                }
                if (fluid == null) {
                    value &= ~SUBMERGED;
                } else {
                    int[] f = submergedFluid.length > material ? submergedFluid : Arrays.copyOf(submergedFluid, p.length);
                    f[material] = keys.id(fluid);
                    submergedFluid = f;
                }
            }
            if ((value & 15) == SHAPED) {
                int[][] b = boxes;
                if (material >= b.length) {
                    b = Arrays.copyOf(b, p.length);
                }
                b[material] = shapeBoxes(key);
                boxes = b;
            }
            p[material] = value;
        }
        packed = p;
        return p[material];
    }

    private int[] shapeBoxes(String key) {
        int[] b;
        try {
            b = resolver.boxes(key);
        } catch (RuntimeException e) {
            b = null;
        }
        if (b == null || b.length == 0) {
            return WHOLE_VOXEL;
        }
        return b.length > ShapeBoxes.MAX ? Arrays.copyOf(b, ShapeBoxes.MAX) : b.clone();
    }
}
