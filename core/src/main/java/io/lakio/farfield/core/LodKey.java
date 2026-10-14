package io.lakio.farfield.core;

/**
 * Packs the position of an LOD node (detail level and node coordinates) into a
 * single {@code long}, for use as a map or storage key.
 *
 * <p>Node coordinates are expressed in nodes of the given level, not in
 * blocks: the block size of a node is decided by the data model. Layout,
 * from the most significant bit:
 *
 * <pre>
 *   level  4 bits  unsigned, 0..15
 *   x     24 bits  signed
 *   z     24 bits  signed
 *   y     12 bits  signed
 * </pre>
 *
 * <p>24 signed bits cover ±8,388,607 nodes, enough for the ±30,000,000-block
 * world border at any node size of 4 blocks or more; 12 signed bits cover
 * ±2047 nodes vertically.
 */
public final class LodKey {

    public static final int MAX_LEVEL = 15;
    public static final int XZ_MIN = -(1 << 23);
    public static final int XZ_MAX = (1 << 23) - 1;
    public static final int Y_MIN = -(1 << 11);
    public static final int Y_MAX = (1 << 11) - 1;

    private static final int Y_BITS = 12;
    private static final int XZ_BITS = 24;
    private static final int Z_SHIFT = Y_BITS;
    private static final int X_SHIFT = Z_SHIFT + XZ_BITS;
    private static final int LEVEL_SHIFT = X_SHIFT + XZ_BITS;

    private static final long Y_MASK = (1L << Y_BITS) - 1;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;

    private LodKey() {
    }

    public static long pack(int level, int x, int y, int z) {
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level out of range: " + level);
        }
        if (x < XZ_MIN || x > XZ_MAX || z < XZ_MIN || z > XZ_MAX) {
            throw new IllegalArgumentException("x/z out of range: " + x + ", " + z);
        }
        if (y < Y_MIN || y > Y_MAX) {
            throw new IllegalArgumentException("y out of range: " + y);
        }
        return ((long) level << LEVEL_SHIFT)
                | ((x & XZ_MASK) << X_SHIFT)
                | ((z & XZ_MASK) << Z_SHIFT)
                | (y & Y_MASK);
    }

    public static int level(long key) {
        return (int) (key >>> LEVEL_SHIFT);
    }

    public static int x(long key) {
        return (int) (key << (64 - X_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
    }

    public static int z(long key) {
        return (int) (key << (64 - Z_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
    }

    public static int y(long key) {
        return (int) (key << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    /** Key of the node one level coarser that contains this node. */
    public static long parent(long key) {
        int level = level(key);
        if (level == MAX_LEVEL) {
            throw new IllegalArgumentException("already at the coarsest level");
        }
        return pack(level + 1, x(key) >> 1, y(key) >> 1, z(key) >> 1);
    }

    public static String toString(long key) {
        return "L" + level(key) + "[" + x(key) + ", " + y(key) + ", " + z(key) + "]";
    }
}
