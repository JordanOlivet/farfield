package io.lakio.farfield.core.util;

import java.util.Arrays;

/**
 * A set of longs, open addressing with linear probing, the keys mixed before
 * they are placed (ADR-143): packed keys such as {@code LodKey} spread badly
 * through {@code Long.hashCode}, which turned a {@code HashSet<Long>}'s bins
 * into trees. No boxing. Not thread-safe.
 */
public final class LongSet {

    /** A free slot. The key itself is held apart ({@link #hasFree}). */
    private static final long FREE = 0;

    private long[] keys;
    private int mask;
    private int size;
    private boolean hasFree;

    public LongSet() {
        this(16);
    }

    public LongSet(int expected) {
        int capacity = Integer.highestOneBit(Math.max(4, expected * 2 - 1)) << 1;
        keys = new long[capacity];
        mask = capacity - 1;
    }

    /** Adds {@code key}; true if it was not there. */
    public boolean add(long key) {
        if (key == FREE) {
            boolean added = !hasFree;
            hasFree = true;
            return added;
        }
        int i = slot(key);
        while (keys[i] != FREE) {
            if (keys[i] == key) {
                return false;
            }
            i = (i + 1) & mask;
        }
        keys[i] = key;
        if (++size * 2 > keys.length) {
            grow();
        }
        return true;
    }

    public boolean contains(long key) {
        if (key == FREE) {
            return hasFree;
        }
        int i = slot(key);
        while (keys[i] != FREE) {
            if (keys[i] == key) {
                return true;
            }
            i = (i + 1) & mask;
        }
        return false;
    }

    public int size() {
        return size + (hasFree ? 1 : 0);
    }

    /** Empties the set, keeping its room. */
    public void clear() {
        if (size > 0) {
            Arrays.fill(keys, FREE);
        }
        size = 0;
        hasFree = false;
    }

    private int slot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ h >>> 32) & mask;
    }

    private void grow() {
        long[] old = keys;
        keys = new long[old.length * 2];
        mask = keys.length - 1;
        for (long k : old) {
            if (k != FREE) {
                int i = slot(k);
                while (keys[i] != FREE) {
                    i = (i + 1) & mask;
                }
                keys[i] = k;
            }
        }
    }
}
