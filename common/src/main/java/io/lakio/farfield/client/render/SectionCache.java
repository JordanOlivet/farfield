package io.lakio.farfield.client.render;

import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.world.LodWorld;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decoded stored sections for the mesher threads: meshing a section reads
 * it and its six same-level neighbors, so every section of an area was
 * decoded about seven times. Least recently used entries go first. Sections
 * in it are only read, never changed, so the mesher threads share them.
 *
 * <p>A section the store rewrites leaves the cache ({@link #invalidate}). A
 * read that started before that write must not put the old data back: each
 * invalidation is stamped, and a read stamped earlier is not kept.
 */
final class SectionCache {

    private static final int CAPACITY = 1024;
    /** Stamps kept per key before they are forgotten and a floor takes over. */
    private static final int STAMPS = 8192;

    private final Map<Long, LodSection> sections = new LinkedHashMap<>(CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, LodSection> eldest) {
            return size() > CAPACITY;
        }
    };
    private final Long2LongOpenHashMap invalidated = new Long2LongOpenHashMap();
    /** Every read stamped before this is refused (stamps were forgotten). */
    private long floor;
    private long clock;
    private long hits;
    private long reads;

    /** The section at {@code key}, decoded once while it stays in the cache; null when not stored. */
    LodSection read(LodWorld world, long key) throws IOException {
        long stamp;
        synchronized (this) {
            reads++;
            LodSection cached = sections.get(key);
            if (cached != null) {
                hits++;
                return cached;
            }
            stamp = ++clock;
        }
        LodSection section = world.exists(key) ? world.read(key) : null;
        if (section != null) {
            synchronized (this) {
                if (stamp > floor && invalidated.get(key) < stamp) {
                    sections.put(key, section);
                }
            }
        }
        return section;
    }

    /** The store wrote {@code key}: its cached data, and reads already running, are stale. Any thread. */
    synchronized void invalidate(long key) {
        sections.remove(key);
        if (invalidated.size() >= STAMPS) {
            invalidated.clear();
            floor = clock;
        }
        invalidated.put(key, ++clock);
    }

    synchronized void clear() {
        sections.clear();
        invalidated.clear();
        floor = ++clock;
    }

    /** Reads served from the cache, and all reads, since the last call. */
    synchronized long[] takeCounts() {
        long[] counts = {hits, reads};
        hits = 0;
        reads = 0;
        return counts;
    }
}
