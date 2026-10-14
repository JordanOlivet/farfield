package io.lakio.farfield.core.anvil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * One-shot XXH32 hash, implemented from the public xxHash specification.
 * Used to verify LZ4 block checksums.
 */
final class XxHash32 {

    private static final int PRIME1 = 0x9E3779B1;
    private static final int PRIME2 = 0x85EBCA77;
    private static final int PRIME3 = 0xC2B2AE3D;
    private static final int PRIME4 = 0x27D4EB2F;
    private static final int PRIME5 = 0x165667B1;

    private static final VarHandle INT_LE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private XxHash32() {
    }

    static int hash(byte[] data, int offset, int length, int seed) {
        int p = offset;
        int end = offset + length;
        int h;
        if (length >= 16) {
            int v1 = seed + PRIME1 + PRIME2;
            int v2 = seed + PRIME2;
            int v3 = seed;
            int v4 = seed - PRIME1;
            int limit = end - 16;
            while (p <= limit) {
                v1 = round(v1, (int) INT_LE.get(data, p));
                v2 = round(v2, (int) INT_LE.get(data, p + 4));
                v3 = round(v3, (int) INT_LE.get(data, p + 8));
                v4 = round(v4, (int) INT_LE.get(data, p + 12));
                p += 16;
            }
            h = Integer.rotateLeft(v1, 1) + Integer.rotateLeft(v2, 7)
                    + Integer.rotateLeft(v3, 12) + Integer.rotateLeft(v4, 18);
        } else {
            h = seed + PRIME5;
        }
        h += length;
        while (p + 4 <= end) {
            h += (int) INT_LE.get(data, p) * PRIME3;
            h = Integer.rotateLeft(h, 17) * PRIME4;
            p += 4;
        }
        while (p < end) {
            h += (data[p] & 0xFF) * PRIME5;
            h = Integer.rotateLeft(h, 11) * PRIME1;
            p++;
        }
        h ^= h >>> 15;
        h *= PRIME2;
        h ^= h >>> 13;
        h *= PRIME3;
        h ^= h >>> 16;
        return h;
    }

    private static int round(int acc, int input) {
        acc += input * PRIME2;
        acc = Integer.rotateLeft(acc, 13);
        return acc * PRIME1;
    }
}
