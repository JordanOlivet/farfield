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
    void firstFitAndCoalescing() {
        RangeAllocator a = new RangeAllocator(100);
        assertEquals(0, a.allocate(30));
        assertEquals(30, a.allocate(30));
        assertEquals(60, a.allocate(30));
        assertEquals(-1, a.allocate(20));
        a.release(30, 30);
        assertEquals(30, a.allocate(10));
        a.release(0, 30);
        a.release(30, 10);
        assertEquals(0, a.allocate(60), "the two freed ranges and the gap merged");
        assertEquals(90, a.used());
        assertThrows(IllegalStateException.class, () -> a.release(95, 5), "already free");
        a.grow(200);
        assertEquals(90, a.allocate(100));
        assertEquals(190, a.used());
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
