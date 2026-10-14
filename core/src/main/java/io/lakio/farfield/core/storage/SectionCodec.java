package io.lakio.farfield.core.storage;

import io.lakio.farfield.core.lod.LodSection;
import io.lakio.farfield.core.lod.NibbleBricks;
import io.lakio.farfield.core.util.ByteReader;
import io.lakio.farfield.core.util.ByteWriter;
import io.lakio.farfield.core.util.IntIntMap;

import java.util.Arrays;

/**
 * Serializes a {@link LodSection} with domain transforms only (palette, bit
 * packing, uniform bricks as one value); a byte compressor is applied on top
 * by the storage layer. Small descriptive maps come first and bulk arrays
 * last, so similar bytes sit together for the compressor.
 *
 * <pre>
 *   u8      format version (1)
 *   varint  palette size, then each material id (varint)
 *   u8      bits per index (1, 2, 4, 8, 16)
 *   64 x    brick entry: varint (index &lt;&lt; 1) if uniform, 1 if packed
 *   64 x    sky light entry: u8 value (0..15) if uniform, 0x10 if packed
 *   64 x    block light entry: same
 *   varint  ((biome + 1) &lt;&lt; 1) if one biome, 1 if per cell; per cell:
 *           varint palette size, each (biome + 1) as varint,
 *           512 cell indices (u8, or u16 if more than 256 biomes)
 *   then    packed bricks (longs, little-endian), packed sky light bricks
 *           (256 bytes each), packed block light bricks
 * </pre>
 *
 * Material and biome ids are those of the world's key table (ADR-008: the
 * store keeps string keys alongside, never bare registry ids).
 */
public final class SectionCodec {

    public static final int VERSION = 1;
    private static final int PACKED_LIGHT = 0x10;

    private final IntIntMap biomeLookup = new IntIntMap(16);
    private int[] biomePalette = new int[16];
    private int[] materials = new int[64];
    private final byte[] nibbles = new byte[NibbleBricks.BRICK_BYTES];

    public void encode(LodSection section, ByteWriter out) {
        out.writeByte(VERSION);
        int n = section.paletteSize();
        out.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            out.writeVarInt(section.paletteEntry(i));
        }
        out.writeByte(section.bits());
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            out.writeVarInt(section.isUniform(b) ? section.uniformIndex(b) << 1 : 1);
        }
        writeLightMap(section.skyLight(), out);
        writeLightMap(section.blockLight(), out);
        writeBiomes(section, out);
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            long[] data = section.brickData(b);
            if (data != null) {
                out.writeLongs(data);
            }
        }
        writeLightData(section.skyLight(), out);
        writeLightData(section.blockLight(), out);
    }

    private static void writeLightMap(NibbleBricks light, ByteWriter out) {
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            out.writeByte(light.isUniform(b) ? light.uniformValue(b) : PACKED_LIGHT);
        }
    }

    private static void writeLightData(NibbleBricks light, ByteWriter out) {
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            byte[] data = light.brickData(b);
            if (data != null) {
                out.writeBytes(data, 0, data.length);
            }
        }
    }

    private void writeBiomes(LodSection section, ByteWriter out) {
        int[] cells = section.biomeCells();
        if (cells == null) {
            out.writeVarInt((section.uniformBiome() + 1) << 1);
            return;
        }
        out.writeVarInt(1);
        biomeLookup.clear();
        int n = 0;
        for (int biome : cells) {
            if (biomeLookup.get(biome) < 0) {
                if (n == biomePalette.length) {
                    biomePalette = Arrays.copyOf(biomePalette, n * 2);
                }
                biomeLookup.put(biome, n);
                biomePalette[n++] = biome;
            }
        }
        out.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            out.writeVarInt(biomePalette[i] + 1);
        }
        boolean wide = n > 256;
        for (int biome : cells) {
            int index = biomeLookup.get(biome);
            out.writeByte(index);
            if (wide) {
                out.writeByte(index >>> 8);
            }
        }
    }

    /**
     * Decodes a section written by {@link #encode}.
     *
     * @throws IllegalStateException on malformed data
     */
    public LodSection decode(byte[] data, int offset, int length) {
        ByteReader in = new ByteReader().reset(data, offset, length);
        int version = in.readByte();
        if (version != VERSION) {
            throw new IllegalStateException("unknown section format version " + version);
        }
        int n = in.readVarInt();
        if (n < 1 || n > LodSection.MAX_PALETTE || n > in.remaining()) {
            throw new IllegalStateException("bad palette size " + n);
        }
        if (materials.length < n) {
            materials = new int[n];
        }
        for (int i = 0; i < n; i++) {
            materials[i] = in.readVarInt();
        }
        LodSection section = LodSection.withPalette(materials, n);
        int bits = in.readByte();
        if (bits != section.bits()) {
            throw new IllegalStateException("bits " + bits + " do not match a palette of " + n);
        }
        int[] brickEntries = new int[LodSection.BRICK_COUNT];
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            brickEntries[b] = in.readVarInt();
        }
        byte[] skyMap = new byte[LodSection.BRICK_COUNT];
        byte[] blockMap = new byte[LodSection.BRICK_COUNT];
        in.readBytes(skyMap, 0, skyMap.length);
        in.readBytes(blockMap, 0, blockMap.length);
        readBiomes(section, in);

        int longs = LodSection.BRICK_VOLUME * bits / 64;
        long limit = (1L << bits) - 1;
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            int entry = brickEntries[b];
            if ((entry & 1) == 0) {
                section.setUniform(b, entry >>> 1);
                continue;
            }
            long[] packed = new long[longs];
            in.readLongs(packed);
            if (n <= limit) {
                for (int v = 0; v < LodSection.BRICK_VOLUME; v++) {
                    if (LodSection.unpack(packed, bits, v) >= n) {
                        throw new IllegalStateException("palette index out of range in brick " + b);
                    }
                }
            }
            section.setBrickData(b, packed);
        }
        readLightData(section.skyLight(), skyMap, in);
        readLightData(section.blockLight(), blockMap, in);
        if (in.remaining() != 0) {
            throw new IllegalStateException(in.remaining() + " trailing bytes");
        }
        return section;
    }

    private void readLightData(NibbleBricks light, byte[] map, ByteReader in) {
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            int entry = map[b];
            if (entry == PACKED_LIGHT) {
                in.readBytes(nibbles, 0, nibbles.length);
                light.setBrick(b, nibbles);
            } else if (entry >= 0 && entry < 16) {
                light.setUniform(b, entry);
            } else {
                throw new IllegalStateException("bad light entry " + entry);
            }
        }
    }

    private void readBiomes(LodSection section, ByteReader in) {
        int head = in.readVarInt();
        if ((head & 1) == 0) {
            section.fillBiome((head >>> 1) - 1);
            return;
        }
        int n = in.readVarInt();
        if (n < 1 || n > LodSection.BIOME_CELLS) {
            throw new IllegalStateException("bad biome palette size " + n);
        }
        int[] palette = new int[n];
        for (int i = 0; i < n; i++) {
            palette[i] = in.readVarInt() - 1;
        }
        boolean wide = n > 256;
        for (int cell = 0; cell < LodSection.BIOME_CELLS; cell++) {
            int index = in.readByte();
            if (wide) {
                index |= in.readByte() << 8;
            }
            if (index >= n) {
                throw new IllegalStateException("biome index out of range");
            }
            section.setBiome(cell & 7, cell >> 6, (cell >> 3) & 7, palette[index]);
        }
    }
}
