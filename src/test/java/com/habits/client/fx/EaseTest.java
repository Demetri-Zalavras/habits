package com.habits.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EaseTest {
    @Test
    void bezierEndpointsAndLinear() {
        Ease.Bezier lin = new Ease.Bezier(1 / 3.0, 1 / 3.0, 2 / 3.0, 2 / 3.0);
        for (double t = 0; t <= 1; t += 0.1) assertEquals(t, lin.at(t), 1e-4);
        assertEquals(0, Ease.EASE_OUT.at(0), 1e-9);
        assertEquals(1, Ease.EASE_OUT.at(1), 1e-9);
        assertTrue(Ease.EASE_OUT.at(0.5) > 0.5);
    }

    @Test
    void overshootGoesPastOne() {
        // the mockup's rise curve
        Ease.Bezier rise = new Ease.Bezier(.2, .9, .3, 1.2);
        double max = 0;
        for (double t = 0; t <= 1; t += 0.01) max = Math.max(max, rise.at(t));
        assertTrue(max > 1.02, "max " + max);
    }

    @Test
    void keyframesInterpolateAndExtrapolate() {
        double[] at = {0, .75, 1}, v = {0, 720, 900};
        assertEquals(360, Ease.keys(.375, at, v), 1e-9);
        assertEquals(810, Ease.keys(.875, at, v), 1e-9);
        assertEquals(936, Ease.keys(1.05, at, v), 1e-9);
    }
}
