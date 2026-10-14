package io.lakio.farfield.core.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Bounds-checked little-endian reader matching {@link ByteWriter}; throws
 * {@link IllegalStateException} on truncated or malformed input.
 */
public final class ByteReader {

    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_LE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private byte[] buf = new byte[0];
    private int pos;
    private int limit;

    public ByteReader reset(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("range outside the array");
        }
        buf = data;
        pos = offset;
        limit = offset + length;
        return this;
    }

    public int remaining() {
        return limit - pos;
    }

    private void require(int n) {
        if (n < 0 || n > limit - pos) {
            throw new IllegalStateException("truncated data: need " + n + " bytes, have " + (limit - pos));
        }
    }

    public int readByte() {
        require(1);
        return buf[pos++] & 0xFF;
    }

    public int readVarInt() {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = readByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
        }
        throw new IllegalStateException("varint too long");
    }

    public int readInt() {
        require(4);
        int v = (int) INT_LE.get(buf, pos);
        pos += 4;
        return v;
    }

    public long readLong() {
        require(8);
        long v = (long) LONG_LE.get(buf, pos);
        pos += 8;
        return v;
    }

    public void readLongs(long[] out) {
        require(out.length * 8);
        for (int i = 0; i < out.length; i++) {
            out[i] = (long) LONG_LE.get(buf, pos);
            pos += 8;
        }
    }

    public void readBytes(byte[] out, int offset, int length) {
        require(length);
        System.arraycopy(buf, pos, out, offset, length);
        pos += length;
    }
}
