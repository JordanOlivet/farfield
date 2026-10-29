package io.lakio.farfield.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettleWatchTest {

    private static final long S = 1_000_000_000L;

    @Test
    void settlesAfterAWholeQuietHold() {
        SettleWatch w = new SettleWatch(0, 2, 60);
        assertEquals(SettleWatch.Outcome.WAITING, w.check(true, 0));
        assertEquals(SettleWatch.Outcome.WAITING, w.check(true, S));
        assertEquals(SettleWatch.Outcome.WAITING, w.check(false, S + 1)); // a mesh came in: the hold starts over
        assertEquals(SettleWatch.Outcome.WAITING, w.check(true, 2 * S));
        assertEquals(SettleWatch.Outcome.WAITING, w.check(true, 3 * S));
        assertEquals(SettleWatch.Outcome.SETTLED, w.check(true, 4 * S));
    }

    @Test
    void timesOutWhenNeverQuietLongEnough() {
        SettleWatch w = new SettleWatch(10 * S, 2, 5);
        assertEquals(SettleWatch.Outcome.WAITING, w.check(false, 11 * S));
        assertEquals(SettleWatch.Outcome.WAITING, w.check(true, 14 * S));
        assertEquals(SettleWatch.Outcome.TIMED_OUT, w.check(true, 15 * S));
        assertEquals(5.0, w.elapsedSeconds(15 * S));
    }
}
