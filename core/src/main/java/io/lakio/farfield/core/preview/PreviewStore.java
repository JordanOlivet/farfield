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
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The noise preview tiles of one dimension (ADR-039), one zstd-compressed
 * file each ({@code t.<x>.<z>.ffp}). The summary of every tile stays in
 * memory (which sections hold something), the last decoded tiles in a small
 * cache. Listeners hear about every tile written. Thread-safe.
 */
public final class PreviewStore {

    private static final Pattern TILE_FILE = Pattern.compile("t\\.(-?\\d+)\\.(-?\\d+)\\.ffp");
    private static final int CACHED_TILES = 64;

    private final Path dir;
    private final Map<Long, PreviewTile.Summary> summaries = new ConcurrentHashMap<>();
    private final Map<Long, PreviewTile> cache = new LinkedHashMap<>(CACHED_TILES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, PreviewTile> eldest) {
            return size() > CACHED_TILES;
        }
    };
    private final List<LongConsumer> listeners = new CopyOnWriteArrayList<>();

    /** Opens the store, reading the summary of every tile already there. */
    public PreviewStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Matcher m = TILE_FILE.matcher(f.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                int x = Integer.parseInt(m.group(1));
                int z = Integer.parseInt(m.group(2));
                try {
                    summaries.put(key(x, z), load(f).summary());
                } catch (IOException | RuntimeException e) {
                    Files.deleteIfExists(f); // a damaged tile is built again
                }
            }
        }
    }

    public static long key(int tileX, int tileZ) {
        return (long) tileX << 32 | (tileZ & 0xFFFFFFFFL);
    }

    public static int tileX(long key) {
        return (int) (key >> 32);
    }

    public static int tileZ(long key) {
        return (int) key;
    }

    public int size() {
        return summaries.size();
    }

    public boolean contains(int tileX, int tileZ) {
        return summaries.containsKey(key(tileX, tileZ));
    }

    /** The summary of a stored tile, or null. */
    public PreviewTile.Summary summary(int tileX, int tileZ) {
        return summaries.get(key(tileX, tileZ));
    }

    /** A stored tile, or null. */
    public PreviewTile read(int tileX, int tileZ) throws IOException {
        long key = key(tileX, tileZ);
        if (!summaries.containsKey(key)) {
            return null;
        }
        synchronized (cache) {
            PreviewTile t = cache.get(key);
            if (t != null) {
                return t;
            }
        }
        PreviewTile t = load(file(tileX, tileZ));
        synchronized (cache) {
            cache.put(key, t);
        }
        return t;
    }

    /** Writes a tile through a temporary file, then tells the listeners. */
    public void write(int tileX, int tileZ, PreviewTile tile) throws IOException {
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
        Path file = file(tileX, tileZ);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, java.util.Arrays.copyOf(out, n + 4));
        replace(tmp, file);
        long key = key(tileX, tileZ);
        synchronized (cache) {
            cache.put(key, tile);
        }
        summaries.put(key, tile.summary());
        for (LongConsumer l : listeners) {
            l.accept(key);
        }
    }

    /**
     * A stored tile's file as it is on disk (raw length, then its zstd
     * frame), or null: sent as is to clients (ADR-068).
     */
    public byte[] readFile(int tileX, int tileZ) throws IOException {
        if (!summaries.containsKey(key(tileX, tileZ))) {
            return null;
        }
        try {
            return Files.readAllBytes(file(tileX, tileZ));
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

    public void addListener(LongConsumer onTileWritten) {
        listeners.add(onTileWritten);
    }

    public void removeListener(LongConsumer onTileWritten) {
        listeners.remove(onTileWritten);
    }

    private Path file(int tileX, int tileZ) {
        return dir.resolve("t." + tileX + "." + tileZ + ".ffp");
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
