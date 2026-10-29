package io.lakio.farfield.core.debug;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlashScanTest {

    @Test
    void framesAndGlitches() {
        assertEquals(List.of(1L, 5L, 6L, 7L), FlashScan.frames("1, 5-7"));
        TreeSet<Long> expected = new TreeSet<>(FlashScan.frames("87,112,113,126,127,138"));
        assertEquals(List.of(List.of(87L), List.of(112L, 113L), List.of(126L, 127L), List.of(138L)),
                FlashScan.glitches(expected));
    }
}
