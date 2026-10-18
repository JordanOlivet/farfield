package io.lakio.farfield.core.mesh;

/**
 * Boxes of a {@link MaterialClassifier#SHAPED} block in its voxel, each packed
 * in one {@code int}: min and max corners in sixteenths of a block (0..16),
 * five bits per coordinate.
 *
 * <pre>
 *   bits  0-14  min x, y, z
 *   bits 15-29  max x, y, z
 * </pre>
 *
 * <p>A valid box is never 0 (its max is above its min on every axis).
 */
public final class ShapeBoxes {

    /** Most boxes a block keeps: level-0 quads carry the box index in three bits. */
    public static final int MAX = 8;

    private ShapeBoxes() {
    }

    public static int pack(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return minX | minY << 5 | minZ << 10 | maxX << 15 | maxY << 20 | maxZ << 25;
    }

    /** Min coordinate along an axis (0 X, 1 Y, 2 Z), in sixteenths. */
    public static int min(int box, int axis) {
        return box >> (axis * 5) & 31;
    }

    /** Max coordinate along an axis (0 X, 1 Y, 2 Z), in sixteenths. */
    public static int max(int box, int axis) {
        return box >> (15 + axis * 5) & 31;
    }

    /** Whether the box reaches its voxel's side facing {@code direction} (see {@link Quad}). */
    public static boolean touches(int box, int direction) {
        int axis = Quad.axis(direction);
        return Quad.positive(direction) ? max(box, axis) == 16 : min(box, axis) == 0;
    }
}
