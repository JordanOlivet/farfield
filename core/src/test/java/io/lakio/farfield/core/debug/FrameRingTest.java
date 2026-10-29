package io.lakio.farfield.core.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FrameRingTest {

    @Test
    void keepsTheLastFramesByNumber() {
        FrameRing ring = new FrameRing(3, 2);
        for (int f = 1; f <= 5; f++) {
            ring.put(f, new int[]{f}, f % 2 == 0 ? new int[]{-f} : null);
        }
        assertNull(ring.get(2, 0), "overwritten by frame 5");
        assertArrayEquals(new int[]{3}, ring.get(3, 0));
        assertNull(ring.get(3, 1), "layer not captured");
        assertArrayEquals(new int[]{-4}, ring.get(4, 1));
        assertNull(ring.get(6, 0));
    }
}
