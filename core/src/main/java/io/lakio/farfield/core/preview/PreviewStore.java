package io.lakio.farfield.core.preview;

import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;
import io.lakio.farfield.core.util.ByteReader;
import io.lakio.farfield.core.util.ByteWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The noise preview tiles of one dimension (ADR-039), one zstd-compressed
 * file each, in a folder per level of the pyramid (ADR-126:
 * {@code L<level>/t.<x>.<z>.ffp}). The summary of every tile stays in
 * memory (which sections hold something), the last decoded tiles in small
 * caches.
 *
 * <p>Readers see level-0 tiles only, the views: {@link #read(int, int)} and
 * {@link #summary(int, int)} give, for each quarter of the place (ADR-127),
 * the finest tile over it with that quarter built, a coarser one cut to it
 * ({@link PreviewTile#expand}); a view exists once every quarter has one.
 * View listeners hear the level-0 places whose view a write changed; tile
 * listeners hear every tile written, by its key ({@link #key(int, int, int)}).
 * Thread-safe.
 */
public final class PreviewStore {

    private static final Pattern TILE_FILE = Pattern.compile("t\\.(-?\\d+)\\.(-?\\d+)\\.ffp");
    private static final Pattern LEVEL_DIR = Pattern.compile("L(\\d+)");
    private static final int CACHED_TILES = 64;
    private static final int CACHED_COARSE = 16;

    private final Path dir;
    private final io.lakio.farfield.core.storage.SectionStore.BeforeWrite beforeWrite;
    /** Every stored tile's summary, by key (any level). */
    private final Map<Long, PreviewTile.Summary> summaries = new ConcurrentHashMap<>();
    /** Summaries of the views cut from a coarser tile, by level-0 key; dropped when a tile over them is written. */
    private final Map<Long, PreviewTile.Summary> viewSummaries = new ConcurrentHashMap<>();
    /** Bit per level holding a tile. */
    private volatile int levels;
    /** Counts writes: a view made across one is not cached. */
    private final AtomicLong writes = new AtomicLong();
    /** Views by level-0 key. */
    private final Map<Long, PreviewTile> cache = lru(CACHED_TILES);
    /** Stored coarse tiles by key. */
    private final Map<Long, PreviewTile> coarse = lru(CACHED_COARSE);
    private final List<LongConsumer> listeners = new CopyOnWriteArrayList<>();
    private final List<LongConsumer> tileListeners = new CopyOnWriteArrayList<>();

    /** Opens the store, reading the summary of every tile already there. */
    public PreviewStore(Path dir) throws IOException {
        this(dir, () -> {
        });
    }

    /**
     * Like {@link #PreviewStore(Path)}; {@code beforeWrite} runs before a
     * tile is written, to persist the key tables its material and biome ids
     * refer to (ADR-080: tiles grown with the world's features name blocks
     * no section of the store had written yet, and those ids were lost at
     * the next start).
     */
    public PreviewStore(Path dir, io.lakio.farfield.core.storage.SectionStore.BeforeWrite beforeWrite)
            throws IOException {
        this.dir = dir;
        this.beforeWrite = beforeWrite;
        Files.createDirectories(dir);
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path f : (Iterable<Path>) entries::iterator) {
                String name = f.getFileName().toString();
                Matcher levelDir = LEVEL_DIR.matcher(name);
                if (TILE_FILE.matcher(name).matches()) {
                    Files.deleteIfExists(f); // a tile of the format before the levels: built again
                } else if (levelDir.matches() && Files.isDirectory(f)) {
                    int level = Integer.parseInt(levelDir.group(1));
                    if (level <= PreviewTile.MAX_LEVEL) {
                        openLevel(f, level);
                    }
                }
            }
        }
    }

    private void openLevel(Path levelDir, int level) throws IOException {
        try (Stream<Path> files = Files.list(levelDir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Matcher m = TILE_FILE.matcher(f.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                int x = Integer.parseInt(m.group(1));
                int z = Integer.parseInt(m.group(2));
                try {
                    PreviewTile t = load(f);
                    if (t.level() != level) {
                        throw new IOException("a level-" + t.level() + " tile among level " + level);
                    }
                    summaries.put(key(level, x, z), t.summary());
                    levels |= 1 << level;
                } catch (IOException | RuntimeException e) {
                    Files.deleteIfExists(f); // a damaged tile is built again
                }
            }
        }
    }

    /** The key of the tile of level {@code level} at tile coordinates (tileX, tileZ) of that level. */
    public static long key(int level, int tileX, int tileZ) {
        return (long) level << 58 | ((long) tileX & 0x3FFFFFFL) << 32 | (tileZ & 0xFFFFFFFFL);
    }

    /** The key of a level-0 tile. */
    public static long key(int tileX, int tileZ) {
        return key(0, tileX, tileZ);
    }

    public static int level(long key) {
        return (int) (key >>> 58);
    }

    public static int tileX(long key) {
        return (int) (key << 6 >> 38);
    }

    public static int tileZ(long key) {
        return (int) key;
    }

    /** Blocks a side of a tile of that level. */
    public static int blocks(int level) {
        return PreviewTile.BLOCKS << level;
    }

    /** The store's folder: the server keeps its biome profiles there too (ADR-130). */
    public Path dir() {
        return dir;
    }

    /** Persists the key tables the store's ids refer to, as before a tile is written (ADR-080). */
    public void persistKeys() throws IOException {
        beforeWrite.run();
    }

    /** Tiles stored, every level. */
    public int size() {
        return summaries.size();
    }

    /** Whether a level-0 view exists there: each of its quarters built in a tile of some level. */
    public boolean contains(int tileX, int tileZ) {
        return sources(tileX, tileZ) != null;
    }

    /** Whether that very tile is stored. */
    public boolean contains(int level, int tileX, int tileZ) {
        return summaries.containsKey(key(level, tileX, tileZ));
    }

    /** The summary of a stored tile, or null. */
    public PreviewTile.Summary summary(int level, int tileX, int tileZ) {
        return summaries.get(key(level, tileX, tileZ));
    }

    /** The summary of the level-0 view there, or null. */
    public PreviewTile.Summary summary(int tileX, int tileZ) {
        long view = key(tileX, tileZ);
        PreviewTile.Summary cached = viewSummaries.get(view);
        if (cached != null) {
            return cached;
        }
        long before = writes.get();
        long[] sources = sources(tileX, tileZ);
        if (sources == null) {
            return null;
        }
        PreviewTile.Summary s;
        try {
            if (sources[0] == sources[1] && sources[0] == sources[2] && sources[0] == sources[3]) {
                s = sourceSummary(sources[0], tileX, tileZ);
            } else {
                short[] min = new short[PreviewTile.CHUNKS * PreviewTile.CHUNKS];
                short[] max = new short[min.length];
                int sea = Integer.MIN_VALUE;
                int half = PreviewTile.CHUNKS / 2;
                for (int q = 0; q < 4; q++) {
                    PreviewTile.Summary part = sourceSummary(sources[q], tileX, tileZ);
                    if (part == null) {
                        return null;
                    }
                    sea = level(sources[q]) == 0 || sea == Integer.MIN_VALUE ? Math.max(sea, part.sea()) : sea;
                    for (int cz = (q >> 1) * half; cz < (q >> 1) * half + half; cz++) {
                        for (int cx = (q & 1) * half; cx < (q & 1) * half + half; cx++) {
                            min[cz * PreviewTile.CHUNKS + cx] = part.chunkMin()[cz * PreviewTile.CHUNKS + cx];
                            max[cz * PreviewTile.CHUNKS + cx] = part.chunkMax()[cz * PreviewTile.CHUNKS + cx];
                        }
                    }
                }
                s = new PreviewTile.Summary(min, max, sea, PreviewTile.ALL_QUARTERS);
            }
        } catch (IOException e) {
            return null;
        }
        if (s != null && writes.get() == before) {
            viewSummaries.put(view, s);
        }
        return s;
    }

    /** The summary of a source tile cut to level-0 place (tileX, tileZ). */
    private PreviewTile.Summary sourceSummary(long source, int tileX, int tileZ) throws IOException {
        int level = level(source);
        if (level == 0) {
            return summaries.get(source);
        }
        PreviewTile tile = coarse(source);
        return tile == null ? null
                : tile.expandedSummary(tileX - (tileX(source) << level), tileZ - (tileZ(source) << level));
    }

    /** A source tile cut to level-0 place (tileX, tileZ), its canopy grown into runs. */
    private PreviewTile sourceView(long source, int tileX, int tileZ) throws IOException {
        int level = level(source);
        if (level == 0) {
            PreviewTile stored = load(file(source));
            return stored.hasCanopy() ? stored.expand(0, 0) : stored;
        }
        PreviewTile stored = coarse(source);
        return stored == null ? null
                : stored.expand(tileX - (tileX(source) << level), tileZ - (tileZ(source) << level));
    }

    /** The level-0 view there, each quarter from the finest tile with it built, or null. */
    public PreviewTile read(int tileX, int tileZ) throws IOException {
        long view = key(tileX, tileZ);
        synchronized (cache) {
            PreviewTile t = cache.get(view);
            if (t != null) {
                return t;
            }
        }
        long before = writes.get();
        long[] sources = sources(tileX, tileZ);
        if (sources == null) {
            return null;
        }
        PreviewTile t;
        if (sources[0] == sources[1] && sources[0] == sources[2] && sources[0] == sources[3]) {
            t = sourceView(sources[0], tileX, tileZ);
        } else {
            t = new PreviewTile();
            java.util.Map<Long, PreviewTile> parts = new java.util.HashMap<>();
            boolean values = false;
            // The finest source first: its tile-wide values (sea, ground) stand for the view.
            for (int pass = 0; pass < 2; pass++) {
                for (int q = 0; q < 4; q++) {
                    boolean fine = level(sources[q]) == 0;
                    if (pass == 0 != fine) {
                        continue;
                    }
                    PreviewTile part = parts.get(sources[q]);
                    if (part == null) {
                        part = sourceView(sources[q], tileX, tileZ);
                        if (part == null) {
                            return null;
                        }
                        parts.put(sources[q], part);
                    }
                    if (!values) {
                        t.copyTileValues(part);
                        values = true;
                    }
                    t.copyQuarter(part, q);
                }
            }
        }
        if (t != null && writes.get() == before) {
            synchronized (cache) {
                cache.put(view, t);
            }
        }
        return t;
    }

    /** A stored tile as written (a coarse one not cut), or null. */
    public PreviewTile read(int level, int tileX, int tileZ) throws IOException {
        long key = key(level, tileX, tileZ);
        if (!summaries.containsKey(key)) {
            return null;
        }
        return level == 0 ? load(file(key)) : coarse(key);
    }

    /** Per quarter of level-0 place (tileX, tileZ), the key of the finest tile with it built; null when one has none. */
    private long[] sources(int tileX, int tileZ) {
        long[] out = new long[4];
        for (int q = 0; q < 4; q++) {
            out[q] = source(tileX, tileZ, q);
            if (out[q] < 0) {
                return null;
            }
        }
        return out;
    }

    /** The key of the finest tile with quarter {@code q} of level-0 place (tileX, tileZ) built; -1 for none. */
    private long source(int tileX, int tileZ, int q) {
        int held = levels;
        while (held != 0) {
            int level = Integer.numberOfTrailingZeros(held);
            held &= held - 1;
            long key = key(level, tileX >> level, tileZ >> level);
            PreviewTile.Summary s = summaries.get(key);
            if (s != null && s.built(quarterIn(level, tileX, tileZ, q))) {
                return key;
            }
        }
        return -1;
    }

    /** The quarter of the level-{@code level} tile over level-0 place (tileX, tileZ) its quarter {@code q} lies in. */
    private static int quarterIn(int level, int tileX, int tileZ, int q) {
        if (level == 0) {
            return q;
        }
        int scale = 1 << level;
        // Half blocks of the coarse tile, in level-0 quarters: its own quarters are scale of them wide.
        int ux = Math.floorMod(tileX, scale) * 2 + (q & 1);
        int uz = Math.floorMod(tileZ, scale) * 2 + (q >> 1);
        return (uz >= scale ? 2 : 0) + (ux >= scale ? 1 : 0);
    }

    private PreviewTile coarse(long key) throws IOException {
        synchronized (coarse) {
            PreviewTile t = coarse.get(key);
            if (t != null) {
                return t;
            }
        }
        if (!summaries.containsKey(key)) {
            return null;
        }
        PreviewTile t;
        try {
            t = load(file(key));
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        }
        synchronized (coarse) {
            coarse.put(key, t);
        }
        return t;
    }

    /** Writes a level-0 tile. */
    public void write(int tileX, int tileZ, PreviewTile tile) throws IOException {
        write(0, tileX, tileZ, tile);
    }

    /** Writes a tile whose quarters may all have changed. */
    public void write(int level, int tileX, int tileZ, PreviewTile tile) throws IOException {
        write(level, tileX, tileZ, tile, 0xF);
    }

    /**
     * Writes a tile of its level through a temporary file, then tells the
     * tile listeners, and the view listeners of the level-0 places where a
     * quarter of {@code changed} (bit q) is now the finest over a quarter of
     * theirs. The tile is not changed, nor kept: the caller may go on with it.
     */
    public void write(int level, int tileX, int tileZ, PreviewTile tile, int changed) throws IOException {
        if (tile.level() != level) {
            throw new IllegalArgumentException("a level-" + tile.level() + " tile written at level " + level);
        }
        beforeWrite.run();
        ByteWriter raw = new ByteWriter(64 * 1024);
        tile.encode(raw);
        ZstdCompressor compressor = new ZstdCompressor();
        byte[] out = new byte[compressor.maxCompressedLength(raw.length()) + 4];
        int n = compressor.compress(raw.array(), 0, raw.length(), out, 4, out.length - 4);
        int length = raw.length();
        out[0] = (byte) length;
        out[1] = (byte) (length >>> 8);
        out[2] = (byte) (length >>> 16);
        out[3] = (byte) (length >>> 24);
        long key = key(level, tileX, tileZ);
        Path file = file(key);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, java.util.Arrays.copyOf(out, n + 4));
        replace(tmp, file);
        // The cached copy is decoded from the bytes written: the caller's tile goes on changing.
        PreviewTile stored = decode(java.util.Arrays.copyOf(out, n + 4));
        writes.incrementAndGet();
        summaries.put(key, stored.summary());
        levels |= 1 << level;
        int scale = 1 << level;
        int x0 = tileX << level;
        int z0 = tileZ << level;
        synchronized (cache) {
            for (int z = z0; z < z0 + scale; z++) {
                for (int x = x0; x < x0 + scale; x++) {
                    cache.remove(key(x, z));
                }
            }
            if (level == 0 && (stored.quarters() & 0xF) == 0xF) {
                cache.put(key, stored.hasCanopy() ? stored.expand(0, 0) : stored);
            }
        }
        if (level > 0) {
            synchronized (coarse) {
                coarse.put(key, stored);
            }
        }
        for (int z = z0; z < z0 + scale; z++) {
            for (int x = x0; x < x0 + scale; x++) {
                viewSummaries.remove(key(x, z));
            }
        }
        writes.incrementAndGet();
        for (LongConsumer l : tileListeners) {
            l.accept(key);
        }
        for (int z = z0; z < z0 + scale; z++) {
            for (int x = x0; x < x0 + scale; x++) {
                boolean heard = false;
                for (int q = 0; q < 4 && !heard; q++) {
                    heard = (changed >> quarterIn(level, x, z, q) & 1) != 0 && source(x, z, q) == key
                            && sources(x, z) != null;
                }
                if (heard) {
                    long view = key(x, z);
                    for (LongConsumer l : listeners) {
                        l.accept(view);
                    }
                }
            }
        }
    }

    /** A stored tile decoded from its file, never shared with the caches: a builder adds a quarter to it. */
    public PreviewTile load(int level, int tileX, int tileZ) throws IOException {
        long key = key(level, tileX, tileZ);
        if (!summaries.containsKey(key)) {
            return null;
        }
        try {
            return load(file(key));
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        }
    }

    /** A stored level-0 tile's file, or null ({@link #readFile(int, int, int)}). */
    public byte[] readFile(int tileX, int tileZ) throws IOException {
        return readFile(0, tileX, tileZ);
    }

    /**
     * A stored tile's file as it is on disk (raw length, then its zstd
     * frame), or null: sent as is to clients (ADR-068).
     */
    public byte[] readFile(int level, int tileX, int tileZ) throws IOException {
        long key = key(level, tileX, tileZ);
        if (!summaries.containsKey(key)) {
            return null;
        }
        try {
            return Files.readAllBytes(file(key));
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        }
    }

    /** Version of a tile file {@link #readFile} returned: its CRC32C, never negative. */
    public static long version(byte[] file) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(file, 0, file.length);
        return crc.getValue();
    }

    /**
     * The tile of a file {@link #readFile} returned, from another store
     * (ADR-068): its ids are that store's until {@link PreviewTile#remapIds}.
     */
    public static PreviewTile decodeFile(byte[] file) throws IOException {
        try {
            return decode(file);
        } catch (RuntimeException e) {
            throw new IOException("bad preview tile file", e);
        }
    }

    /** Hears the level-0 places whose view changed, by their key. */
    public void addListener(LongConsumer onViewChanged) {
        listeners.add(onViewChanged);
    }

    public void removeListener(LongConsumer onViewChanged) {
        listeners.remove(onViewChanged);
    }

    /** Hears every tile written, by its key ({@link #key(int, int, int)}). */
    public void addTileListener(LongConsumer onTileWritten) {
        tileListeners.add(onTileWritten);
    }

    public void removeTileListener(LongConsumer onTileWritten) {
        tileListeners.remove(onTileWritten);
    }

    private Path file(long key) {
        return dir.resolve("L" + level(key)).resolve("t." + tileX(key) + "." + tileZ(key) + ".ffp");
    }

    private static Map<Long, PreviewTile> lru(int size) {
        return new LinkedHashMap<>(size, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, PreviewTile> eldest) {
                return size() > size;
            }
        };
    }

    /**
     * Moves a written file over the old one. On Windows the move fails while
     * another thread reads the old file (a reader of the preview, the
     * streaming): tried again for a moment.
     */
    private static void replace(Path tmp, Path file) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (java.nio.file.AccessDeniedException e) {
                if (attempt == 10) {
                    throw e;
                }
                try {
                    Thread.sleep(10L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static PreviewTile load(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    private static PreviewTile decode(byte[] data) throws IOException {
        if (data.length < 4) {
            throw new IOException("truncated preview tile");
        }
        int length = (data[0] & 0xFF) | (data[1] & 0xFF) << 8 | (data[2] & 0xFF) << 16 | (data[3] & 0xFF) << 24;
        byte[] raw = new byte[length];
        new ZstdDecompressor().decompress(data, 4, data.length - 4, raw, 0, length);
        return PreviewTile.decode(new ByteReader().reset(raw, 0, length));
    }
}
