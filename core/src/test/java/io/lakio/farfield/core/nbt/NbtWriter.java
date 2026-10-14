package io.lakio.farfield.core.nbt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Minimal binary NBT writer for tests: named fields inside compounds, and
 * unnamed payloads for list elements.
 */
public final class NbtWriter {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(bytes);

    /** Starts the root compound. */
    public NbtWriter root() {
        return beginCompound("");
    }

    public NbtWriter beginCompound(String name) {
        return tag(NbtTag.COMPOUND, name);
    }

    public NbtWriter end() {
        return u8(NbtTag.END);
    }

    public NbtWriter putByte(String name, int v) {
        return tag(NbtTag.BYTE, name).u8(v);
    }

    public NbtWriter putShort(String name, int v) {
        tag(NbtTag.SHORT, name);
        return io(() -> out.writeShort(v));
    }

    public NbtWriter putInt(String name, int v) {
        tag(NbtTag.INT, name);
        return io(() -> out.writeInt(v));
    }

    public NbtWriter putLong(String name, long v) {
        tag(NbtTag.LONG, name);
        return io(() -> out.writeLong(v));
    }

    public NbtWriter putFloat(String name, float v) {
        tag(NbtTag.FLOAT, name);
        return io(() -> out.writeFloat(v));
    }

    public NbtWriter putDouble(String name, double v) {
        tag(NbtTag.DOUBLE, name);
        return io(() -> out.writeDouble(v));
    }

    public NbtWriter putString(String name, String v) {
        tag(NbtTag.STRING, name);
        return string(v);
    }

    public NbtWriter putByteArray(String name, byte[] v) {
        tag(NbtTag.BYTE_ARRAY, name);
        return io(() -> {
            out.writeInt(v.length);
            out.write(v);
        });
    }

    public NbtWriter putIntArray(String name, int[] v) {
        tag(NbtTag.INT_ARRAY, name);
        return io(() -> {
            out.writeInt(v.length);
            for (int x : v) {
                out.writeInt(x);
            }
        });
    }

    public NbtWriter putLongArray(String name, long[] v) {
        tag(NbtTag.LONG_ARRAY, name);
        return io(() -> {
            out.writeInt(v.length);
            for (long x : v) {
                out.writeLong(x);
            }
        });
    }

    /** Starts a list; follow with {@code count} unnamed payloads of {@code elementType}. */
    public NbtWriter beginList(String name, int elementType, int count) {
        tag(NbtTag.LIST, name);
        u8(elementType);
        return io(() -> out.writeInt(count));
    }

    /** Unnamed string payload (list element). */
    public NbtWriter string(String v) {
        return io(() -> out.writeUTF(v));
    }

    /** Unnamed int payload (list element). */
    public NbtWriter intValue(int v) {
        return io(() -> out.writeInt(v));
    }

    public NbtWriter raw(byte[] data) {
        return io(() -> out.write(data));
    }

    public byte[] toByteArray() {
        return bytes.toByteArray();
    }

    private NbtWriter tag(int type, String name) {
        u8(type);
        return string(name);
    }

    private NbtWriter u8(int v) {
        return io(() -> out.writeByte(v));
    }

    private NbtWriter io(IoAction action) {
        try {
            action.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    private interface IoAction {
        void run() throws IOException;
    }
}
