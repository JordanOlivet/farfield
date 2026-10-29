package io.lakio.farfield.core.debug;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashDetectorTest {

    private static final int W = 8;
    private static final int H = 4;
    private static final int GROUND = 0x406020;
    private static final int SKY = 0x80B0FF;

    private static int[] ground() {
        int[] f = new int[W * H];
        Arrays.fill(f, GROUND);
        return f;
    }

    /** Ground with a sky-coloured box (x0..x1, y0..y1). */
    private static int[] hole(int x0, int y0, int x1, int y1) {
        int[] f = ground();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                f[y * W + x] = SKY;
            }
        }
        return f;
    }

    private static FlashDetector detector() {
        return new FlashDetector(W, H, 100, 40, 3);
    }

    @Test
    void oneFrameHoleIsReportedWithItsBox() {
        FlashDetector d = detector();
        assertTrue(d.push(1, ground()).isEmpty());
        assertTrue(d.push(2, hole(2, 1, 4, 2)).isEmpty());
        List<FlashDetector.Flash> flashes = d.push(3, ground());
        assertEquals(List.of(new FlashDetector.Flash(2, 1, 6, 6, 2, 1, 4, 2)), flashes);
    }

    @Test
    void twoFrameHoleIsReportedOnceAsTwoFrames() {
        FlashDetector d = detector();
        d.push(1, ground());
        d.push(2, ground());
        assertTrue(d.push(3, hole(0, 0, 1, 1)).isEmpty());
        assertTrue(d.push(4, hole(0, 0, 1, 1)).isEmpty(), "frame 3 is not a one-frame flash: 4 looks like it");
        List<FlashDetector.Flash> flashes = d.push(5, ground());
        assertEquals(1, flashes.size());
        assertEquals(new FlashDetector.Flash(3, 2, 4, 4, 0, 0, 1, 1), flashes.get(0));
    }

    @Test
    void aLastingChangeIsNoFlash() {
        FlashDetector d = detector();
        d.push(1, ground());
        d.push(2, hole(0, 0, 3, 3));
        d.push(3, hole(0, 0, 3, 3));
        assertTrue(d.push(4, hole(0, 0, 3, 3)).isEmpty());
    }

    @Test
    void anEdgeSweepingAcrossIsNoFlash() {
        FlashDetector d = detector();
        d.push(1, hole(0, 0, 1, 3));
        d.push(2, hole(0, 0, 3, 3));
        assertTrue(d.push(3, hole(0, 0, 5, 3)).isEmpty(), "the sky advances: frames 1 and 3 disagree where 2 changed");
    }

    @Test
    void smallFlashesAndGapsAreLeftOut() {
        FlashDetector d = detector();
        d.push(1, ground());
        d.push(2, hole(0, 0, 0, 1)); // 2 pixels, under the minimum
        assertTrue(d.push(3, ground()).isEmpty());
        d.push(10, ground());
        d.push(11, hole(0, 0, 3, 3));
        d.push(13, ground()); // frame 12 was not read back: no verdict on 11
        assertTrue(d.push(14, ground()).isEmpty());
    }

    @Test
    void scatteredPixelsAreJudgedByTheirLargestArea() {
        FlashDetector d = detector();
        int[] speckled = ground();
        for (int i : new int[]{0, 3, 6, 17, 20, 23}) { // six lone pixels, none touching
            speckled[i] = SKY;
        }
        d.push(1, ground());
        d.push(2, speckled);
        assertTrue(d.push(3, ground()).isEmpty(), "6 flagged pixels, but the largest area is 1");
    }

    @Test
    void distanceSumsTheChannels() {
        assertEquals(0, FlashDetector.distance(0x123456, 0x123456));
        assertEquals(765, FlashDetector.distance(0x000000, 0xFFFFFF));
        assertEquals(3, FlashDetector.distance(0x010101, 0x020000));
    }
}
