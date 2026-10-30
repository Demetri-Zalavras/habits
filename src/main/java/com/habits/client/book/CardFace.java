package com.habits.client.book;

import com.habits.client.book.CardPaint.Weight;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The static part of a card (everything but the moving holo), painted once into its own texture at the exact pixel
 * size it is shown at and cached per (skill, level, progress, on/off, size class, pixel width). Layout follows
 * the remodel mockup in CSS "em" units: 1 em = card width / 26.
 */
final class CardFace {
    private CardFace() {}

    enum Size { FULL, BINDER, ICON }

    /** Where the moving overlays go, in face pixels. */
    record Layout(float artX0, float artY0, float artX1, float artY1, float bodyX0, float bodyY0, float bodyX1, float bodyY1) {}

    record Key(ResourceLocation skill, int level, int progress, boolean enabled, Size size, int w) {}

    static final class Entry {
        final RenderTarget target;
        final Layout layout;
        final int w, h;

        Entry(RenderTarget target, Layout layout, int w, int h) {
            this.target = target;
            this.layout = layout;
            this.w = w;
            this.h = h;
        }
    }

    private static final int MAX_ENTRIES = 64;
    private static final Map<Key, Entry> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static final RandomSource RAND = RandomSource.create();

    static Entry get(SkillDef def, int level, int progress, boolean enabled, Size size, int w) {
        Key key = new Key(def.id(), level, progress, enabled, size, w);
        Entry e = CACHE.get(key);
        if (e != null) return e;
        long t0 = System.nanoTime();
        e = paint(def, level, progress, enabled, size, w);
        buildNs = buildNs == 0 ? System.nanoTime() - t0 : (buildNs * 7 + (System.nanoTime() - t0)) / 8;
        if (CardProf.ON) CardProf.buildFrameNs += System.nanoTime() - t0;
        CACHE.put(key, e);
        LAYOUTS.put(def.id().toString(), e);
        if (CACHE.size() > MAX_ENTRIES) {
            var it = CACHE.entrySet().iterator();
            release(it.next().getValue().target);
            it.remove();
        }
        return e;
    }

    /** The cached face, or null (never builds). */
    static Entry peek(SkillDef def, int level, int progress, boolean enabled, Size size, int w) {
        return CACHE.get(new Key(def.id(), level, progress, enabled, size, w));
    }

    static final int DEFAULT_W = 260;

    /** Typical full-card rects (in a 260 px face) for the holo-only layer when no face of that card exists yet. */
    static Layout defaultLayout() {
        float em = DEFAULT_W / 26f, b = .55f * em, h = heightFor(DEFAULT_W);
        float ix0 = 1.15f * em + .24f * em;
        return new Layout(ix0, 4.6f * em, DEFAULT_W - ix0, 21.6f * em, b, b, DEFAULT_W - b, h - b);
    }

    /** Running average cost of one face build, for frame budgets. */
    static long buildNs;
    /** Last built face per skill: the holo-only layer borrows its art rect. */
    static final Map<String, Entry> LAYOUTS = new java.util.HashMap<>();

    // render targets are reused by size: creating a framebuffer per face costs more than painting it
    private static final Map<Long, java.util.ArrayDeque<RenderTarget>> POOL = new java.util.HashMap<>();

    private static TextureTarget acquire(int w, int h) {
        var q = POOL.get(((long) w << 32) | h);
        RenderTarget t = q == null ? null : q.poll();
        return t instanceof TextureTarget tt ? tt : new TextureTarget(w, h, true, Minecraft.ON_OSX);
    }

    private static void release(RenderTarget t) {
        var q = POOL.computeIfAbsent(((long) t.width << 32) | t.height, k -> new java.util.ArrayDeque<>());
        if (q.size() < 8) q.add(t);
        else t.destroyBuffers();
    }

    static void clearNow() {
        CACHE.values().forEach(e -> e.target.destroyBuffers());
        CACHE.clear();
        LAYOUTS.clear();
        POOL.values().forEach(q -> q.forEach(RenderTarget::destroyBuffers));
        POOL.clear();
    }

    /** Drop every cached face (skills or resources reloaded). */
    static void clear() {
        RenderSystem.recordRenderCall(CardFace::clearNow);
    }

    static int heightFor(int w) {
        return Math.round(w * 88f / 63f);
    }

    // ------------------------------------------------------------------ painting into the offscreen target

    private static Entry paint(SkillDef def, int level, int progress, boolean enabled, Size size, int w) {
        Minecraft mc = Minecraft.getInstance();
        long t0 = System.nanoTime();
        CardProf.start();
        int h = heightFor(w);
        int prevFbo = com.mojang.blaze3d.platform.GlStateManager.getBoundFramebuffer();
        int vx = com.mojang.blaze3d.platform.GlStateManager.Viewport.x(), vy = com.mojang.blaze3d.platform.GlStateManager.Viewport.y(),
                vw = com.mojang.blaze3d.platform.GlStateManager.Viewport.width(), vh = com.mojang.blaze3d.platform.GlStateManager.Viewport.height();
        TextureTarget t = acquire(w, h);
        t.setClearColor(0, 0, 0, 0);
        t.clear(Minecraft.ON_OSX);
        t.bindWrite(true);
        Matrix4f proj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting sorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, w, h, 0, 1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
        GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        CardProf.mark("target");
        Layout layout;
        try {
            layout = switch (size) {
                case FULL -> full(g, def, level, progress, w, h);
                case BINDER -> binder(g, def, level, w, h);
                case ICON -> icon(g, def, level, w, h);
            };
            float em = w / 26f;
            CardProf.mark("rest");
            g.pose().pushPose();
            g.pose().translate(0, 0, 400); // above the items (z 150+)
            if (!enabled) CardPaint.shape(g, 0, 0, w, h, size == Size.ICON ? 2 : .95f * em, 1, 1, CardPaint.solid(0xA0141416));
            g.pose().popPose();
            g.flush();
            // the face is opaque inside its rounded outline and empty outside, whatever the layers blended to
            RenderSystem.colorMask(false, false, false, true);
            CardPaint.shape(g, 0, 0, w, h, 0, 1, 1, CardPaint.solid(0x00000000));
            RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                    com.mojang.blaze3d.platform.GlStateManager.DestFactor.ZERO);
            shapeNoBlend(g, w, h, size == Size.ICON ? 2 : .95f * em);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.defaultBlendFunc();
            CardProf.mark("alpha");
        } finally {
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.setProjectionMatrix(proj, sorting);
            // FX paints cards inside its own target: go back to whatever was bound, not the main screen
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, prevFbo);
            com.mojang.blaze3d.platform.GlStateManager._viewport(vx, vy, vw, vh);
        }
        t.setFilterMode(org.lwjgl.opengl.GL11.GL_NEAREST);
        CardProf.mark("restore");
        if (CardProf.ON) {
            long[] tt = {System.nanoTime() - t0};
            com.mojang.logging.LogUtils.getLogger().info("[cardprof] build {} {} {}px {} ms", def.id(), size, w, String.format("%.2f", tt[0] / 1e6));
        }
        return new Entry(t, layout, w, h);
    }

    /** Opaque silhouette written straight into alpha (blending off, so the last write wins). */
    private static void shapeNoBlend(GuiGraphics g, int w, int h, float r) {
        RenderSystem.disableBlend();
        com.mojang.blaze3d.vertex.BufferBuilder b = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        Matrix4f m = g.pose().last().pose();
        for (int y = 0; y < h; y++) {
            float d = Math.min(y + 0.5f, h - y - 0.5f), in = 0;
            if (d < r) { float dy = r - d; in = r - (float) Math.sqrt(Math.max(0, r * r - dy * dy)); }
            float x0 = Math.round(in), x1 = w - Math.round(in);
            b.addVertex(m, x0, y, 0).setColor(0xFF000000);
            b.addVertex(m, x0, y + 1, 0).setColor(0xFF000000);
            b.addVertex(m, x1, y + 1, 0).setColor(0xFF000000);
            b.addVertex(m, x1, y, 0).setColor(0xFF000000);
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.enableBlend();
    }

    // ------------------------------------------------------------------ palettes

    private static final float[] FACE_POS = {0, .38f, .64f, 1};
    private static final int[] FACE_COL = {0xFF3B3C43, 0xFF151519, 0xFF2B2C32, 0xFF0E0E11};
    private static final float[] GOLD_POS = {0, .18f, .36f, .52f, .70f, .86f, 1};
    private static final int[] GOLD_COL = {0xFF6E4A0C, 0xFFF7D679, 0xFFA26F18, 0xFFFFEAA6, 0xFF8A5D12, 0xFFE9BF5C, 0xFF5E3F0A};
    private static final float[] METAL_POS = {0, .28f, .48f, .70f, 1};
    private static final int[] METAL_COL = {0xFFE6E8EE, 0xFF6B6F79, 0xFFF6F7FA, 0xFF575B64, 0xFFC9CCD3};
    private static final int[] METAL_GOLD = {0xFFFFF0B8, 0xFFA8741C, 0xFFFFE08A, 0xFF7A5410, 0xFFF3CD6A};

    /** The visible middle of a gradient drawn at background-size {@code size} (CSS 240% shows t in [.29, .71]). */
    private static CardPaint.Fill zoomed(float x0, float y0, float x1, float y1, float deg, float zoom,
                                         float[] pos, int[] col) {
        float k = 1 / zoom, off = (1 - k) / 2;
        float[] p = new float[pos.length];
        for (int i = 0; i < pos.length; i++) p[i] = (pos[i] - off) / k;
        return CardPaint.linear(x0, y0, x1, y1, deg, p, col);
    }

    private static int r(SkillDef d) {
        return d.rarity().color | 0xFF000000;
    }

    // ------------------------------------------------------------------ shared pieces

    /** Outer face (border color by rarity) and the body panel. Returns the body rect. */
    private static float[] frame(GuiGraphics g, SkillDef def, int w, int h, float em) {
        int rc = r(def);
        boolean leg = def.rarity() == Rarity.LEGENDARY;
        CardPaint.shape(g, 0, 0, w, h, .95f * em, 1, 1, CardPaint.solid(0xF0000000));
        CardPaint.Fill face = leg ? zoomed(0, 0, w, h, 135, 2.4f, GOLD_POS, GOLD_COL)
                : CardPaint.linear(0, 0, w, h, 155, FACE_POS, FACE_COL);
        CardPaint.shape(g, 1, 1, w - 1, h - 1, .95f * em - 1, 8, 10, face);
        CardPaint.shape(g, .95f * em, 1, w - .95f * em, 2, 0, 1, 1, CardPaint.solid(0x2EFFFFFF));
        float b = .55f * em;
        int ring1 = leg ? 0xFF1A1206 : 0xFF060607, ring2 = leg ? 0xFF6E4A0C : CardPaint.mix(rc, .8f, 0xFF000000);
        CardPaint.shape(g, b - .24f * em, b - .24f * em, w - b + .24f * em, h - b + .24f * em, .79f * em, 1, 1, CardPaint.solid(ring1));
        CardPaint.shape(g, b - .12f * em, b - .12f * em, w - b + .12f * em, h - b + .12f * em, .67f * em, 1, 1, CardPaint.solid(ring2));
        CardPaint.Fill bodyFill = leg
                ? CardPaint.linear(b, b, w - b, h - b, 172, new float[]{0, .55f, 1}, new int[]{0xFF2A2112, 0xFF141009, 0xFF1D160A})
                : CardPaint.linear(b, b, w - b, h - b, 172, new float[]{0, .5f, 1},
                new int[]{CardPaint.mix(rc, .17f, 0xFF1C1D22), 0xFF131418, CardPaint.mix(rc, .10f, 0xFF0F1013)});
        CardPaint.shape(g, b, b, w - b, h - b, .55f * em, 4, 8, bodyFill);
        // inset shadow, 2.4em blur: darken toward the edges
        // inset shadow (2.4em blur): four edge ramps, one draw
        float fall = 1.5f * em;
        int dark = 0x80000000;
        CardPaint.edgeRamps(g, b, b, w - b, h - b, fall, dark);
        return new float[]{b, b, w - b, h - b};
    }

    /** Tiled block art, darkened; tile size snapped to whole pixels per texel. */
    private static void tiles(GuiGraphics g, ResourceLocation art, float x0, float y0, float x1, float y1, float tilePx, float bright) {
        Block block = BuiltInRegistries.BLOCK.get(art);
        BlockState state = (block == Blocks.AIR ? Blocks.STONE : block).defaultBlockState();
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        List<BakedQuad> up = model.getQuads(state, Direction.UP, RAND);
        TextureAtlasSprite sp = up.isEmpty() ? model.getParticleIcon() : up.get(0).getSprite();
        float r = bright, gr = bright, bl = bright;
        if (!up.isEmpty() && up.get(0).isTinted()) { r *= .57f; gr *= .74f; bl *= .35f; }
        int tile = Math.max(16, Math.round(tilePx / 16f) * 16);
        int ix0 = Math.round(x0), iy0 = Math.round(y0), ix1 = Math.round(x1), iy1 = Math.round(y1);
        RenderSystem.setShaderTexture(0, sp.atlasLocation());
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        Matrix4f m = g.pose().last().pose();
        com.mojang.blaze3d.vertex.BufferBuilder b = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int ty = iy0; ty < iy1; ty += tile) {
            for (int tx = ix0; tx < ix1; tx += tile) {
                int xb = Math.min(tx + tile, ix1), yb = Math.min(ty + tile, iy1);
                float u1 = sp.getU((xb - tx) / (float) tile), v1 = sp.getV((yb - ty) / (float) tile);
                b.addVertex(m, tx, ty, 0).setUv(sp.getU0(), sp.getV0()).setColor(r, gr, bl, 1);
                b.addVertex(m, tx, yb, 0).setUv(sp.getU0(), v1).setColor(r, gr, bl, 1);
                b.addVertex(m, xb, yb, 0).setUv(u1, v1).setColor(r, gr, bl, 1);
                b.addVertex(m, xb, ty, 0).setUv(u1, sp.getV0()).setColor(r, gr, bl, 1);
            }
        }
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(b.buildOrThrow());
    }

    /** Vanilla item, scaled to whole pixels per texel, centered at (cx, cy), with a hard drop shadow. */
    private static void item(GuiGraphics g, ItemStack stack, float cx, float cy, float maxPx, float shadowDx, float shadowDy,
                             float bgLuma, int rarity) {
        int k = Math.max(1, Math.round(maxPx / 16f));
        float size = 16f * k;
        float x = Math.round(cx - size / 2), y = Math.round(cy - size / 2);
        drawItem(g, stack, x + Math.max(1, Math.round(shadowDx)), y + Math.max(1, Math.round(shadowDy)), k, 0, 0, 0, .5f);
        // icon about as bright as the art behind it (obsidian on obsidian): a one-texel rim. The shader color multiplies
        // the texture, so a big factor lifts even dark texels to a bright silhouette; light art gets a dark rim instead.
        float itemLum = luminance(Minecraft.getInstance().getItemRenderer().getModel(stack, null, null, 0).getParticleIcon());
        // only dark on dark: a rim around thin light items reads as noise, and light art already has the drop shadow
        if (Math.abs(itemLum - bgLuma) < RIM_CONTRAST && bgLuma < .2f && itemLum < .2f) {
            int rc = CardPaint.mix(0xFFFFFFFF, .5f, rarity);
            float boost = Math.min(20, 1.1f / Math.max(.05f, itemLum));
            float rr = ((rc >> 16) & 0xFF) / 255f * boost, rg = ((rc >> 8) & 0xFF) / 255f * boost, rb = (rc & 0xFF) / 255f * boost;
            for (int i = 0; i < 4; i++)
                drawItem(g, stack, x + (i == 0 ? -k : i == 1 ? k : 0), y + (i == 2 ? -k : i == 3 ? k : 0), k, rr, rg, rb, 1);
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, 10);
        drawItem(g, stack, x, y, k, 1, 1, 1, 1);
        g.pose().popPose();
    }

    private static void drawItem(GuiGraphics g, ItemStack stack, float x, float y, float scale, float r, float gr, float b, float a) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.setColor(r, gr, b, a);
        g.renderFakeItem(stack, 0, 0);
        g.setColor(1, 1, 1, 1);
        g.pose().popPose();
    }

    /** Art window: metal frame, tiles, rarity glow, vignette, item. */
    private static void artWindow(GuiGraphics g, SkillDef def, float x0, float y0, float x1, float y1, float em, float border,
                                  float itemFrac, float tileEm) {
        boolean leg = def.rarity() == Rarity.LEGENDARY;
        int rc = r(def);
        // drop shadow + outline ring, then the metal border
        CardPaint.shape(g, x0 - 1, y0 + .1f * em, x1 + 1, y1 + .45f * em, .4f * em, 1, 1, CardPaint.solid(0x55000000));
        CardPaint.shape(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, .28f * em + 1, 1, 1, CardPaint.solid(0xFF050506));
        CardPaint.shape(g, x0, y0, x1, y1, .28f * em, 6, 6,
                zoomed(x0, y0, x1, y1, 135, 2.2f, METAL_POS, leg ? METAL_GOLD : METAL_COL));
        float ix0 = x0 + border, iy0 = y0 + border, ix1 = x1 - border, iy1 = y1 - border;
        CardPaint.shape(g, ix0, iy0, ix1, iy1, 0, 1, 1, CardPaint.solid(0xFF000000));
        CardProf.mark("a.border");
        tiles(g, def.art(), ix0, iy0, ix1, iy1, 3.05f * em, leg ? .40f : .56f);
        CardProf.mark("a.tiles");
        float aw = ix1 - ix0, ah = iy1 - iy0;
        float far = (float) Math.sqrt(2);
        // CSS lists the glow first, so it sits above the vignette
        CardPaint.shape(g, ix0, iy0, ix1, iy1, 0, 10, 10, CardPaint.radial(ix0 + aw / 2, iy0 + ah / 2, aw / 2 * far, ah / 2 * far,
                new float[]{.42f, 1}, new int[]{0x00000000, 0xB8000000}));
        CardPaint.shape(g, ix0, iy0, ix1, iy1, 0, 10, 10, CardPaint.radial(ix0 + aw * .5f, iy0 + ah * .52f, aw * .52f, ah * .62f,
                new float[]{0, .72f}, new int[]{CardPaint.alpha(.52f, rc), CardPaint.alpha(0, rc)}));
        CardProf.mark("a.glow");
        float al = artLuma(def);
        CardProf.mark("a.luma");
        item(g, icon(def), ix0 + aw / 2, iy0 + ah / 2, ah * itemFrac, .24f * em, .3f * em, al * (leg ? .40f : .56f), rc);
        CardProf.mark("a.item");
    }

    private static final float RIM_CONTRAST = 0.08f;
    /** Mean luminance of a sprite's opaque pixels; keyed by sprite identity, so a resource reload starts fresh. */
    private static final Map<TextureAtlasSprite, Float> LUMA = new java.util.WeakHashMap<>();

    static float luminance(TextureAtlasSprite sp) {
        return LUMA.computeIfAbsent(sp, s -> {
            try {
                com.mojang.blaze3d.platform.NativeImage img = s.contents().getOriginalImage();
                double sum = 0;
                int n = 0;
                for (int y = 0; y < s.contents().height(); y++) {
                    for (int x = 0; x < s.contents().width(); x++) {
                        int p = img.getPixelRGBA(x, y); // ABGR
                        if ((p >>> 24) < 128) continue;
                        sum += 0.2126 * (p & 0xFF) + 0.7152 * ((p >> 8) & 0xFF) + 0.0722 * ((p >> 16) & 0xFF);
                        n++;
                    }
                }
                return n == 0 ? 0.5f : (float) (sum / n / 255);
            } catch (RuntimeException e) {
                return 0.5f; // image already freed: assume mid grey, so no rim
            }
        });
    }

    private static float artLuma(SkillDef def) {
        Block block = BuiltInRegistries.BLOCK.get(def.art());
        BlockState state = (block == Blocks.AIR ? Blocks.STONE : block).defaultBlockState();
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        List<BakedQuad> up = model.getQuads(state, Direction.UP, RAND);
        return luminance(up.isEmpty() ? model.getParticleIcon() : up.get(0).getSprite());
    }

    private static ItemStack icon(SkillDef def) {
        return new ItemStack(BuiltInRegistries.ITEM.get(def.icon()));
    }

    /** Big pixel numeral (vanilla font at a whole scale), right-aligned at {@code xr}, baseline {@code base}. */
    private static float pixelNum(GuiGraphics g, String s, float xr, float base, int k, int argb, float shadowPx) {
        int tw = CardPaint.font().width(s) - 1;
        float x = Math.round(xr - tw * k);
        g.pose().pushPose();
        g.pose().translate(x, Math.round(base - 7 * k), 0);
        g.pose().scale(k, k, 1);
        g.drawString(CardPaint.font(), s, 0, Math.max(1, shadowPx) / k, 0xA6000000, false);
        g.drawString(CardPaint.font(), s, 0, 0, argb, false);
        g.pose().popPose();
        return tw * k;
    }

    private static void shadowed(GuiGraphics g, Component c, float x, float base, int argb, float shadowPx) {
        CardPaint.draw(g, c, x, base + Math.max(1, Math.round(shadowPx)), 0xA6000000, false);
        CardPaint.draw(g, c, x, base, argb, false);
    }

    private static String t(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** "LV" small + big numeral, right-aligned at {@code xr}; returns the total width. */
    private static float levelReadout(GuiGraphics g, SkillDef def, int level, float xr, float base, float smallEm, float bigEm, float em) {
        int k = Math.max(1, Math.round(bigEm * em * .7f / 7f));
        float numW = pixelNum(g, String.valueOf(level), xr, base, k, 0xFFFFFFFF, .07f * em);
        Component lv = CardPaint.text(t("card.habits.lv"), Weight.XB, smallEm * em);
        float lw = CardPaint.width(lv);
        CardPaint.draw(g, lv, xr - numW - .14f * em - lw, base, CardPaint.mix(r(def), .75f, 0xFFFFFFFF), false);
        return numW + .14f * em + lw;
    }

    // ------------------------------------------------------------------ the full card

    private static Layout full(GuiGraphics g, SkillDef def, int level, int progress, int w, int h) {
        float em = w / 26f;
        float[] body = frame(g, def, w, h, em);
        CardProf.mark("frame");
        Readout ro = Readout.of(def, level, progress);
        int rc = r(def);
        float ix0 = .55f * em + .6f * em, ix1 = w - ix0, iy0 = .55f * em + .5f * em, iy1 = h - .55f * em - .45f * em;
        float iw = ix1 - ix0;

        // bottom-up: stats, rule, next, effect, ability, strip
        Component eff = CardPaint.text(ro.effect, Weight.SB, .96f * em);
        List<FormattedCharSequence> effLines = CardPaint.wrap(eff, (int) iw, 2);
        float effLine = .96f * em * 1.2f;
        float statsH = .52f * em + .28f * em + .8f * em + .28f * em + .26f * em;
        float valCap = 2.5f * em * .7f;
        int valK = Math.max(1, Math.round(valCap / 7f));
        float abilityH = Math.max(1.38f * em, valK * 7 + .3f * em + .54f * em * .75f);
        float lowerH = abilityH + .28f * em + effLines.size() * effLine + .28f * em + .76f * em + .34f * em + 1 + .34f * em + statsH;
        float lowerY = iy1 - lowerH;
        float stripH = .66f * em + .84f * em;
        float stripY = lowerY - .3f * em - stripH;

        // top
        float tagH = .56f * em + .58f * em;
        boolean max = level >= 10;
        String tag = t(max ? "card.habits.mastered" : "card.habits.stage").toUpperCase(Locale.ROOT);
        float tagTextW = CardPaint.tracked(g, tag, Weight.XB, .56f * em, .16f * em, 0, 0, 0, true);
        float tagW = tagTextW + .6f * em + 1.2f * em;
        float topH = tagH + .18f * em + 1.62f * em * .95f;
        float artY0 = iy0 + topH + .3f * em, artY1 = stripY + .08f * em - .3f * em;

        artWindow(g, def, ix0, artY0, ix1, artY1, em, .24f * em, .74f, 3.05f);
        Layout layout = new Layout(ix0 + .24f * em, artY0 + .24f * em, ix1 - .24f * em, artY1 - .24f * em, body[0], body[1], body[2], body[3]);

        CardProf.mark("art");
        // stage tag (slanted right edge)
        int tagTop = max ? 0xFFFFF1C2 : 0xFFF4F4F6, tagBot = max ? 0xFFE3A52C : 0xFFA3A6AE;
        CardPaint.quad(g, ix0, iy0, ix0 + tagW, iy0, ix0 + tagW - .7f * em, iy0 + tagH, ix0, iy0 + tagH, tagTop, tagTop, tagBot, tagBot);
        CardPaint.tracked(g, tag, Weight.XB, .56f * em, .16f * em, ix0 + .6f * em, iy0 + .32f * em + .56f * em * .72f, 0xFF18171A, false);
        // level readout + name share a baseline
        float nameBase = iy0 + tagH + .18f * em + 1.62f * em * .82f;
        float lvW = levelReadout(g, def, level, ix1, nameBase, .72f, 2.05f, em);
        Component name = CardPaint.fit(CardPaint.text(def.displayName().getString(), Weight.XB, 1.62f * em), (int) (iw - lvW - .4f * em));
        shadowed(g, name, ix0, nameBase, 0xFFFFFFFF, .07f * em);

        CardProf.mark("top");
        // info strip
        float sx0 = ix0 + .6f * em, sx1 = ix1 - .6f * em;
        CardPaint.shape(g, sx0, stripY, sx1, stripY + stripH, 0, 16, 1, CardPaint.linear(sx0, stripY, sx1, stripY + stripH, 90,
                new float[]{0, .12f, .88f, 1}, new int[]{0x00FFFFFF, 0x0FFFFFFF, 0x0FFFFFFF, 0x00FFFFFF}));
        CardPaint.Fill edge = CardPaint.linear(sx0, 0, sx1, 1, 90, new float[]{0, .5f, 1},
                new int[]{CardPaint.alpha(0, rc), CardPaint.alpha(.55f, rc), CardPaint.alpha(0, rc)});
        CardPaint.shape(g, sx0, stripY, sx1, stripY + 1, 0, 16, 1, edge);
        CardPaint.shape(g, sx0, stripY + stripH - 1, sx1, stripY + stripH, 0, 16, 1, edge);
        float sp = .66f * em;
        Component strip = Component.empty()
                .append(CardPaint.text(def.category().displayName().getString() + "  ·  ", Weight.SB, sp).withColor(0xD8D3C8))
                .append(CardPaint.text(def.rarity().displayName().getString(), Weight.SB, sp).withColor(CardPaint.mix(rc, .7f, 0xFFFFFFFF) & 0xFFFFFF))
                .append(CardPaint.text("  ·  " + t("card.habits.strip_found", def.foundByText().getString()), Weight.SB, sp).withColor(0xD8D3C8));
        strip = fitMixed(strip, (int) (sx1 - sx0 - em));
        CardPaint.draw(g, strip, (sx0 + sx1) / 2 - CardPaint.width(strip) / 2f, stripY + stripH / 2 + sp * .36f, 0xFFFFFFFF, false);

        CardProf.mark("strip");
        // ability row: name left, big value + unit right
        float y = lowerY;
        float valBase = y + valK * 7;
        float valW = pixelNum(g, ro.value, ix1, valBase, valK, 0xFFFFFFFF, .07f * em);
        String unit = ro.unit.toUpperCase(Locale.ROOT);
        float unitW = CardPaint.tracked(g, unit, Weight.SB, .54f * em, .12f * em, 0, 0, 0, true);
        CardPaint.tracked(g, unit, Weight.SB, .54f * em, .12f * em, ix1 - unitW, valBase + .3f * em + .54f * em * .72f, 0xFFA7A299, false);
        Component ab = CardPaint.fit(CardPaint.text(ro.ability, Weight.XB, 1.38f * em), (int) (iw - Math.max(valW, unitW) - .45f * em));
        shadowed(g, ab, ix0, y + abilityH / 2 + 1.38f * em * .36f, 0xFFFFFFFF, .05f * em);
        y += abilityH + .28f * em;
        for (FormattedCharSequence line : effLines) {
            CardPaint.draw(g, line, ix0, y + effLine * .78f, 0xFFE9E4DA, false);
            y += effLine;
        }
        y += .28f * em;
        float nextBase = y + .76f * em * .74f;
        float nw = CardPaint.tracked(g, t("card.habits.next").toUpperCase(Locale.ROOT), Weight.XB, .76f * em, .12f * em, ix0, nextBase,
                CardPaint.mix(rc, .75f, 0xFFFFFFFF), false);
        Component next = CardPaint.fit(CardPaint.text(ro.next, Weight.SB, .76f * em), (int) (iw - nw - .5f * em));
        CardPaint.draw(g, next, ix0 + nw + .5f * em, nextBase, 0xFFCFCAC0, false);
        y += .76f * em + .34f * em;
        CardPaint.shape(g, ix0, y, ix1, y + 1, 0, 16, 1, CardPaint.linear(ix0, y, ix1, y + 1, 90,
                new float[]{0, .5f, 1}, new int[]{0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF}));
        y += 1 + .34f * em;

        CardProf.mark("lower");
        // stats: found by / drop / progress
        float[] frac = {1.15f, .85f, 1.1f};
        float gap = .2f * em, colW = (iw - 2 * gap) / 3.1f, cx = ix0;
        String[] labels = {t("card.habits.found_by"), t("card.habits.drop"), t(max ? "card.habits.level" : "card.habits.progress")};
        String[] values = {ro.foundShort, ro.odds, max ? t("card.habits.max") : t("card.habits.to_level", ro.progress, ro.needed, level + 1)};
        for (int i = 0; i < 3; i++) {
            float cw = colW * frac[i], mid = cx + cw / 2;
            if (i > 0) CardPaint.shape(g, cx - gap / 2, y, cx - gap / 2 + 1, y + statsH, 0, 1, 1, CardPaint.solid(0x14FFFFFF));
            String lab = labels[i].toUpperCase(Locale.ROOT);
            float lw = CardPaint.tracked(g, lab, Weight.XB, .52f * em, .16f * em, 0, 0, 0, true);
            CardPaint.tracked(g, lab, Weight.XB, .52f * em, .16f * em, mid - lw / 2, y + .52f * em * .74f, 0xFF8E8A82, false);
            float vb = y + .52f * em + .28f * em + .8f * em * .74f;
            float iconW = i == 0 ? 1.25f * em + .3f * em : 0;
            Component val = CardPaint.fit(CardPaint.text(values[i], Weight.SB, .8f * em), (int) (cw - iconW - .2f * em));
            float vw = CardPaint.width(val) + iconW, vx = mid - vw / 2;
            if (i == 0) {
                float isz = 1.25f * em;
                drawItem(g, foundIcon(def), Math.round(vx), Math.round(vb - isz * .8f), isz / 16f, 1, 1, 1, 1);
            }
            CardPaint.draw(g, val, vx + iconW, vb, 0xFFEFEBE3, false);
            if (i == 2) {
                float bw = cw * .8f, bx = mid - bw / 2, by = y + .52f * em + .28f * em + .8f * em + .28f * em, bh = Math.max(2, .26f * em);
                CardPaint.shape(g, bx, by, bx + bw, by + bh, bh / 2, 1, 1, CardPaint.solid(0x99000000));
                float p = max ? 1 : ro.needed == 0 ? 0 : ro.progress / (float) ro.needed;
                if (p > 0) CardPaint.shape(g, bx, by, bx + Math.max(bh, bw * p), by + bh, bh / 2, 8, 1,
                        CardPaint.linear(bx, by, bx + bw * p, by + bh, 90, new float[]{0, 1}, new int[]{CardPaint.mix(rc, .7f, 0xFF000000), rc}));
            }
            cx += cw + gap;
        }
        return layout;
    }

    /** Strip with several colored parts: cut the plain text and keep the leading styles where possible. */
    private static Component fitMixed(Component c, int max) {
        if (CardPaint.width(c) <= max) return c;
        Component last = c.getSiblings().get(c.getSiblings().size() - 1);
        Component head = Component.empty().append(c.getSiblings().get(0)).append(c.getSiblings().get(1));
        int room = max - CardPaint.width(head);
        if (room < 20) return CardPaint.fit(Component.literal(c.getString()).withStyle(last.getStyle()), max);
        return Component.empty().append(head).append(CardPaint.fit(last, room));
    }

    /** Small icon for "Found by": the tool/item the trigger names, else the category's icon. */
    private static ItemStack foundIcon(SkillDef def) {
        var item = def.trigger().item();
        if (item.isPresent() && !item.get().ids().isEmpty()) {
            Item it = BuiltInRegistries.ITEM.get(item.get().ids().get(0));
            if (it != Items.AIR) return new ItemStack(it);
        }
        var match = def.trigger().match();
        if (match.isPresent() && !match.get().ids().isEmpty()) {
            Item it = BuiltInRegistries.ITEM.get(match.get().ids().get(0));
            if (it != Items.AIR) return new ItemStack(it);
        }
        return new ItemStack(BinderScreen.tabIcon(def.category()));
    }

    // ------------------------------------------------------------------ binder size

    private static final ResourceLocation[] SYMBOLS = new ResourceLocation[Rarity.values().length];

    static {
        for (Rarity r : Rarity.values()) SYMBOLS[r.ordinal()] = com.habits.Habits.id("textures/book/sym_" + r.getSerializedName() + ".png");
    }

    private static Layout binder(GuiGraphics g, SkillDef def, int level, int w, int h) {
        float em = w / 26f;
        float[] body = frame(g, def, w, h, em);
        CardProf.mark("b.frame");
        int rc = r(def);
        float ix0 = .55f * em + .75f * em, ix1 = w - ix0, iy0 = .55f * em + .7f * em, iy1 = h - .55f * em - .8f * em;
        float iw = ix1 - ix0;
        float nameBase = iy0 + 2.5f * em * .8f;
        float topH = 2.5f * em * .95f;
        float symH = 2.1f * em, miniY = iy1 - symH;
        float ay0 = iy0 + topH + .55f * em, ay1 = miniY - .55f * em, bd = Math.max(1, .35f * em);
        artWindow(g, def, ix0, ay0, ix1, ay1, em, bd, .62f, 3.05f);
        Layout layout = new Layout(ix0 + bd, ay0 + bd, ix1 - bd, ay1 - bd, body[0], body[1], body[2], body[3]);
        CardProf.mark("b.art");
        float lvW = levelReadout(g, def, level, ix1, nameBase, 1.1f, 3f, em);
        CardProf.mark("b.level");
        Component name = CardPaint.fit(CardPaint.text(def.displayName().getString(), Weight.XB, 2.5f * em), (int) (iw - lvW - .4f * em));
        shadowed(g, name, ix0, nameBase, 0xFFFFFFFF, .07f * em);
        CardProf.mark("b.name");
        // 10 level pips + rarity symbol
        float symW = def.rarity() == Rarity.EPIC ? symH * 1.0f : symH;
        float px1 = ix1 - symW - .5f * em, pgap = .22f * em, ph = .95f * em, pw = (px1 - ix0 - 9 * pgap) / 10f;
        float py = miniY + (symH - ph) / 2;
        for (int i = 0; i < 10; i++) {
            float x = ix0 + i * (pw + pgap);
            boolean on = i < level;
            if (on) CardPaint.shape(g, x - .25f * em, py - .25f * em, x + pw + .25f * em, py + ph + .25f * em, .4f * em, 1, 1,
                    CardPaint.solid(CardPaint.alpha(.22f, rc)));
            CardPaint.shape(g, x, py, x + pw, py + ph, .15f * em, 1, 1, CardPaint.solid(on ? rc : 0x1FFFFFFF));
        }
        RenderSystem.enableBlend();
        CardRenderer.quad(g, SYMBOLS[def.rarity().ordinal()], ix1 - symW, miniY, ix1, miniY + symH, 0, 0, 1, 1,
                ((rc >> 16) & 0xFF) / 255f, ((rc >> 8) & 0xFF) / 255f, (rc & 0xFF) / 255f, 1);
        return layout;
    }

    // ------------------------------------------------------------------ inventory icon size

    private static Layout icon(GuiGraphics g, SkillDef def, int level, int w, int h) {
        int rc = r(def);
        boolean leg = def.rarity() == Rarity.LEGENDARY;
        CardPaint.shape(g, 0, 0, w, h, 2, 6, 6, leg ? CardPaint.linear(0, 0, w, h, 135, new float[]{0, .5f, 1},
                new int[]{0xFFFFE9A6, 0xFFB47B1C, 0xFFFFD76A}) : CardPaint.solid(rc));
        float b = Math.max(1, w / 20f);
        CardPaint.shape(g, b, b, w - b, h - b, 1, 1, 1, CardPaint.solid(0xFF000000));
        tiles(g, def.art(), b, b, w - b, h - b, 16, .56f);
        item(g, icon(def), w / 2f, h * .46f, h * .76f, 1, 1, artLuma(def) * .56f, rc);
        return new Layout(b, b, w - b, h - b, b, b, w - b, h - b);
    }

    // ------------------------------------------------------------------ values shown on the card

    /** The numbers and lines a card prints, from the skill data. */
    record Readout(String ability, String value, String unit, String effect, String next, String foundShort, String odds,
                   int progress, int needed) {
        static Readout of(SkillDef def, int level, int progress) {
            int lv = Math.max(1, level);
            String tpl = def.perkText().map(s -> s.replace("{v}", "%s"))
                    .orElseGet(() -> Language.getInstance().getOrDefault(def.perk().translationKey()));
            String[] vu = valueAndUnit(tpl, SkillDef.format(def.perkValue(lv)));
            String abilityKey = def.translationKey() + ".ability";
            String ability = Language.getInstance().has(abilityKey) ? t(abilityKey) : t("card.habits.ability");
            String effect = def.perkLine(lv).getString();
            String next = level >= 10 ? t("card.habits.mastered_line") : t("card.habits.next_line", lv + 1, def.perkLine(lv + 1).getString());
            // catalog unit when present; the sign, % and x still come from the template
            String key = def.translationKey();
            String digits = SkillDef.format(def.perkValue(lv));
            String unitKey = digits.equals("1") && Language.getInstance().has(key + ".unit.one") ? key + ".unit.one" : key + ".unit";
            if (Language.getInstance().has(unitKey)) vu[1] = t(unitKey);
            if (level < 10 && Language.getInstance().has(key + ".unit")) {
                // just the delta: "Lv 4: 32 logs", with the template's sign, % and x
                String nd = SkillDef.format(def.perkValue(lv + 1));
                String nk = nd.equals("1") && Language.getInstance().has(key + ".unit.one") ? key + ".unit.one" : key + ".unit";
                next = t("card.habits.next_value", lv + 1, valueAndUnit(tpl, nd)[0], t(nk));
            }
            String found = Language.getInstance().has(key + ".found_short") ? t(key + ".found_short") : def.foundByText().getString();
            found = found.isEmpty() ? found : Character.toUpperCase(found.charAt(0)) + found.substring(1);
            double c = def.trigger().chance();
            // XP per action at the default rates (the server's multipliers and boosts are not known here)
            double xp = c * com.habits.core.Leveling.XP_PER_CARD * com.habits.core.CoreInit.at(com.habits.core.CoreInit.XP_RATE, def.rarity());
            String odds = t("card.habits.odds", xp >= 10 ? String.valueOf(Math.round(xp)) : String.format(java.util.Locale.ROOT, "%.1f", xp));
            return new Readout(ability, vu[0], vu[1], effect, next, found, odds, progress, com.habits.core.Leveling.xpToNext(level, def.rarity()));
        }
    }

    private static final java.util.Set<String> MODS = java.util.Set.of("more", "extra", "bonus", "additional", "connected",
            "nearby", "max", "maximum", "total", "free", "faster");
    private static final java.util.Set<String> STOP = java.util.Set.of("of", "to", "and", "the", "a", "an", "for", "in", "on",
            "with", "at", "by", "from", "per", "is", "are", "you", "your", "it", "that", "who");

    /**
     * Pull the big number and its unit out of a perk line template ("Mining speed +%s%%" -> "+12%", "mining speed";
     * "Bend arrows within %s blocks" -> "3", "blocks"). Best effort until skills carry a unit (CONTRACT Requests).
     */
    static String[] valueAndUnit(String tpl, String v) {
        int i = tpl.indexOf("%s");
        if (i < 0) i = tpl.indexOf("%1$s");
        if (i < 0) return new String[]{v, ""};
        int end = tpl.indexOf('s', i) + 1;
        String sign = i > 0 && (tpl.charAt(i - 1) == '+' || tpl.charAt(i - 1) == '-') ? String.valueOf(tpl.charAt(i - 1)) : "";
        boolean pct = tpl.startsWith("%%", end);
        boolean times = !pct && end < tpl.length() && tpl.charAt(end) == 'x'
                && (end + 1 == tpl.length() || !Character.isLetter(tpl.charAt(end + 1)));
        String after = tpl.substring(pct ? end + 2 : end).trim();
        String unit = words(after, true);
        if (unit.isEmpty()) unit = words(tpl.substring(0, sign.isEmpty() ? i : i - 1).trim(), false);
        if (times) unit = words(tpl.substring(end + 1).trim(), true);
        return new String[]{sign + v + (pct ? "%" : times ? "x" : ""), unit};
    }

    private static String words(String s, boolean fromStart) {
        String[] ws = s.split("[^A-Za-z']+");
        List<String> out = new java.util.ArrayList<>();
        if (fromStart) {
            // words up to the first stop word or punctuation, at most 3; skip modifiers while a noun follows
            String head = s.split("[.,;:!?(]", 2)[0];
            for (String w : head.split("[^A-Za-z']+")) {
                if (w.isEmpty()) continue;
                if (STOP.contains(w.toLowerCase(Locale.ROOT)) || out.size() == 3) break;
                out.add(w);
            }
            while (out.size() > 1 && MODS.contains(out.get(0).toLowerCase(Locale.ROOT))) out.remove(0);
            return out.isEmpty() ? "" : out.get(0);
        }
        for (int k = ws.length - 1; k >= 0 && out.size() < 2; k--) if (!ws[k].isEmpty()) out.add(0, ws[k]);
        return String.join(" ", out);
    }
}
