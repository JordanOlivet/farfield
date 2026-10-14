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

    private final int[] scratch = new int[BRICK_VOLUME];
    private int[] remap = new int[16];

    /** A section filled with one material (typically air). */
    public LodSection(int fillMaterial) {
        paletteIndexOf(fillMaterial);
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
     * {@code (dx, dy, dz)} (each 0 or 1) of this section. A section without
     * block data leaves the octant unchanged.
     */
    public void writeChunkSection(int dx, int dy, int dz, SectionData section) {
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

    /** Turns bricks whose voxels all share one index back into uniform bricks. */
    public void compact() {
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
