package io.lakio.farfield.core.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Growable little-endian byte output with LEB128 varints. Reusable through
 * {@link #reset()}.
 */
public final class ByteWriter {

    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_LE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private byte[] buf;
    private int pos;

    public ByteWriter(int initialCapacity) {
        buf = new byte[Math.max(16, initialCapacity)];
    }

    public void reset() {
        pos = 0;
    }

    public int length() {
        return pos;
    }

    /** The backing array; valid bytes are {@code [0, length())}. */
    public byte[] array() {
        return buf;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, pos);
    }

    private void ensure(int n) {
        if (pos + n > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(pos + n, buf.length * 2));
        }
    }

    public void writeByte(int v) {
        ensure(1);
        buf[pos++] = (byte) v;
    }

    public void writeVarInt(int v) {
        ensure(5);
        while ((v & ~0x7F) != 0) {
            buf[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[pos++] = (byte) v;
    }

    public void writeInt(int v) {
        ensure(4);
        INT_LE.set(buf, pos, v);
        pos += 4;
    }

    public void writeLong(long v) {
        ensure(8);
        LONG_LE.set(buf, pos, v);
        pos += 8;
    }

    public void writeLongs(long[] values) {
        ensure(values.length * 8);
        for (long v : values) {
            LONG_LE.set(buf, pos, v);
            pos += 8;
        }
    }

    public void writeBytes(byte[] data, int offset, int length) {
        ensure(length);
        System.arraycopy(data, offset, buf, pos, length);
        pos += length;
    }
}
