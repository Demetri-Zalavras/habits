package com.habits.client.book;

import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import net.minecraft.client.gui.GuiGraphics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The moving light on a card, drawn every frame over the cached face with additive blending (Minecraft has no
 * color-dodge, so strengths are tuned by eye against the mockup). Everything follows the light point (lx, ly):
 * <ul>
 *   <li>Common: a faint glare.</li>
 *   <li>Uncommon: one soft sheen band across the body.</li>
 *   <li>Rare: spectral holo in the art window, lit around the light.</li>
 *   <li>Epic: holo art + star sparkles over the whole card.</li>
 *   <li>Legendary: gold holo art, an etched diamond pattern that glints, a gold glint running round the border.</li>
 *   <li>Reverse Holo: holo on the frame and body, art clean. Full Art: holo over the whole scene.</li>
 * </ul>
 */
final class CardHolo {
    private CardHolo() {}

    private static final int[] SPEC = {0x5FC8E0, 0x958CF0, 0xE08CC0, 0xECC57C, 0x7FD9B0};
    private static final int[] GOLD_SPEC = {0xFFF1C9, 0xE6B04F, 0xFFF8E6, 0xC98A2A, 0xFFE2A0};
    // etch gradient: read per grid corner per frame, so built once (was two new arrays per corner)
    private static final float[] ETCH_AT = {0, .22f, .5f, .85f};
    private static final int[] ETCH_COLORS = {0xFFE9C27A, 0xFFA4732A, 0xFF4A320C, 0xFF24180A};

    /** Everything the shine's colors depend on (rarity is the only part of the skill it reads). */
    private record Still(Rarity r, CardFace.Layout L, int faceW, float x, float y, float w, float h, float lx, float ly,
                         float alpha) {}

    /** Colors of cards whose light is at rest (strength 0: every binder card but the hovered one), recomputed only on a change. */
    private static final Map<Still, List<int[]>> STILL = new LinkedHashMap<>(64, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Still, List<int[]>> e) {
            return size() > 64;
        }
    };

    static void draw(GuiGraphics g, SkillDef def, CardFace.Layout L, int faceW, float x, float y, float w, float h,
                     float lx, float ly, float o, float alpha) {
        if (o != 0) {
            paint(g, def, L, faceW, x, y, w, h, lx, ly, o, alpha);
            return;
        }
        // the same inputs give the same colors, so a still card replays them instead of re-running every fill
        Still key = new Still(def.rarity(), L, faceW, x, y, w, h, lx, ly, alpha);
        List<int[]> colors = STILL.get(key);
        if (colors != null) CardPaint.replay(colors);
        else CardPaint.record();
        boolean done = false;
        try {
            paint(g, def, L, faceW, x, y, w, h, lx, ly, o, alpha);
            done = true;
        } finally {
            if (colors != null) CardPaint.replay(null);
            else if (done) STILL.put(key, CardPaint.stopRecording());
            else CardPaint.stopRecording();
        }
    }

    private static void paint(GuiGraphics g, SkillDef def, CardFace.Layout L, int faceW, float x, float y, float w, float h,
                              float lx, float ly, float o, float alpha) {
        float f = w / faceW;
        float ax0 = x + L.artX0() * f, ay0 = y + L.artY0() * f, ax1 = x + L.artX1() * f, ay1 = y + L.artY1() * f;
        float bx0 = x + L.bodyX0() * f, by0 = y + L.bodyY0() * f, bx1 = x + L.bodyX1() * f, by1 = y + L.bodyY1() * f;
        Rarity r = def.rarity();
        boolean leg = r == Rarity.LEGENDARY;
        // light point in GUI space and the farthest-corner distance (CSS radial "farthest-corner")
        float px = x + lx * w, py = y + ly * h;
        float far = (float) Math.sqrt(Math.max(lx, 1 - lx) * w * Math.max(lx, 1 - lx) * w + Math.max(ly, 1 - ly) * h * Math.max(ly, 1 - ly) * h);
        float em = w / 26f;

        CardRenderer.additive();
        // glare: a soft white spot under the light, everywhere
        float glare = (r == Rarity.COMMON ? .08f + o * .3f : leg ? .3f + o * .8f : .15f + o * .7f) * .32f * alpha;
        CardPaint.texGridColor(g, bx0, by0, bx1, by1, 14, 18, (gx, gy) -> {
            float d = dist(gx, gy, px, py) / far;
            float a = d < .24f ? lerp(.55f, .12f, d / .24f) : Math.max(0, lerp(.12f, 0, (d - .24f) / .5f));
            return CardPaint.alpha(a * glare, 0xFFFFFF);
        });

        if (r == Rarity.UNCOMMON) {
            float op = (.3f + o * .7f) * .5f * alpha, c = lx * 1.4f - .2f;
            CardPaint.texGridColor(g, bx0, by0, bx1, by1, 24, 4, (gx, gy) -> {
                float s = ((gx - x) / w + (gy - y) / h * .47f) / 1.47f;
                float a = Math.max(0, 1 - Math.abs(s - c) / .13f);
                return CardPaint.alpha(a * a * op, 0xFFFFFF);
            });
        }

        // holo layers brighten what is already bright (a color-dodge stand-in): result = dst * (1 + src)
        dodge();
        if (r.ordinal() >= Rarity.RARE.ordinal()) { // holo art window
            float op = (leg ? (.3f + o * .45f) * .6f : .32f + o * .5f) * .45f * alpha;
            int[] spec = leg ? GOLD_SPEC : SPEC;
            CardPaint.texGridColor(g, ax0, ay0, ax1, ay1, 16, 16, (gx, gy) ->
                    spectral(spec, gx, gy, x, y, em, lx, ly, op * lit(dist(gx, gy, px, py) / far, 1, .7f, .2f, .08f)));
        }

        if (r == Rarity.EPIC) {
            float op = (.45f + o * .55f) * 1.6f * alpha, tile = 6.5f * em;
            CardPaint.texGrid(g, CardRenderer.sparkle(), bx0, by0, bx1, by1, 14, 18, (gx, gy) ->
                    spectral(SPEC, gx, gy, x, y, em, lx, ly, op * Math.max(.3f, lit(dist(gx, gy, px, py) / far, 1, .6f, .3f, .3f))),
                    1 / tile, 1 / tile, -x / tile, -y / tile);
        }

        if (leg) {
            // etched diamonds, gold lit from the light, fading out below the art
            float op = (.25f + o * .55f) * 1.6f * alpha, tile = 2.3f * em;
            CardPaint.texGrid(g, CardRenderer.etch(), bx0, by0, bx1, by1, 14, 18, (gx, gy) -> {
                float d = dist(gx, gy, px, py) / far, fy = (gy - y) / h;
                float mask = fy < .52f ? 1 : fy < .68f ? lerp(1, .3f, (fy - .52f) / .16f) : .3f;
                int c = CardPaint.stops(d, ETCH_AT, ETCH_COLORS);
                return premul(c, op * mask);
            }, 1 / tile, 1 / tile, -x / tile, -y / tile);
        }

        CardRenderer.additive();
        if (leg) {
            // the metal border catches a glint band that runs with the light
            float op = (.35f + o * .65f) * .55f * alpha, c = lx * 1.4f - .2f;
            int col = 0xFFF1C2;
            CardPaint.Fill band = (gx, gy) -> {
                float s = ((gx - x) / w + (gy - y) / h * .47f) / 1.47f;
                float a = Math.max(0, 1 - Math.abs(s - c) / .18f);
                return CardPaint.alpha(a * op, col);
            };
            CardPaint.texGridColor(g, x, y, x + w, by0, 20, 1, band);
            CardPaint.texGridColor(g, x, by1, x + w, y + h, 20, 1, band);
            CardPaint.texGridColor(g, x, by0, bx0, by1, 1, 20, band);
            CardPaint.texGridColor(g, bx1, by0, x + w, by1, 1, 20, band);
        }
    }

    /** CSS mask "lit": opaque at the light, fading to a floor at the far corner. */
    private static float lit(float d, float a0, float a30, float a70, float a100) {
        if (d < .3f) return lerp(a0, a30, d / .3f);
        if (d < .7f) return lerp(a30, a70, (d - .3f) / .4f);
        return lerp(a70, a100, Math.min(1, (d - .7f) / .3f));
    }

    /** A muted rainbow band at 115 degrees that slides with the light. */
    private static int spectral(int[] spec, float gx, float gy, float x, float y, float em, float lx, float ly, float a) {
        float ex = (gx - x) / em, ey = (gy - y) / em;
        float t = (ex * .906f + ey * .423f) / 11f + lx * 1.3f + ly * .7f;
        t = t - (float) Math.floor(t);
        float s = t * spec.length;
        int i = (int) s;
        int c = CardPaint.mix(0xFF000000 | spec[(i + 1) % spec.length], s - i, 0xFF000000 | spec[i % spec.length]);
        return premul(c, a);
    }

    /** Dodge blending takes the strength from the color, not alpha. */
    private static int premul(int c, float a) {
        a = Math.max(0, Math.min(1.5f, a));
        int r = Math.min(255, Math.round(((c >> 16) & 0xFF) * a)), g = Math.min(255, Math.round(((c >> 8) & 0xFF) * a)),
                b = Math.min(255, Math.round((c & 0xFF) * a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void dodge() {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.DST_COLOR,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * Math.max(0, Math.min(1, t));
    }
}
