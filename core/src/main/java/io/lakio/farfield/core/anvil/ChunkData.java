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

    /**
     * Starts filling this holder from a live chunk (the game side) instead
     * of {@link ChunkDecoder}: a full, lit chunk at the given position with
     * no section yet.
     */
    public void beginLive(int chunkX, int chunkZ) {
        reset();
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.status = "minecraft:full";
        this.lightOn = true;
    }

    /** Adds a section (cleared, no blocks, biomes or light) at section Y index {@code y}. */
    public SectionData addLiveSection(int y) {
        SectionData s = addSection();
        s.y = y;
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

    /**
     * Gives sky light to the sections stored without it, as the game reads
     * them: the bottom layer of the nearest section above that has sky
     * light, in every layer, column by column. The game keeps no sky light
     * for sections it lights that way (a water pocket closed in stone is
     * one, dark); left without, they read full light. Sections with no lit
     * section above (the sky, or a dimension without sky light) stay
     * without.
     */
    public void inheritSkyLight() {
        for (int i = 0; i < sectionCount; i++) {
            SectionData s = sections[i];
            if (s.hasSkyLight()) {
                continue;
            }
            SectionData above = null;
            for (int j = 0; j < sectionCount; j++) {
                SectionData a = sections[j];
                if (a.hasSkyLight() && a.y() > s.y() && (above == null || a.y() < above.y())) {
                    above = a;
                }
            }
            if (above == null) {
                continue;
            }
            // Bottom layer: block indices 0..255, nibbles in bytes 0..127.
            byte[] light = s.skyLight;
            for (int layer = 0; layer < 16; layer++) {
                System.arraycopy(above.skyLight, 0, light, layer * 128, 128);
            }
            s.hasSkyLight = true;
        }
    }

    @Override
    public String toString() {
        return "Chunk[" + chunkX + ", " + chunkZ + ", v" + dataVersion + ", " + status
                + ", " + sectionCount + " sections, warnings=0x" + Integer.toHexString(warnings) + "]";
    }
}
