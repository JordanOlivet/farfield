package io.lakio.farfield.core.select;

import java.util.Arrays;

/**
 * The order in which a renderer asks for the meshes (or a server for the
 * preview tiles) a selection wants: nearest first, with what lies behind the
 * camera counted up to {@link #BEHIND} times farther, so terrain fills in
 * around and in front of the player first. Throughput stays the same; only
 * what comes first changes (ADR-056).
 */
public final class WantOrder {

    /** How much farther a section straight behind the camera counts. */
    public static final double BEHIND = 4;

    private long[] keys = new long[256];
    /** Score bits in the high half (positive floats sort as their bits), the index of the key in the low half. */
    private long[] entries = new long[256];
    private int size;

    /**
     * @param distance blocks from the camera to the nearest point of the section
     * @param facing   cosine between the view direction and the direction to the
     *                 section: 1 in front (no penalty), -1 straight behind
     */
    public static double score(double distance, double facing) {
        double f = Math.max(-1, Math.min(1, facing));
        return distance * (1 + (BEHIND - 1) * (1 - f) / 2);
    }

    public void clear() {
        size = 0;
    }

    public void add(long key, double score) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            entries = Arrays.copyOf(entries, size * 2);
        }
        keys[size] = key;
        float s = (float) Math.max(0, score);
        entries[size] = (long) Float.floatToIntBits(s) << 32 | size;
        size++;
    }

    /** Sorts the keys by score, lowest first; equal scores keep the order they were added in. */
    public void sort() {
        Arrays.sort(entries, 0, size);
    }

    public int size() {
        return size;
    }

    /** The {@code i}-th key, in score order after {@link #sort}. */
    public long key(int i) {
        return keys[(int) entries[i]];
    }
}
