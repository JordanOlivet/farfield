package io.lakio.farfield.core.util;

/**
 * A reusable byte buffer: a backing array that only grows, plus the length of
 * the valid data in it. One per worker thread avoids allocating per chunk.
 */
public final class GrowableBytes {

    private byte[] data;
    private int length;

    public GrowableBytes(int initialCapacity) {
        this.data = new byte[Math.max(16, initialCapacity)];
    }

    public byte[] data() {
        return data;
    }

    public int length() {
        return length;
    }

    public void setLength(int length) {
        if (length < 0 || length > data.length) {
            throw new IllegalArgumentException("length outside capacity: " + length);
        }
        this.length = length;
    }

    /**
     * Makes room for at least {@code capacity} bytes, keeping the first
     * {@link #length()} bytes, and returns the (possibly new) backing array.
     */
    public byte[] ensureCapacity(int capacity) {
        if (capacity > data.length) {
            int grown = (int) Math.min(Integer.MAX_VALUE - 8, Math.max((long) capacity, data.length * 2L));
            byte[] bigger = new byte[grown];
            System.arraycopy(data, 0, bigger, 0, length);
            data = bigger;
        }
        return data;
    }
}
