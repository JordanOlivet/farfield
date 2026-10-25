package io.lakio.farfield.core.storage;

import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;
import io.lakio.farfield.core.LodKey;
import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.util.ByteWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The LOD sections of one dimension on disk (ADR-019): one {@link LogStore}
 * per level and region of 32x32 section columns
 * ({@code L<level>/r.<rx>.<rz>.ffl}), each record a {@link SectionCodec}
 * encoding compressed with zstd.
 *
 * <pre>
 *   record payload: raw length (4, little-endian) | zstd frame
 * </pre>
 *
 * <p>Thread-safe: concurrent readers and writers. Writes to one region file
 * are serialized by the {@link LogStore}; callers must not write the same
 * key from two threads at once (the last write would win).
 */
public final class SectionStore implements AutoCloseable {

    public static final int REGION_SHIFT = 5;
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.ffl");

    /** Called before any record is written, to persist what records refer to (the key files). */
    @FunctionalInterface
    public interface BeforeWrite {
        void run() throws IOException;
    }

    private final Path dir;
    private final BeforeWrite beforeWrite;
    private final ConcurrentHashMap<Long, LogStore> open = new ConcurrentHashMap<>();
    /** Regions that have a file on disk (opened lazily). */
    private final java.util.Set<Long> existing = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);

    private static final class Buffers {
        final SectionCodec codec = new SectionCodec();
        final ByteWriter raw = new ByteWriter(64 * 1024);
        final ZstdCompressor compressor = new ZstdCompressor();
        final ZstdDecompressor decompressor = new ZstdDecompressor();
        byte[] compressed = new byte[16 * 1024];
        byte[] decompressed = new byte[64 * 1024];
    }

    public SectionStore(Path dir, BeforeWrite beforeWrite) throws IOException {
        this.dir = dir;
        this.beforeWrite = beforeWrite;
        Files.createDirectories(dir);
        try (Stream<Path> levels = Files.list(dir)) {
            for (Path levelDir : (Iterable<Path>) levels::iterator) {
                String name = levelDir.getFileName().toString();
                if (!Files.isDirectory(levelDir) || !name.matches("L\\d+")) {
                    continue;
                }
                int level = Integer.parseInt(name.substring(1));
                try (Stream<Path> files = Files.list(levelDir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        Matcher m = REGION_FILE.matcher(f.getFileName().toString());
                        if (m.matches()) {
                            existing.add(regionKey(level, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
                        }
                    }
                }
            }
        }
    }

    private static long regionKey(int level, int rx, int rz) {
        return LodKey.pack(level, rx, 0, rz);
    }

    private static long regionOf(long key) {
        return regionKey(LodKey.level(key), LodKey.x(key) >> REGION_SHIFT, LodKey.z(key) >> REGION_SHIFT);
    }

    private Path fileOf(long region) {
        return dir.resolve("L" + LodKey.level(region))
                .resolve("r." + LodKey.x(region) + "." + LodKey.z(region) + ".ffl");
    }

    private LogStore store(long region, boolean create) throws IOException {
        if (closed) {
            throw new IOException("section store closed");
        }
        LogStore store = open.get(region);
        if (store != null || (!create && !existing.contains(region))) {
            return store;
        }
        try {
            return open.computeIfAbsent(region, r -> {
                try {
                    LogStore s = LogStore.open(fileOf(r));
                    existing.add(r);
                    return s;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** Calls {@code action} with every stored key of a level (order unspecified). */
    public void forEachKey(int level, java.util.function.LongConsumer action) throws IOException {
        for (long region : existing) {
            if (LodKey.level(region) != level) {
                continue;
            }
            LogStore s = store(region, false);
            if (s != null) {
                for (long key : s.keys()) {
                    action.accept(key);
                }
            }
        }
    }

    /**
     * The codec bytes of a section (decompressed), or null if absent. The
     * array is the calling thread's buffer: valid until its next call.
     *
     * @return null if absent; otherwise the buffer, with the length in {@code length[0]}
     */
    public byte[] readEncoded(long key, int[] length) throws IOException {
        LogStore store = store(regionOf(key), false);
        byte[] record = store == null ? null : store.get(key);
        if (record == null) {
            return null;
        }
        int rawLength = (record[0] & 0xFF) | (record[1] & 0xFF) << 8 | (record[2] & 0xFF) << 16 | (record[3] & 0xFF) << 24;
        Buffers b = BUFFERS.get();
        if (b.decompressed.length < rawLength) {
            b.decompressed = new byte[Integer.highestOneBit(rawLength) << 1];
        }
        int n = b.decompressor.decompress(record, 4, record.length - 4, b.decompressed, 0, rawLength);
        if (n != rawLength) {
            throw new IOException("bad record length for " + LodKey.toString(key));
        }
        length[0] = n;
        return b.decompressed;
    }

    /**
     * The stored record of a section as it is on disk (raw length, then the
     * zstd frame of its codec bytes), or null if absent: sent as is to
     * clients (ADR-068). A new array.
     */
    public byte[] readRecord(long key) throws IOException {
        LogStore store = store(regionOf(key), false);
        return store == null ? null : store.get(key);
    }

    /** Writes a record {@link #readRecord} returned, from another store. */
    public void writeRecord(long key, byte[] record, int offset, int length) throws IOException {
        beforeWrite.run();
        store(regionOf(key), true).put(key, record, offset, length);
    }

    /** Version of a section's record ({@link LogStore#version}), or -1 if absent; cheap. */
    public long version(long key) throws IOException {
        LogStore store = store(regionOf(key), false);
        return store == null ? -1 : store.version(key);
    }

    /**
     * Calls {@code action} with every key of one region file (32 x 32 section
     * columns of a level, {@link #REGION_SHIFT}) and its version.
     */
    public void forEachVersion(int level, int rx, int rz, LogStore.KeyVersionConsumer action) throws IOException {
        LogStore s = store(regionKey(level, rx, rz), false);
        if (s != null) {
            s.forEachVersion(action);
        }
    }

    /** True if a section is stored at {@code key}; cheap (index lookup). */
    public boolean contains(long key) throws IOException {
        LogStore store = store(regionOf(key), false);
        return store != null && store.contains(key);
    }

    /** The decoded section, or null if absent. */
    public LodSection read(long key) throws IOException {
        int[] length = new int[1];
        byte[] raw = readEncoded(key, length);
        return raw == null ? null : BUFFERS.get().codec.decode(raw, 0, length[0]);
    }

    /** Encodes a section with the calling thread's codec; valid until its next call. */
    public ByteWriter encode(LodSection section) {
        Buffers b = BUFFERS.get();
        b.raw.reset();
        b.codec.encode(section, b.raw);
        return b.raw;
    }

    public LodSection decode(byte[] raw, int offset, int length) {
        return BUFFERS.get().codec.decode(raw, offset, length);
    }

    /** Compresses and writes the codec bytes of a section. */
    public void writeEncoded(long key, byte[] raw, int length) throws IOException {
        beforeWrite.run();
        Buffers b = BUFFERS.get();
        int max = b.compressor.maxCompressedLength(length) + 4;
        if (b.compressed.length < max) {
            b.compressed = new byte[Integer.highestOneBit(max) << 1];
        }
        int n = b.compressor.compress(raw, 0, length, b.compressed, 4, b.compressed.length - 4);
        b.compressed[0] = (byte) length;
        b.compressed[1] = (byte) (length >>> 8);
        b.compressed[2] = (byte) (length >>> 16);
        b.compressed[3] = (byte) (length >>> 24);
        store(regionOf(key), true).put(key, b.compressed, 0, n + 4);
    }

    public void write(long key, LodSection section) throws IOException {
        ByteWriter raw = encode(section);
        writeEncoded(key, raw.array(), raw.length());
    }

    /** Makes every record written so far durable. */
    public void sync() throws IOException {
        for (LogStore s : open.values()) {
            s.sync();
        }
    }

    /** Number of region files opened so far, and records and bytes in them. */
    public long[] openStats() {
        long records = 0;
        long bytes = 0;
        for (LogStore s : open.values()) {
            records += s.size();
            bytes += s.fileBytes();
        }
        return new long[]{open.size(), records, bytes};
    }

    @Override
    public void close() throws IOException {
        closed = true;
        IOException failure = null;
        for (LogStore s : open.values()) {
            try {
                s.close();
            } catch (IOException e) {
                failure = failure == null ? e : failure;
            }
        }
        open.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
