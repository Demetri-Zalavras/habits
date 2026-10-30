package com.habits.danger;

import java.util.List;

/** Pure zone math (JUnit-tested): the overworld ramp, multipliers and tier lookup. */
public final class ZoneMath {
    private ZoneMath() {}

    /** 0 at or inside {@code start}, 1 at or past {@code end}, linear between. */
    public static double ramp(double dist, double start, double end) {
        if (end <= start) return dist >= start ? 1 : 0;
        return Math.max(0, Math.min(1, (dist - start) / (end - start)));
    }

    /** 1 at t = 0, {@code max} at t = 1. */
    public static double mult(double max, double t) {
        return 1 + (max - 1) * t;
    }

    /** How many tier start distances {@code dist} has reached (0 = safe). */
    public static int tier(double dist, List<? extends Number> starts) {
        int n = 0;
        for (Number s : starts) if (dist >= s.doubleValue()) n++;
        return n;
    }

    /** A per-tier list entry; the last entry repeats when the list is short, {@code def} when it is empty. */
    public static double at(List<? extends Number> l, int i, double def) {
        return l.isEmpty() ? def : l.get(Math.min(i, l.size() - 1)).doubleValue();
    }

    /** "40,30,18,9,3" -> weights; bad numbers count as 0. */
    public static List<Double> weights(String s) {
        String[] parts = s.split(",");
        Double[] out = new Double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Math.max(0, Double.parseDouble(parts[i].trim())); } catch (NumberFormatException e) { out[i] = 0.0; }
        }
        return List.of(out);
    }
}
