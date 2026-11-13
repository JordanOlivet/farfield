package io.lakio.farfield.core.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangeAllocatorTest {

    @Test
    void bestFitAndCoalescing() {
        RangeAllocator a = new RangeAllocator(100);
        assertEquals(0, a.allocate(30));
        assertEquals(30, a.allocate(30));
        assertEquals(60, a.allocate(30));
        assertEquals(-1, a.allocate(20));
        a.release(30, 30);
        assertEquals(90, a.allocate(10), "the smallest free range that holds it, not the first");
        assertEquals(30, a.allocate(10));
        a.release(0, 30);
        a.release(30, 10);
        assertEquals(0, a.allocate(60), "the two freed ranges and the gap merged");
        assertEquals(100, a.used());
        a.release(90, 10);
        assertEquals(90, a.used());
        assertThrows(IllegalStateException.class, () -> a.release(95, 5), "already free");
        a.grow(200);
        assertEquals(90, a.allocate(100));
        assertEquals(190, a.used());
    }

    @Test
    void equalLengthsTakeTheLowestStart() {
        RangeAllocator a = new RangeAllocator(100);
        for (int i = 0; i < 10; i++) {
            assertEquals(i * 10, a.allocate(10));
        }
        a.release(60, 10);
        a.release(20, 10);
        assertEquals(20, a.allocate(10));
        assertEquals(60, a.allocate(10));
        assertEquals(-1, a.allocate(1));
    }

    @Test
    void aMostlyEmptySpaceShrinksToTwiceItsUse() {
        long mb64 = 8L << 20; // the quad buffer's first size, in quads
        assertEquals(mb64, RangeAllocator.shrunkCapacity(4 * mb64, mb64 / 2, mb64), "256 MB with 32 MB used: 64 MB");
        assertEquals(2 * mb64, RangeAllocator.shrunkCapacity(8 * mb64, mb64, mb64), "512 MB with 64 MB used: 128 MB");
        assertEquals(-1, RangeAllocator.shrunkCapacity(4 * mb64, mb64 + 1, mb64), "more than a quarter used");
        assertEquals(-1, RangeAllocator.shrunkCapacity(mb64, 0, mb64), "never below the first size");
        assertEquals(mb64, RangeAllocator.shrunkCapacity(2 * mb64, 0, mb64));
        for (long capacity = mb64; capacity <= 16 * mb64; capacity += mb64 / 2) {
            for (long used = 0; used <= capacity; used += mb64 / 8) {
                long target = RangeAllocator.shrunkCapacity(capacity, used, mb64);
                if (target >= 0) {
                    assertTrue(target < capacity && target >= mb64 && used * 2 <= target, capacity + " " + used);
                }
            }
        }
    }

    @Test
    void randomAllocationsNeverOverlap() {
        RangeAllocator a = new RangeAllocator(10_000);
        SplittableRandom random = new SplittableRandom(3);
        List<long[]> live = new ArrayList<>();
        boolean[] taken = new boolean[10_000];
        for (int i = 0; i < 20_000; i++) {
            if (!live.isEmpty() && random.nextInt(3) == 0) {
                long[] r = live.remove(random.nextInt(live.size()));
                a.release(r[0], r[1]);
                for (long u = r[0]; u < r[0] + r[1]; u++) {
                    taken[(int) u] = false;
                }
            } else {
                long length = 1 + random.nextInt(200);
                long start = a.allocate(length);
                if (start >= 0) {
                    for (long u = start; u < start + length; u++) {
                        assertTrue(!taken[(int) u], "overlap at " + u);
                        taken[(int) u] = true;
                    }
                    live.add(new long[]{start, length});
                }
            }
        }
        long sum = 0;
        for (long[] r : live) {
            sum += r[1];
        }
        assertEquals(sum, a.used());
    }
}
