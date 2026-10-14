package io.lakio.farfield.core.lod;

import java.util.Arrays;

/**
 * One 4-bit value per voxel of a {@link LodSection} (sky or block light), in
 * the same 4x4x4 bricks of 8x8x8: a brick is either uniform (one value, no
 * data) or 256 bytes of nibbles, low nibble first, voxel order
 * {@code (y * 8 + z) * 8 + x}.
 */
public final class NibbleBricks {

    public static final int BRICK_BYTES = LodSection.BRICK_VOLUME / 2;

    private final byte[][] data = new byte[LodSection.BRICK_COUNT][];
    private final byte[] uniform = new byte[LodSection.BRICK_COUNT];

    public NibbleBricks(int fill) {
        fill(fill);
    }

    public void fill(int value) {
        Arrays.fill(data, null);
        Arrays.fill(uniform, (byte) (value & 15));
    }

    public boolean isUniform(int brick) {
        return data[brick] == null;
    }

    public int uniformValue(int brick) {
        return uniform[brick];
    }

    /** Nibbles of a non-uniform brick (null if uniform). Do not modify. */
    public byte[] brickData(int brick) {
        return data[brick];
    }

    public int get(int x, int y, int z) {
        int b = LodSection.brickIndex(x >> 3, y >> 3, z >> 3);
        byte[] d = data[b];
        if (d == null) {
            return uniform[b];
        }
        int v = LodSection.voxelInBrick(x, y, z);
        return (d[v >> 1] >> ((v & 1) << 2)) & 15;
    }

    public void set(int x, int y, int z, int value) {
        int b = LodSection.brickIndex(x >> 3, y >> 3, z >> 3);
        byte[] d = data[b];
        if (d == null) {
            if (uniform[b] == value) {
                return;
            }
            d = new byte[BRICK_BYTES];
            Arrays.fill(d, (byte) (uniform[b] * 0x11));
            data[b] = d;
        }
        int v = LodSection.voxelInBrick(x, y, z);
        int shift = (v & 1) << 2;
        d[v >> 1] = (byte) ((d[v >> 1] & ~(15 << shift)) | ((value & 15) << shift));
    }

    /** Sets a brick from 256 bytes of nibbles (copied), collapsing it if uniform. */
    public void setBrick(int brick, byte[] nibbles) {
        byte first = nibbles[0];
        boolean same = (first & 15) == ((first >> 4) & 15);
        for (int i = 1; i < BRICK_BYTES && same; i++) {
            same = nibbles[i] == first;
        }
        if (same) {
            data[brick] = null;
            uniform[brick] = (byte) (first & 15);
        } else {
            data[brick] = nibbles.clone();
        }
    }

    public void setUniform(int brick, int value) {
        data[brick] = null;
        uniform[brick] = (byte) (value & 15);
    }

    /** Turns packed bricks whose voxels all share one value back into uniform bricks. */
    public void compact() {
        for (int b = 0; b < LodSection.BRICK_COUNT; b++) {
            if (data[b] != null) {
                setBrick(b, data[b]);
            }
        }
    }

    public int uniformBrickCount() {
        int n = 0;
        for (byte[] d : data) {
            if (d == null) {
                n++;
            }
        }
        return n;
    }
}
