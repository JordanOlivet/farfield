package io.lakio.farfield.core.anvil;

import io.lakio.farfield.core.nbt.NbtTag;
import io.lakio.farfield.core.nbt.NbtWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/** Builds synthetic 1.21.1-style chunk NBT and region files for tests. */
public final class TestChunks {

    public static final int DATA_VERSION_1_21_1 = 3955;

    private TestChunks() {
    }

    /** A block state as written: name and properties in write order (key, value, key, value...). */
    public record State(String name, String... properties) {
    }

    /** A section to write; null arrays mean "absent". */
    public record Section(int y, State[] palette, int[] indices, String[] biomes, int[] biomeIndices,
                          byte[] skyLight, byte[] blockLight) {
    }

    /** Packs values of {@code bits} bits into longs, low bits first, never spanning two longs. */
    public static long[] pack(int[] values, int bits) {
        int perLong = 64 / bits;
        long[] out = new long[(values.length + perLong - 1) / perLong];
        for (int i = 0; i < values.length; i++) {
            out[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
        }
        return out;
    }

    public static byte[] chunk(int x, int z, String status, List<Section> sections) {
        NbtWriter w = new NbtWriter().root()
                .putInt("DataVersion", DATA_VERSION_1_21_1)
                .putInt("xPos", x)
                .putInt("yPos", -4)
                .putInt("zPos", z)
                .putLong("LastUpdate", 1234)
                .putString("Status", status)
                // Fields the decoder must skip.
                .beginList("block_entities", NbtTag.COMPOUND, 1)
                .putString("id", "minecraft:chest").putInt("x", 1).beginList("Items", NbtTag.END, 0).end()
                .beginCompound("Heightmaps").putLongArray("WORLD_SURFACE", new long[37]).end()
                .putByte("isLightOn", 1);
        w.beginList("sections", NbtTag.COMPOUND, sections.size());
        for (Section s : sections) {
            writeSection(w, s);
        }
        w.beginCompound("structures").beginCompound("starts").end().end();
        return w.end().toByteArray();
    }

    static void writeSection(NbtWriter w, Section s) {
        // Fields in a different order than the game writes them, on purpose.
        if (s.skyLight() != null) {
            w.putByteArray("SkyLight", s.skyLight());
        }
        if (s.palette() != null) {
            w.beginCompound("block_states");
            if (s.indices() != null) {
                int bits = Math.max(4, ChunkDecoder.ceilLog2(s.palette().length));
                w.putLongArray("data", pack(s.indices(), bits));
            }
            w.beginList("palette", NbtTag.COMPOUND, s.palette().length);
            for (State state : s.palette()) {
                if (state.properties().length > 0) {
                    w.beginCompound("Properties");
                    for (int i = 0; i < state.properties().length; i += 2) {
                        w.putString(state.properties()[i], state.properties()[i + 1]);
                    }
                    w.end();
                }
                w.putString("Name", state.name()).end();
            }
            w.end();
        }
        w.putByte("Y", s.y());
        if (s.biomes() != null) {
            w.beginCompound("biomes");
            w.beginList("palette", NbtTag.STRING, s.biomes().length);
            for (String b : s.biomes()) {
                w.string(b);
            }
            if (s.biomeIndices() != null) {
                w.putLongArray("data", pack(s.biomeIndices(), ChunkDecoder.ceilLog2(s.biomes().length)));
            }
            w.end();
        }
        if (s.blockLight() != null) {
            w.putByteArray("BlockLight", s.blockLight());
        }
        w.end();
    }

    public static byte[] zlib(byte[] data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(bytes)) {
            out.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
