package io.lakio.farfield.core.anvil;

import io.lakio.farfield.core.nbt.NbtFormatException;
import io.lakio.farfield.core.nbt.NbtReader;
import io.lakio.farfield.core.nbt.NbtTag;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Decodes the NBT of a chunk in the 1.18+ format (DataVersion 2844 and up,
 * which covers 1.18 to 1.21.x) into a {@link ChunkData}, without Minecraft
 * classes. Only what LODs need is read (position, status, block states,
 * biomes, light); everything else (entities, block entities, ticks,
 * structures, heightmaps) is skipped without being parsed into objects.
 *
 * <p>Decoding has two steps so callers can drop a chunk (wrong status,
 * legacy format) before paying for its sections: {@link #decodeHeader} then
 * {@link #decodeSections}.
 *
 * <p>Not thread-safe: one instance per worker thread. The registries may be
 * shared.
 */
public final class ChunkDecoder {

    /** First data version with the 1.18 chunk layout (snapshot 21w43a). */
    public static final int MIN_DATA_VERSION = 2844;

    private static final byte[] DATA_VERSION = ascii("DataVersion");
    private static final byte[] X_POS = ascii("xPos");
    private static final byte[] Z_POS = ascii("zPos");
    private static final byte[] STATUS = ascii("Status");
    private static final byte[] SECTIONS = ascii("sections");
    private static final byte[] LEVEL = ascii("Level");
    private static final byte[] IS_LIGHT_ON = ascii("isLightOn");
    private static final byte[] LAST_UPDATE = ascii("LastUpdate");
    private static final byte[] Y = ascii("Y");
    private static final byte[] BLOCK_STATES = ascii("block_states");
    private static final byte[] BIOMES = ascii("biomes");
    private static final byte[] PALETTE = ascii("palette");
    private static final byte[] DATA = ascii("data");
    private static final byte[] SKY_LIGHT = ascii("SkyLight");
    private static final byte[] BLOCK_LIGHT = ascii("BlockLight");
    private static final byte[] NAME = ascii("Name");
    private static final byte[] PROPERTIES = ascii("Properties");

    private final KeyRegistry blockStates;
    private final KeyRegistry biomes;
    private final NbtReader reader = new NbtReader();
    private final StringBuilder keyBuilder = new StringBuilder(64);
    private String[] propertyNames = new String[8];
    private String[] propertyValues = new String[8];

    private int sectionsPosition;
    private boolean legacy;

    public ChunkDecoder(KeyRegistry blockStates, KeyRegistry biomes) {
        this.blockStates = blockStates;
        this.biomes = biomes;
    }

    public KeyRegistry blockStates() {
        return blockStates;
    }

    public KeyRegistry biomes() {
        return biomes;
    }

    /**
     * Reads the root compound's scalar fields into {@code out} (sections
     * are located but not decoded).
     *
     * @param chunkX expected chunk X (from the region slot); the slot wins
     *               over the stored position, as in the game
     * @return false if the chunk uses a format older than 1.18 (not supported)
     * @throws NbtFormatException on malformed NBT
     */
    public boolean decodeHeader(byte[] nbt, int offset, int length, int chunkX, int chunkZ, ChunkData out) {
        out.reset();
        out.chunkX = chunkX;
        out.chunkZ = chunkZ;
        sectionsPosition = -1;
        legacy = false;

        NbtReader r = reader;
        r.reset(nbt, offset, length);
        if (r.readTagType() != NbtTag.COMPOUND) {
            throw new NbtFormatException("chunk root is not a compound");
        }
        r.skip(r.readUnsignedShort());

        int storedX = chunkX;
        int storedZ = chunkZ;
        int type;
        while ((type = r.readTagType()) != NbtTag.END) {
            int nameLength = r.readStringLength();
            int name = r.position();
            r.skip(nameLength);
            if (type == NbtTag.INT && is(name, nameLength, DATA_VERSION)) {
                out.dataVersion = r.readInt();
            } else if (type == NbtTag.INT && is(name, nameLength, X_POS)) {
                storedX = r.readInt();
            } else if (type == NbtTag.INT && is(name, nameLength, Z_POS)) {
                storedZ = r.readInt();
            } else if (type == NbtTag.STRING && is(name, nameLength, STATUS)) {
                out.status = r.readString();
            } else if (type == NbtTag.LIST && is(name, nameLength, SECTIONS)) {
                sectionsPosition = r.position();
                r.skipPayload(type);
            } else if (type == NbtTag.BYTE && is(name, nameLength, IS_LIGHT_ON)) {
                out.lightOn = r.readByte() != 0;
            } else if (type == NbtTag.LONG && is(name, nameLength, LAST_UPDATE)) {
                out.lastUpdate = r.readLong();
            } else {
                if (type == NbtTag.COMPOUND && is(name, nameLength, LEVEL)) {
                    legacy = true;
                }
                r.skipPayload(type);
            }
        }
        if (storedX != chunkX || storedZ != chunkZ) {
            out.warnings |= ChunkData.WARN_MISPLACED;
        }
        if (legacy || out.dataVersion < MIN_DATA_VERSION) {
            legacy = true;
            return false;
        }
        return true;
    }

    /** Decodes the sections located by the last successful {@link #decodeHeader}. */
    public void decodeSections(ChunkData out) {
        if (legacy) {
            throw new IllegalStateException("legacy chunk format");
        }
        if (sectionsPosition < 0) {
            return;
        }
        NbtReader r = reader;
        r.seek(sectionsPosition);
        int elementType = r.readTagType();
        int count = checkedCount(r.readArrayLength());
        if (count == 0) {
            return;
        }
        if (elementType != NbtTag.COMPOUND) {
            throw new NbtFormatException("sections is not a list of compounds");
        }
        for (int i = 0; i < count; i++) {
            SectionData s = out.addSection();
            if (!decodeSection(s, out)) {
                out.dropLastSection();
            }
        }
    }

    /** Decodes one section compound; returns false if it holds nothing usable. */
    private boolean decodeSection(SectionData s, ChunkData chunk) {
        NbtReader r = reader;
        boolean hasY = false;
        int type;
        while ((type = r.readTagType()) != NbtTag.END) {
            int nameLength = r.readStringLength();
            int name = r.position();
            r.skip(nameLength);
            if (is(name, nameLength, Y) && (type == NbtTag.BYTE || type == NbtTag.INT)) {
                s.y = type == NbtTag.BYTE ? r.readByte() : r.readInt();
                hasY = true;
            } else if (type == NbtTag.COMPOUND && is(name, nameLength, BLOCK_STATES)) {
                decodeBlockStates(s, chunk);
            } else if (type == NbtTag.COMPOUND && is(name, nameLength, BIOMES)) {
                decodeBiomes(s, chunk);
            } else if (type == NbtTag.BYTE_ARRAY && is(name, nameLength, SKY_LIGHT)) {
                s.hasSkyLight = readLight(s.skyLight, chunk);
            } else if (type == NbtTag.BYTE_ARRAY && is(name, nameLength, BLOCK_LIGHT)) {
                s.hasBlockLight = readLight(s.blockLight, chunk);
            } else {
                r.skipPayload(type);
            }
        }
        return hasY && (s.hasBlocks || s.hasBiomes || s.hasSkyLight || s.hasBlockLight);
    }

    private void decodeBlockStates(SectionData s, ChunkData chunk) {
        NbtReader r = reader;
        int paletteSize = -1;
        int dataPosition = -1;
        int dataLongs = 0;
        int type;
        while ((type = r.readTagType()) != NbtTag.END) {
            int nameLength = r.readStringLength();
            int name = r.position();
            r.skip(nameLength);
            if (type == NbtTag.LIST && is(name, nameLength, PALETTE)) {
                int elementType = r.readTagType();
                int count = checkedCount(r.readArrayLength());
                if (count > 0 && elementType != NbtTag.COMPOUND) {
                    throw new NbtFormatException("block palette is not a list of compounds");
                }
                int[] palette = s.ensureBlockPalette(count);
                for (int i = 0; i < count; i++) {
                    palette[i] = blockStates.id(readBlockStateKey());
                }
                paletteSize = count;
            } else if (type == NbtTag.LONG_ARRAY && is(name, nameLength, DATA)) {
                dataLongs = r.readArrayLength();
                dataPosition = r.position();
                r.skip(dataLongs > Integer.MAX_VALUE / 8 ? -1 : dataLongs * 8);
            } else {
                r.skipPayload(type);
            }
        }
        if (paletteSize <= 0) {
            chunk.warnings |= ChunkData.WARN_MISSING_PALETTE;
            return;
        }
        s.blockPaletteSize = paletteSize;
        if (paletteSize > 1) {
            int bits = Math.max(4, ceilLog2(paletteSize));
            if (dataPosition < 0 || !unpackShorts(dataPosition, dataLongs, bits, s.blockIndices, paletteSize, chunk)) {
                chunk.warnings |= ChunkData.WARN_BAD_DATA_LENGTH;
                s.blockPaletteSize = 0;
                return;
            }
        }
        s.hasBlocks = true;
    }

    private void decodeBiomes(SectionData s, ChunkData chunk) {
        NbtReader r = reader;
        int paletteSize = -1;
        int dataPosition = -1;
        int dataLongs = 0;
        int type;
        while ((type = r.readTagType()) != NbtTag.END) {
            int nameLength = r.readStringLength();
            int name = r.position();
            r.skip(nameLength);
            if (type == NbtTag.LIST && is(name, nameLength, PALETTE)) {
                int elementType = r.readTagType();
                int count = checkedCount(r.readArrayLength());
                if (count > 0 && elementType != NbtTag.STRING) {
                    throw new NbtFormatException("biome palette is not a list of strings");
                }
                int[] palette = s.ensureBiomePalette(count);
                for (int i = 0; i < count; i++) {
                    palette[i] = biomes.id(r.readString());
                }
                paletteSize = count;
            } else if (type == NbtTag.LONG_ARRAY && is(name, nameLength, DATA)) {
                dataLongs = r.readArrayLength();
                dataPosition = r.position();
                r.skip(dataLongs > Integer.MAX_VALUE / 8 ? -1 : dataLongs * 8);
            } else {
                r.skipPayload(type);
            }
        }
        if (paletteSize <= 0) {
            chunk.warnings |= ChunkData.WARN_MISSING_PALETTE;
            return;
        }
        s.biomePaletteSize = paletteSize;
        if (paletteSize > 1) {
            int bits = ceilLog2(paletteSize);
            if (dataPosition < 0 || !unpackBytes(dataPosition, dataLongs, bits, s.biomeIndices, paletteSize, chunk)) {
                chunk.warnings |= ChunkData.WARN_BAD_DATA_LENGTH;
                s.biomePaletteSize = 0;
                return;
            }
        }
        s.hasBiomes = true;
    }

    /**
     * Reads a block-state compound ({@code Name}, optional {@code Properties})
     * into its canonical key: the name, then the properties sorted by name in
     * brackets, e.g. {@code minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]}.
     */
    private String readBlockStateKey() {
        NbtReader r = reader;
        String blockName = null;
        int properties = 0;
        int type;
        while ((type = r.readTagType()) != NbtTag.END) {
            int nameLength = r.readStringLength();
            int name = r.position();
            r.skip(nameLength);
            if (type == NbtTag.STRING && is(name, nameLength, NAME)) {
                blockName = r.readString();
            } else if (type == NbtTag.COMPOUND && is(name, nameLength, PROPERTIES)) {
                int propertyType;
                while ((propertyType = r.readTagType()) != NbtTag.END) {
                    String key = r.readString();
                    if (propertyType != NbtTag.STRING) {
                        r.skipPayload(propertyType);
                        continue;
                    }
                    if (properties == propertyNames.length) {
                        propertyNames = Arrays.copyOf(propertyNames, properties * 2);
                        propertyValues = Arrays.copyOf(propertyValues, properties * 2);
                    }
                    propertyNames[properties] = key;
                    propertyValues[properties] = r.readString();
                    properties++;
                }
            } else {
                r.skipPayload(type);
            }
        }
        if (blockName == null) {
            throw new NbtFormatException("block state without a Name");
        }
        if (properties == 0) {
            return blockName;
        }
        // Insertion sort: states have a handful of properties.
        for (int i = 1; i < properties; i++) {
            String n = propertyNames[i];
            String v = propertyValues[i];
            int j = i - 1;
            while (j >= 0 && propertyNames[j].compareTo(n) > 0) {
                propertyNames[j + 1] = propertyNames[j];
                propertyValues[j + 1] = propertyValues[j];
                j--;
            }
            propertyNames[j + 1] = n;
            propertyValues[j + 1] = v;
        }
        StringBuilder sb = keyBuilder;
        sb.setLength(0);
        sb.append(blockName).append('[');
        for (int i = 0; i < properties; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(propertyNames[i]).append('=').append(propertyValues[i]);
        }
        return sb.append(']').toString();
    }

    private boolean readLight(byte[] target, ChunkData chunk) {
        NbtReader r = reader;
        int length = r.readArrayLength();
        if (length != SectionData.LIGHT_BYTES) {
            r.skip(length);
            chunk.warnings |= ChunkData.WARN_BAD_LIGHT;
            return false;
        }
        int at = r.position();
        r.skip(length);
        System.arraycopy(r.buffer(), at, target, 0, length);
        return true;
    }

    /**
     * Unpacks {@code out.length} indices of {@code bits} bits stored in the
     * longs at {@code position}, low bits first, entries never spanning two
     * longs (the 1.16+ layout).
     *
     * @return false if the number of longs does not match
     */
    private boolean unpackShorts(int position, int longs, int bits, short[] out, int paletteSize, ChunkData chunk) {
        int perLong = 64 / bits;
        int count = out.length;
        if (longs != (count + perLong - 1) / perLong) {
            return false;
        }
        NbtReader r = reader;
        long mask = (1L << bits) - 1;
        boolean bad = false;
        int i = 0;
        for (int w = 0; w < longs; w++) {
            long word = r.longAt(position + (w << 3));
            int n = Math.min(perLong, count - i);
            for (int j = 0; j < n; j++) {
                int v = (int) (word & mask);
                word >>>= bits;
                if (v >= paletteSize) {
                    v = 0;
                    bad = true;
                }
                out[i++] = (short) v;
            }
        }
        if (bad) {
            chunk.warnings |= ChunkData.WARN_BAD_INDEX;
        }
        return true;
    }

    private boolean unpackBytes(int position, int longs, int bits, byte[] out, int paletteSize, ChunkData chunk) {
        int perLong = 64 / bits;
        int count = out.length;
        if (longs != (count + perLong - 1) / perLong) {
            return false;
        }
        NbtReader r = reader;
        long mask = (1L << bits) - 1;
        boolean bad = false;
        int i = 0;
        for (int w = 0; w < longs; w++) {
            long word = r.longAt(position + (w << 3));
            int n = Math.min(perLong, count - i);
            for (int j = 0; j < n; j++) {
                int v = (int) (word & mask);
                word >>>= bits;
                if (v >= paletteSize) {
                    v = 0;
                    bad = true;
                }
                out[i++] = (byte) v;
            }
        }
        if (bad) {
            chunk.warnings |= ChunkData.WARN_BAD_INDEX;
        }
        return true;
    }

    /** Rejects list sizes that cannot fit in the remaining data, before allocating for them. */
    private int checkedCount(int count) {
        if (count > reader.remaining()) {
            throw new NbtFormatException("list of " + count + " elements in " + reader.remaining() + " bytes");
        }
        return count;
    }

    static int ceilLog2(int n) {
        return n <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    private boolean is(int position, int length, byte[] expected) {
        return length == expected.length
                && Arrays.equals(reader.buffer(), position, position + length, expected, 0, length);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
