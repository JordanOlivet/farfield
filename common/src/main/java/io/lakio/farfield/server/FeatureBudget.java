package io.lakio.farfield.server;

import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The preview's cost guard for features (ADR-131): every placed feature's
 * time per chunk in the stand-in world (the stand-in chunks it made
 * meanwhile left out) and the blocks it set that the preview keeps (above
 * the ground or in the top blocks a lake digs, and drawn by the LODs), per
 * biome of the chunk: a feature that does nothing where it ran first may
 * matter in another biome (freeze_top_layer lays the snow of cold biomes
 * only). Once a feature has run in {@link #MIN_CHUNKS} chunks of a biome, it
 * is left out of the preview in that biome when it costs at least {@link #COSTLY_NANOS} a chunk and keeps
 * fewer than {@link #KEPT_PER_MS} blocks per millisecond: a mod's feature
 * that searches far for few blocks (Supplementaries' shore barnacles took
 * 4.1 of a tile's 8.3 s in the user's pack). No list of mods: what it costs
 * and what it leaves decide. For the server's session; thread-safe.
 */
final class FeatureBudget {

    /** Chunks a feature runs in before it is judged. */
    static final int MIN_CHUNKS = 64;
    /** Time a chunk from which a feature counts as costly: half a millisecond (a pack tile's features: 4 to 5 ms a chunk). */
    static final long COSTLY_NANOS = 500_000;
    /** Blocks kept per millisecond under which a costly feature is not worth its time. */
    static final double KEPT_PER_MS = 4;

    private static final class Stat {
        final AtomicLong nanos = new AtomicLong();
        final AtomicLong chunks = new AtomicLong();
        final AtomicLong kept = new AtomicLong();
    }

    private record Key(PlacedFeature feature, int biome) {
    }

    private final Map<Key, Stat> stats = new ConcurrentHashMap<>();
    private final Set<Key> excluded = ConcurrentHashMap.newKeySet();

    /** Whether a feature is left out in chunks of a biome (by the world's biome ids). */
    boolean excluded(PlacedFeature feature, int biome) {
        return !excluded.isEmpty() && excluded.contains(new Key(feature, biome));
    }

    /** Features left out so far. */
    int excludedCount() {
        return excluded.size();
    }

    /**
     * A feature ran in one chunk of a biome for {@code nanos} and set
     * {@code kept} blocks the preview keeps; true when this leaves it out in
     * that biome from now on.
     */
    boolean ran(PlacedFeature feature, int biome, long nanos, int kept) {
        Key key = new Key(feature, biome);
        Stat s = stats.computeIfAbsent(key, f -> new Stat());
        long total = s.nanos.addAndGet(nanos);
        long chunks = s.chunks.incrementAndGet();
        long blocks = s.kept.addAndGet(kept);
        if (chunks < MIN_CHUNKS || total / chunks < COSTLY_NANOS || blocks >= KEPT_PER_MS * total / 1e6) {
            return false;
        }
        return excluded.add(key);
    }

    /** What a feature cost so far in a biome: ms per chunk, kept blocks per chunk, chunks. */
    String describe(PlacedFeature feature, int biome) {
        Stat s = stats.get(new Key(feature, biome));
        if (s == null) {
            return "not run";
        }
        long chunks = Math.max(1, s.chunks.get());
        return String.format(java.util.Locale.ROOT, "%.2f ms a chunk for %.2f kept blocks a chunk over %d chunks",
                s.nanos.get() / 1e6 / chunks, s.kept.get() / (double) chunks, s.chunks.get());
    }
}
