package io.lakio.farfield.core.preview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewPyramidTest {

    /** A quarter of a level at a distance. */
    private record Quarter(int level, double distance) {
    }

    @Test
    void theWantedTileFollowsTheDrawnVoxels() {
        // Detail 7.5: level 0 drawn within 480 blocks (2 * 240), its tiles of level 0.
        assertEquals(0, PreviewPyramid.wantedTile(300, 7.5));
        // 10,000 blocks: level 5 drawn (41 sections of 240), tiles of level 3.
        assertEquals(3, PreviewPyramid.wantedTile(10_000, 7.5));
        assertEquals(PreviewPyramid.LEVELS - 3, PreviewPyramid.wantedTile(Double.MAX_VALUE / 4, 7.5));
    }

    @Test
    void theFourStepsComeInOrderAndSkipTheLevelsNoViewNeeds() {
        double ring = 12 * 16 + PreviewPyramid.NEAR_RING_MARGIN;
        double detail = 7.5;
        // The coarsest level first over the ring, with the acceptable levels elsewhere (ADR-153).
        assertEquals(0, PreviewPyramid.step(15, 0, 8_000_000, ring, detail));
        assertEquals(3, PreviewPyramid.step(15, 30_000_000, 30_000_100, ring, detail));
        // The near ring at 300 blocks: level 2 (two coarser than wanted) then level 0.
        assertEquals(1, PreviewPyramid.step(2, 300, 400, ring, detail));
        assertEquals(2, PreviewPyramid.step(0, 300, 400, ring, detail));
        // At 10,000 blocks: level 5 then level 3; level 4 between and level 9 are not built.
        assertEquals(3, PreviewPyramid.step(5, 10_000, 10_500, ring, detail));
        assertEquals(4, PreviewPyramid.step(3, 10_000, 10_500, ring, detail));
        assertEquals(-1, PreviewPyramid.step(4, 10_000, 10_500, ring, detail));
        assertEquals(-1, PreviewPyramid.step(9, 10_000, 10_500, ring, detail));
        // Any step before the next, whatever the distance.
        assertTrue(PreviewPyramid.stepOrder(1, 2_000_000) < PreviewPyramid.stepOrder(2, 0));
        assertTrue(PreviewPyramid.stepOrder(3, 0) < PreviewPyramid.stepOrder(3, 100));
    }

    @Test
    void theFarFrontTakesTheCoarsestLevelFirstWhateverItsDistance() {
        assertTrue(PreviewPyramid.farOrder(15, 30_000_000) < PreviewPyramid.farOrder(14, 0));
        assertTrue(PreviewPyramid.farOrder(15, 0) < PreviewPyramid.farOrder(15, 1_000));
        assertTrue(PreviewPyramid.farOrder(3, PreviewPyramid.MAX_RANGE) < PreviewPyramid.farOrder(2, 0));
    }

    @Test
    void theTwoFrontsTakeTurnsAndEachQuarterOnce() {
        List<Quarter> all = new ArrayList<>();
        for (int level = PreviewPyramid.LEVELS - 1; level >= 0; level--) {
            for (int i = 0; i < 4; i++) {
                all.add(new Quarter(level, i * (256.0 * (1 << level))));
            }
        }
        List<Quarter> rings = new ArrayList<>(all);
        rings.sort(Comparator.comparingDouble(q -> PreviewPyramid.order(q.level(), q.distance())));
        List<Quarter> far = new ArrayList<>();
        for (Quarter q : all) {
            if (PreviewPyramid.inFarFront(q.level())) {
                far.add(q);
            }
        }
        far.sort(Comparator.comparingDouble(q -> PreviewPyramid.farOrder(q.level(), q.distance())));

        List<Quarter> merged = PreviewPyramid.twoFronts(rings, far);

        assertEquals(all.size(), merged.size());
        assertEquals(all.size(), merged.stream().distinct().count());
        // The rings open with the coarsest level's own quarter; the far front's farthest level-15 quarter
        // comes by the eighth turn, where the rings alone put it after every finer quarter nearer.
        assertEquals(rings.get(0), merged.get(0));
        Quarter farthestTop = new Quarter(15, 3 * 256.0 * (1 << 15));
        assertTrue(merged.indexOf(farthestTop) <= 7, "at " + merged.indexOf(farthestTop));
        assertTrue(rings.indexOf(farthestTop) > 40, "at " + rings.indexOf(farthestTop));
    }

    @Test
    void spreadTileKeysComeBackAndNoLongerShareHashesAlongADiagonal() {
        java.util.Set<Integer> hashes = new java.util.HashSet<>();
        for (int i = -64; i < 64; i++) {
            long key = PreviewStore.key(3, i, i);
            assertEquals(key, PreviewStore.unspread(PreviewStore.spread(key)));
            if (i >= 0) {
                assertEquals(0, Long.hashCode(PreviewStore.key(0, i, i)), "a diagonal's plain keys share one hash");
            }
            hashes.add(Long.hashCode(PreviewStore.spread(PreviewStore.key(0, i, i))));
        }
        assertTrue(hashes.size() > 120, "spread: " + hashes.size() + " hashes of 128");
    }

    @Test
    void eitherFrontRunningOutLeavesTheOtherAlone() {
        List<String> rings = List.of("a", "b", "c", "d");
        List<String> far = List.of("x");
        assertEquals(List.of("a", "x", "b", "c", "d"), PreviewPyramid.twoFronts(rings, far));
        assertEquals(List.of("x"), PreviewPyramid.twoFronts(List.of(), far));
    }
}
