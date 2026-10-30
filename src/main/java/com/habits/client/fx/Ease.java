package com.habits.client.fx;

/** Animation math, same semantics as the Web Animations API the approved mockup used. Pure, unit tested. */
public final class Ease {
    private Ease() {}

    /** CSS cubic-bezier(x1, y1, x2, y2). Overshoot (y outside 0..1) passes through. */
    public record Bezier(double x1, double y1, double x2, double y2) {
        public double at(double t) {
            if (t <= 0) return 0;
            if (t >= 1) return 1;
            double s = t;
            for (int i = 0; i < 8; i++) { // Newton on x(s) = t
                double x = curve(s, x1, x2) - t, d = slope(s, x1, x2);
                if (Math.abs(x) < 1e-6) break;
                if (Math.abs(d) < 1e-6) break;
                s -= x / d;
            }
            if (s < 0 || s > 1 || Math.abs(curve(s, x1, x2) - t) > 1e-4) { // bisection fallback
                double lo = 0, hi = 1;
                s = t;
                for (int i = 0; i < 40; i++) {
                    if (curve(s, x1, x2) < t) lo = s; else hi = s;
                    s = (lo + hi) / 2;
                }
            }
            return curve(s, y1, y2);
        }

        private static double curve(double s, double a, double b) {
            double u = 1 - s;
            return 3 * u * u * s * a + 3 * u * s * s * b + s * s * s;
        }

        private static double slope(double s, double a, double b) {
            double u = 1 - s;
            return 3 * u * u * a + 6 * u * s * (b - a) + 3 * s * s * (1 - b);
        }
    }

    public static final Bezier EASE_OUT = new Bezier(0, 0, .58, 1);
    public static final Bezier EASE_IN_OUT = new Bezier(.42, 0, .58, 1);

    /** 0..1 progress of a {@code dur} ms segment starting at {@code start}, clamped. */
    public static double progress(double now, double start, double dur) {
        return Math.max(0, Math.min(1, (now - start) / dur));
    }

    /**
     * Piecewise-linear keyframes at offsets {@code at} (ascending, 0..1) with values {@code v}. Progress outside 0..1
     * (bezier overshoot) extrapolates the first / last segment, like WAAPI.
     */
    public static double keys(double p, double[] at, double[] v) {
        int i = 0;
        while (i < at.length - 2 && p > at[i + 1]) i++;
        double span = at[i + 1] - at[i];
        return v[i] + (v[i + 1] - v[i]) * (p - at[i]) / span;
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
