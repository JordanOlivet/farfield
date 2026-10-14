package io.lakio.farfield.core.mesh;

import java.util.Arrays;

/**
 * Packed quads of one section, grouped by face direction so a renderer can
 * skip the groups facing away from the camera. Reusable.
 */
public final class MeshBuffer {

    private final long[][] quads = new long[Quad.DIRECTIONS][];
    private final int[] counts = new int[Quad.DIRECTIONS];

    public MeshBuffer() {
        for (int d = 0; d < Quad.DIRECTIONS; d++) {
            quads[d] = new long[256];
        }
    }

    public void clear() {
        Arrays.fill(counts, 0);
    }

    void add(int direction, long quad) {
        int n = counts[direction];
        long[] q = quads[direction];
        if (n == q.length) {
            q = Arrays.copyOf(q, n * 2);
            quads[direction] = q;
        }
        q[n] = quad;
        counts[direction] = n + 1;
    }

    public int count(int direction) {
        return counts[direction];
    }

    /** Quads of a direction; only the first {@link #count} entries are valid. Do not modify. */
    public long[] quads(int direction) {
        return quads[direction];
    }

    public int total() {
        int n = 0;
        for (int c : counts) {
            n += c;
        }
        return n;
    }
}
