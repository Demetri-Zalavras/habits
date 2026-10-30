package com.habits.client.book;

import com.mojang.logging.LogUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/** Dev timing for card face builds (sections in CPU nanoseconds). Only active with {@code HABITS_PROF} set. */
final class CardProf {
    private CardProf() {}

    static final boolean ON = System.getenv("HABITS_PROF") != null;
    private static final Map<String, long[]> TOTALS = new LinkedHashMap<>();
    private static long last;

    static void start() {
        if (ON) last = System.nanoTime();
    }

    static void mark(String section) {
        if (!ON) return;
        long now = System.nanoTime();
        long[] t = TOTALS.computeIfAbsent(section, k -> new long[3]);
        long d = now - last;
        t[0] += d;
        t[1]++;
        t[2] = Math.max(t[2], d);
        last = now;
    }

    static long START_DRAW;
    private static long drawNs, drawN, drawMax;

    /** A cached-face draw (blit + holo), excluding any face build. */
    static void draw(long ns) {
        drawNs += ns;
        drawN++;
        drawMax = Math.max(drawMax, ns);
    }

    /** Per binder frame: CPU ns in the holo layer, in face builds, and in the whole screen render. */
    static long holoNs, buildFrameNs, faceNs;
    private static final java.util.List<long[]> FRAMES = new java.util.ArrayList<>();

    /** Ends a binder frame (call after a flush so the draws are submitted). */
    static void frame(long totalNs) {
        FRAMES.add(new long[]{totalNs, holoNs, buildFrameNs, faceNs});
        holoNs = buildFrameNs = faceNs = 0;
        if (FRAMES.size() >= 2000) dumpFrames();
    }

    /** Mean split of the binder frame, and the same split for the slowest 1% of frames. */
    static void dumpFrames() {
        if (FRAMES.isEmpty()) return;
        FRAMES.sort((a, b) -> Long.compare(b[0], a[0]));
        int n = FRAMES.size(), top = Math.max(1, n / 100);
        LogUtils.getLogger().info("[cardprof] binder frames {}: all {} | worst 1% {} | worst {}", n, split(0, n), split(0, top), split(0, 1));
        FRAMES.clear();
        if (!TOTALS.isEmpty()) dump("face build sections");
    }

    private static String split(int from, int to) {
        double t = 0, h = 0, b = 0, fc = 0;
        for (int i = from; i < to; i++) { long[] f = FRAMES.get(i); t += f[0]; h += f[1]; b += f[2]; fc += f[3]; }
        int n = to - from;
        return String.format("total %.3f holo %.3f build %.3f face+shadow %.3f rest %.3f ms", t / n / 1e6, h / n / 1e6, b / n / 1e6,
                fc / n / 1e6, (t - h - b - fc) / n / 1e6);
    }

    static void dump(String label) {
        if (!ON) return;
        StringBuilder sb = new StringBuilder("[cardprof] ").append(label);
        TOTALS.forEach((k, t) -> sb.append(String.format(" | %s avg %.2f max %.2f ms (n=%d)", k, t[0] / 1e6 / t[1], t[2] / 1e6, t[1])));
        if (drawN > 0) sb.append(String.format(" | DRAW avg %.3f max %.3f ms (n=%d)", drawNs / 1e6 / drawN, drawMax / 1e6, drawN));
        drawNs = drawN = drawMax = 0;
        LogUtils.getLogger().info(sb.toString());
        TOTALS.clear();
    }
}
