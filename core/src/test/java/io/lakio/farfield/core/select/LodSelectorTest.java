package io.lakio.farfield.core.select;

import io.lakio.farfield.core.LodKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodSelectorTest {

    /** Terrain everywhere in y -64..0 (sections that touch it exist), all ready unless listed. */
    private static LodSelector.Sections terrain(Set<Long> notReady) {
        return new LodSelector.Sections() {
            @Override
            public boolean exists(long key) {
                int size = 32 << LodKey.level(key);
                int minY = LodKey.y(key) * size;
                return minY < 0 && minY + size > -64;
            }

            @Override
            public boolean ready(long key) {
                return !notReady.contains(key);
            }
        };
    }

    @Test
    void finerNearCoarserFarAndNoOverlap() {
        LodSelector selector = new LodSelector(5, 2.0);
        List<Long> draw = new ArrayList<>();
        List<Long> want = new ArrayList<>();
        selector.select(0, 70, 0, -64, 320, 4000, terrain(Set.of()), draw::add, want::add);
        assertTrue(want.isEmpty());
        int[] perLevel = new int[5];
        for (long key : draw) {
            perLevel[LodKey.level(key)]++;
        }
        assertTrue(perLevel[0] > 0 && perLevel[4] > 0, java.util.Arrays.toString(perLevel));
        // The camera's own column is drawn at level 0.
        assertTrue(draw.contains(LodKey.pack(0, 0, -1, 0)));
        // No drawn section contains another one.
        Set<Long> drawn = new HashSet<>(draw);
        for (long key : draw) {
            long k = key;
            while (LodKey.level(k) < 4) {
                k = LodKey.parent(k);
                assertTrue(!drawn.contains(k), LodKey.toString(key) + " inside " + LodKey.toString(k));
            }
        }
    }

    @Test
    void parentCoversChildrenThatAreNotReady() {
        LodSelector selector = new LodSelector(5, 2.0);
        long child = LodKey.pack(0, 0, -1, 0);
        List<Long> draw = new ArrayList<>();
        List<Long> want = new ArrayList<>();
        selector.select(0, 70, 0, -64, 320, 4000, terrain(Set.of(child)), draw::add, want::add);
        assertEquals(List.of(child), want);
        assertTrue(draw.contains(LodKey.parent(child)));
        assertTrue(!draw.contains(child));
    }
}
