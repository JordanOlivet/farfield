package io.lakio.farfield.core.nbt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtReaderTest {

    @Test
    void readsPrimitivesAndStrings() {
        byte[] data = new NbtWriter().root()
                .putByte("b", -3)
                .putShort("s", -1234)
                .putInt("i", 0x12345678)
                .putLong("l", 0x0123456789ABCDEFL)
                .putString("ascii", "minecraft:stone")
                .putString("unicode", "café ☃ \u0000end")
                .end()
                .toByteArray();
        NbtReader r = new NbtReader();
        r.reset(data, 0, data.length);
        assertEquals(NbtTag.COMPOUND, r.readTagType());
        assertEquals("", r.readString());

        assertEquals(NbtTag.BYTE, r.readTagType());
        assertTrue(r.readStringEquals("b".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(-3, r.readByte());
        assertEquals(NbtTag.SHORT, r.readTagType());
        assertFalse(r.readStringEquals("x".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(-1234, r.readShort());
        assertEquals(NbtTag.INT, r.readTagType());
        assertEquals("i", r.readString());
        assertEquals(0x12345678, r.readInt());
        assertEquals(NbtTag.LONG, r.readTagType());
        assertEquals("l", r.readString());
        assertEquals(0x0123456789ABCDEFL, r.readLong());
        assertEquals(NbtTag.STRING, r.readTagType());
        assertEquals("ascii", r.readString());
        assertEquals("minecraft:stone", r.readString());
        assertEquals(NbtTag.STRING, r.readTagType());
        assertEquals("unicode", r.readString());
        assertEquals("café ☃ \u0000end", r.readString());
        assertEquals(NbtTag.END, r.readTagType());
        assertEquals(0, r.remaining());
    }

    @Test
    void skipsEveryTagType() {
        NbtWriter w = new NbtWriter().root()
                .putByte("a", 1)
                .putShort("b", 2)
                .putInt("c", 3)
                .putLong("d", 4)
                .putFloat("e", 5f)
                .putDouble("f", 6d)
                .putByteArray("g", new byte[100])
                .putString("h", "text")
                .putIntArray("i", new int[]{1, 2, 3})
                .putLongArray("j", new long[]{1, 2})
                .beginList("k", NbtTag.INT, 3).intValue(1).intValue(2).intValue(3)
                .beginList("l", NbtTag.COMPOUND, 2)
                .putString("x", "y").beginList("nested", NbtTag.STRING, 2).string("p").string("q").end()
                .beginCompound("inner").putInt("z", 1).end().end()
                .beginList("empty", NbtTag.END, 0)
                .end();
        byte[] data = w.toByteArray();
        // Trailing marker after the skipped compound.
        byte[] withMarker = new byte[data.length + 1];
        System.arraycopy(data, 0, withMarker, 0, data.length);
        withMarker[data.length] = 42;

        NbtReader r = new NbtReader();
        r.reset(withMarker, 0, withMarker.length);
        assertEquals(NbtTag.COMPOUND, r.readTagType());
        r.skip(r.readUnsignedShort());
        r.skipPayload(NbtTag.COMPOUND);
        assertEquals(42, r.readByte());
    }

    @Test
    void rejectsTruncatedData() {
        byte[] data = new NbtWriter().root().putLongArray("data", new long[16]).end().toByteArray();
        NbtReader r = new NbtReader();
        // The logical end cuts the long array: skipping must fail even though
        // the backing array is longer.
        r.reset(data, 0, data.length - 20);
        r.readTagType();
        r.skip(r.readUnsignedShort());
        assertThrows(NbtFormatException.class, () -> r.skipPayload(NbtTag.COMPOUND));
    }

    @Test
    void rejectsUnknownTagTypes() {
        byte[] data = {NbtTag.COMPOUND, 0, 0, 99};
        NbtReader r = new NbtReader();
        r.reset(data, 0, data.length);
        r.readTagType();
        r.skip(r.readUnsignedShort());
        assertThrows(NbtFormatException.class, r::readTagType);
    }

    @Test
    void rejectsExcessiveNesting() {
        NbtWriter w = new NbtWriter().root();
        for (int i = 0; i < NbtReader.MAX_DEPTH + 5; i++) {
            w.beginCompound("n");
        }
        for (int i = 0; i < NbtReader.MAX_DEPTH + 6; i++) {
            w.end();
        }
        byte[] data = w.toByteArray();
        NbtReader r = new NbtReader();
        r.reset(data, 0, data.length);
        r.readTagType();
        r.skip(r.readUnsignedShort());
        assertThrows(NbtFormatException.class, () -> r.skipPayload(NbtTag.COMPOUND));
    }
}
