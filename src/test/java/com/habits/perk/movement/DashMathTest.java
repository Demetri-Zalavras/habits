package com.habits.perk.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashMathTest {
    @Test
    void groundReachIsTheOldGroundDivisor() {
        assertEquals(2.2, DashMath.reach(0), 0.01);
    }

    @Test
    void reachGrowsWithAirtimeTowardEndlessAirDrag() {
        for (int n = 0; n < 60; n++) assertTrue(DashMath.reach(n + 1) > DashMath.reach(n), "n=" + n);
        assertEquals(1 / (1 - DashMath.AIR_DRAG), DashMath.reach(200), 1e-3);
    }

    @Test
    void capKeepsEveryDirectionUnderThePerAxisClamp() {
        for (int deg = 0; deg < 360; deg += 5) {
            double a = Math.toRadians(deg);
            assertTrue(Math.abs(DashMath.CAP * Math.cos(a)) <= 3.9 && Math.abs(DashMath.CAP * Math.sin(a)) <= 3.9, "deg " + deg);
        }
    }

    @Test
    void airTicksMatchVanillaFalls() {
        // the dash's little 0.05 hop off flat ground: two rising moves, then the landing move
        assertEquals(3, DashMath.airTicks(0, 0.05));
        // a jump (0.42) is 12 ticks of airtime in vanilla
        assertEquals(12, DashMath.airTicks(0, 0.42));
        assertTrue(DashMath.airTicks(12, 0) > DashMath.airTicks(1, 0));
    }
}
