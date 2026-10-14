package io.lakio.farfield.core.anvil;

import java.util.Arrays;

/**
 * One decoded chunk: header fields and its sections. A reusable holder owned
 * by one decoder thread; its content is only valid until the next decode.
 */
public final class ChunkData {

    /** A section's block index data had an unexpected length; its blocks were dropped. */
    public static final int WARN_BAD_DATA_LENGTH = 1;
    /** A palette index pointed past the palette; such cells read as palette entry 0. */
    public static final int WARN_BAD_INDEX = 1 << 1;
    /** A light array had an unexpected length and was ignored. */
    public static final int WARN_BAD_LIGHT = 1 << 2;
    /** A section had block or biome data without a usable palette; that data was dropped. */
    public static final int WARN_MISSING_PALETTE = 1 << 3;
    /** The chunk's stored position differs from its slot in the region file; the slot wins. */
    public static final int WARN_MISPLACED = 1 << 4;

    int chunkX;
    int chunkZ;
    int dataVersion;
    String status = "";
    boolean lightOn;
    long lastUpdate;
    int warnings;

    int sectionCount;
    SectionData[] sections = new SectionData[0];

    void reset() {
        chunkX = 0;
        chunkZ = 0;
        dataVersion = 0;
        status = "";
        lightOn = false;
        lastUpdate = 0;
        warnings = 0;
        sectionCount = 0;
    }

    SectionData addSection() {
        if (sectionCount == sections.length) {
            sections = Arrays.copyOf(sections, Math.max(32, sections.length * 2));
        }
        SectionData s = sections[sectionCount];
        if (s == null) {
            s = new SectionData();
            sections[sectionCount] = s;
        }
        s.reset();
        sectionCount++;
        return s;
    }

    void dropLastSection() {
        sectionCount--;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public int dataVersion() {
        return dataVersion;
    }

    /** Generation status as stored, e.g. {@code minecraft:full}. */
    public String status() {
        return status;
    }

    public boolean isFull() {
        return "minecraft:full".equals(status) || "full".equals(status);
    }

    /** The game's {@code isLightOn} flag: true when the stored light is complete. */
    public boolean isLightOn() {
        return lightOn;
    }

    /** Game tick of the last save. */
    public long lastUpdate() {
        return lastUpdate;
    }

    /** Bit set of {@code WARN_*} flags raised while decoding. */
    public int warnings() {
        return warnings;
    }

    public int sectionCount() {
        return sectionCount;
    }

    public SectionData section(int i) {
        if (i < 0 || i >= sectionCount) {
            throw new IndexOutOfBoundsException(i);
        }
        return sections[i];
    }

    @Override
    public String toString() {
        return "Chunk[" + chunkX + ", " + chunkZ + ", v" + dataVersion + ", " + status
                + ", " + sectionCount + " sections, warnings=0x" + Integer.toHexString(warnings) + "]";
    }
}
