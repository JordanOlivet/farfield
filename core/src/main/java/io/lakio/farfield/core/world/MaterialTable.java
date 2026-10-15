package io.lakio.farfield.core.world;

import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.lod.MipBuilder;
import io.lakio.farfield.core.mesh.MaterialClassifier;

import java.util.Arrays;

/**
 * Per-material properties the engine needs, indexed by block-state id of a
 * world's {@link KeyRegistry}: the mesher's class, the downsampling shape
 * and the block's height in sixteenths. Resolved lazily from the key by the Minecraft side on first use and
 * cached; thread-safe.
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
    }

    /** Fallback for engines without a game: air keys are empty, all else full opaque cubes. */
    public static final Resolver SIMPLE = key -> {
        String name = key.indexOf('[') < 0 ? key : key.substring(0, key.indexOf('['));
        boolean air = name.equals("minecraft:air") || name.equals("minecraft:cave_air")
                || name.equals("minecraft:void_air");
        return air ? pack(AIR, EMPTY) : pack(OPAQUE, FULL);
    };

    private static final int UNRESOLVED = -1;

    private final KeyRegistry keys;
    private final Resolver resolver;
    private volatile int[] packed = new int[0];

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

    @Override
    public int classOf(int material) {
        return properties(material) & 15;
    }

    @Override
    public int shapeOf(int material) {
        return properties(material) >> 4 & 15;
    }

    @Override
    public int heightOf(int material) {
        return properties(material) >> 8 & 31;
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
            int value;
            try {
                value = resolver.resolve(keys.key(material));
            } catch (RuntimeException e) {
                value = pack(OPAQUE, FULL);
            }
            p[material] = value;
        }
        packed = p;
        return p[material];
    }
}
