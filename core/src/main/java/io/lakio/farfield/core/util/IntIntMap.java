package io.lakio.farfield.core.util;

import java.util.Arrays;

/**
 * Open-addressing map from int keys to non-negative int values, without
 * boxing. {@link #get} returns -1 for a missing key. Not thread-safe.
 */
public final class IntIntMap {

    private static final int EMPTY = Integer.MIN_VALUE;

    private int[] keys;
    private int[] values;
    private int size;
    private int mask;

    public IntIntMap(int expected) {
        int capacity = Integer.highestOneBit(Math.max(4, expected * 2 - 1)) << 1;
        allocate(capacity);
    }

    private void allocate(int capacity) {
        keys = new int[capacity];
        values = new int[capacity];
        Arrays.fill(keys, EMPTY);
        mask = capacity - 1;
        size = 0;
    }

    private static int mix(int key) {
        int h = key * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    public int get(int key) {
        int i = mix(key) & mask;
        while (true) {
            int k = keys[i];
            if (k == key) {
                return values[i];
            }
            if (k == EMPTY) {
                return -1;
            }
            i = (i + 1) & mask;
        }
    }

    /** Sets {@code key} to {@code value} (value must not be negative). */
    public void put(int key, int value) {
        if (key == EMPTY) {
            throw new IllegalArgumentException("reserved key " + key);
        }
        if ((size + 1) * 2 > keys.length) {
            int[] oldKeys = keys;
            int[] oldValues = values;
            allocate(oldKeys.length * 2);
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != EMPTY) {
                    put(oldKeys[i], oldValues[i]);
                }
            }
        }
        int i = mix(key) & mask;
        while (keys[i] != EMPTY && keys[i] != key) {
            i = (i + 1) & mask;
        }
        if (keys[i] == EMPTY) {
            size++;
        }
        keys[i] = key;
        values[i] = value;
    }

    public int size() {
        return size;
    }

    public void clear() {
        Arrays.fill(keys, EMPTY);
        size = 0;
    }
}
