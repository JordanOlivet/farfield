package io.lakio.farfield.core.anvil;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps string keys (canonical block states such as
 * {@code minecraft:oak_log[axis=y]}, or biome ids) to dense int ids, in order
 * of first sight. Thread-safe: decoder threads share one instance per key
 * kind; lookups of known keys are lock-free.
 *
 * <p>Ids are only meaningful within one registry instance. Persisted data
 * must store the keys (ADR-008), never these ids.
 */
public final class KeyRegistry {

    private final ConcurrentHashMap<String, Integer> ids = new ConcurrentHashMap<>();
    private volatile String[] keys = new String[64];
    private int size;
    /** A mirror of another store's table: only {@link #idForced} adds keys (ADR-068). */
    private volatile boolean frozen;

    /**
     * The id of a key, added when new.
     *
     * @throws IllegalStateException for a new key once {@link #freeze frozen}
     */
    public int id(String key) {
        Integer id = ids.get(key);
        if (id != null) {
            return id;
        }
        if (frozen) {
            throw new IllegalStateException("key table frozen, cannot add " + key);
        }
        return idForced(key);
    }

    /** The id of a key, or -1 if it is not in the table. */
    public int find(String key) {
        Integer id = ids.get(key);
        return id == null ? -1 : id;
    }

    /**
     * Stops {@link #id} from adding keys: the table mirrors another one id
     * for id, and a key added here would shift every later one.
     */
    public void freeze() {
        frozen = true;
    }

    public boolean frozen() {
        return frozen;
    }

    /** {@link #id}, adding the key even when frozen: for the keys of the table mirrored. */
    public int idForced(String key) {
        Integer id = ids.get(key);
        if (id != null) {
            return id;
        }
        synchronized (this) {
            id = ids.get(key);
            if (id != null) {
                return id;
            }
            int next = size;
            String[] k = keys;
            if (next == k.length) {
                k = Arrays.copyOf(k, next * 2);
            }
            k[next] = key;
            keys = k;
            size = next + 1;
            ids.put(key, next);
            return next;
        }
    }

    /** The key of an id returned by {@link #id(String)}. */
    public String key(int id) {
        String[] k = keys;
        if (id < 0 || id >= k.length || k[id] == null) {
            throw new IllegalArgumentException("unknown id " + id);
        }
        return k[id];
    }

    public synchronized int size() {
        return size;
    }
}
