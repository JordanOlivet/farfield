package io.lakio.farfield.core.world;

import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.anvil.ChunkData;
import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.anvil.SectionData;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.lod.MipBuilder;
import io.lakio.farfield.core.preview.PreviewStore;
import io.lakio.farfield.core.storage.LogStore;
import io.lakio.farfield.core.storage.SectionStore;
import io.lakio.farfield.core.util.ByteWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongConsumer;

/**
 * The LOD data of one dimension: its block-state and biome key tables, its
 * {@link SectionStore}, and the incremental build of the mip pyramid.
 *
 * <ul>
 *   <li>{@link #ingest(ChunkData)} writes a chunk into the level-0 sections
 *       it covers (a 32³ section spans 2x2 chunks and two chunk sections in
 *       height). Callable from any number of threads; chunks of the same
 *       section column are serialized. Modified sections stay in a
 *       write-back cache, so the four chunks of a column cost one store
 *       write; {@link #flush(long)} writes the ones untouched for a while
 *       and the cache writes its oldest entries when it grows too big.</li>
 *   <li>Writing a section marks its parent dirty; {@link #buildMips} rebuilds
 *       the dirty parents level by level from their stored children.</li>
 *   <li>A section whose encoding did not change is not rewritten and does
 *       not propagate.</li>
 * </ul>
 *
 * <p>The engine owns no thread: the game side runs ingests on its workers
 * and calls {@link #flush(long)} and {@link #buildMips} periodically.
 * {@link #read} sees written sections only. Listeners hear about every
 * section written (key of the section), from the writing thread.
 */
public final class LodWorld implements AutoCloseable {

    /** Levels 0 (one voxel per block) to 4 (one voxel per 16 blocks). */
    public static final int LEVELS = 5;
    private static final int LOCK_STRIPES = 64;

    private final Path dir;
    private final KeyFile blockStateKeys;
    private final KeyFile biomeKeys;
    private final MaterialTable materials;
    private final SectionStore store;
    private final PreviewStore preview;
    private final int air;
    private final Object[] locks = new Object[LOCK_STRIPES];
    @SuppressWarnings("unchecked")
    private final Set<Long>[] dirty = new Set[LEVELS];
    /**
     * Version of the mip rules the stored coarse levels were built with; a
     * world built with older rules rebuilds them all once, from level 0
     * (version 2: per-voxel fill; 3: ground covered by plants, ADR-060).
     */
    private static final String MIPS_VERSION = "3";
    private final Path mipsVersionFile;
    private volatile int mipsToUpgrade;
    private final List<LongConsumer> listeners = new CopyOnWriteArrayList<>();
    private final ThreadLocal<MipBuilder> mips;
    private final ConcurrentHashMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final int maxPending;
    private final ConcurrentLinkedQueue<Long> evictQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean selectingEvictions = new AtomicBoolean();

    private final LongAdder chunksIngested = new LongAdder();
    private final LongAdder sectionsWritten = new LongAdder();
    private final LongAdder sectionsUnchanged = new LongAdder();

    /** A level-0 section modified in memory, not yet written. */
    private static final class Pending {
        final LodSection section;
        /** The stored encoding it was read from, null if it was absent. */
        final byte[] stored;
        volatile long touched;

        Pending(LodSection section, byte[] stored) {
            this.section = section;
            this.stored = stored;
        }
    }

    private LodWorld(Path dir, MaterialTable.Resolver resolver, int maxPending) throws IOException {
        this.dir = dir;
        this.maxPending = maxPending;
        blockStateKeys = KeyFile.open(dir.resolve("block_states.txt"));
        biomeKeys = KeyFile.open(dir.resolve("biomes.txt"));
        air = blockStateKeys.registry().id("minecraft:air");
        materials = new MaterialTable(blockStateKeys.registry(), resolver);
        deleteOldPreview(dir.resolve("sections").resolve("L8"));
        store = new SectionStore(dir.resolve("sections"), () -> {
            blockStateKeys.persist();
            biomeKeys.persist();
        });
        preview = new PreviewStore(dir.resolve("preview"), () -> {
            blockStateKeys.persist();
            biomeKeys.persist();
        });
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
        for (int level = 0; level < LEVELS; level++) {
            dirty[level] = ConcurrentHashMap.newKeySet();
        }
        mips = ThreadLocal.withInitial(() -> new MipBuilder(air, materials));
        mipsVersionFile = dir.resolve("mips.version");
        String version = Files.exists(mipsVersionFile) ? Files.readString(mipsVersionFile).trim() : "";
        if (!MIPS_VERSION.equals(version)) {
            store.forEachKey(0, key -> dirty[1].add(LodKey.parent(key)));
            mipsToUpgrade = dirty[1].size();
            if (mipsToUpgrade == 0) {
                Files.writeString(mipsVersionFile, MIPS_VERSION);
            }
        }
    }

    /** The level-4 preview sections of ADR-038, replaced by the tiles of ADR-039. */
    private static void deleteOldPreview(Path level) throws IOException {
        if (!Files.isDirectory(level)) {
            return;
        }
        try (var files = Files.list(level)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.delete(f);
            }
        }
        Files.delete(level);
    }

    /**
     * Level-1 parents queued at open to rebuild the coarse levels with the
     * current mip rules (0 when they are current); cleared once rebuilt.
     */
    public int mipsToUpgrade() {
        return mipsToUpgrade;
    }

    /** Opens with a write-back cache of up to 2048 level-0 sections. */
    public static LodWorld open(Path dir, MaterialTable.Resolver resolver) throws IOException {
        return open(dir, resolver, 2048);
    }

    public static LodWorld open(Path dir, MaterialTable.Resolver resolver, int maxPending) throws IOException {
        return new LodWorld(dir, resolver, maxPending);
    }

    public KeyRegistry blockStates() {
        return blockStateKeys.registry();
    }

    public KeyRegistry biomes() {
        return biomeKeys.registry();
    }

    public MaterialTable materials() {
        return materials;
    }

    /** Id of {@code minecraft:air} in {@link #blockStates()}. */
    public int air() {
        return air;
    }

    public void addListener(LongConsumer onSectionWritten) {
        listeners.add(onSectionWritten);
    }

    public void removeListener(LongConsumer onSectionWritten) {
        listeners.remove(onSectionWritten);
    }

    /** The stored section at {@code key} ({@link LodKey}), or null. Thread-safe. */
    public LodSection read(long key) throws IOException {
        return store.read(key);
    }

    /** True if a section is stored at {@code key}. Thread-safe, cheap. */
    public boolean exists(long key) throws IOException {
        return store.contains(key);
    }

    /** The stored record of a section, as {@link SectionStore#readRecord}; null if absent. */
    public byte[] readRecord(long key) throws IOException {
        return store.readRecord(key);
    }

    /** Version of a stored section ({@link SectionStore#version}), -1 if absent. */
    public long version(long key) throws IOException {
        return store.version(key);
    }

    /** Every stored key of a region file and its version ({@link SectionStore#forEachVersion}). */
    public void forEachVersion(int level, int rx, int rz, LogStore.KeyVersionConsumer action) throws IOException {
        store.forEachVersion(level, rx, rz, action);
    }

    /**
     * Writes a record received from a server's store (ADR-068) as it is,
     * without touching the mips: on a client, a server's world is mirrored,
     * never rebuilt. Listeners hear about it as about any written section.
     */
    public void putRecord(long key, byte[] record, int offset, int length) throws IOException {
        store.writeRecord(key, record, offset, length);
        sectionsWritten.increment();
        for (LongConsumer l : listeners) {
            l.accept(key);
        }
    }

    /** The section of a record from a server's store, read with this store's codec. */
    public LodSection decodeRecord(long key, byte[] record) throws IOException {
        return store.decodeRecord(record, key);
    }

    /**
     * Writes a section received from a server's store once its ids are this
     * store's (ADR-068), like {@link #putRecord}: the mips stay untouched.
     */
    public void putSection(long key, LodSection section) throws IOException {
        store.write(key, section);
        sectionsWritten.increment();
        for (LongConsumer l : listeners) {
            l.accept(key);
        }
    }

    /**
     * Tells the listeners a stored section is current again without writing
     * it (a server answered that the mirror's copy is its latest, ADR-068).
     */
    public void touch(long key) {
        for (LongConsumer l : listeners) {
            l.accept(key);
        }
    }

    /**
     * A random id of this store, made once and kept in {@code stream.epoch}:
     * a client's mirror of a server's world is valid for one epoch only
     * (a server whose store was deleted gets a new one).
     */
    public long epoch() throws IOException {
        Path file = dir.resolve("stream.epoch");
        synchronized (this) {
            if (Files.exists(file)) {
                try {
                    return Long.parseUnsignedLong(Files.readString(file).trim(), 16);
                } catch (NumberFormatException e) {
                    // Rewritten below.
                }
            }
            long epoch = new java.security.SecureRandom().nextLong() | 1;
            Files.writeString(file, Long.toUnsignedString(epoch, 16));
            return epoch;
        }
    }

    /** The noise preview tiles of this world (ADR-039). */
    public PreviewStore preview() {
        return preview;
    }

    /**
     * Writes a chunk (block ids and biome ids of this world's key tables)
     * into its level-0 sections. Sections without blocks are ignored;
     * sections without sky light take it from above first, as the game
     * reads them ({@link ChunkData#inheritSkyLight}).
     */
    public void ingest(ChunkData chunk) throws IOException {
        chunk.inheritSkyLight();
        int cx = chunk.chunkX();
        int cz = chunk.chunkZ();
        int lx = cx >> 1;
        int lz = cz >> 1;
        int n = chunk.sectionCount();
        int[] order = new int[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (chunk.section(i).hasBlocks()) {
                order[count++] = i;
            }
        }
        long now = System.nanoTime();
        synchronized (lockOf(lx, lz)) {
            boolean[] done = new boolean[count];
            for (int a = 0; a < count; a++) {
                if (done[a]) {
                    continue;
                }
                int ly = chunk.section(order[a]).y() >> 1;
                long key = LodKey.pack(0, lx, ly, lz);
                Pending p = pending.get(key);
                if (p == null) {
                    int[] length = new int[1];
                    byte[] old = store.readEncoded(key, length);
                    p = old == null
                            ? new Pending(new LodSection(air), null)
                            : new Pending(store.decode(old, 0, length[0]), Arrays.copyOf(old, length[0]));
                    pending.put(key, p);
                }
                // The two chunk sections of this level-0 section (vertical octants).
                for (int b = a; b < count; b++) {
                    SectionData s = chunk.section(order[b]);
                    if (!done[b] && s.y() >> 1 == ly) {
                        p.section.writeChunkSection(cx & 1, s.y() & 1, cz & 1, s);
                        done[b] = true;
                    }
                }
                p.touched = now;
            }
        }
        chunksIngested.increment();
        if (pending.size() > maxPending) {
            evict();
        }
    }

    private Object lockOf(int lx, int lz) {
        return locks[Math.floorMod(lx * 31 + lz, LOCK_STRIPES)];
    }

    /**
     * The cache is over its limit: one thread queues the oldest quarter, and
     * every ingesting thread over the limit writes a few of them, so the
     * writes run in parallel.
     */
    private void evict() throws IOException {
        if (evictQueue.isEmpty() && selectingEvictions.compareAndSet(false, true)) {
            try {
                // Snapshot the times first: other threads keep touching entries while we sort.
                List<long[]> entries = new ArrayList<>(pending.size());
                for (Map.Entry<Long, Pending> e : pending.entrySet()) {
                    entries.add(new long[]{e.getKey(), e.getValue().touched});
                }
                entries.sort(Comparator.comparingLong(e -> e[1]));
                int target = entries.size() - maxPending * 3 / 4;
                for (int i = 0; i < target; i++) {
                    evictQueue.add(entries.get(i)[0]);
                }
            } finally {
                selectingEvictions.set(false);
            }
        }
        for (int i = 0; i < 16; i++) {
            Long key = evictQueue.poll();
            if (key == null) {
                break;
            }
            writePending(key);
        }
    }

    private void writePending(long key) throws IOException {
        synchronized (lockOf(LodKey.x(key), LodKey.z(key))) {
            Pending p = pending.remove(key);
            if (p != null) {
                write(key, p.section, p.stored);
            }
        }
    }

    /**
     * Writes the cached sections untouched for at least {@code idleNanos}
     * (0: all of them). Returns the number of sections handled.
     */
    public int flush(long idleNanos) throws IOException {
        long now = System.nanoTime();
        int n = 0;
        for (Map.Entry<Long, Pending> e : pending.entrySet()) {
            if (idleNanos == 0 || now - e.getValue().touched >= idleNanos) {
                writePending(e.getKey());
                n++;
            }
        }
        return n;
    }

    /** Level-0 sections waiting in the write-back cache. */
    public int pendingSections() {
        return pending.size();
    }

    /** Compacts, encodes and writes a section unless its encoding is unchanged; marks its parent dirty. */
    private boolean write(long key, LodSection section, byte[] oldEncoding) throws IOException {
        section.compact();
        section.prunePalette();
        section.compactBiomes();
        ByteWriter raw = store.encode(section);
        if (oldEncoding != null && Arrays.equals(oldEncoding, 0, oldEncoding.length, raw.array(), 0, raw.length())) {
            sectionsUnchanged.increment();
            return false;
        }
        store.writeEncoded(key, raw.array(), raw.length());
        sectionsWritten.increment();
        int level = LodKey.level(key);
        if (level + 1 < LEVELS) {
            dirty[level + 1].add(LodKey.parent(key));
        }
        for (LongConsumer l : listeners) {
            l.accept(key);
        }
        return true;
    }

    /** True when some parent waits for {@link #buildMips}. */
    public boolean hasDirtyMips() {
        for (Set<Long> d : dirty) {
            if (!d.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** {@link #buildMips(ExecutorService)} on the calling thread. */
    public int buildMips() throws IOException {
        return buildMips(null);
    }

    /**
     * Rebuilds every dirty parent, level 1 first, so a change reaches the top
     * level in one call; the parents of one level are built in parallel on
     * {@code executor} when given. Returns the number of parents written.
     * One call at a time.
     */
    public synchronized int buildMips(ExecutorService executor) throws IOException {
        int written = buildMipLevels(executor);
        if (mipsToUpgrade > 0 && !hasDirtyMips()) {
            Files.writeString(mipsVersionFile, MIPS_VERSION);
            mipsToUpgrade = 0;
        }
        return written;
    }

    private int buildMipLevels(ExecutorService executor) throws IOException {
        int written = 0;
        for (int level = 1; level < LEVELS; level++) {
            List<Long> keys = new ArrayList<>(dirty[level]);
            dirty[level].removeAll(keys);
            if (keys.isEmpty()) {
                continue;
            }
            if (executor == null) {
                for (long key : keys) {
                    written += buildParent(key) ? 1 : 0;
                }
                continue;
            }
            int tasks = Math.min(keys.size(), 64);
            List<Future<Integer>> futures = new ArrayList<>(tasks);
            for (int t = 0; t < tasks; t++) {
                List<Long> share = keys.subList(keys.size() * t / tasks, keys.size() * (t + 1) / tasks);
                futures.add(executor.submit(() -> {
                    int built = 0;
                    for (long key : share) {
                        built += buildParent(key) ? 1 : 0;
                    }
                    return built;
                }));
            }
            for (Future<Integer> f : futures) {
                try {
                    written += f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while building mips", e);
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
                }
            }
        }
        return written;
    }

    private boolean buildParent(long key) throws IOException {
        int level = LodKey.level(key);
        int x = LodKey.x(key);
        int y = LodKey.y(key);
        int z = LodKey.z(key);
        LodSection[] children = new LodSection[8];
        boolean any = false;
        for (int i = 0; i < 8; i++) {
            children[i] = store.read(LodKey.pack(level - 1, x * 2 + (i & 1), y * 2 + (i >> 2), z * 2 + ((i >> 1) & 1)));
            any |= children[i] != null;
        }
        if (!any) {
            return false;
        }
        LodSection parent = mips.get().build(children, level - 1 == 0);
        int[] length = new int[1];
        byte[] old = store.readEncoded(key, length);
        byte[] oldCopy = old == null ? null : Arrays.copyOf(old, length[0]);
        return write(key, parent, oldCopy);
    }

    public void sync() throws IOException {
        store.sync();
    }

    public long chunksIngested() {
        return chunksIngested.sum();
    }

    public long sectionsWritten() {
        return sectionsWritten.sum();
    }

    public long sectionsUnchanged() {
        return sectionsUnchanged.sum();
    }

    /** Region files opened, records and bytes in them. */
    public long[] storeStats() {
        return store.openStats();
    }

    /** Writes the cache, builds the mips and closes the store. */
    @Override
    public void close() throws IOException {
        try {
            flush(0);
            buildMips();
        } finally {
            store.close();
        }
    }
}
