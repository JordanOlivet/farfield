package io.lakio.farfield.core.world;

import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.lod.MipBuilder;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.mesh.ShapeBoxes;

import java.util.Arrays;

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
    private static final int[] WHOLE_VOXEL = {ShapeBoxes.pack(0, 0, 0, 16, 16, 16)};

    private final KeyRegistry keys;
    private final Resolver resolver;
    private volatile int[] packed = new int[0];
    /** Boxes per material, set before {@link #packed} publishes the material; null when not shaped. */
    private volatile int[][] boxes = new int[0][];

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

    @Override
    public boolean isFluid(int material) {
        return (properties(material) & FLUID) != 0;
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
