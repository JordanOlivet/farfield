package io.lakio.farfield.core.util;

/**
 * Bit-matrix helpers.
 */
public final class Bits {

    private Bits() {
    }

    /**
     * Transposes in place the 32x32 bit matrix stored in
     * {@code a[off .. off + 32)}: bit {@code j} of {@code a[off + i]} moves to
     * bit {@code i} of {@code a[off + j]} (bit 0 is the least significant).
     * Recursive block swap: swap the off-diagonal 16x16 blocks, then the 8x8
     * blocks inside each, and so on down to single bits.
     */
    public static void transpose32(int[] a, int off) {
        int m = 0x0000FFFF;
        for (int j = 16; j != 0; j >>>= 1, m ^= m << j) {
            for (int k = 0; k < 32; k = (k + j + 1) & ~j) {
                int t = ((a[off + k] >>> j) ^ a[off + k + j]) & m;
                a[off + k + j] ^= t;
                a[off + k] ^= t << j;
            }
        }
    }
}
