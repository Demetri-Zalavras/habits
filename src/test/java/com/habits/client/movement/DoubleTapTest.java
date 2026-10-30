package com.habits.client.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoubleTapTest {
    @Test
    void twoTapsInsideTheWindowFire() {
        DoubleTap t = new DoubleTap(7);
        assertFalse(t.press(100), "first tap");
        assertTrue(t.press(107), "second tap 7 ticks later");
    }

    @Test
    void slowTapsDoNot() {
        DoubleTap t = new DoubleTap(7);
        assertFalse(t.press(100));
        assertFalse(t.press(108), "8 ticks is too slow");
        assertTrue(t.press(110), "but the slow tap starts a new pair");
    }

    @Test
    void tripleTapFiresOnce() {
        DoubleTap t = new DoubleTap(7);
        t.press(0);
        assertTrue(t.press(3));
        assertFalse(t.press(6), "third tap starts a new pair instead of firing again");
        assertTrue(t.press(9));
    }

    @Test
    void firstTapAtTickZeroDoesNotFire() {
        assertFalse(new DoubleTap(7).press(0));
    }
}
