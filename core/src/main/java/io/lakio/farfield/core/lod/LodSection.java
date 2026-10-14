package io.lakio.farfield.core.lod;

import io.lakio.farfield.core.anvil.SectionData;
import io.lakio.farfield.core.util.IntIntMap;

import java.util.Arrays;

/**
 * A 32x32x32 cube of voxels (one LOD node), stored as 4x4x4 paletted bricks of
 * 8x8x8 (ADR-008).
 *
 * <ul>
 *   <li>One palette per section maps palette indices to material ids (for
 *       now the block-state ids of a {@code KeyRegistry}).</li>
 *   <li>A brick is either <b>uniform</b> (one palette index, no data: air,
 *       stone, water bodies cost nothing) or holds 512 indices bit-packed with
 *       1, 2, 4, 8 or 16 bits each, entries never spanning two longs.</li>
 *   <li>Sky and block light per voxel in {@link NibbleBricks} (same bricks;
 *       defaults 15 and 0).</li>
 *   <li>Biomes per 4x4x4-voxel cell, 8x8x8 cells, index
 *       {@code (y * 8 + z) * 8 + x}: one value for the whole section until
 *       cells differ. Biome ids come from a registry; -1 means unknown.</li>
 * </ul>
 *
 * <p>Voxel order inside a brick: {@code (y * 8 + z) * 8 + x}; brick order in
 * the section: {@code (by * 4 + bz) * 4 + bx}. A 16x16x16 chunk section maps
 * onto exactly 2x2x2 bricks.
 *
 * <p>Not thread-safe.
 */
public final class LodSection {

    public static final int SIZE = 32;
    public static final int BRICK_SIZE = 8;
    public static final int BRICKS_PER_AXIS = SIZE / BRICK_SIZE;
    public static final int BRICK_COUNT = BRICKS_PER_AXIS * BRICKS_PER_AXIS * BRICKS_PER_AXIS;
    public static final int BRICK_VOLUME = BRICK_SIZE * BRICK_SIZE * BRICK_SIZE;
    public static final int MAX_PALETTE = 1 << 16;

    private int[] palette = new int[8];
    private int paletteSize;
    private final IntIntMap paletteLookup = new IntIntMap(8);
    private int bits = 1;

    private final long[][] bricks = new long[BRICK_COUNT][];
    private final int[] uniform = new int[BRICK_COUNT];

    public static final int BIOME_CELLS_PER_AXIS = 8;
    public static final int BIOME_CELLS = BIOME_CELLS_PER_AXIS * BIOME_CELLS_PER_AXIS * BIOME_CELLS_PER_AXIS;

    private final NibbleBricks skyLight = new NibbleBricks(15);
    private final NibbleBricks blockLight = new NibbleBricks(0);
    private int[] biomes;
    private int uniformBiome = -1;

    private final int[] scratch = new int[BRICK_VOLUME];
    private final byte[] nibbleScratch = new byte[NibbleBricks.BRICK_BYTES];
    private int[] remap = new int[16];

    /** A section filled with one material (typically air), full sky light, unknown biome. */
    public LodSection(int fillMaterial) {
        paletteIndexOf(fillMaterial);
    }

    private LodSection() {
    }

    /**
     * A section whose palette is exactly {@code materials[0 .. count)} (no
     * duplicates), every brick uniform at index 0. For decoders, which then
     * restore bricks with {@link #setBrickData} and {@link #setUniform}.
     */
    public static LodSection withPalette(int[] materials, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("empty palette");
        }
        LodSection section = new LodSection();
        for (int i = 0; i < count; i++) {
            if (section.paletteIndexOf(materials[i]) != i) {
                throw new IllegalArgumentException("duplicate palette entry " + materials[i]);
            }
        }
        return section;
    }

    /** Sets a brick to one palette index. */
    public void setUniform(int brick, int paletteIndex) {
        if (paletteIndex < 0 || paletteIndex >= paletteSize) {
            throw new IllegalArgumentException("palette index out of range: " + paletteIndex);
        }
        bricks[brick] = null;
        uniform[brick] = paletteIndex;
    }

    /**
     * Sets a brick's packed indices (taken as is, not copied), in the layout
     * of {@link #brickData} for the current {@link #bits()}.
     */
    public void setBrickData(int brick, long[] data) {
        if (data.length != BRICK_VOLUME * bits / 64) {
            throw new IllegalArgumentException("brick data of " + data.length + " longs for " + bits + " bits");
        }
        bricks[brick] = data;
    }

    public NibbleBricks skyLight() {
        return skyLight;
    }

    public NibbleBricks blockLight() {
        return blockLight;
    }

    /** Biome of a 4x4x4-voxel cell (cell coordinates 0..7). */
    public int biome(int cellX, int cellY, int cellZ) {
        return biomes == null ? uniformBiome : biomes[(cellY * 8 + cellZ) * 8 + cellX];
    }

    /** Per-cell biomes, or null when the whole section has {@link #uniformBiome()}. Do not modify. */
    public int[] biomeCells() {
        return biomes;
    }

    public int uniformBiome() {
        return uniformBiome;
    }

    public void fillBiome(int biome) {
        biomes = null;
        uniformBiome = biome;
    }

    public void setBiome(int cellX, int cellY, int cellZ, int biome) {
        if (biomes == null) {
            if (biome == uniformBiome) {
                return;
            }
            biomes = new int[BIOME_CELLS];
            Arrays.fill(biomes, uniformBiome);
        }
        biomes[(cellY * 8 + cellZ) * 8 + cellX] = biome;
    }

    /** Collapses per-cell biomes back to one value when they are all equal. */
    public void compactBiomes() {
        if (biomes == null) {
            return;
        }
        int first = biomes[0];
        for (int b : biomes) {
            if (b != first) {
                return;
            }
        }
        fillBiome(first);
    }

    public static int brickIndex(int bx, int by, int bz) {
        return (by * BRICKS_PER_AXIS + bz) * BRICKS_PER_AXIS + bx;
    }

    static int voxelInBrick(int x, int y, int z) {
        return ((y & 7) << 6) | ((z & 7) << 3) | (x & 7);
    }

    public int paletteSize() {
        return paletteSize;
    }

    public int paletteEntry(int index) {
        return palette[index];
    }

    /** Bits per packed index in non-uniform bricks: 1, 2, 4, 8 or 16. */
    public int bits() {
        return bits;
    }

    public boolean isUniform(int brick) {
        return bricks[brick] == null;
    }

    /** Palette index of a uniform brick. */
    public int uniformIndex(int brick) {
        return uniform[brick];
    }

    /** Packed indices of a non-uniform brick (null if uniform). Do not modify. */
    public long[] brickData(int brick) {
        return bricks[brick];
    }

    /** Palette index at a voxel inside a non-uniform brick's packed data. */
    public static int unpack(long[] data, int bits, int voxel) {
        int shift = 6 - Integer.numberOfTrailingZeros(bits);
        int word = voxel >>> shift;
        int offset = (voxel & ((1 << shift) - 1)) * bits;
        return (int) (data[word] >>> offset) & ((1 << bits) - 1);
    }

    public int paletteIndex(int x, int y, int z) {
        int b = brickIndex(x >> 3, y >> 3, z >> 3);
        long[] data = bricks[b];
        return data == null ? uniform[b] : unpack(data, bits, voxelInBrick(x, y, z));
    }

    public int material(int x, int y, int z) {
        return palette[paletteIndex(x, y, z)];
    }

    /** Index of a material in the palette, added if absent. */
    public int paletteIndexOf(int material) {
        int index = paletteLookup.get(material);
        if (index >= 0) {
            return index;
        }
        if (paletteSize == MAX_PALETTE) {
            throw new IllegalStateException("section palette full");
        }
        index = paletteSize;
        if (index == palette.length) {
            palette = Arrays.copyOf(palette, index * 2);
        }
        palette[index] = material;
        paletteLookup.put(material, index);
        paletteSize++;
        int needed = bitsFor(paletteSize);
        if (needed > bits) {
            repack(needed);
        }
        return index;
    }

    static int bitsFor(int paletteSize) {
        int log = paletteSize <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        return log <= 1 ? 1 : Integer.highestOneBit(log - 1) << 1;
    }

    private void repack(int newBits) {
        int oldBits = bits;
        bits = newBits;
        for (int b = 0; b < BRICK_COUNT; b++) {
            long[] data = bricks[b];
            if (data != null) {
                for (int v = 0; v < BRICK_VOLUME; v++) {
                    scratch[v] = unpack(data, oldBits, v);
                }
                bricks[b] = pack(scratch, newBits);
            }
        }
    }

    private static long[] pack(int[] entries, int bits) {
        int perLong = 64 / bits;
        long[] data = new long[BRICK_VOLUME / perLong];
        for (int v = 0; v < BRICK_VOLUME; v++) {
            data[v / perLong] |= (long) entries[v] << ((v % perLong) * bits);
        }
        return data;
    }

    public void set(int x, int y, int z, int material) {
        int index = paletteIndexOf(material);
        int b = brickIndex(x >> 3, y >> 3, z >> 3);
        long[] data = bricks[b];
        if (data == null) {
            if (uniform[b] == index) {
                return;
            }
            Arrays.fill(scratch, uniform[b]);
            data = pack(scratch, bits);
            bricks[b] = data;
        }
        int v = voxelInBrick(x, y, z);
        int shift = 6 - Integer.numberOfTrailingZeros(bits);
        int word = v >>> shift;
        int offset = (v & ((1 << shift) - 1)) * bits;
        long mask = ((1L << bits) - 1) << offset;
        data[word] = (data[word] & ~mask) | ((long) index << offset);
    }

    /** Sets a whole brick to one material. */
    public void fillBrick(int brick, int material) {
        int index = paletteIndexOf(material);
        bricks[brick] = null;
        uniform[brick] = index;
    }

    /**
     * Writes one decoded 16x16x16 chunk section into the octant
     * {@code (dx, dy, dz)} (each 0 or 1) of this section: blocks, light and
     * biomes. Parts the chunk section does not carry (no block data, no light
     * array, no biomes) leave the octant's current values.
     */
    public void writeChunkSection(int dx, int dy, int dz, SectionData section) {
        if (section.hasSkyLight()) {
            writeLight(dx, dy, dz, section.skyLight(), skyLight);
        }
        if (section.hasBlockLight()) {
            writeLight(dx, dy, dz, section.blockLight(), blockLight);
        }
        if (section.hasBiomes()) {
            for (int cy = 0; cy < 4; cy++) {
                for (int cz = 0; cz < 4; cz++) {
                    for (int cx = 0; cx < 4; cx++) {
                        setBiome(dx * 4 + cx, dy * 4 + cy, dz * 4 + cz, section.biomeAt(cx, cy, cz));
                    }
                }
            }
        }
        if (!section.hasBlocks()) {
            return;
        }
        int n = section.blockPaletteSize();
        if (remap.length < n) {
            remap = new int[Math.max(n, remap.length * 2)];
        }
        for (int i = 0; i < n; i++) {
            remap[i] = paletteIndexOf(section.blockPaletteEntry(i));
        }
        short[] indices = section.blockIndices();
        for (int by = 0; by < 2; by++) {
            for (int bz = 0; bz < 2; bz++) {
                for (int bx = 0; bx < 2; bx++) {
                    int b = brickIndex(dx * 2 + bx, dy * 2 + by, dz * 2 + bz);
                    if (section.isUniform()) {
                        bricks[b] = null;
                        uniform[b] = remap[0];
                        continue;
                    }
                    int first = -1;
                    boolean same = true;
                    int v = 0;
                    for (int ly = 0; ly < 8; ly++) {
                        int sy = (by * 8 + ly) << 8;
                        for (int lz = 0; lz < 8; lz++) {
                            int row = sy | ((bz * 8 + lz) << 4) | (bx * 8);
                            for (int lx = 0; lx < 8; lx++) {
                                int index = remap[indices[row + lx]];
                                scratch[v++] = index;
                                if (index != first) {
                                    if (first < 0) {
                                        first = index;
                                    } else {
                                        same = false;
                                    }
                                }
                            }
                        }
                    }
                    if (same) {
                        bricks[b] = null;
                        uniform[b] = first;
                    } else {
                        bricks[b] = pack(scratch, bits);
                    }
                }
            }
        }
    }

    private void writeLight(int dx, int dy, int dz, byte[] chunkNibbles, NibbleBricks target) {
        for (int by = 0; by < 2; by++) {
            for (int bz = 0; bz < 2; bz++) {
                for (int bx = 0; bx < 2; bx++) {
                    int b = brickIndex(dx * 2 + bx, dy * 2 + by, dz * 2 + bz);
                    byte[] out = nibbleScratch;
                    int v = 0;
                    for (int ly = 0; ly < 8; ly++) {
                        for (int lz = 0; lz < 8; lz++) {
                            int row = ((by * 8 + ly) << 8) | ((bz * 8 + lz) << 4) | (bx * 8);
                            // 8 voxels starting at an even index: 4 whole bytes of the chunk array.
                            System.arraycopy(chunkNibbles, row >> 1, out, v >> 1, 4);
                            v += 8;
                        }
                    }
                    target.setBrick(b, out);
                }
            }
        }
    }

    /**
     * Turns bricks whose voxels all share one value back into uniform bricks
     * (block indices and both light arrays), so equal sections always encode
     * to the same bytes.
     */
    public void compact() {
        skyLight.compact();
        blockLight.compact();
        for (int b = 0; b < BRICK_COUNT; b++) {
            long[] data = bricks[b];
            if (data == null) {
                continue;
            }
            int first = unpack(data, bits, 0);
            boolean same = true;
            for (int v = 1; v < BRICK_VOLUME && same; v++) {
                same = unpack(data, bits, v) == first;
            }
            if (same) {
                bricks[b] = null;
                uniform[b] = first;
            }
        }
    }

    /**
     * Drops palette entries that no voxel uses any more (left behind when a
     * stored section is partly overwritten), keeping the order of the others,
     * and shrinks the index width if it can.
     */
    public void prunePalette() {
        boolean[] used = new boolean[paletteSize];
        int count = 0;
        for (int b = 0; b < BRICK_COUNT; b++) {
            long[] data = bricks[b];
            if (data == null) {
                if (!used[uniform[b]]) {
                    used[uniform[b]] = true;
                    count++;
                }
                continue;
            }
            for (int v = 0; v < BRICK_VOLUME; v++) {
                int index = unpack(data, bits, v);
                if (!used[index]) {
                    used[index] = true;
                    count++;
                }
            }
        }
        if (count == paletteSize) {
            return;
        }
        int[] newIndex = new int[paletteSize];
        int[] newPalette = new int[Math.max(8, Integer.highestOneBit(Math.max(1, count - 1)) << 1)];
        paletteLookup.clear();
        int n = 0;
        for (int i = 0; i < paletteSize; i++) {
            if (used[i]) {
                newIndex[i] = n;
                newPalette[n] = palette[i];
                paletteLookup.put(palette[i], n);
                n++;
            }
        }
        int oldBits = bits;
        int newBits = bitsFor(n);
        for (int b = 0; b < BRICK_COUNT; b++) {
            long[] data = bricks[b];
            if (data == null) {
                uniform[b] = newIndex[uniform[b]];
                continue;
            }
            for (int v = 0; v < BRICK_VOLUME; v++) {
                scratch[v] = newIndex[unpack(data, oldBits, v)];
            }
            bricks[b] = pack(scratch, newBits);
        }
        palette = newPalette;
        paletteSize = n;
        bits = newBits;
    }

    public int uniformBrickCount() {
        int n = 0;
        for (long[] data : bricks) {
            if (data == null) {
                n++;
            }
        }
        return n;
    }

    /** Approximate payload size in bytes (palette and packed bricks), for statistics. */
    public int payloadBytes() {
        int bytes = paletteSize * 4 + BRICK_COUNT * 2;
        for (long[] data : bricks) {
            if (data != null) {
                bytes += data.length * 8;
            }
        }
        return bytes;
    }
}
