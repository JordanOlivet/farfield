package io.lakio.farfield.core.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongSetTest {

    @Test
    void behavesAsAHashSetThroughGrowthAndClear() {
        LongSet set = new LongSet();
        Set<Long> reference = new HashSet<>();
        Random random = new Random(7);
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 20_000; i++) {
                long key = random.nextInt(4) == 0 ? random.nextInt(50) - 25 : random.nextLong();
                assertEquals(reference.add(key), set.add(key));
            }
            for (long key : reference) {
                assertTrue(set.contains(key));
            }
            assertFalse(set.contains(Long.MIN_VALUE + 12345) && !reference.contains(Long.MIN_VALUE + 12345));
            assertEquals(reference.size(), set.size());
            set.clear();
            reference.clear();
            assertEquals(0, set.size());
            assertFalse(set.contains(0));
        }
    }

    @Test
    void zeroIsAKeyLikeAnyOther() {
        LongSet set = new LongSet(4);
        assertFalse(set.contains(0));
        assertTrue(set.add(0));
        assertFalse(set.add(0));
        assertTrue(set.contains(0));
        assertEquals(1, set.size());
    }
}
