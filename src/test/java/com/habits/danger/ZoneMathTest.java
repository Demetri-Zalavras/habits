package com.habits.danger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZoneMathTest {
    @Test
    void rampAndMultipliers() {
        assertEquals(0, ZoneMath.ramp(0, 500, 5000));
        assertEquals(0, ZoneMath.ramp(500, 500, 5000));
        assertEquals(0.5, ZoneMath.ramp(2750, 500, 5000), 1e-9);
        assertEquals(1, ZoneMath.ramp(5000, 500, 5000));
        assertEquals(1, ZoneMath.ramp(1e6, 500, 5000));
        assertEquals(1, ZoneMath.ramp(600, 500, 500));
        assertEquals(1, ZoneMath.mult(3, 0));
        assertEquals(2, ZoneMath.mult(3, 0.5), 1e-9);
        assertEquals(3, ZoneMath.mult(3, 1));
    }

    @Test
    void tiers() {
        List<Integer> starts = List.of(500, 2000, 3500);
        assertEquals(0, ZoneMath.tier(499, starts));
        assertEquals(1, ZoneMath.tier(500, starts));
        assertEquals(2, ZoneMath.tier(2750, starts));
        assertEquals(3, ZoneMath.tier(99999, starts));
    }

    @Test
    void perTierLists() {
        List<Integer> l = List.of(1, 2, 3);
        assertEquals(1, ZoneMath.at(l, 0, 9));
        assertEquals(3, ZoneMath.at(l, 7, 9));
        assertEquals(9, ZoneMath.at(List.of(), 0, 9));
        assertEquals(List.of(40.0, 0.0, 3.0), ZoneMath.weights("40, x ,3"));
    }
}
