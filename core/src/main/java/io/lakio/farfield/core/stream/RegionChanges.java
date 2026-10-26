package io.lakio.farfield.core.stream;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.storage.SectionStore;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The regions of one store written since they were last announced, so the
 * clients that read their indexes ask again ({@link StreamProtocol.Changed}).
 * A region is announced once its writes paused for {@code quietNanos}, or
 * {@code maxWaitNanos} after its first write not announced yet: an import
 * writing a region for minutes still reaches the clients, but each region
 * at most once per {@code maxWaitNanos}, since a client re-reading an index
 * makes the server read the whole region. Thread-safe.
 */
public final class RegionChanges {

    private final long quietNanos;
    private final long maxWaitNanos;
    /** Region key to {first write, last write} not announced yet. */
    private final Map<Long, long[]> pending = new ConcurrentHashMap<>();

    public RegionChanges(long quietNanos, long maxWaitNanos) {
        this.quietNanos = quietNanos;
        this.maxWaitNanos = maxWaitNanos;
    }

    /** The region of a section, as {@link StreamProtocol.Changed} carries it. */
    public static long regionOf(long key) {
        return LodKey.pack(LodKey.level(key), LodKey.x(key) >> SectionStore.REGION_SHIFT, 0,
                LodKey.z(key) >> SectionStore.REGION_SHIFT);
    }

    /** A section was written; any thread. */
    public void written(long key, long nowNanos) {
        pending.compute(regionOf(key), (r, t) -> {
            if (t == null) {
                return new long[]{nowNanos, nowNanos};
            }
            t[1] = nowNanos;
            return t;
        });
    }

    /** Removes and hands over the regions due now. */
    public void drainDue(long nowNanos, java.util.function.LongConsumer action) {
        Iterator<Map.Entry<Long, long[]>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, long[]> e = it.next();
            long[] t = e.getValue();
            if (nowNanos - t[1] >= quietNanos || nowNanos - t[0] >= maxWaitNanos) {
                // A write landing meanwhile is covered too: the index is read after the notice.
                if (pending.remove(e.getKey(), t)) {
                    action.accept(e.getKey());
                }
            }
        }
    }

    public int pendingRegions() {
        return pending.size();
    }
}
