package com.habits.progression;

import java.util.List;
import java.util.Locale;

/** Pure math for sigils and charms (JUnit: PMathTest). No config or world access. */
public final class PMath {
    private PMath() {}

    /** Param keys Swift shortens: cooldown, cooldown_s, cooldown_min, storm_cooldown, dupe_cd, interval, pick_interval,
     * delay, fade_delay, channel ... (waits before or between good things; durations like buff_seconds are not). */
    public static boolean isCooldownKey(String key) {
        return key.startsWith("cooldown") || key.endsWith("_cooldown") || key.equals("cd") || key.endsWith("_cd")
                || key.equals("interval") || key.endsWith("_interval") || key.equals("delay") || key.endsWith("_delay")
                || key.equals("channel");
    }

    /** Param keys Reach grows: radius, range, reach, slam_radius, chain_range, lock, follow, ping, search, near, distance
     * (not radius_level, range_cut or distance_level). */
    public static boolean isRangeKey(String key) {
        return key.equals("radius") || key.equals("range") || key.equals("reach")
                || key.endsWith("_radius") || key.endsWith("_range")
                || switch (key) { case "lock", "follow", "ping", "search", "near", "distance" -> true; default -> false; };
    }

    /** Scales every number in a param ("a..b", "v1,...,v10" or a plain number) by {@code f}; anything else is kept. */
    public static String scaleParam(String s, double f) {
        try {
            int dots = s.indexOf("..");
            if (dots >= 0) return num(Double.parseDouble(s.substring(0, dots).trim()) * f) + ".."
                    + num(Double.parseDouble(s.substring(dots + 2).trim()) * f);
            if (s.indexOf(',') >= 0) {
                String[] parts = s.split(",");
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) b.append(',');
                    b.append(num(Double.parseDouble(parts[i].trim()) * f));
                }
                return b.toString();
            }
            return num(Double.parseDouble(s.trim()) * f);
        } catch (NumberFormatException e) {
            return s; // ids, tags, conditions
        }
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    /** Sockets open at a level: one per threshold reached (default 1/4/7/10). */
    public static int socketCount(int level, List<? extends Number> thresholds) {
        int n = 0;
        for (Number t : thresholds) if (level >= t.intValue()) n++;
        return n;
    }

    /** Charm slots: {@code base + owned / perSkills}, at most {@code max}. */
    public static int charmSlots(int ownedSkills, int base, int perSkills, int max) {
        return Math.min(max, base + (perSkills <= 0 ? 0 : ownedSkills / perSkills));
    }

    /** Swift total: summed percents, capped. Returns the cooldown factor (0.7 = 30% shorter). */
    public static double cooldownFactor(double swiftPercent, double capPercent) {
        return 1 - Math.min(capPercent, Math.max(0, swiftPercent)) / 100.0;
    }

    /** Index picked by weights (non-positive weights never win); 0 when all are zero. {@code r} in [0,1). */
    public static int pickWeighted(List<? extends Number> weights, double r) {
        double total = 0;
        for (Number w : weights) total += Math.max(0, w.doubleValue());
        if (total <= 0) return 0;
        double x = r * total;
        for (int i = 0; i < weights.size(); i++) {
            x -= Math.max(0, weights.get(i).doubleValue());
            if (x < 0) return i;
        }
        return weights.size() - 1;
    }
}
