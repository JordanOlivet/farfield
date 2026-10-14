package io.lakio.farfield.core.mesh;

/**
 * A quad packed in one {@code long} (8 bytes), meant for vertex pulling: the
 * shader expands it to four corners.
 *
 * <pre>
 *   bits  0- 4  x of the origin voxel (0..31)
 *   bits  5- 9  y
 *   bits 10-14  z
 *   bits 15-19  size along the face's U axis, minus 1
 *   bits 20-24  size along the face's V axis, minus 1
 *   bits 25-27  direction (see below)
 *   bits 28-47  material id (20 bits)
 *   bits 48-63  reserved (light, AO)
 * </pre>
 *
 * <p>Directions: 0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z. A face lies on the side
 * of its voxels facing its direction. The plane axes are cyclic: X faces
 * span (U, V) = (Y, Z), Y faces (Z, X), Z faces (X, Y). The quad covers the
 * voxels from its origin to origin + (sizeU - 1) along U and
 * origin + (sizeV - 1) along V.
 */
public final class Quad {

    public static final int NEG_X = 0;
    public static final int POS_X = 1;
    public static final int NEG_Y = 2;
    public static final int POS_Y = 3;
    public static final int NEG_Z = 4;
    public static final int POS_Z = 5;
    public static final int DIRECTIONS = 6;

    public static final int MAX_MATERIAL = (1 << 20) - 1;

    private Quad() {
    }

    public static long pack(int x, int y, int z, int sizeU, int sizeV, int direction, int material) {
        return (long) x
                | (long) y << 5
                | (long) z << 10
                | (long) (sizeU - 1) << 15
                | (long) (sizeV - 1) << 20
                | (long) direction << 25
                | (long) material << 28;
    }

    public static int x(long q) {
        return (int) q & 31;
    }

    public static int y(long q) {
        return (int) (q >>> 5) & 31;
    }

    public static int z(long q) {
        return (int) (q >>> 10) & 31;
    }

    public static int sizeU(long q) {
        return ((int) (q >>> 15) & 31) + 1;
    }

    public static int sizeV(long q) {
        return ((int) (q >>> 20) & 31) + 1;
    }

    public static int direction(long q) {
        return (int) (q >>> 25) & 7;
    }

    public static int material(long q) {
        return (int) (q >>> 28) & MAX_MATERIAL;
    }

    /** Axis of a direction: 0 X, 1 Y, 2 Z. */
    public static int axis(int direction) {
        return direction >> 1;
    }

    public static boolean positive(int direction) {
        return (direction & 1) != 0;
    }

    public static String toString(long q) {
        return "Quad[" + "-+".charAt(direction(q) & 1) + "XYZ".charAt(axis(direction(q)))
                + " at " + x(q) + "," + y(q) + "," + z(q) + " size " + sizeU(q) + "x" + sizeV(q)
                + " material " + material(q) + "]";
    }
}
