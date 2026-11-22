package io.lakio.farfield.core.mesh;

import java.util.Arrays;

/**
 * The order translucent quads of one section blend in, far to near, without
 * sorting them again each frame (ADR-176).
 *
 * <ul>
 *   <li>Within a direction: faces are drawn only from their front (back faces
 *       are culled), so every face of a group that shows has the camera on
 *       its positive side for a positive direction: the lowest plane is the
 *       farthest along any ray. A negative direction is the other way round.
 *       Sorted once at mesh time ({@link #sortFarToNear}), that order holds
 *       for every camera.</li>
 *   <li>Between directions: a section's translucent voxels are mostly thin
 *       horizontal layers (the sea's top, ice over it), whose walls (holes in
 *       the ice, a shore) stand below the top seen over them. The side faces
 *       go first, then the horizontal faces, the ones facing the camera last
 *       ({@link #directions}).</li>
 * </ul>
 */
public final class TranslucentOrder {

    private static final int[] CAMERA_ABOVE = {Quad.NEG_X, Quad.POS_X, Quad.NEG_Z, Quad.POS_Z, Quad.NEG_Y, Quad.POS_Y};
    private static final int[] CAMERA_BELOW = {Quad.NEG_X, Quad.POS_X, Quad.NEG_Z, Quad.POS_Z, Quad.POS_Y, Quad.NEG_Y};

    private TranslucentOrder() {
    }

    /**
     * The directions in drawing order: the side faces, then the horizontal
     * ones, those facing the camera last. Do not modify.
     *
     * @param cameraBelow the camera is below the section's middle
     */
    public static int[] directions(boolean cameraBelow) {
        return cameraBelow ? CAMERA_BELOW : CAMERA_ABOVE;
    }

    /**
     * Sorts {@code quads[from, to)}, all facing {@code direction}, far to
     * near for a camera in front of them: positive faces by rising plane,
     * negative ones by falling plane. On one plane of an upward group, the
     * lower fill first (a water top under the ice top of the voxel beside it).
     */
    public static void sortFarToNear(long[] quads, int from, int to, int direction) {
        int n = to - from;
        if (n < 2) {
            return;
        }
        int axis = Quad.axis(direction);
        boolean positive = Quad.positive(direction);
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            long q = quads[from + i];
            int plane = axis == 0 ? Quad.x(q) : axis == 1 ? Quad.y(q) : Quad.z(q);
            int rank = (positive ? plane : 31 - plane) * 16 + (positive ? Quad.fill(q) : 0);
            keys[i] = (long) rank << 32 | i;
        }
        Arrays.sort(keys);
        long[] sorted = new long[n];
        for (int i = 0; i < n; i++) {
            sorted[i] = quads[from + (int) keys[i]];
        }
        System.arraycopy(sorted, 0, quads, from, n);
    }
}
