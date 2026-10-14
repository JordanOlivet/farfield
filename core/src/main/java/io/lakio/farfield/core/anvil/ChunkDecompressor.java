package io.lakio.farfield.core.anvil;

import io.lakio.farfield.core.util.GrowableBytes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * Decompresses chunk payloads. Holds a reusable {@link Inflater}, so use one
 * instance per thread and {@link #close()} it when done.
 *
 * <p>Compression types, as stored in the byte that precedes each chunk
 * payload (the 0x80 bit, meaning "payload in an external .mcc file", is
 * handled by {@link RegionFile}):
 * 1 gzip, 2 zlib (the default), 3 uncompressed, 4 LZ4 (1.20.5+),
 * 127 custom named algorithm (not supported).
 */
public final class ChunkDecompressor implements AutoCloseable {

    public static final int GZIP = 1;
    public static final int ZLIB = 2;
    public static final int NONE = 3;
    public static final int LZ4 = 4;
    public static final int CUSTOM = 127;

    private final Inflater inflater = new Inflater();

    /**
     * Decompresses {@code length} bytes of {@code src} with the given
     * compression type into {@code out}, replacing its content.
     */
    public void decompress(int type, byte[] src, int offset, int length, GrowableBytes out) {
        out.setLength(0);
        switch (type) {
            case ZLIB -> inflate(src, offset, length, out);
            case NONE -> {
                byte[] dst = out.ensureCapacity(length);
                System.arraycopy(src, offset, dst, 0, length);
                out.setLength(length);
            }
            case LZ4 -> Lz4BlockStream.decode(src, offset, length, out);
            case GZIP -> gunzip(src, offset, length, out);
            default -> throw new AnvilFormatException("unsupported chunk compression type " + type);
        }
    }

    private void inflate(byte[] src, int offset, int length, GrowableBytes out) {
        inflater.reset();
        inflater.setInput(src, offset, length);
        // Chunk NBT usually expands 4x to 8x.
        byte[] dst = out.ensureCapacity(length * 6);
        int n = 0;
        try {
            while (!inflater.finished()) {
                if (n == dst.length) {
                    out.setLength(n);
                    dst = out.ensureCapacity(n * 2);
                }
                int k = inflater.inflate(dst, n, dst.length - n);
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new AnvilFormatException("truncated zlib stream");
                }
                n += k;
            }
        } catch (DataFormatException e) {
            throw new AnvilFormatException("corrupt zlib stream", e);
        }
        out.setLength(n);
    }

    private static void gunzip(byte[] src, int offset, int length, GrowableBytes out) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(src, offset, length))) {
            byte[] dst = out.ensureCapacity(length * 6);
            int n = 0;
            int k;
            while (true) {
                if (n == dst.length) {
                    out.setLength(n);
                    dst = out.ensureCapacity(n * 2);
                }
                k = in.read(dst, n, dst.length - n);
                if (k < 0) {
                    break;
                }
                n += k;
            }
            out.setLength(n);
        } catch (IOException e) {
            throw new AnvilFormatException("corrupt gzip stream", e);
        }
    }

    @Override
    public void close() {
        inflater.end();
    }
}
