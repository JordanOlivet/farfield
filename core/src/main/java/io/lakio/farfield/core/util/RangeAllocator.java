package io.lakio.farfield.core.util;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Best-fit allocator of ranges in a linear space of {@code capacity} units
 * (a GPU buffer of quads, for instance): the smallest free range that holds
 * the length, the lowest of equal ones. Free ranges are coalesced on
 * release. Not thread-safe.
 * <p>
 * It was first-fit over the free ranges in address order: with the buffer
 * fragmented by a flight's churn the walk took 8 % of the render thread
 * (ADR-140); the free ranges are now indexed by length too.
 */
public final class RangeAllocator {

    /** Free ranges: start -> length. */
    private final TreeMap<Long, Long> free = new TreeMap<>();
    /** The same free ranges as {length, start}, by length then start. */
    private final TreeSet<long[]> bySize = new TreeSet<>(
            Comparator.<long[]>comparingLong(r -> r[0]).thenComparingLong(r -> r[1]));
    private long capacity;
    private long used;

    public RangeAllocator(long capacity) {
        this.capacity = capacity;
        if (capacity > 0) {
            put(0L, capacity);
        }
    }

    /** Returns the start of a range of {@code length} units, or -1 if no free range is large enough. */
    public long allocate(long length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }
        long[] fit = bySize.ceiling(new long[]{length, Long.MIN_VALUE});
        if (fit == null) {
            return -1;
        }
        long size = fit[0];
        long start = fit[1];
        remove(start, size);
        if (size > length) {
            put(start + length, size - length);
        }
        used += length;
        return start;
    }

    private void put(long start, long length) {
        free.put(start, length);
        bySize.add(new long[]{length, start});
    }

    private void remove(long start, long length) {
        free.remove(start);
        bySize.remove(new long[]{length, start});
    }

    public void release(long start, long length) {
        long end = start + length;
        Map.Entry<Long, Long> before = free.floorEntry(start);
        if (before != null && before.getKey() + before.getValue() > start) {
            throw new IllegalStateException("range " + start + "+" + length + " is already free");
        }
        long newStart = start;
        long newLength = length;
        if (before != null && before.getKey() + before.getValue() == start) {
            newStart = before.getKey();
            newLength += before.getValue();
            remove(before.getKey(), before.getValue());
        }
        Long after = free.get(end);
        if (after != null) {
            newLength += after;
            remove(end, after);
        }
        put(newStart, newLength);
        used -= length;
    }

    /** Extends the space (the buffer grew); the new units are free. */
    public void grow(long newCapacity) {
        if (newCapacity <= capacity) {
            return;
        }
        release0(capacity, newCapacity - capacity);
        capacity = newCapacity;
    }

    private void release0(long start, long length) {
        used += length; // release() subtracts it again
        release(start, length);
    }

    public long capacity() {
        return capacity;
    }

    /**
     * The capacity a mostly empty space can be compacted down to, or -1 to
     * keep it: when at most a quarter of {@code capacity} is used, the
     * smallest {@code minimum} times a power of two that holds twice
     * {@code used}, so the compacted space is at most half full and the next
     * doubling is not right behind.
     */
    public static long shrunkCapacity(long capacity, long used, long minimum) {
        if (capacity <= minimum || used * 4 > capacity) {
            return -1;
        }
        long target = minimum;
        while (target < used * 2) {
            target *= 2;
        }
        return target < capacity ? target : -1;
    }

    public long used() {
        return used;
    }
}
