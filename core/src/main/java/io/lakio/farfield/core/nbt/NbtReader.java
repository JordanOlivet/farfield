package io.lakio.farfield.core.nbt;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Forward-only cursor over binary NBT (big-endian, uncompressed) held in a
 * byte array. It never builds a tag tree: callers walk the structure, read
 * the fields they need and {@link #skipPayload skip} the rest, which keeps
 * chunk decoding free of per-tag allocation.
 *
 * <p>Every read is bounds-checked against the logical end of the data (the
 * backing array may be a larger reusable buffer) and throws
 * {@link NbtFormatException} on truncated or malformed input.
 */
public final class NbtReader {

    /** Same nesting limit as the game, so hostile data cannot overflow the stack. */
    public static final int MAX_DEPTH = 512;

    private static final VarHandle SHORT_BE =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT_BE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_BE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private byte[] buf = new byte[0];
    private int pos;
    private int limit;

    /** Points the reader at {@code length} bytes of {@code data} from {@code offset}. */
    public void reset(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("range outside the array");
        }
        this.buf = data;
        this.pos = offset;
        this.limit = offset + length;
    }

    public byte[] buffer() {
        return buf;
    }

    public int position() {
        return pos;
    }

    /** Moves the cursor to an absolute position previously returned by {@link #position()}. */
    public void seek(int position) {
        if (position < 0 || position > limit) {
            throw new NbtFormatException("seek outside the data: " + position);
        }
        pos = position;
    }

    public int remaining() {
        return limit - pos;
    }

    private void require(int n) {
        if (n < 0 || n > limit - pos) {
            throw new NbtFormatException("truncated NBT: need " + n + " bytes at " + pos + ", have " + (limit - pos));
        }
    }

    public int readTagType() {
        require(1);
        int type = buf[pos++];
        if (type < NbtTag.END || type > NbtTag.LONG_ARRAY) {
            throw new NbtFormatException("unknown tag type " + type + " at " + (pos - 1));
        }
        return type;
    }

    public byte readByte() {
        require(1);
        return buf[pos++];
    }

    public short readShort() {
        require(2);
        short v = (short) SHORT_BE.get(buf, pos);
        pos += 2;
        return v;
    }

    public int readUnsignedShort() {
        return readShort() & 0xFFFF;
    }

    public int readInt() {
        require(4);
        int v = (int) INT_BE.get(buf, pos);
        pos += 4;
        return v;
    }

    public long readLong() {
        require(8);
        long v = (long) LONG_BE.get(buf, pos);
        pos += 8;
        return v;
    }

    /** Reads the big-endian long at an absolute position, without moving the cursor. */
    public long longAt(int position) {
        return (long) LONG_BE.get(buf, position);
    }

    /** Reads a length prefix (int) for an array payload and checks it is non-negative. */
    public int readArrayLength() {
        int n = readInt();
        if (n < 0) {
            throw new NbtFormatException("negative array length " + n + " at " + (pos - 4));
        }
        return n;
    }

    /** Skips {@code n} bytes. */
    public void skip(int n) {
        require(n);
        pos += n;
    }

    /**
     * Reads a string length prefix and leaves the cursor on the first string
     * byte. Pair with {@link #position()} and {@link #skip(int)} to compare
     * names without decoding them.
     */
    public int readStringLength() {
        int len = readUnsignedShort();
        require(len);
        return len;
    }

    /**
     * Reads the next string and returns true if its bytes equal
     * {@code expected} (an ASCII name). The cursor ends after the string
     * either way.
     */
    public boolean readStringEquals(byte[] expected) {
        int len = readStringLength();
        boolean equal = len == expected.length
                && Arrays.equals(buf, pos, pos + len, expected, 0, len);
        pos += len;
        return equal;
    }

    /** Reads and decodes a string (Java modified UTF-8). */
    public String readString() {
        int len = readStringLength();
        String s = decodeModifiedUtf8(buf, pos, len);
        pos += len;
        return s;
    }

    /** Skips the payload of a tag of the given type (the name has been read already). */
    public void skipPayload(int type) {
        skipPayload(type, 0);
    }

    private void skipPayload(int type, int depth) {
        if (depth > MAX_DEPTH) {
            throw new NbtFormatException("NBT nested deeper than " + MAX_DEPTH);
        }
        switch (type) {
            case NbtTag.BYTE -> skip(1);
            case NbtTag.SHORT -> skip(2);
            case NbtTag.INT, NbtTag.FLOAT -> skip(4);
            case NbtTag.LONG, NbtTag.DOUBLE -> skip(8);
            case NbtTag.BYTE_ARRAY -> skip(readArrayLength());
            case NbtTag.STRING -> skip(readUnsignedShort());
            case NbtTag.INT_ARRAY -> skipElements(readArrayLength(), 4);
            case NbtTag.LONG_ARRAY -> skipElements(readArrayLength(), 8);
            case NbtTag.LIST -> {
                int elementType = readTagType();
                int count = readArrayLength();
                int size = NbtTag.fixedSize(elementType);
                if (size >= 0) {
                    skipElements(count, size);
                } else {
                    for (int i = 0; i < count; i++) {
                        skipPayload(elementType, depth + 1);
                    }
                }
            }
            case NbtTag.COMPOUND -> {
                int fieldType;
                while ((fieldType = readTagType()) != NbtTag.END) {
                    skip(readUnsignedShort());
                    skipPayload(fieldType, depth + 1);
                }
            }
            default -> throw new NbtFormatException("cannot skip tag type " + type);
        }
    }

    private void skipElements(int count, int size) {
        long bytes = (long) count * size;
        if (bytes > Integer.MAX_VALUE) {
            throw new NbtFormatException("array too large: " + count + " elements");
        }
        skip((int) bytes);
    }

    /**
     * Decodes Java modified UTF-8 (the encoding NBT strings use). Pure ASCII,
     * by far the common case for block and biome keys, takes a fast path.
     */
    public static String decodeModifiedUtf8(byte[] data, int offset, int length) {
        int end = offset + length;
        int i = offset;
        while (i < end && data[i] >= 0) {
            i++;
        }
        if (i == end) {
            return new String(data, offset, length, StandardCharsets.ISO_8859_1);
        }
        char[] chars = new char[length];
        int n = 0;
        for (int j = offset; j < offset + length; ) {
            int b = data[j] & 0xFF;
            if (b < 0x80) {
                chars[n++] = (char) b;
                j++;
            } else if ((b & 0xE0) == 0xC0) {
                if (j + 1 >= end) {
                    throw new NbtFormatException("truncated UTF-8 sequence");
                }
                chars[n++] = (char) (((b & 0x1F) << 6) | (data[j + 1] & 0x3F));
                j += 2;
            } else if ((b & 0xF0) == 0xE0) {
                if (j + 2 >= end) {
                    throw new NbtFormatException("truncated UTF-8 sequence");
                }
                chars[n++] = (char) (((b & 0x0F) << 12) | ((data[j + 1] & 0x3F) << 6) | (data[j + 2] & 0x3F));
                j += 3;
            } else {
                throw new NbtFormatException("invalid modified UTF-8 byte " + b);
            }
        }
        return new String(chars, 0, n);
    }
}
