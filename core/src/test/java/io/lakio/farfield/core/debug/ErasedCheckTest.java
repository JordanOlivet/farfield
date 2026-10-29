package io.lakio.farfield.core.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErasedCheckTest {

    private static final int SKY = 0x80B0FF;
    private static final int LOD = 0x406020;
    private static final int VANILLA = 0x705030;

    @Test
    void lodPixelsBackToTheSkyAreErased() {
        // 4 x 2: the LODs draw columns 1 to 3; vanilla covers column 1, column 3 ends as the sky.
        int[] before = {SKY, SKY, SKY, SKY, SKY, SKY, SKY, SKY};
        int[] lods = {SKY, LOD, LOD, LOD, SKY, LOD, LOD, LOD};
        int[] end = {SKY, VANILLA, LOD, SKY, SKY, VANILLA, LOD, SKY};
        ErasedCheck.Result r = ErasedCheck.check(4, before, lods, end, 60, 30);
        assertEquals(new ErasedCheck.Result(2, 6, 3, 0, 3, 1), r);
    }

    @Test
    void untouchedSkyAndFoggedLodsAreNotErased() {
        int[] before = {SKY, SKY};
        int[] lods = {SKY, 0x80B0EF}; // a LOD fogged into the sky: under the drawn threshold
        int[] end = {SKY, SKY};
        ErasedCheck.Result r = ErasedCheck.check(2, before, lods, end, 60, 30);
        assertEquals(0, r.pixels());
        assertEquals(0, r.drawn());
    }
}
