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
 *   bits 28-43  material id (16 bits)
 *   bits 44-52  biome id + 1 (0: none or unknown), set only for materials
 *               whose color depends on the biome
 *   bits 53-56  sky light in front of the face (0..15)
 *   bits 57-60  block light in front of the face (0..15)
 *   bits 61-63  fill of its voxels in eighths, minus one (7: full; coarse
 *               levels, see {@code LodSection#fill}); on the finest level,
 *               the box index of a {@link MaterialClassifier#SHAPED} voxel
 * </pre>
 *
 * <p>Directions: 0 -X, 1 +X, 2 -Y, 3 +Y, 4 -Z, 5 +Z; 6 and 7 are the two
 * vertical diagonals of a {@link MaterialClassifier#PLANT} voxel, unit
 * quads from (0, 0) to (1, 1) and from (1, 0) to (0, 1) in (X, Z), which the
 * shader turns toward the camera. A face lies on the side
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
    /** A plant's diagonal from its voxel's (x, z) corner to (x + 1, z + 1). */
    public static final int DIAGONAL_A = 6;
    /** A plant's diagonal from (x + 1, z) to (x, z + 1). */
    public static final int DIAGONAL_B = 7;

    /** Largest material id; ids are a world's seen block states (6,460 in a 398-mod pack). */
    public static final int MAX_MATERIAL = (1 << 16) - 1;
    /** Largest biome id a quad can carry (127 in a 398-mod pack); higher ids are stored as unknown. */
    public static final int MAX_BIOME = (1 << 9) - 2;
    /** Fill of a full voxel, in eighths. */
    public static final int FULL_FILL = 8;

    private Quad() {
    }

    /** A quad without biome, in full sky light. */
    public static long pack(int x, int y, int z, int sizeU, int sizeV, int direction, int material) {
        return pack(x, y, z, sizeU, sizeV, direction, material, -1, 15, 0, FULL_FILL);
    }

    /** A quad in full sky light; {@code biome} is a biome id, or -1 for none or unknown. */
    public static long pack(int x, int y, int z, int sizeU, int sizeV, int direction, int material, int biome) {
        return pack(x, y, z, sizeU, sizeV, direction, material, biome, 15, 0, FULL_FILL);
    }

    /**
     * @param biome biome id, or -1 for none or unknown
     * @param sky   sky light in front of the face, 0..15
     * @param block block light in front of the face, 0..15
     * @param fill  fill of its voxels in eighths, 1..8
     */
    public static long pack(int x, int y, int z, int sizeU, int sizeV, int direction, int material, int biome,
                            int sky, int block, int fill) {
        long biomeBits = biome < 0 || biome > MAX_BIOME ? 0 : biome + 1;
        return (long) x
                | (long) y << 5
                | (long) z << 10
                | (long) (sizeU - 1) << 15
                | (long) (sizeV - 1) << 20
                | (long) direction << 25
                | (long) (material & MAX_MATERIAL) << 28
                | biomeBits << 44
                | (long) (sky & 15) << 53
                | (long) (block & 15) << 57
                | (long) (Math.max(1, Math.min(FULL_FILL, fill)) - 1) << 61;
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

    /** Fill of the quad's voxels in eighths (8: full). */
    public static int fill(long q) {
        return (int) (q >>> 61) + 1;
    }

    /** Biome id, or -1 for none or unknown. */
    public static int biome(long q) {
        return (int) (q >>> 44 & 0x1FF) - 1;
    }

    public static int skyLight(long q) {
        return (int) (q >>> 53) & 15;
    }

    public static int blockLight(long q) {
        return (int) (q >>> 57) & 15;
    }

    /**
     * The octants of its section (16 voxels a side) whose voxels the quad
     * covers, one bit per octant {@code x | z << 1 | y << 2}, as
     * {@link io.lakio.farfield.core.select.LodSelector} numbers children.
     */
    public static int octants(long q) {
        int x = x(q);
        int y = y(q);
        int z = z(q);
        int x1 = x;
        int y1 = y;
        int z1 = z;
        int d = direction(q);
        if (d < DIRECTIONS) {
            // The plane axes are cyclic: X faces span (Y, Z), Y faces (Z, X), Z faces (X, Y).
            int u = sizeU(q) - 1;
            int v = sizeV(q) - 1;
            switch (axis(d)) {
                case 0 -> {
                    y1 += u;
                    z1 += v;
                }
                case 1 -> {
                    z1 += u;
                    x1 += v;
                }
                default -> {
                    x1 += u;
                    y1 += v;
                }
            }
        }
        int mask = 0;
        for (int oy = y >> 4; oy <= y1 >> 4; oy++) {
            for (int oz = z >> 4; oz <= z1 >> 4; oz++) {
                for (int ox = x >> 4; ox <= x1 >> 4; ox++) {
                    mask |= 1 << (ox | oz << 1 | oy << 2);
                }
            }
        }
        return mask;
    }

    /** Axis of a direction: 0 X, 1 Y, 2 Z. */
    public static int axis(int direction) {
        return direction >> 1;
    }

    public static boolean positive(int direction) {
        return (direction & 1) != 0;
    }

    public static String toString(long q) {
        String direction = direction(q) >= DIRECTIONS ? (direction(q) == DIAGONAL_A ? "/" : "\\")
                : "" + "-+".charAt(direction(q) & 1) + "XYZ".charAt(axis(direction(q)));
        return "Quad[" + direction
                + " at " + x(q) + "," + y(q) + "," + z(q) + " size " + sizeU(q) + "x" + sizeV(q)
                + " material " + material(q) + (biome(q) >= 0 ? " biome " + biome(q) : "")
                + " light " + skyLight(q) + "/" + blockLight(q) + "]";
    }
}
