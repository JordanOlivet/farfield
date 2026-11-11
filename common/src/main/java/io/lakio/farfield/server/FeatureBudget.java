package io.lakio.farfield.server;

import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The preview's cost guard for features (ADR-131): every placed feature's
 * time per chunk in the stand-in world (the stand-in chunks it made
 * meanwhile left out) and the blocks it set that the preview keeps (above
 * the ground or in the top blocks a lake digs, and drawn by the LODs), per
 * biome of the chunk: a feature that does nothing where it ran first may
 * matter in another biome (freeze_top_layer lays the snow of cold biomes
 * only). Once a feature has run in {@link #MIN_CHUNKS} chunks of a biome, it
 * is left out of the preview in that biome when it costs at least {@link
 * #COSTLY_NANOS} a chunk and keeps fewer than {@link #KEPT_PER_MS} blocks
 * per millisecond: a mod's feature that searches far for few blocks
 * (Supplementaries' shore barnacles took 4.1 of a tile's 8.3 s in the
 * user's pack, for no block kept). No list of mods: what it costs and what
 * it leaves decide.
 *
 * <p>A feature left out still runs in one chunk in {@link #RECHECK_EVERY}
 * of its biome, so its figures stay current and it comes back when it no
 * longer meets the rule (ADR-132). The features left out and their figures
 * are kept in the world ({@link #save}, {@link #load}), so a restart does not
 * pay their first chunks again. Thread-safe.
 */
final class FeatureBudget {

    /** Chunks a feature runs in before it is judged. */
    static final int MIN_CHUNKS = 64;
    /** Time a chunk from which a feature counts as costly: half a millisecond (a pack tile's features: 4 to 5 ms a chunk). */
    static final long COSTLY_NANOS = 500_000;
    /** Blocks kept per millisecond under which a costly feature is not worth its time. */
    static final double KEPT_PER_MS = 4;
    /**
     * A feature left out comes back only this far inside the rule (a quarter
     * cheaper, or a quarter more kept blocks): one at the edge went in and
     * out at every re-check.
     */
    static final double BACK_MARGIN = 1.25;
    /** A feature left out runs anyway in one chunk in this many, to stay measured. */
    static final int RECHECK_EVERY = 64;
    private static final int FILE_VERSION = 1;

    private static final class Stat {
        final AtomicLong nanos = new AtomicLong();
        final AtomicLong chunks = new AtomicLong();
        final AtomicLong kept = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
    }

    private record Key(PlacedFeature feature, int biome) {
    }

    private final Map<Key, Stat> stats = new ConcurrentHashMap<>();
    private final Set<Key> excluded = ConcurrentHashMap.newKeySet();
    /** The features left out or their figures changed since the last {@link #save}. */
    private final AtomicBoolean dirty = new AtomicBoolean();

    /**
     * Whether a feature is left out in a chunk of a biome (by the world's
     * biome ids): one call in {@link #RECHECK_EVERY} says no, a re-check.
     */
    boolean excluded(PlacedFeature feature, int biome) {
        if (excluded.isEmpty()) {
            return false;
        }
        Key key = new Key(feature, biome);
        if (!excluded.contains(key)) {
            return false;
        }
        Stat s = stats.get(key);
        return s == null || s.skipped.incrementAndGet() % RECHECK_EVERY != 0;
    }

    /** Features left out so far, every biome counted. */
    int excludedCount() {
        return excluded.size();
    }

    /**
     * A feature ran in one chunk of a biome for {@code nanos} and set
     * {@code kept} blocks the preview keeps: 1 when this leaves it out in
     * that biome from now on, -1 when it brings it back, 0 otherwise.
     */
    int ran(PlacedFeature feature, int biome, long nanos, int kept) {
        Key key = new Key(feature, biome);
        Stat s = stats.computeIfAbsent(key, f -> new Stat());
        long total = s.nanos.addAndGet(nanos);
        long chunks = s.chunks.incrementAndGet();
        long blocks = s.kept.addAndGet(kept);
        boolean costly = chunks >= MIN_CHUNKS && total / chunks >= COSTLY_NANOS && blocks < KEPT_PER_MS * total / 1e6;
        if (excluded.contains(key)) {
            dirty.set(true);
            // Back only well inside the rule.
            costly = total / chunks * BACK_MARGIN >= COSTLY_NANOS && blocks < KEPT_PER_MS * BACK_MARGIN * total / 1e6;
        }
        if (costly) {
            if (excluded.add(key)) {
                dirty.set(true);
                return 1;
            }
            return 0;
        }
        if (excluded.remove(key)) {
            dirty.set(true);
            return -1;
        }
        return 0;
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

    /**
     * Writes the features left out, with their figures, by feature name and
     * biome id, when they changed since the last save; {@code stamp} as the
     * biome profiles' (ADR-130). True when written.
     */
    boolean save(Path file, String stamp, Function<PlacedFeature, String> names) throws IOException {
        if (!dirty.getAndSet(false)) {
            return false;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(FILE_VERSION);
            out.writeUTF(stamp);
            Set<Key> left = Set.copyOf(excluded);
            out.writeInt(left.size());
            for (Key key : left) {
                Stat s = stats.get(key);
                out.writeUTF(names.apply(key.feature()));
                out.writeInt(key.biome());
                out.writeLong(s == null ? 0 : s.nanos.get());
                out.writeLong(s == null ? 0 : s.chunks.get());
                out.writeLong(s == null ? 0 : s.kept.get());
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    /**
     * Reads the features left out that {@link #save} wrote with the same
     * {@code stamp}; a name no feature has any more is skipped. Returns the
     * entries read (0 without a file), -1 for another stamp or version, -2
     * when the file does not read.
     */
    int load(Path file, String stamp, Function<String, PlacedFeature> features) {
        if (!Files.exists(file)) {
            return 0;
        }
        int read = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != FILE_VERSION || !in.readUTF().equals(stamp)) {
                return -1;
            }
            for (int n = in.readInt(); n > 0; n--) {
                String name = in.readUTF();
                int biome = in.readInt();
                long nanos = in.readLong();
                long chunks = in.readLong();
                long kept = in.readLong();
                PlacedFeature feature = features.apply(name);
                if (feature == null) {
                    continue;
                }
                Key key = new Key(feature, biome);
                Stat s = stats.computeIfAbsent(key, k -> new Stat());
                s.nanos.set(nanos);
                s.chunks.set(chunks);
                s.kept.set(kept);
                excluded.add(key);
                read++;
            }
        } catch (IOException | RuntimeException e) {
            return -2;
        }
        return read;
    }
}
