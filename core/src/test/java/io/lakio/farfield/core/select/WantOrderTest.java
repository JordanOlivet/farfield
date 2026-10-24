package io.lakio.farfield.core.select;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WantOrderTest {

    @Test
    void behindCountsFartherAndInFrontNot() {
        assertEquals(100, WantOrder.score(100, 1), 1e-9);
        assertEquals(100 * WantOrder.BEHIND, WantOrder.score(100, -1), 1e-9);
        assertEquals(100 * (1 + WantOrder.BEHIND) / 2, WantOrder.score(100, 0), 1e-9);
        assertEquals(0, WantOrder.score(0, -1), 1e-9);
    }

    @Test
    void nearestFirstThenInsertionOrderOnTies() {
        WantOrder order = new WantOrder();
        order.add(10, WantOrder.score(300, 1));
        order.add(11, WantOrder.score(100, -1));
        order.add(12, WantOrder.score(50, 1));
        order.add(13, WantOrder.score(0, -1));
        order.add(14, WantOrder.score(50, 1));
        order.sort();
        long[] expected = {13, 12, 14, 10, 11};
        assertEquals(expected.length, order.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], order.key(i), "position " + i);
        }
    }

    @Test
    void growsAndClears() {
        WantOrder order = new WantOrder();
        for (int i = 1000; i > 0; i--) {
            order.add(i, i);
        }
        order.sort();
        assertEquals(1000, order.size());
        assertEquals(1, order.key(0));
        assertEquals(1000, order.key(999));
        order.clear();
        assertEquals(0, order.size());
    }
}
