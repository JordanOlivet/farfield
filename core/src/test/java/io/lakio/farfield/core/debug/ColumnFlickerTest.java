package io.lakio.farfield.core.debug;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnFlickerTest {

    /** Feeds one column's states, one per frame from frame 1; returns every flicker found. */
    private static List<ColumnFlicker.Flicker> run(int... states) {
        ColumnFlicker c = new ColumnFlicker();
        List<ColumnFlicker.Flicker> all = new java.util.ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            c.begin(i + 1);
            c.set(3, -7, states[i]);
            all.addAll(c.end());
        }
        return all;
    }

    @Test
    void previewToRealIsNoGlitch() {
        int p = ColumnFlicker.preview(2);
        int r = ColumnFlicker.real(0);
        assertTrue(run(p, p, r, r, r).isEmpty());
    }

    @Test
    void aHoleBetweenPreviewAndRealIsOne() {
        int p = ColumnFlicker.preview(2);
        int r = ColumnFlicker.real(0);
        assertEquals(List.of(new ColumnFlicker.Flicker(3, 1, 3, -7, p, ColumnFlicker.NONE, r)),
                run(p, p, ColumnFlicker.NONE, r, r));
    }

    @Test
    void aCoarseLevelForTwoFramesThatComesBackIsOne() {
        int fine = ColumnFlicker.real(0);
        int coarse = ColumnFlicker.real(2);
        assertEquals(List.of(new ColumnFlicker.Flicker(2, 2, 3, -7, fine, coarse, fine)),
                run(fine, coarse, coarse, fine));
    }

    @Test
    void aStepThroughAMiddleLevelIsNoGlitch() {
        // Coarse to fine through a middle level that lasts one frame: each step is better.
        assertTrue(run(ColumnFlicker.real(3), ColumnFlicker.real(1), ColumnFlicker.real(0)).isEmpty());
    }

    @Test
    void aStateThatLastsIsNoGlitch() {
        int a = ColumnFlicker.real(0);
        int b = ColumnFlicker.NONE;
        assertTrue(run(a, b, b, b, a).isEmpty(), "a hole of three frames is the hole detector's");
    }

    @Test
    void aColumnNotWatchedForAFrameStartsAgain() {
        ColumnFlicker c = new ColumnFlicker();
        int a = ColumnFlicker.real(0);
        c.begin(1);
        c.set(0, 0, a);
        c.end();
        c.begin(2);
        c.set(0, 0, ColumnFlicker.NONE);
        c.end();
        c.begin(4); // frame 3 did not watch it
        c.set(0, 0, a);
        assertTrue(c.end().isEmpty());
    }

    @Test
    void describesRanks() {
        assertEquals("level 2", ColumnFlicker.describe(ColumnFlicker.real(2)));
        assertEquals("preview 3", ColumnFlicker.describe(ColumnFlicker.preview(3)));
        assertEquals("nothing", ColumnFlicker.describe(ColumnFlicker.NONE));
    }
}
