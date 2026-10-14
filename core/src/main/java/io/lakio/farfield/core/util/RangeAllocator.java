package io.lakio.farfield.core.util;

import java.util.Map;
import java.util.TreeMap;

/**
 * First-fit allocator of ranges in a linear space of {@code capacity} units
 * (a GPU buffer of quads, for instance). Free ranges are coalesced on
 * release. Not thread-safe.
 */
public final class RangeAllocator {

    /** Free ranges: start -> length. */
    private final TreeMap<Long, Long> free = new TreeMap<>();
    private long capacity;
    private long used;

    public RangeAllocator(long capacity) {
        this.capacity = capacity;
        if (capacity > 0) {
            free.put(0L, capacity);
        }
    }

    /** Returns the start of a range of {@code length} units, or -1 if no free range is large enough. */
    public long allocate(long length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }
        for (Map.Entry<Long, Long> e : free.entrySet()) {
            long size = e.getValue();
            if (size >= length) {
                // Read the entry before removing it: TreeMap may reuse its node.
                long start = e.getKey();
                free.remove(start);
                if (size > length) {
                    free.put(start + length, size - length);
                }
                used += length;
                return start;
            }
        }
        return -1;
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
            free.remove(before.getKey());
        }
        Long after = free.get(end);
        if (after != null) {
            newLength += after;
            free.remove(end);
        }
        free.put(newStart, newLength);
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

    public long used() {
        return used;
    }
}
