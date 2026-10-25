package io.lakio.farfield.core.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.CRC32C;

/**
 * A key-value file for LOD blobs: an append-only log of records plus an
 * in-memory index of the latest record per key.
 *
 * <pre>
 *   record: magic "FFL1" (4) | key (8) | length (4) | CRC32C of key, length
 *           and payload (4) | payload        (little-endian)
 * </pre>
 *
 * <ul>
 *   <li><b>Crash safety</b>: a write only appends; nothing is overwritten.
 *       After a crash, reopening scans the records written since the last
 *       index snapshot and cuts the file at the first incomplete record
 *       (torn tail). Records made durable by {@link #sync()} survive.</li>
 *   <li><b>Fast open</b>: {@link #close()} writes an index snapshot next to
 *       the log ({@code .idx}, with its own checksum and the log length it
 *       covers); a missing or stale snapshot falls back to a scan.</li>
 *   <li><b>Corruption</b>: a record whose checksum fails is skipped at scan
 *       time and {@link #get} reports it; replacing a key leaves garbage
 *       that {@link #compact(Path)} reclaims.</li>
 * </ul>
 *
 * <p>One writer thread; {@link #get} may run concurrently with it.
 */
public final class LogStore implements AutoCloseable {

    static final int MAGIC = 0x314C4646; // "FFL1" little-endian
    static final int HEADER = 20;
    private static final int INDEX_MAGIC = 0x58444946; // "FIDX"
    private static final int MAX_RECORD = 64 << 20;

    private final Path file;
    private final FileChannel channel;
    /**
     * Extra read-only channels: positional reads on one shared channel are
     * serialized on Windows, so concurrent readers each borrow their own.
     */
    private final ConcurrentLinkedQueue<FileChannel> readers = new ConcurrentLinkedQueue<>();
    private final List<FileChannel> allReaders = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, Long> index = new ConcurrentHashMap<>();
    private final ByteBuffer header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
    private final CRC32C crc = new CRC32C();
    private long end;
    private long liveBytes;
    private int corruptRecords;
    private long truncatedBytes;

    private LogStore(Path file, FileChannel channel) {
        this.file = file;
        this.channel = channel;
    }

    public static LogStore open(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        LogStore store = new LogStore(file, channel);
        long from = store.loadSnapshot();
        store.scan(from);
        // The snapshot is now stale for any append: drop it until the next clean close.
        Files.deleteIfExists(indexFile(file));
        return store;
    }

    static Path indexFile(Path file) {
        return file.resolveSibling(file.getFileName() + ".idx");
    }

    private static long location(long offset, int length) {
        return offset << 26 | length;
    }

    private static long offsetOf(long location) {
        return location >>> 26;
    }

    private static int lengthOf(long location) {
        return (int) (location & ((1 << 26) - 1));
    }

    /** Loads the index snapshot if it matches the log; returns where scanning must start. */
    private long loadSnapshot() throws IOException {
        Path idx = indexFile(file);
        if (!Files.exists(idx)) {
            return 0;
        }
        byte[] bytes = Files.readAllBytes(idx);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 28 || b.getInt(0) != INDEX_MAGIC) {
            return 0;
        }
        long covered = b.getLong(4);
        long live = b.getLong(12);
        int count = b.getInt(20);
        if (bytes.length != 28 + count * 16L || covered > channel.size()) {
            return 0;
        }
        CRC32C check = new CRC32C();
        check.update(bytes, 0, bytes.length - 4);
        if ((int) check.getValue() != b.getInt(bytes.length - 4)) {
            return 0;
        }
        for (int i = 0; i < count; i++) {
            index.put(b.getLong(24 + i * 16), b.getLong(32 + i * 16));
        }
        liveBytes = live;
        return covered;
    }

    private void scan(long from) throws IOException {
        long size = channel.size();
        long pos = from;
        ByteBuffer payload = ByteBuffer.allocate(64 * 1024);
        while (pos < size) {
            if (size - pos < HEADER) {
                break;
            }
            header.clear();
            readFully(header, pos);
            int magic = header.getInt(0);
            long key = header.getLong(4);
            int length = header.getInt(12);
            int expected = header.getInt(16);
            if (magic != MAGIC || length < 0 || length > MAX_RECORD || pos + HEADER + length > size) {
                break;
            }
            if (payload.capacity() < length) {
                payload = ByteBuffer.allocate(Math.max(length, payload.capacity() * 2));
            }
            payload.clear().limit(length);
            readFully(payload, pos + HEADER);
            if (checksum(key, length, payload.array(), 0, length) == expected) {
                Long previous = index.put(key, location(pos, length));
                if (previous != null) {
                    liveBytes -= HEADER + lengthOf(previous);
                }
                liveBytes += HEADER + length;
            } else {
                corruptRecords++;
            }
            pos += HEADER + length;
        }
        if (pos < size) {
            // Torn tail of an interrupted write.
            truncatedBytes = size - pos;
            channel.truncate(pos);
            channel.force(true);
        }
        end = pos;
    }

    private int checksum(long key, int length, byte[] data, int offset, int count) {
        crc.reset();
        ByteBuffer meta = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        meta.putLong(key).putInt(length);
        crc.update(meta.array(), 0, 12);
        crc.update(data, offset, count);
        return (int) crc.getValue();
    }

    /** Appends a record for {@code key}; durable after the next {@link #sync()}. */
    public synchronized void put(long key, byte[] data, int offset, int length) throws IOException {
        if (length > MAX_RECORD) {
            throw new IllegalArgumentException("record too large: " + length);
        }
        ByteBuffer record = ByteBuffer.allocate(HEADER + length).order(ByteOrder.LITTLE_ENDIAN);
        record.putInt(MAGIC).putLong(key).putInt(length).putInt(checksum(key, length, data, offset, length));
        record.put(data, offset, length).flip();
        long pos = end;
        while (record.hasRemaining()) {
            pos += channel.write(record, pos);
        }
        Long previous = index.put(key, location(end, length));
        if (previous != null) {
            liveBytes -= HEADER + lengthOf(previous);
        }
        liveBytes += HEADER + length;
        end = pos;
    }

    /**
     * Returns the latest payload of {@code key}, or null if absent.
     *
     * @throws IOException if the record's checksum no longer matches
     */
    public byte[] get(long key) throws IOException {
        Long location = index.get(key);
        if (location == null) {
            return null;
        }
        long offset = offsetOf(location);
        int length = lengthOf(location);
        ByteBuffer record = ByteBuffer.allocate(HEADER + length).order(ByteOrder.LITTLE_ENDIAN);
        FileChannel reader = readers.poll();
        if (reader == null) {
            reader = FileChannel.open(file, StandardOpenOption.READ);
            allReaders.add(reader);
        }
        try {
            long pos = offset;
            while (record.hasRemaining()) {
                int k = reader.read(record, pos);
                if (k < 0) {
                    throw new IOException("unexpected end of " + file.getFileName());
                }
                pos += k;
            }
        } finally {
            readers.add(reader);
        }
        byte[] bytes = record.array();
        int expected = record.getInt(16);
        CRC32C check = new CRC32C();
        check.update(bytes, 4, 12);
        check.update(bytes, HEADER, length);
        if (record.getInt(0) != MAGIC || record.getLong(4) != key || (int) check.getValue() != expected) {
            throw new IOException("corrupt record for key " + Long.toHexString(key) + " in " + file.getFileName());
        }
        byte[] payload = new byte[length];
        System.arraycopy(bytes, HEADER, payload, 0, length);
        return payload;
    }

    public boolean contains(long key) {
        return index.containsKey(key);
    }

    public int size() {
        return index.size();
    }

    /** Bytes of the log file. */
    public long fileBytes() {
        return end;
    }

    /** Bytes of the latest record of every key (headers included); the rest is garbage. */
    public long liveBytes() {
        return liveBytes;
    }

    /** Records skipped at open because their checksum failed. */
    public int corruptRecords() {
        return corruptRecords;
    }

    /** Bytes cut from the end of the file at open (incomplete last write). */
    public long truncatedBytes() {
        return truncatedBytes;
    }

    public Iterable<Long> keys() {
        return index.keySet();
    }

    /**
     * Version of a key's latest record (its place in the log, which every
     * write of the key moves; a compaction too), or -1 if absent.
     */
    public long version(long key) {
        Long location = index.get(key);
        return location == null ? -1 : location;
    }

    /** Calls {@code action} with every key and its {@link #version} (order unspecified). */
    public void forEachVersion(KeyVersionConsumer action) {
        for (Map.Entry<Long, Long> e : index.entrySet()) {
            action.accept(e.getKey(), e.getValue());
        }
    }

    /** A key and its version ({@link #version}). */
    @FunctionalInterface
    public interface KeyVersionConsumer {
        void accept(long key, long version);
    }

    /** Makes every record written so far durable. */
    public void sync() throws IOException {
        channel.force(false);
    }

    /**
     * Rewrites the log of a closed store with its live records only. A crash
     * at any point leaves either the old or the new log, each readable.
     */
    public static void compact(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".compact");
        Files.deleteIfExists(tmp);
        Files.deleteIfExists(indexFile(tmp));
        try (LogStore source = open(file); LogStore copy = open(tmp)) {
            for (Long key : source.index.keySet()) {
                byte[] payload = source.get(key);
                copy.put(key, payload, 0, payload.length);
            }
        }
        // Drop the old snapshot first: a crash between the moves then falls back to a scan.
        Files.deleteIfExists(indexFile(file));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.move(indexFile(tmp), indexFile(file), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void readFully(ByteBuffer dst, long position) throws IOException {
        long pos = position;
        while (dst.hasRemaining()) {
            int k = channel.read(dst, pos);
            if (k < 0) {
                throw new IOException("unexpected end of " + file.getFileName());
            }
            pos += k;
        }
    }

    /** Syncs, writes the index snapshot and closes. */
    @Override
    public synchronized void close() throws IOException {
        if (!channel.isOpen()) {
            return;
        }
        channel.force(true);
        int count = index.size();
        ByteBuffer b = ByteBuffer.allocate(28 + count * 16).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(INDEX_MAGIC).putLong(end).putLong(liveBytes).putInt(count);
        for (Map.Entry<Long, Long> e : index.entrySet()) {
            b.putLong(e.getKey()).putLong(e.getValue());
        }
        CRC32C check = new CRC32C();
        check.update(b.array(), 0, b.position());
        b.putInt((int) check.getValue());
        Path idx = indexFile(file);
        Path tmp = idx.resolveSibling(idx.getFileName() + ".tmp");
        Files.write(tmp, b.array());
        Files.move(tmp, idx, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        channel.close();
        synchronized (allReaders) {
            for (FileChannel reader : allReaders) {
                reader.close();
            }
            allReaders.clear();
        }
    }
}
