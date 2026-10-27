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
    void sectionsOutsideTheViewKeepTheUsualDetail() {
        // A zoomed view (detail 40) toward +X; behind it, the usual detail 2.
        LodSelector selector = new LodSelector(5, 40.0);
        selector.setOutsideView(key -> LodKey.x(key) >= 0, 2.0);
        List<Long> draw = new ArrayList<>();
        selector.select(0, 70, 0, -64, 320, 4000, terrain(Set.of()), draw::add, k -> { });
        int finestAhead = 0;
        int finestBehind = 0;
        for (long key : draw) {
            if (LodKey.level(key) == 0) {
                if (LodKey.x(key) >= 0) {
                    finestAhead++;
                } else {
                    finestBehind++;
                }
            }
        }
        // Ahead, level 0 reaches 40 sections of 32 blocks; behind, 2 of them.
        assertTrue(finestAhead > 20 * finestBehind, finestAhead + " ahead, " + finestBehind + " behind");
        assertTrue(finestBehind > 0);
    }

    @Test
    void aLevelLockDrawsNothingFinerOverTheWholeDistance() {
        LodSelector selector = new LodSelector(5, 2.0);
        selector.setForcedLevel(2);
        List<Long> draw = new ArrayList<>();
        selector.select(0, 70, 0, -64, 320, 4000, terrain(Set.of()), draw::add, k -> { });
        assertTrue(draw.stream().allMatch(k -> LodKey.level(k) >= 2), "nothing finer than the lock");
        // The locked level up close, coarser levels far away, up to the whole distance.
        assertTrue(draw.contains(LodKey.pack(2, 0, -1, 0)));
        assertTrue(draw.stream().anyMatch(k -> LodKey.level(k) == 4 && Math.abs(LodKey.x(k) * 512) > 3000));

        selector.setForcedLevel(-1);
        draw.clear();
        selector.select(0, 70, 0, -64, 320, 4000, terrain(Set.of()), draw::add, k -> { });
        assertTrue(draw.stream().anyMatch(k -> LodKey.level(k) == 0));
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

    @Test
    void descendingParentKeepsDescendingWhenAChildIsNotReady() {
        LodSelector selector = new LodSelector(5, 2.0);
        long child = LodKey.pack(0, 0, -1, 0);
        List<Long> draw = new ArrayList<>();
        List<Long> want = new ArrayList<>();
        // First selection: everything ready, the parent descends.
        selector.select(0, 70, 0, -64, 320, 4000, terrain(Set.of()), draw::add, want::add);
        assertTrue(draw.contains(child));
        // A child becomes not ready (new or remeshed): the parent stays split,
        // its ready siblings stay drawn, the child is asked for, no coarse flash.
        draw.clear();
        selector.select(0, 70, 0, -64, 320, 4000, terrain(Set.of(child)), draw::add, want::add);
        assertTrue(!draw.contains(LodKey.parent(child)));
        assertTrue(!draw.contains(child));
        assertTrue(want.contains(child));
        assertTrue(draw.contains(LodKey.pack(0, 1, -1, 0)));
        assertEquals(0, selector.fallbacks());
    }
}
