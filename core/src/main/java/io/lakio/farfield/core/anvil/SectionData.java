package io.lakio.farfield.core.anvil;

import java.util.Arrays;

/**
 * One decoded 16x16x16 chunk section: block states and biomes as a palette
 * of registry ids plus per-cell palette indices, and the sky/block light
 * nibble arrays. A reusable holder: the arrays are allocated once and
 * overwritten by each decode.
 *
 * <p>Cell order (as in the game): block index {@code (y * 16 + z) * 16 + x},
 * biome index {@code (y * 4 + z) * 4 + x} over 4x4x4-block cells, light
 * nibble {@code index / 2}, low nibble first.
 *
 * <p>Sections just outside the build height carry light only
 * ({@link #hasBlocks()} is false).
 */
public final class SectionData {

    public static final int BLOCK_COUNT = 4096;
    public static final int BIOME_COUNT = 64;
    public static final int LIGHT_BYTES = 2048;

    int y;

    boolean hasBlocks;
    int blockPaletteSize;
    int[] blockPalette = new int[16];
    final short[] blockIndices = new short[BLOCK_COUNT];

    boolean hasBiomes;
    int biomePaletteSize;
    int[] biomePalette = new int[4];
    final byte[] biomeIndices = new byte[BIOME_COUNT];

    boolean hasSkyLight;
    final byte[] skyLight = new byte[LIGHT_BYTES];
    boolean hasBlockLight;
    final byte[] blockLight = new byte[LIGHT_BYTES];

    void reset() {
        y = 0;
        hasBlocks = false;
        blockPaletteSize = 0;
        hasBiomes = false;
        biomePaletteSize = 0;
        hasSkyLight = false;
        hasBlockLight = false;
    }

    int[] ensureBlockPalette(int n) {
        if (blockPalette.length < n) {
            blockPalette = new int[Math.max(n, blockPalette.length * 2)];
        }
        return blockPalette;
    }

    int[] ensureBiomePalette(int n) {
        if (biomePalette.length < n) {
            biomePalette = new int[Math.max(n, biomePalette.length * 2)];
        }
        return biomePalette;
    }

    /** Section Y index (block Y / 16). */
    public int y() {
        return y;
    }

    public boolean hasBlocks() {
        return hasBlocks;
    }

    public int blockPaletteSize() {
        return blockPaletteSize;
    }

    /** Block-state registry id of a palette entry. */
    public int blockPaletteEntry(int i) {
        return blockPalette[i];
    }

    /** True when every block of the section is the same state (palette of one, no index data). */
    public boolean isUniform() {
        return blockPaletteSize == 1;
    }

    /**
     * Palette indices of the 4096 blocks. Only valid when
     * {@link #hasBlocks()} and not {@link #isUniform()}. Do not modify.
     */
    public short[] blockIndices() {
        return blockIndices;
    }

    /** Block-state registry id at a block index. */
    public int blockAt(int index) {
        return blockPaletteSize == 1 ? blockPalette[0] : blockPalette[blockIndices[index]];
    }

    public int blockAt(int x, int y, int z) {
        return blockAt((y << 8) | (z << 4) | x);
    }

    public boolean hasBiomes() {
        return hasBiomes;
    }

    public int biomePaletteSize() {
        return biomePaletteSize;
    }

    public int biomePaletteEntry(int i) {
        return biomePalette[i];
    }

    /** Biome registry id of a 4x4x4 cell (cell coordinates 0..3). */
    public int biomeAt(int cellX, int cellY, int cellZ) {
        return biomePaletteSize == 1 ? biomePalette[0]
                : biomePalette[biomeIndices[(cellY << 4) | (cellZ << 2) | cellX]];
    }

    public boolean hasSkyLight() {
        return hasSkyLight;
    }

    public boolean hasBlockLight() {
        return hasBlockLight;
    }

    /** Sky light (0..15) at a block index, or 0 when absent. */
    public int skyLightAt(int index) {
        return hasSkyLight ? nibble(skyLight, index) : 0;
    }

    /** Block light (0..15) at a block index, or 0 when absent. */
    public int blockLightAt(int index) {
        return hasBlockLight ? nibble(blockLight, index) : 0;
    }

    /** Raw sky light nibbles (2048 bytes); valid when {@link #hasSkyLight()}. Do not modify. */
    public byte[] skyLight() {
        return skyLight;
    }

    /** Raw block light nibbles (2048 bytes); valid when {@link #hasBlockLight()}. Do not modify. */
    public byte[] blockLight() {
        return blockLight;
    }

    private static int nibble(byte[] data, int index) {
        return (data[index >> 1] >> ((index & 1) << 2)) & 0xF;
    }

    @Override
    public String toString() {
        return "Section[y=" + y + ", blocks=" + (hasBlocks ? blockPaletteSize + " states" : "none")
                + ", biomes=" + (hasBiomes ? biomePaletteSize : 0)
                + ", sky=" + hasSkyLight + ", block=" + hasBlockLight + "]";
    }

    /** Copy of the block palette, for tests and tools. */
    public int[] blockPaletteCopy() {
        return Arrays.copyOf(blockPalette, blockPaletteSize);
    }
}
