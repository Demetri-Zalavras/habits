package com.habits.progression;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PMathTest {
    @Test
    void paramKeys() {
        for (String k : List.of("cooldown", "cooldown_s", "cooldown_min", "storm_cooldown", "dupe_cd", "interval", "pick_interval", "delay", "fade_delay", "channel")) assertTrue(PMath.isCooldownKey(k), k);
        for (String k : List.of("seconds", "buff_seconds", "fade_s", "radius")) assertFalse(PMath.isCooldownKey(k), k);
        for (String k : List.of("radius", "range", "reach", "slam_radius", "chain_range", "glow_range", "lock", "near", "distance")) assertTrue(PMath.isRangeKey(k), k);
        for (String k : List.of("radius_level", "range_cut", "distance_level")) assertFalse(PMath.isRangeKey(k), k);
    }

    @Test
    void scaleParamForms() {
        assertEquals("1.5000..6.0000", PMath.scaleParam("1..4", 1.5));
        assertEquals("2.0000,4.0000", PMath.scaleParam("1,2", 2));
        assertEquals("5.0000", PMath.scaleParam("10", 0.5));
        assertEquals("#minecraft:logs", PMath.scaleParam("#minecraft:logs", 2));
    }

    @Test
    void socketsAndSlots() {
        List<Integer> t = List.of(1, 4, 7, 10);
        assertEquals(0, PMath.socketCount(0, t));
        assertEquals(1, PMath.socketCount(3, t));
        assertEquals(2, PMath.socketCount(4, t));
        assertEquals(4, PMath.socketCount(10, t));
        assertEquals(1, PMath.charmSlots(24, 1, 25, 5));
        assertEquals(2, PMath.charmSlots(25, 1, 25, 5));
        assertEquals(5, PMath.charmSlots(600, 1, 25, 5));
    }

    @Test
    void swiftCapAndWeights() {
        assertEquals(0.7, PMath.cooldownFactor(30, 75), 1e-9);
        assertEquals(0.25, PMath.cooldownFactor(120, 75), 1e-9);
        List<Integer> w = List.of(60, 25, 10, 4, 1);
        assertEquals(0, PMath.pickWeighted(w, 0.0));
        assertEquals(0, PMath.pickWeighted(w, 0.599));
        assertEquals(1, PMath.pickWeighted(w, 0.6));
        assertEquals(4, PMath.pickWeighted(w, 0.999));
        assertEquals(0, PMath.pickWeighted(List.of(0, 0), 0.5));
    }
}
