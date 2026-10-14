package io.lakio.farfield.core.anvil;

import io.lakio.farfield.core.util.GrowableBytes;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Decoder for the "LZ4Block" stream framing that region files use for
 * compression type 4 (Minecraft 1.20.5+ writes it with the lz4-java
 * library's block output stream), implemented from the format description,
 * without a dependency.
 *
 * <p>A stream is a sequence of blocks, each with a 21-byte header:
 *
 * <pre>
 *   magic            8 bytes  "LZ4Block"
 *   token            1 byte   method (high nibble: 0x10 raw, 0x20 LZ4)
 *                             | block size exponent (low nibble, size = 2^(10+n))
 *   compressed size  4 bytes  little-endian
 *   original size    4 bytes  little-endian
 *   checksum         4 bytes  little-endian, XXH32 (seed 0x9747B28C) of the
 *                             original bytes, masked to its low 28 bits
 * </pre>
 *
 * <p>A block with both sizes at zero ends the stream.
 */
final class Lz4BlockStream {

    private static final byte[] MAGIC = {'L', 'Z', '4', 'B', 'l', 'o', 'c', 'k'};
    private static final int HEADER_LENGTH = MAGIC.length + 1 + 4 + 4 + 4;
    private static final int METHOD_RAW = 0x10;
    private static final int METHOD_LZ4 = 0x20;
    private static final int BLOCK_SIZE_BASE = 10;
    private static final int CHECKSUM_SEED = 0x9747B28C;
    private static final int CHECKSUM_MASK = 0x0FFFFFFF;
    private static final int MIN_MATCH = 4;

    private static final VarHandle INT_LE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private Lz4BlockStream() {
    }

    /** Decodes a whole stream from {@code src} and appends the result to {@code out}. */
    static void decode(byte[] src, int offset, int length, GrowableBytes out) {
        int p = offset;
        int end = offset + length;
        while (p < end) {
            if (end - p < HEADER_LENGTH) {
                throw new AnvilFormatException("truncated LZ4 block header");
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (src[p + i] != MAGIC[i]) {
                    throw new AnvilFormatException("bad LZ4 block magic");
                }
            }
            int token = src[p + 8] & 0xFF;
            int compressedSize = (int) INT_LE.get(src, p + 9);
            int originalSize = (int) INT_LE.get(src, p + 13);
            int checksum = (int) INT_LE.get(src, p + 17);
            p += HEADER_LENGTH;

            int method = token & 0xF0;
            int maxBlockSize = 1 << (BLOCK_SIZE_BASE + (token & 0x0F));
            if (originalSize == 0 && compressedSize == 0) {
                return;
            }
            if (originalSize < 0 || originalSize > maxBlockSize || compressedSize < 0 || compressedSize > end - p) {
                throw new AnvilFormatException("bad LZ4 block sizes: " + compressedSize + " -> " + originalSize);
            }

            int start = out.length();
            byte[] dst = out.ensureCapacity(start + originalSize);
            if (method == METHOD_RAW) {
                if (compressedSize != originalSize) {
                    throw new AnvilFormatException("raw LZ4 block with mismatched sizes");
                }
                System.arraycopy(src, p, dst, start, originalSize);
            } else if (method == METHOD_LZ4) {
                decompressBlock(src, p, compressedSize, dst, start, originalSize);
            } else {
                throw new AnvilFormatException("unknown LZ4 block method 0x" + Integer.toHexString(method));
            }
            if ((XxHash32.hash(dst, start, originalSize, CHECKSUM_SEED) & CHECKSUM_MASK) != checksum) {
                throw new AnvilFormatException("LZ4 block checksum mismatch");
            }
            out.setLength(start + originalSize);
            p += compressedSize;
        }
    }

    /**
     * Decompresses one raw LZ4 block (the LZ4 block format: sequences of a
     * token, literals, a 2-byte little-endian match offset and a match
     * length) that must expand to exactly {@code originalSize} bytes.
     */
    static void decompressBlock(byte[] src, int srcOffset, int srcLength, byte[] dst, int dstOffset, int originalSize) {
        int sp = srcOffset;
        int srcEnd = srcOffset + srcLength;
        int dp = dstOffset;
        int dstEnd = dstOffset + originalSize;
        while (true) {
            if (sp >= srcEnd) {
                throw new AnvilFormatException("truncated LZ4 sequence");
            }
            int token = src[sp++] & 0xFF;

            int literals = token >>> 4;
            if (literals == 15) {
                int b;
                do {
                    if (sp >= srcEnd) {
                        throw new AnvilFormatException("truncated LZ4 literal length");
                    }
                    b = src[sp++] & 0xFF;
                    literals += b;
                } while (b == 255);
            }
            if (literals > srcEnd - sp || literals > dstEnd - dp) {
                throw new AnvilFormatException("LZ4 literals overflow");
            }
            System.arraycopy(src, sp, dst, dp, literals);
            sp += literals;
            dp += literals;

            if (sp == srcEnd) {
                // The last sequence has literals only.
                if (dp != dstEnd) {
                    throw new AnvilFormatException("LZ4 block decoded to " + (dp - dstOffset) + " bytes, expected " + originalSize);
                }
                return;
            }
            if (srcEnd - sp < 2) {
                throw new AnvilFormatException("truncated LZ4 match offset");
            }
            int matchOffset = (src[sp] & 0xFF) | (src[sp + 1] & 0xFF) << 8;
            sp += 2;
            if (matchOffset == 0 || matchOffset > dp - dstOffset) {
                throw new AnvilFormatException("bad LZ4 match offset " + matchOffset);
            }

            int matchLength = token & 0x0F;
            if (matchLength == 15) {
                int b;
                do {
                    if (sp >= srcEnd) {
                        throw new AnvilFormatException("truncated LZ4 match length");
                    }
                    b = src[sp++] & 0xFF;
                    matchLength += b;
                } while (b == 255);
            }
            matchLength += MIN_MATCH;
            if (matchLength > dstEnd - dp) {
                throw new AnvilFormatException("LZ4 match overflow");
            }

            int from = dp - matchOffset;
            if (matchOffset >= matchLength) {
                System.arraycopy(dst, from, dst, dp, matchLength);
                dp += matchLength;
            } else {
                // Overlapping copy: repeats the last matchOffset bytes.
                for (int i = 0; i < matchLength; i++) {
                    dst[dp++] = dst[from + i];
                }
            }
        }
    }
}
