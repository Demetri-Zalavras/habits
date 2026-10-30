package com.habits.perk.movement;

/** Pure vanilla-physics math for the dash, so a burst covers the same ground in the air as on the ground. */
final class DashMath {
    private DashMath() {}

    static final double AIR_DRAG = 0.91F, GROUND_DRAG = 0.6F * 0.91F;
    /** Vanilla's motion packet clamps each axis to 3.9; capping the total keeps every direction under it, so a diagonal
     * dash is never longer or shorter than one along an axis. */
    static final double CAP = 3.9;

    /** Ticks of falling before a player at {@code drop} blocks above the floor with vertical speed {@code vy} lands. */
    static int airTicks(double drop, double vy) {
        int n = 0;
        // ponytail: 200 ticks is past where the result stops changing (0.91^200 is 0); falls longer than that are treated as endless
        for (double y = 0; n < 200 && y + vy > -drop; n++) {
            y += vy;
            vy = (vy - 0.08) * 0.98F;
        }
        return n + 1;
    }

    /** Blocks travelled per unit of starting horizontal speed: {@code n} ticks of air drag, then ground friction to a stop. */
    static double reach(int n) {
        double a = Math.pow(AIR_DRAG, n);
        return (1 - a) / (1 - AIR_DRAG) + a / (1 - GROUND_DRAG);
    }
}
