package com.habits.client.book;

import com.habits.Habits;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Drawing primitives for the card remodel: vertex-colored gradient shapes (rounded rects filled by a color function),
 * flat quads, and text in the bundled Barlow Condensed (one font per pixel size, see tools/gen_card_fonts.py).
 * Coordinates are whatever the current pose says; the card face draws in its own pixel grid.
 */
final class CardPaint {
    private CardPaint() {}

    /** Color at a point, ARGB. */
    interface Fill {
        int at(float x, float y);
    }

    // ------------------------------------------------------------------ colors

    static int argb(int a, int rgb) {
        return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF);
    }

    static int alpha(float a, int rgb) {
        return argb(Math.round(a * 255), rgb);
    }

    /** CSS color-mix(in srgb, a p, b). */
    static int mix(int a, float p, int b) {
        int r = Math.round(((a >> 16) & 0xFF) * p + ((b >> 16) & 0xFF) * (1 - p));
        int g = Math.round(((a >> 8) & 0xFF) * p + ((b >> 8) & 0xFF) * (1 - p));
        int bl = Math.round((a & 0xFF) * p + (b & 0xFF) * (1 - p));
        int al = Math.round((a >>> 24) * p + (b >>> 24) * (1 - p));
        return (al << 24) | (r << 16) | (g << 8) | bl;
    }

    /** Multi-stop gradient lookup; {@code pos} ascending 0..1, colors ARGB. */
    static int stops(float t, float[] pos, int[] col) {
        if (t <= pos[0]) return col[0];
        for (int i = 1; i < pos.length; i++) {
            if (t <= pos[i]) return mix(col[i], (t - pos[i - 1]) / Math.max(1e-6f, pos[i] - pos[i - 1]), col[i - 1]);
        }
        return col[col.length - 1];
    }

    /** CSS linear-gradient(angle) over a rect. */
    static Fill linear(float x0, float y0, float x1, float y1, float deg, float[] pos, int[] col) {
        double a = Math.toRadians(deg);
        float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
        float w = x1 - x0, h = y1 - y0, cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        float len = Math.abs(w * sx) + Math.abs(h * sy);
        return (x, y) -> stops(((x - cx) * sx + (y - cy) * sy) / len + 0.5f, pos, col);
    }

    /** CSS radial-gradient(ellipse rx ry at cx cy). */
    static Fill radial(float cx, float cy, float rx, float ry, float[] pos, int[] col) {
        return (x, y) -> {
            float dx = (x - cx) / rx, dy = (y - cy) / ry;
            return stops((float) Math.sqrt(dx * dx + dy * dy), pos, col);
        };
    }

    static Fill solid(int argb) {
        return (x, y) -> argb;
    }

    // ------------------------------------------------------------------ shapes

    /** Rounded rect filled by {@code f}, as a grid of vertex-colored quads ({@code cols} x {@code rows} in the middle,
     * one-pixel rows through the corners so the curve stays smooth). */
    static void shape(GuiGraphics g, float x0, float y0, float x1, float y1, float r, int cols, int rows, Fill f) {
        r = Math.max(0, Math.min(r, Math.min(x1 - x0, y1 - y0) / 2));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // row edges: corner rows 1 px apart, the straight middle split into `rows`
        // corners in steps of up to 2 px (a few rows round them off well enough at card sizes)
        int cr = (int) Math.ceil(r), step = cr > 8 ? 2 : 1, cn = (cr + step - 1) / step;
        float[] ys = new float[cn * 2 + rows + 1];
        int n = 0;
        for (int i = 0; i < cn; i++) ys[n++] = y0 + i * step;
        for (int i = 0; i <= rows; i++) ys[n++] = (y0 + cr) + (y1 - y0 - 2 * cr) * i / (float) rows;
        for (int i = cn - 1; i >= 0; i--) ys[n++] = y1 - i * step;
        for (int j = 0; j + 1 < n; j++) {
            float ya = ys[j], yb = ys[j + 1];
            if (yb <= ya) continue;
            float inset = inset((ya + yb) / 2, y0, y1, r);
            float xa0 = x0 + inset, xa1 = x1 - inset;
            // neighbouring quads share an edge: its colors are computed once (same floats as computing them per quad)
            float u0 = xa0 + (xa1 - xa0) * 0 / cols;
            int c0a = f.at(u0, ya), c0b = f.at(u0, yb);
            for (int i = 0; i < cols; i++) {
                float u1 = xa0 + (xa1 - xa0) * (i + 1) / cols;
                int c1a = f.at(u1, ya), c1b = f.at(u1, yb);
                b.addVertex(m, u0, ya, 0).setColor(c0a);
                b.addVertex(m, u0, yb, 0).setColor(c0b);
                b.addVertex(m, u1, yb, 0).setColor(c1b);
                b.addVertex(m, u1, ya, 0).setColor(c1a);
                u0 = u1;
                c0a = c1a;
                c0b = c1b;
            }
        }
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    private static float inset(float y, float y0, float y1, float r) {
        if (r <= 0) return 0;
        float d = Math.min(y - y0, y1 - y);
        if (d >= r) return 0;
        float dy = r - d;
        return r - (float) Math.sqrt(Math.max(0, r * r - dy * dy));
    }

    /** Darkening ramps along the four inner edges of a rect (CSS inset box-shadow), one draw. */
    static void edgeRamps(GuiGraphics g, float x0, float y0, float x1, float y1, float fall, int dark) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f m = g.pose().last().pose();
        int clear = dark & 0x00FFFFFF;
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // top, bottom (dark edge first), left, right
        b.addVertex(m, x0, y0, 0).setColor(dark); b.addVertex(m, x0, y0 + fall, 0).setColor(clear);
        b.addVertex(m, x1, y0 + fall, 0).setColor(clear); b.addVertex(m, x1, y0, 0).setColor(dark);
        b.addVertex(m, x0, y1 - fall, 0).setColor(clear); b.addVertex(m, x0, y1, 0).setColor(dark);
        b.addVertex(m, x1, y1, 0).setColor(dark); b.addVertex(m, x1, y1 - fall, 0).setColor(clear);
        b.addVertex(m, x0, y0, 0).setColor(dark); b.addVertex(m, x0, y1, 0).setColor(dark);
        b.addVertex(m, x0 + fall, y1, 0).setColor(clear); b.addVertex(m, x0 + fall, y0, 0).setColor(clear);
        b.addVertex(m, x1 - fall, y0, 0).setColor(clear); b.addVertex(m, x1 - fall, y1, 0).setColor(clear);
        b.addVertex(m, x1, y1, 0).setColor(dark); b.addVertex(m, x1, y0, 0).setColor(dark);
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    /** A filled convex quad with per-corner colors (clockwise from top left). */
    static void quad(GuiGraphics g, float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy,
                     int ca, int cb, int cc, int cd) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        b.addVertex(m, ax, ay, 0).setColor(ca);
        b.addVertex(m, dx, dy, 0).setColor(cd);
        b.addVertex(m, cx, cy, 0).setColor(cc);
        b.addVertex(m, bx, by, 0).setColor(cb);
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    /** Thin circle outline (Full Art rings). */
    static void ring(GuiGraphics g, float cx, float cy, float r, float w, int argb) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        int seg = Math.max(24, (int) (r * 1.2f));
        for (int i = 0; i <= seg; i++) {
            double a = Math.PI * 2 * i / seg;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            b.addVertex(m, cx + c * (r + w), cy + s * (r + w), 0).setColor(argb);
            b.addVertex(m, cx + c * r, cy + s * r, 0).setColor(argb);
        }
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    /**
     * A textured grid whose vertex colors come from {@code f} (additive overlays: holo, sheen, glare). {@code u}/{@code v}
     * map a point to texture coords (textures repeat).
     */
    static void texGrid(GuiGraphics g, ResourceLocation tex, float x0, float y0, float x1, float y1, int cols, int rows,
                        Fill f, float uScale, float vScale, float uOff, float vOff) {
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Matrix4f m = g.pose().last().pose();
        int[] c = gridColors(x0, y0, x1, y1, cols, rows, f);
        float[] xs = GX, ys = GY;
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int j = 0; j < rows; j++) {
            float ya = ys[j], yb = ys[j + 1];
            for (int i = 0; i < cols; i++) {
                float xa = xs[i], xb = xs[i + 1];
                int k = j * (cols + 1) + i;
                b.addVertex(m, xa, ya, 0).setUv(xa * uScale + uOff, ya * vScale + vOff).setColor(c[k]);
                b.addVertex(m, xa, yb, 0).setUv(xa * uScale + uOff, yb * vScale + vOff).setColor(c[k + cols + 1]);
                b.addVertex(m, xb, yb, 0).setUv(xb * uScale + uOff, yb * vScale + vOff).setColor(c[k + cols + 2]);
                b.addVertex(m, xb, ya, 0).setUv(xb * uScale + uOff, ya * vScale + vOff).setColor(c[k + 1]);
            }
        }
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    /** Untextured vertex-colored grid; keeps the current blend mode (overlays set additive). */
    static void texGridColor(GuiGraphics g, float x0, float y0, float x1, float y1, int cols, int rows, Fill f) {
        if (x1 <= x0 || y1 <= y0) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f m = g.pose().last().pose();
        int[] c = gridColors(x0, y0, x1, y1, cols, rows, f);
        float[] xs = GX, ys = GY;
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int j = 0; j < rows; j++) {
            float ya = ys[j], yb = ys[j + 1];
            for (int i = 0; i < cols; i++) {
                float xa = xs[i], xb = xs[i + 1];
                int k = j * (cols + 1) + i;
                b.addVertex(m, xa, ya, 0).setColor(c[k]);
                b.addVertex(m, xa, yb, 0).setColor(c[k + cols + 1]);
                b.addVertex(m, xb, yb, 0).setColor(c[k + cols + 2]);
                b.addVertex(m, xb, ya, 0).setColor(c[k + 1]);
            }
        }
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    // grid corners and their colors, reused (render thread only): each corner is shared by up to four quads, so its
    // color is computed once instead of once per quad; same expressions as before, so the same floats and colors
    private static float[] GX = new float[64], GY = new float[64];
    private static int[] GC = new int[1024];

    private static int[] gridColors(float x0, float y0, float x1, float y1, int cols, int rows, Fill f) {
        if (GX.length <= cols) GX = new float[cols + 1];
        if (GY.length <= rows) GY = new float[rows + 1];
        if (GC.length < (cols + 1) * (rows + 1)) GC = new int[(cols + 1) * (rows + 1)];
        for (int i = 0; i <= cols; i++) GX[i] = x0 + (x1 - x0) * i / cols;
        for (int j = 0; j <= rows; j++) GY[j] = y0 + (y1 - y0) * j / rows;
        if (replay != null) return replay.get(replayAt++);
        for (int j = 0, k = 0; j <= rows; j++)
            for (int i = 0; i <= cols; i++) GC[k++] = f.at(GX[i], GY[j]);
        if (record != null) record.add(java.util.Arrays.copyOf(GC, (cols + 1) * (rows + 1)));
        return GC;
    }

    // grid colors captured from one pass and fed back on a later identical pass (CardHolo's still cards)
    private static List<int[]> record, replay;
    private static int replayAt;

    /** Capture the colors of every grid drawn until {@link #stopRecording}. */
    static void record() {
        record = new java.util.ArrayList<>(8);
    }

    static List<int[]> stopRecording() {
        List<int[]> r = record;
        record = null;
        return r;
    }

    /** Draw the next grids with these captured colors instead of calling their fills; null ends it. */
    static void replay(List<int[]> colors) {
        replay = colors;
        replayAt = 0;
    }

    // ------------------------------------------------------------------ text

    enum Weight { XB, SB, MD }

    /** Pixel sizes that have a font definition (keep in sync with tools/gen_card_fonts.py). */
    private static final int[] SIZES = {6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 27, 30, 34, 38, 43, 48, 54, 60};
    /** Set false if the bundled font failed to load; text then falls back to the vanilla font. */
    static boolean ttf = true;

    static Style style(Weight w, float px) {
        if (!ttf) return Style.EMPTY;
        int best = SIZES[0];
        for (int s : SIZES) if (Math.abs(s - px) < Math.abs(best - px)) best = s;
        return Style.EMPTY.withFont(Habits.id("card/" + w.name().toLowerCase() + "_" + best));
    }

    static MutableComponent text(String s, Weight w, float px) {
        return Component.literal(s).withStyle(style(w, px));
    }

    static Font font() {
        return Minecraft.getInstance().font;
    }

    /** Baseline-anchored draw: Minecraft puts the baseline 7 units under y for every font. Returns the width. */
    static int draw(GuiGraphics g, Component c, float x, float baseline, int argb, boolean shadow) {
        g.pose().pushPose();
        g.pose().translate(Math.round(x), Math.round(baseline) - 7, 0);
        int w = g.drawString(font(), c, 0, 0, argb, shadow);
        g.pose().popPose();
        return w;
    }

    static void draw(GuiGraphics g, FormattedCharSequence c, float x, float baseline, int argb, boolean shadow) {
        g.pose().pushPose();
        g.pose().translate(Math.round(x), Math.round(baseline) - 7, 0);
        g.drawString(font(), c, 0, 0, argb, shadow);
        g.pose().popPose();
    }

    /** Letter-spaced uppercase label (CSS letter-spacing). Returns the drawn width. */
    static float tracked(GuiGraphics g, String s, Weight w, float px, float spacing, float x, float baseline, int argb, boolean measureOnly) {
        float cx = x;
        Style st = style(w, px);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            Component ch = Component.literal(new String(Character.toChars(cp))).withStyle(st);
            if (!measureOnly) draw(g, ch, cx, baseline, argb, false);
            cx += font().width(ch) + spacing;
            i += Character.charCount(cp);
        }
        return cx - x - spacing;
    }

    static int width(Component c) {
        return font().width(c);
    }

    /** Cut to fit {@code max} with an ellipsis. */
    static Component fit(Component c, int max) {
        Font f = font();
        if (f.width(c) <= max) return c;
        Style st = c.getStyle();
        String s = c.getString();
        Component dots = Component.literal("...").withStyle(st);
        int room = max - f.width(dots);
        String cut = f.getSplitter().plainHeadByWidth(s, room, st);
        return Component.literal(cut.stripTrailing() + "...").withStyle(st);
    }

    /** Wrap to at most {@code maxLines}, the last line ellipsized. */
    static List<FormattedCharSequence> wrap(Component c, int width, int maxLines) {
        Font f = font();
        List<FormattedCharSequence> lines = f.split(c, width);
        if (lines.size() <= maxLines) return lines;
        // re-wrap: keep maxLines - 1 lines, then the rest squeezed into one ellipsized line
        List<net.minecraft.network.chat.FormattedText> parts = f.getSplitter().splitLines(c, width, c.getStyle());
        StringBuilder rest = new StringBuilder();
        for (int i = maxLines - 1; i < parts.size(); i++) rest.append(parts.get(i).getString()).append(' ');
        List<FormattedCharSequence> out = new java.util.ArrayList<>(lines.subList(0, maxLines - 1));
        out.add(fit(Component.literal(rest.toString().trim()).withStyle(c.getStyle()), width).getVisualOrderText());
        return out;
    }
}
