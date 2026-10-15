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
 *   bits 28-45  material id (18 bits)
 *   bits 46-55  biome id + 1 (0: none or unknown), set only for materials
 *               whose color depends on the biome
 *   bits 56-59  sky light in front of the face (0..15)
 *   bits 60-63  block light in front of the face (0..15)
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

    /** Largest material id; ids are a world's seen block states, far below it. */
    public static final int MAX_MATERIAL = (1 << 18) - 1;
    /** Largest biome id a quad can carry; higher ids are stored as unknown. */
    public static final int MAX_BIOME = (1 << 10) - 2;

    private Quad() {
    }

    /** A quad without biome, in full sky light. */
    public static long pack(int x, int y, int z, int sizeU, int sizeV, int direction, int material) {
        return pack(x, y, z, sizeU, sizeV, direction, material, -1, 15, 0);
    }

    /** A quad in full sky light; {@code biome} is a biome id, or -1 for none or unknown. */
    public static long pack(int x, int y, int z, int sizeU, int sizeV, int direction, int material, int biome) {
        return pack(x, y, z, sizeU, sizeV, direction, material, biome, 15, 0);
    }

    /**
     * @param biome biome id, or -1 for none or unknown
     * @param sky   sky light in front of the face, 0..15
     * @param block block light in front of the face, 0..15
     */
    public static long pack(int x, int y, int z, int sizeU, int sizeV, int direction, int material, int biome,
                            int sky, int block) {
        long biomeBits = biome < 0 || biome > MAX_BIOME ? 0 : biome + 1;
        return (long) x
                | (long) y << 5
                | (long) z << 10
                | (long) (sizeU - 1) << 15
                | (long) (sizeV - 1) << 20
                | (long) direction << 25
                | (long) (material & MAX_MATERIAL) << 28
                | biomeBits << 46
                | (long) (sky & 15) << 56
                | (long) (block & 15) << 60;
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

    /** Biome id, or -1 for none or unknown. */
    public static int biome(long q) {
        return (int) (q >>> 46 & 0x3FF) - 1;
    }

    public static int skyLight(long q) {
        return (int) (q >>> 56) & 15;
    }

    public static int blockLight(long q) {
        return (int) (q >>> 60) & 15;
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
                + " material " + material(q) + (biome(q) >= 0 ? " biome " + biome(q) : "")
                + " light " + skyLight(q) + "/" + blockLight(q) + "]";
    }
}
