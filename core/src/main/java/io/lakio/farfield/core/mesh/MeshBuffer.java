package io.lakio.farfield.core.mesh;

import java.util.Arrays;

/**
 * Packed quads of one section, grouped by face direction so a renderer can
 * skip the groups facing away from the camera, and the plants' diagonals in
 * a list of their own ({@link #PLANTS}). Reusable.
 */
public final class MeshBuffer {

    /** The list of the {@link MaterialClassifier#PLANT} quads, after the six directions. */
    public static final int PLANTS = Quad.DIRECTIONS;
    private static final int LISTS = PLANTS + 1;

    private final long[][] quads = new long[LISTS][];
    private final int[] counts = new int[LISTS];

    public MeshBuffer() {
        for (int d = 0; d < LISTS; d++) {
            quads[d] = new long[256];
        }
    }

    public void clear() {
        Arrays.fill(counts, 0);
    }

    /** @param list a face direction, or {@link #PLANTS} */
    void add(int list, long quad) {
        int n = counts[list];
        long[] q = quads[list];
        if (n == q.length) {
            q = Arrays.copyOf(q, n * 2);
            quads[list] = q;
        }
        q[n] = quad;
        counts[list] = n + 1;
    }

    /** @param list a face direction, or {@link #PLANTS} */
    public int count(int list) {
        return counts[list];
    }

    /** Quads of a direction or of {@link #PLANTS}; only the first {@link #count} entries are valid. Do not modify. */
    public long[] quads(int list) {
        return quads[list];
    }

    public int total() {
        int n = 0;
        for (int c : counts) {
            n += c;
        }
        return n;
    }
}
