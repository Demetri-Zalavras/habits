package com.habits.client.book;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Draws a card: the trading-card remodel (docs/DESIGN.md "Card remodel"). Owner: BOOK-UI; FX reuses it for the pickup
 * flip and the inventory icon. Proportions 63:88: height = {@link #heightFor(int)}. Callers wanting rotation or scale
 * push their own transforms on {@code g.pose()} first; the renderer draws at (x, y) in that space.
 * <p>The static face is painted once per (skill, level, progress, on/off, size, pixel width) into an offscreen
 * texture at the exact screen size ({@link CardFace}); only the light-following holo is drawn every frame.
 * <p>Size by on-screen width (like the mockup): under 40 px the inventory icon, under 190 px the binder card, else the
 * full card. {@link Size} forces one.
 */
public final class CardRenderer {
    private CardRenderer() {}

    /** Old 63 x 88 card-pixel grid, still used by the binder layout and the card back. */
    static final int W = 63, H = 88;

    public enum Size { AUTO, FULL, BINDER, ICON }

    private static final ResourceLocation VIGNETTE = Habits.id("book/art_vignette"),
            BACK_FRAME = Habits.id("book/back_frame"), BACK_EMBLEM = Habits.id("book/back_emblem"),
            SPARKLE = Habits.id("textures/book/sparkle.png"), ETCH = Habits.id("textures/book/etch.png");

    /** Card height for a width (63:88). */
    public static int heightFor(int width) {
        return Math.round(width * 88f / 63f);
    }

    /**
     * Card front with the light gently swaying on its own (anything that has no pointer).
     *
     * @param level   owned level 0..10
     * @param alpha   0..1 overall opacity
     * @param foil    draw the moving holo/sheen/glare layer
     * @param enabled false greys the card out (perk toggled off)
     */
    public static void render(GuiGraphics g, SkillDef def, int level, int x, int y, int width, float alpha, boolean foil, boolean enabled) {
        boolean holo = foil || def.rarity() != Rarity.COMMON;
        float t = (Util.getMillis() % 7000L) / 7000f, e = .5f - .5f * (float) Math.cos(t * Math.PI * 2);
        render(g, def, level, Size.AUTO, x, y, width, alpha, holo, enabled, .22f + .56f * e, .3f + .32f * e, .75f);
    }

    /**
     * Card front, full control; the cached face is sized from the current pose (sharp when the pose is not animating).
     *
     * @param lightX   where the light sits on the card, 0 (left) .. 1 (right): the pointer in a GUI, the tilt in a flip
     * @param lightY   0 (top) .. 1 (bottom)
     * @param strength 0 (resting) .. 1 (fully lit, pointer on the card)
     */
    public static void render(GuiGraphics g, SkillDef def, int level, Size size, int x, int y, int width,
                              float alpha, boolean foil, boolean enabled, float lightX, float lightY, float strength) {
        float exact = width * pixelScale(g);
        int wPx = Math.abs(exact - Math.round(exact)) < 0.02f ? Math.round(exact) : (int) Math.ceil(exact / 16f) * 16;
        render(g, def, level, size, x, y, width, wPx, alpha, foil, enabled, lightX, lightY, strength);
    }

    /**
     * Same, with the face's pixel width given: the face is painted (once) at {@code pixelWidth} and drawn as one quad under
     * whatever pose the caller has, so an animating pose (a flip) never repaints it. Pass the card's largest on-screen width.
     */
    public static void render(GuiGraphics g, SkillDef def, int level, Size size, int x, int y, int width,
                              int pixelWidth, float alpha, boolean foil, boolean enabled, float lightX, float lightY, float strength) {
        if (alpha <= 0.004f || width <= 0) return;
        int wPx = Math.max(16, Math.min(pixelWidth, 760));
        CardFace.Size fs = faceSize(size, wPx);
        int progress = progress(def, level);
        g.flush();
        CardFace.Entry e = CardFace.get(def, level, progress, enabled, fs, wPx);
        long td = CardProf.ON ? System.nanoTime() : 0;
        drawFace(g, e, fs, x, y, width, alpha, Math.abs(width * pixelScale(g) - wPx) < 0.5f);
        long th = CardProf.ON ? System.nanoTime() : 0;
        if (CardProf.ON) CardProf.faceNs += th - td;
        if (foil && enabled && fs != CardFace.Size.ICON)
            CardHolo.draw(g, def, e.layout, e.w, x, y, width, width * 88f / 63f, clamp01(lightX), clamp01(lightY), strength, alpha);
        if (CardProf.ON) CardProf.holoNs += System.nanoTime() - th;
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableBlend();
        if (CardProf.ON) CardProf.draw(System.nanoTime() - td);
    }

    /**
     * Only the moving light (holo, sparkle, etch, sheen, glare) for this rarity: fills and textured quads, no
     * items, no text, so it draws under any pose. For a flip that spins a cached face: {@code angle} -1..1 (0 = facing).
     */
    public static void renderHolo(GuiGraphics g, SkillDef def, int x, int y, int width, float angle, float alpha) {
        if (alpha <= 0.004f) return;
        CardFace.Entry e = CardFace.LAYOUTS.get(def.id().toString());
        CardFace.Layout layout = e != null ? e.layout : CardFace.defaultLayout();
        int lw = e != null ? e.w : CardFace.DEFAULT_W;
        float a = Math.max(-1, Math.min(1, angle));
        CardHolo.draw(g, def, layout, lw, x, y, width, width * 88f / 63f, .5f + a * .45f, .32f, .55f + .45f * Math.abs(a), alpha);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableBlend();
    }

    /**
     * Build a face ahead of time so the frame that shows it does not pay for it. Builds now only if the running average
     * build cost fits {@code nanosBudget}; returns true once the face is cached. Call each frame until true.
     */
    public static boolean prepare(SkillDef def, int level, boolean enabled, Size size, int pixelWidth, long nanosBudget) {
        int wPx = Math.max(16, Math.min(pixelWidth, 760));
        CardFace.Size fs = faceSize(size, wPx);
        int progress = progress(def, level);
        if (CardFace.peek(def, level, progress, enabled, fs, wPx) != null) return true;
        // build when the average cost fits, and always allow one build from a fresh 3 ms+ budget so a big card still lands
        if (nanosBudget <= 0 || (nanosBudget < CardFace.buildNs && nanosBudget < 3_000_000L)) return false;
        CardFace.get(def, level, progress, enabled, fs, wPx);
        return true;
    }

    private static CardFace.Size faceSize(Size size, int wPx) {
        return switch (size) {
            case FULL -> CardFace.Size.FULL;
            case BINDER -> CardFace.Size.BINDER;
            case ICON -> CardFace.Size.ICON;
            case AUTO -> wPx < 40 ? CardFace.Size.ICON : wPx < 190 ? CardFace.Size.BINDER : CardFace.Size.FULL;
        };
    }

    private static int progress(SkillDef def, int level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        SkillProgress p = HabitsApi.collection(mc.player).get(def.id());
        return p.level() == level ? p.progress() : 0;
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    /** Soft drop shadow (one draw) and the cached face as one quad. */
    private static void drawFace(GuiGraphics g, CardFace.Entry e, CardFace.Size fs, float x, float y, float width, float alpha, boolean exact) {
        float h = width * 88f / 63f;
        if (fs != CardFace.Size.ICON) shadow(g, x, y, width, h, alpha);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        e.target.setFilterMode(exact ? GL11.GL_NEAREST : GL11.GL_LINEAR);
        RenderSystem.setShaderTexture(0, e.target.getColorTextureId());
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Matrix4f m = g.pose().last().pose();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buf.addVertex(m, x, y, 0).setUv(0, 1).setColor(1, 1, 1, alpha);
        buf.addVertex(m, x, y + h, 0).setUv(0, 0).setColor(1, 1, 1, alpha);
        buf.addVertex(m, x + width, y + h, 0).setUv(1, 0).setColor(1, 1, 1, alpha);
        buf.addVertex(m, x + width, y, 0).setUv(1, 1).setColor(1, 1, 1, alpha);
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    /** Box shadow under the card (CSS 0 1.4em 2.6em -.8em): one vertex-colored grid, darkest under the middle. */
    private static void shadow(GuiGraphics g, float x, float y, float w, float h, float alpha) {
        float blur = w * .07f, dy = w * .03f;
        float x0 = x + blur * .3f, y0 = y + dy + blur * .3f, x1 = x + w - blur * .3f, y1 = y + h + dy - blur * .3f;
        CardPaint.shape(g, x0 - blur, y0 - blur, x1 + blur, y1 + blur, blur * 1.5f, 8, 10, (px, py) -> {
            float ox = Math.max(0, Math.max(x0 - px, px - x1)), oy = Math.max(0, Math.max(y0 - py, py - y1));
            float d = (float) Math.sqrt(ox * ox + oy * oy) / blur;
            float a = Math.max(0, 1 - d);
            return CardPaint.alpha(.55f * a * a * alpha, 0);
        });
    }

    /** Drop cached faces right now (render thread). */
    static void clearNow() {
        CardFace.clearNow();
    }

    /** Drop cached faces (skills re-synced, resources reloaded). */
    public static void clearCache() {
        CardFace.clear();
    }

    /** Screen pixels per GUI unit under the current pose (ignores a flip's rotation: the larger axis wins). */
    static float pixelScale(GuiGraphics g) {
        Matrix4f m = g.pose().last().pose();
        float sx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01());
        float sy = (float) Math.sqrt(m.m10() * m.m10() + m.m11() * m.m11());
        return Math.max(sx, sy) * (float) Minecraft.getInstance().getWindow().getGuiScale();
    }

    /** Card back (plank/leather, gold trim, diamond emblem with a book). */
    public static void renderBack(GuiGraphics g, int x, int y, int width, float alpha) {
        if (alpha <= 0.004f) return;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(width / (float) W, width / (float) W, 1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        float d = density(g);
        shadow(g, 0, 0, W, H, alpha);
        TextureAtlasSprite planks = Minecraft.getInstance().getBlockRenderer().getBlockModel(Blocks.OAK_PLANKS.defaultBlockState()).getParticleIcon();
        tile(g, planks, 5, 5, W - 10, H - 10, 0.64f, 0.46f, 0.33f, alpha);
        tint(g, 0xFFFFFF, alpha);
        blit(g, VIGNETTE, 5, 5, W - 10, H - 10);
        blit(g, BACK_FRAME, 0, 0, W, H);
        blit(g, BACK_EMBLEM, 15, 28, 33, 33);
        g.setColor(1, 1, 1, 1);
        int k = Math.max(1, (int) Math.floor(d * 17 / 16f));
        float size = 16f * k / d;
        float ix = snap(31.5f - size / 2, d), iy = snap(44.5f - size / 2, d);
        item(g, new ItemStack(Items.BOOK), ix + size / 16, iy + size / 16, size, 0, 0, 0, 0.7f * alpha);
        item(g, new ItemStack(Items.BOOK), ix, iy, size, alpha, alpha, alpha, alpha);
        g.setColor(1, 1, 1, 1);
        g.pose().popPose();
    }

    // ------------------------------------------------------------------ helpers (also used by the binder)

    /** Tile a 16 px atlas sprite over a rect, centered, clipping the edge tiles by UV. */
    private static void tile(GuiGraphics g, TextureAtlasSprite sp, int x, int y, int w, int h, float r, float gr, float b, float a) {
        int ox = x - (16 - w % 16) % 16 / 2, oy = y - (16 - h % 16) % 16 / 2;
        for (int ty = oy; ty < y + h; ty += 16) {
            for (int tx = ox; tx < x + w; tx += 16) {
                int x0 = Math.max(tx, x), y0 = Math.max(ty, y), x1 = Math.min(tx + 16, x + w), y1 = Math.min(ty + 16, y + h);
                quad(g, sp.atlasLocation(), x0, y0, x1, y1, sp.getU((x0 - tx) / 16f), sp.getV((y0 - ty) / 16f),
                        sp.getU((x1 - tx) / 16f), sp.getV((y1 - ty) / 16f), r, gr, b, a);
            }
        }
    }

    private static void item(GuiGraphics g, ItemStack stack, float x, float y, float size, float r, float gr, float b, float a) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(size / 16, size / 16, 1);
        g.setColor(r, gr, b, a);
        g.renderFakeItem(stack, 0, 0);
        g.setColor(1, 1, 1, 1);
        g.pose().popPose();
    }

    /** Screen pixels per card px under the current pose. */
    static float density(GuiGraphics g) {
        Matrix4f m = g.pose().last().pose();
        float sx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01());
        return Math.max(0.25f, sx * (float) Minecraft.getInstance().getWindow().getGuiScale());
    }

    private static float snap(float v, float d) {
        return Math.round(v * d) / d;
    }

    static void quad(GuiGraphics g, ResourceLocation tex, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
                     float r, float gr, float b, float a) {
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Matrix4f m = g.pose().last().pose();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buf.addVertex(m, x0, y0, 0).setUv(u0, v0).setColor(r, gr, b, a);
        buf.addVertex(m, x0, y1, 0).setUv(u0, v1).setColor(r, gr, b, a);
        buf.addVertex(m, x1, y1, 0).setUv(u1, v1).setColor(r, gr, b, a);
        buf.addVertex(m, x1, y0, 0).setUv(u1, v0).setColor(r, gr, b, a);
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    /** blitSprite with blending on (any fill before it turns blending off). */
    static void blit(GuiGraphics g, ResourceLocation sprite, int x, int y, int w, int h) {
        RenderSystem.enableBlend();
        g.blitSprite(sprite, x, y, w, h);
    }

    static void tint(GuiGraphics g, int rgb, float alpha) {
        g.setColor(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, alpha);
    }

    static int mul(int argb, float alpha) {
        return (Math.round(((argb >>> 24) & 0xFF) * alpha) << 24) | (argb & 0xFFFFFF);
    }

    static void additive() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
    }

    static ResourceLocation sparkle() {
        return SPARKLE;
    }

    static ResourceLocation etch() {
        return ETCH;
    }
}
