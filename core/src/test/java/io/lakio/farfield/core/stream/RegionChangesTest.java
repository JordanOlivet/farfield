package io.lakio.farfield.core.stream;

import io.lakio.farfield.core.LodKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionChangesTest {

    private static final long S = 1_000_000_000L;

    @Test
    void aRegionIsAnnouncedOnceQuietOrAfterTheLongestWaitAndItsWaitIsCounted() {
        RegionChanges changes = new RegionChanges(3 * S, 20 * S);
        long quiet = LodKey.pack(0, 0, 0, 0);
        long busy = LodKey.pack(0, 10_000, 0, 0);
        changes.written(quiet, 0);
        List<Long> due = new ArrayList<>();
        changes.drainDue(2 * S, due::add);
        assertEquals(List.of(), due);
        changes.drainDue(3 * S, due::add);
        assertEquals(List.of(RegionChanges.regionOf(quiet)), due);
        // A region written every second waits for the longest wait.
        due.clear();
        for (long t = 0; t < 20; t++) {
            changes.written(busy, t * S);
            changes.drainDue(t * S, due::add);
        }
        assertEquals(List.of(), due);
        changes.written(busy, 20 * S);
        changes.drainDue(20 * S, due::add);
        assertEquals(List.of(RegionChanges.regionOf(busy)), due);
        assertArrayEquals(new long[]{2, 23 * S, 20 * S}, changes.takeWaits());
        assertArrayEquals(new long[]{0, 0, 0}, changes.takeWaits());
    }
}
