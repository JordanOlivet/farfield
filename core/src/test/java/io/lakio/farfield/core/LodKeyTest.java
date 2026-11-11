package io.lakio.farfield.core;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LodKeyTest {

    @Test
    void roundTripsRandomPositions() {
        SplittableRandom random = new SplittableRandom(42);
        for (int i = 0; i < 100_000; i++) {
            int level = random.nextInt(LodKey.MAX_LEVEL + 1);
            int x = random.nextInt(LodKey.XZ_MIN, LodKey.XZ_MAX + 1);
            int y = random.nextInt(LodKey.Y_MIN, LodKey.Y_MAX + 1);
            int z = random.nextInt(LodKey.XZ_MIN, LodKey.XZ_MAX + 1);
            long key = LodKey.pack(level, x, y, z);
            assertEquals(level, LodKey.level(key));
            assertEquals(x, LodKey.x(key));
            assertEquals(y, LodKey.y(key));
            assertEquals(z, LodKey.z(key));
        }
    }

    @Test
    void convertsKeysOfTheFourBitLayout() {
        // The layout before ADR-138: level 4 bits, x 24, z 24, y 12.
        int[][] cases = {{0, 3, -2, -1}, {4, -8_000_000, 100, 8_000_000}, {7, 12, -1023, -5}, {15, -1, 1023, 0}};
        for (int[] c : cases) {
            long old = (long) c[0] << 60 | ((long) c[1] & 0xFFFFFFL) << 36 | ((long) c[3] & 0xFFFFFFL) << 12
                    | (c[2] & 0xFFFL);
            long key = LodKey.fromLayout1(old);
            assertEquals(c[0], LodKey.level(key));
            assertEquals(c[1], LodKey.x(key));
            assertEquals(c[2], LodKey.y(key));
            assertEquals(c[3], LodKey.z(key));
        }
    }

    @Test
    void roundTripsExtremes() {
        int[][] cases = {
                {0, LodKey.XZ_MIN, LodKey.Y_MIN, LodKey.XZ_MIN},
                {LodKey.MAX_LEVEL, LodKey.XZ_MAX, LodKey.Y_MAX, LodKey.XZ_MAX},
                {7, -1, -1, -1},
                {3, 0, 0, 0},
        };
        for (int[] c : cases) {
            long key = LodKey.pack(c[0], c[1], c[2], c[3]);
            assertEquals(c[0], LodKey.level(key));
            assertEquals(c[1], LodKey.x(key));
            assertEquals(c[2], LodKey.y(key));
            assertEquals(c[3], LodKey.z(key));
        }
    }

    @Test
    void parentHalvesCoordinatesTowardsNegativeInfinity() {
        long parent = LodKey.parent(LodKey.pack(2, -3, 5, 4));
        assertEquals(3, LodKey.level(parent));
        assertEquals(-2, LodKey.x(parent));
        assertEquals(2, LodKey.y(parent));
        assertEquals(2, LodKey.z(parent));
    }

    @Test
    void rejectsOutOfRangeInput() {
        assertThrows(IllegalArgumentException.class, () -> LodKey.pack(LodKey.MAX_LEVEL + 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LodKey.pack(0, LodKey.XZ_MAX + 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LodKey.pack(0, 0, LodKey.Y_MIN - 1, 0));
        assertThrows(IllegalArgumentException.class, () -> LodKey.parent(LodKey.pack(LodKey.MAX_LEVEL, 0, 0, 0)));
    }
}
