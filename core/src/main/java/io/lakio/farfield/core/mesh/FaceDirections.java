package io.lakio.farfield.core.mesh;

/**
 * Which face directions of a section can face the camera. Every face of a
 * section lies in its box: a face toward +X at plane x is seen from the front
 * only when the camera is at x' > x, so no +X face of the section can face a
 * camera at or before the box's min X. A renderer that keeps a section's
 * quads grouped by direction skips the other groups before the vertex shader
 * runs on them (the rasterizer would cull them afterwards anyway).
 */
public final class FaceDirections {

    /** Every direction: the camera inside the box (or within the margin) sees all six. */
    public static final int ALL = (1 << Quad.DIRECTIONS) - 1;
    /** Blocks of slack around the box, against float rounding of the vertex positions. */
    private static final float MARGIN = 1f;

    private FaceDirections() {
    }

    /**
     * @param minX min corner of the section box minus the camera position, in blocks
     * @param size edge of the box in blocks
     * @return one bit per {@link Quad} direction that can face the camera
     */
    public static int front(float minX, float minY, float minZ, float size) {
        return axis(minX, size) | axis(minY, size) << 2 | axis(minZ, size) << 4;
    }

    /** Bit 0: the negative direction can face the camera, bit 1: the positive one. */
    private static int axis(float min, float size) {
        int mask = 0;
        if (min + size > -MARGIN) { // camera before the max plane: faces toward -axis
            mask |= 1;
        }
        if (min < MARGIN) { // camera past the min plane: faces toward +axis
            mask |= 2;
        }
        return mask;
    }
}
