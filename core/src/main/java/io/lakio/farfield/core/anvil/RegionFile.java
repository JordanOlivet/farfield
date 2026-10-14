package io.lakio.farfield.core.anvil;

import io.lakio.farfield.core.util.GrowableBytes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only access to one Anvil region file ({@code r.<x>.<z>.mca}, 32x32
 * chunks).
 *
 * <p>Layout: a 4 KiB table of 1024 locations (3-byte sector offset, 1-byte
 * sector count, big-endian), a 4 KiB table of 1024 timestamps (seconds), then
 * 4 KiB sectors. A chunk starts with its length (4 bytes, counting the next
 * byte), a compression type byte, then the compressed NBT. When the type has
 * the 0x80 bit set the payload is in {@code c.<chunkX>.<chunkZ>.mcc} next to
 * the region file (chunks larger than 1 MiB). Chunk index =
 * {@code (x & 31) + (z & 31) * 32}.
 *
 * <p>Not thread-safe: open one instance per worker.
 */
public final class RegionFile implements AutoCloseable {

    public static final int SECTOR_BYTES = 4096;
    public static final int CHUNKS_PER_SIDE = 32;
    public static final int CHUNK_COUNT = CHUNKS_PER_SIDE * CHUNKS_PER_SIDE;
    public static final int EXTERNAL_FLAG = 0x80;

    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int HEADER_BYTES = 2 * SECTOR_BYTES;
    private static final int CHUNK_HEADER_BYTES = 5;

    private final Path path;
    private final FileChannel channel;
    private final long fileSize;
    private final int regionX;
    private final int regionZ;
    private final int[] locations = new int[CHUNK_COUNT];
    private final int[] timestamps = new int[CHUNK_COUNT];

    private RegionFile(Path path, FileChannel channel, int regionX, int regionZ) throws IOException {
        this.path = path;
        this.channel = channel;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.fileSize = channel.size();
        if (fileSize >= HEADER_BYTES) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            readFully(header, 0);
            header.flip();
            header.asIntBuffer().get(locations).get(timestamps);
        }
        // A file shorter than the header is a region the game created but
        // never wrote to: it holds no chunks.
    }

    /** Opens a region file; its region coordinates come from its name. */
    public static RegionFile open(Path path) throws IOException {
        int[] coords = parseName(path.getFileName().toString());
        if (coords == null) {
            throw new IllegalArgumentException("not a region file name: " + path.getFileName());
        }
        return new RegionFile(path, FileChannel.open(path, StandardOpenOption.READ), coords[0], coords[1]);
    }

    /** Returns {regionX, regionZ} for a name like {@code r.-3.12.mca}, or null. */
    public static int[] parseName(String fileName) {
        Matcher m = NAME.matcher(fileName);
        if (!m.matches()) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Path path() {
        return path;
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    public boolean hasChunk(int index) {
        return locations[index] != 0;
    }

    /** Number of non-empty entries in the location table. */
    public int chunkCount() {
        int n = 0;
        for (int location : locations) {
            if (location != 0) {
                n++;
            }
        }
        return n;
    }

    /** Last save time of a chunk, in seconds since the epoch (0 if unknown). */
    public int timestamp(int index) {
        return timestamps[index];
    }

    public int chunkX(int index) {
        return regionX * CHUNKS_PER_SIDE + (index & (CHUNKS_PER_SIDE - 1));
    }

    public int chunkZ(int index) {
        return regionZ * CHUNKS_PER_SIDE + (index / CHUNKS_PER_SIDE);
    }

    /**
     * Reads the still-compressed payload of a chunk into {@code out}.
     *
     * @return false if the chunk is absent
     * @throws AnvilFormatException if the location or chunk header is invalid
     */
    public boolean read(int index, RawChunk out) throws IOException {
        int location = locations[index];
        if (location == 0) {
            return false;
        }
        long offset = (long) (location >>> 8) * SECTOR_BYTES;
        int sectors = location & 0xFF;
        if (offset < HEADER_BYTES || sectors == 0 || offset + CHUNK_HEADER_BYTES > fileSize) {
            throw new AnvilFormatException("bad location entry 0x" + Integer.toHexString(location));
        }
        // The last chunk of a file may end before its last sector is padded.
        int span = (int) Math.min((long) sectors * SECTOR_BYTES, fileSize - offset);
        GrowableBytes bytes = out.bytes;
        bytes.setLength(0);
        byte[] buf = bytes.ensureCapacity(span);
        readFully(ByteBuffer.wrap(buf, 0, span), offset);

        int length = ((buf[0] & 0xFF) << 24) | ((buf[1] & 0xFF) << 16) | ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        int type = buf[4] & 0xFF;
        if (length < 1 || length > span - 4) {
            throw new AnvilFormatException("bad chunk length " + length + " (" + span + " bytes of sectors)");
        }
        out.timestamp = timestamps[index];
        if ((type & EXTERNAL_FLAG) != 0) {
            out.compression = type & ~EXTERNAL_FLAG;
            out.external = true;
            readExternal(chunkX(index), chunkZ(index), out);
        } else {
            out.compression = type;
            out.external = false;
            out.offset = CHUNK_HEADER_BYTES;
            out.length = length - 1;
            bytes.setLength(CHUNK_HEADER_BYTES + out.length);
        }
        return true;
    }

    private void readExternal(int chunkX, int chunkZ, RawChunk out) throws IOException {
        Path file = path.resolveSibling("c." + chunkX + "." + chunkZ + ".mcc");
        try (FileChannel ext = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ext.size();
            if (size > Integer.MAX_VALUE - 8) {
                throw new AnvilFormatException("external chunk too large: " + file.getFileName());
            }
            int n = (int) size;
            out.bytes.setLength(0);
            byte[] buf = out.bytes.ensureCapacity(n);
            ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
            long pos = 0;
            while (bb.hasRemaining()) {
                int k = ext.read(bb, pos);
                if (k < 0) {
                    throw new AnvilFormatException("external chunk file shrank: " + file.getFileName());
                }
                pos += k;
            }
            out.bytes.setLength(n);
            out.offset = 0;
            out.length = n;
        } catch (NoSuchFileException e) {
            throw new AnvilFormatException("missing external chunk file " + file.getFileName(), e);
        }
    }

    private void readFully(ByteBuffer dst, long position) throws IOException {
        long pos = position;
        while (dst.hasRemaining()) {
            int k = channel.read(dst, pos);
            if (k < 0) {
                throw new AnvilFormatException("unexpected end of region file " + path.getFileName());
            }
            pos += k;
        }
    }

    /** True if {@code path} has a region file name (does not check the content). */
    public static boolean isRegionFile(Path path) {
        return parseName(path.getFileName().toString()) != null && Files.isRegularFile(path);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * A chunk payload as stored: compression type and the compressed bytes at
     * {@code bytes.data()[offset .. offset + length)}. Reused across reads.
     */
    public static final class RawChunk {
        public final GrowableBytes bytes = new GrowableBytes(64 * 1024);
        public int compression;
        public int offset;
        public int length;
        public int timestamp;
        public boolean external;
    }
}
