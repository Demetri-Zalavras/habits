package com.habits.client.fx;

import com.habits.client.book.CardRenderer;
import com.habits.core.CoreRegistries;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The pickup flip: a HUD layer, never a screen, so it never pauses or takes input. Timeline and curves are the approved
 * mockup's {@code play()} (cards2.src.html), in "mockup px" where the card is 132 wide; {@code u} converts to GUI px.
 * Everything runs off {@link #now()} (real ms), so it is frame-rate independent.
 */
final class FlipHud {
    private static final Ease.Bezier RISE = new Ease.Bezier(.2, .9, .3, 1.2);
    private static final Ease.Bezier FLIP = new Ease.Bezier(.3, .1, .2, 1);
    private static final Ease.Bezier RING = new Ease.Bezier(.1, .7, .3, 1);
    private static final Ease.Bezier BURST = new Ease.Bezier(.1, .8, .3, 1);
    private static final Ease.Bezier FLY = new Ease.Bezier(.6, 0, .4, 1);
    /** Base timings in ms (face up at REVEAL, fly-out FLY_MS); each rarity scales them by its {@code time_scale}. */
    static final double REVEAL = 800, FLY_MS = 400;
    // keyframes and scratch objects, kept static so a frame allocates nothing
    private static final double[] RISE_AT = {0, .7, 1}, RISE_Y = {260, -14, 0}, RISE_S = {.6, 1.06, 1}, RISE_A = {0, 1, 1};
    private static final double[] FLIP_AT = {0, .75, 1}, FLIP_V = new double[3];
    private static final float[] SLOT = new float[3], US = new float[4];
    private static final Vector3f C = new Vector3f();
    private static final Matrix4f PERSP = new Matrix4f(), DIVIDE = new Matrix4f();
    private static final Quaternionf Q = new Quaternionf();
    private static Flip painting;
    private static int paintW, paintH;
    private static float lightX = .5f, strength = 1;
    private static final CardTarget.Painter PAINT_FRONT = g -> {
        if (FxCard.USE_BOOK_RENDERER)
            CardRenderer.render(g, painting.def, painting.e.newLevel(), CardRenderer.Size.FULL, 0, 0, paintW,
                    painting.pixelW, 1, true, true, lightX, .35f, strength);
        else FxCard.front(g, painting.def, painting.e.newLevel(), paintW, paintH, 1);
    };
    private static final CardTarget.Painter PAINT_BACK = g -> FxCard.back(g, paintW, paintH, 1);

    /** Dev shots pin the clock to step through frames; null = real time. */
    static Long frozen;

    private static final ArrayDeque<Flip> QUEUE = new ArrayDeque<>();
    private static Flip cur;

    private FlipHud() {}

    static long now() {
        return frozen != null ? frozen : Util.getMillis();
    }

    /** What a right-click add did (core's CardAdded payload, field for field). The flip shows this result. */
    record Added(ResourceLocation skill, int newLevel, int progress, int needed, boolean wasNew, boolean leveledUp) {}

    static void enqueue(SkillDef def, Added e) {
        if (def == null || !FxClientInit.FLIP.get()) return;
        QUEUE.add(new Flip(def, e));
    }

    /** Dev shots: start the queued flip at the pinned clock right now, not at the next HUD render. */
    static void startNow() {
        cur = QUEUE.poll();
        if (cur != null) cur.start = now();
    }

    /** Dev shots: what is on screen now. */
    static String playing() {
        return cur == null ? "nothing" : cur.def.id() + " " + cur.def.rarity() + " at " + (now() - cur.start) + " ms";
    }

    static void clear() {
        QUEUE.clear();
        cur = null;
    }

    /** Left click or Esc: once the card has been up {@link #SKIP_AFTER} ms and is face up, it leaves for the book right
     * now. Returns true when it did (the caller may then swallow the input); false if there was nothing to skip yet. */
    static boolean skip() {
        if (cur == null || cur.start < 0) return false;
        long t = now() - cur.start;
        if (t < Math.max(SKIP_AFTER, cur.reveal + 1) || t >= cur.out) return false;
        cur.skip(t);
        return true;
    }

    private static final long SKIP_AFTER = 1000;

    private static final class Flip {
        final SkillDef def;
        final Added e;
        final double big, hold, k, reveal, fly, turns;
        boolean ready;
        int bookSlot = Integer.MIN_VALUE;
        int pixelW;
        final int rc;
        final float[] pa, pd, pt;
        long start = -1;
        double out;
        boolean revealSound, flipSound;

        Flip(SkillDef def, Added e) {
            this.def = def;
            this.e = e;
            Rarity r = def.rarity();
            big = FxClientInit.perRarity(FxClientInit.INTENSITY, r);
            hold = FxClientInit.perRarity(FxClientInit.HOLD_MS, r);
            rc = r.color;
            k = FxClientInit.perRarity(FxClientInit.SPEED, r);
            reveal = REVEAL * k;
            fly = FLY_MS * k;
            turns = FxClientInit.perRarity(FxClientInit.TURNS, r) * 360;
            out = reveal + hold;
            faceKey = List.of(def.id(), def.rarity(), e.newLevel());
            int n = (int) Math.round(FxClientInit.perRarity(FxClientInit.PARTICLES, r));
            Random rnd = new Random(def.id().hashCode() * 31L + e.newLevel());
            pa = new float[n];
            pd = new float[n];
            pt = new float[n];
            for (int i = 0; i < n; i++) {
                pa[i] = (float) (rnd.nextDouble() * Math.PI * 2);
                pd[i] = (float) ((90 + rnd.nextDouble() * 110) * big);
                pt[i] = (float) ((700 + rnd.nextDouble() * 400) * k); // lifetimes stretch with the spin
            }
        }

        String kicker, status;
        Component name;
        List<FormattedCharSequence> perk;
        int perkWidth = -1;

        /** Label text, built once (and again only if the screen width changes). */
        void texts(Font font, int width) {
            if (width == perkWidth) return;
            perkWidth = width;
            // the payload is the player's CURRENT state: picking a card up does not add it yet
            name = def.displayName();
            status = e.wasNew() ? Component.translatable("fx.habits.new_skill").getString()
                    : e.needed() <= 0 ? Component.translatable("fx.habits.maxed", name, e.newLevel()).getString()
                    : Component.translatable("fx.habits.progress", name, e.newLevel(), e.progress(), e.needed()).getString();
            kicker = (def.rarity().displayName().getString() + "  ·  " + status).toUpperCase(Locale.ROOT);
            perk = font.split(def.perkLine(Math.max(1, e.newLevel())), (int) (width / PERK_SCALE));
        }

        /** What the face shows: the same card picked up again reuses the painted face. */
        final Object faceKey;

        void skip(double at) {
            out = Math.min(out, at);
        }

        boolean revealed() {
            return out > reveal;
        }

        double end() {
            return out + fly + 220;
        }
    }

    static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = now();
        if (cur != null && cur.start >= 0 && now - cur.start > cur.end()) cur = null;
        // more cards waiting: this one reads for a shorter time, so a stack of unlocks does not take minutes
        if (cur != null && !QUEUE.isEmpty()) cur.skip(cur.reveal + FxClientInit.QUEUED_HOLD_MS.get());
        if (cur == null) {
            cur = QUEUE.poll();
            if (cur == null) return;
            cur.start = now;
        }
        // each HUD layer below sits 200 higher; with ~50 layers (big modpacks) this one is at the GUI far plane and
        // anything drawn above it is clipped. Top layer, so start from z 0 on a fresh depth buffer instead.
        g.pose().pushPose();
        g.pose().translate(0, 0, -g.pose().last().pose().m32());
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        draw(g, mc, cur, now - cur.start);
        g.pose().popPose();
    }

    private static void draw(GuiGraphics g, Minecraft mc, Flip f, double t) {
        FxTextures.ensure();
        int sw = g.guiWidth(), sh = g.guiHeight();
        int cw = Math.max(40, (int) Math.round(sh * FxClientInit.CARD_HEIGHT.get() * 63 / 88));
        if (FxCard.USE_BOOK_RENDERER) {
            // build the face now, while only the back is showing (the face first shows ~300 ms later), so the build
            // never lands on a frame where the card is on screen; the next queued card builds only if it is cheap
            f.pixelW = (int) Math.round(cw * mc.getWindow().getGuiScale());
            if (!f.ready) f.ready = CardRenderer.prepare(f.def, f.e.newLevel(), true, CardRenderer.Size.FULL, f.pixelW, 3_000_000L);
            Flip next = QUEUE.peek();
            if (next != null && !next.ready)
                next.ready = CardRenderer.prepare(next.def, next.e.newLevel(), true, CardRenderer.Size.FULL, f.pixelW, 1_000_000L);
        }
        int ch = Math.round(cw * 88f / 63f);
        double u = cw / 132.0;
        float cx = sw / 2f, cy = sh * .385f; // high enough that the label clears the hotbar
        double out = f.out, big = f.big;
        boolean revealed = f.revealed() && t >= f.reveal;
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(0, 0, 400);
        Matrix4f m = ps.last().pose();

        // world dims: in over 250 ms, out 150 ms after the card leaves
        double dim = Ease.EASE_OUT.at(Ease.progress(t, 0, 250 * f.k)) * (1 - Ease.EASE_OUT.at(Ease.progress(t, out + 150, 300)));
        if (dim > 0) {
            g.flush();
            FxDraw.darken();
            FxDraw.tex(m, FxTextures.VIGNETTE, 0, 0, sw, sh, FxDraw.grey(dim * FxClientInit.DIM.get()));
            FxDraw.normal();
            BufferBuilder b = FxDraw.colored(VertexFormat.Mode.QUADS);
            rect(b, m, 0, 0, sw, sh, FxDraw.argb(0, dim * FxClientInit.DIM.get() * .45));
            FxDraw.end(b);
            FxDraw.reset();
        }

        if (revealed) {
            FxDraw.additive();
            // flash: quick white lift of the whole screen
            double fl = Ease.progress(t, f.reveal, 300);
            if (fl < 1) {
                double a = fl < .2 ? fl / .2 : 1 - Ease.EASE_OUT.at((fl - .2) / .8);
                BufferBuilder b = FxDraw.colored(VertexFormat.Mode.QUADS);
                rect(b, m, 0, 0, sw, sh, FxDraw.argb(0xFFFFFF, a * .22));
                FxDraw.end(b);
            }
            // soft glow: swells in 200 ms, settles, breathes through the hold, fades out after the card leaves
            double sw0 = Ease.EASE_OUT.at(Ease.progress(t, f.reveal, 200)), settle = Ease.EASE_IN_OUT.at(Ease.progress(t, f.reveal + 200, 500));
            double gone = Ease.EASE_OUT.at(Ease.progress(t, out, f.fly + 100));
            double breathe = Math.sin((t - f.reveal - 700) / 1600.0 * Math.PI * 2) * settle;
            double ga = sw0 * Ease.lerp(.95, .75, settle) * (1 + .12 * breathe) * (1 - gone);
            double gs = Ease.lerp(.5, .7 + big * .35, sw0) * Ease.lerp(1, .92, settle) * (1 + .03 * breathe);
            if (ga > 0) {
                float r = (float) (260 * u * gs);
                FxDraw.tex(m, FxTextures.GLOW, cx - r, cy - r, cx + r, cy + r, FxDraw.light(f.rc, ga * .85));
                float r2 = r * .45f; // hot core so the middle reads white-ish
                FxDraw.tex(m, FxTextures.GLOW, cx - r2, cy - r2, cx + r2, cy + r2, FxDraw.light(lighten(f.rc, .5), ga * .4));
            }
            // one shockwave ring, not on Common
            if (f.def.rarity() != Rarity.COMMON) {
                double rp = Ease.progress(t, f.reveal, 650 * f.k);
                if (rp < 1) {
                    double e = RING.at(rp);
                    ring(m, cx, cy, (float) (100 * u * Ease.lerp(.4, 1.2 + big, e)), (float) (Ease.lerp(4, 1, e) * u),
                            (float) (12 * u), f.rc, Ease.lerp(.9, 0, e));
                }
            }
            // particle burst
            BufferBuilder pb = FxDraw.colored(VertexFormat.Mode.QUADS);
            for (int i = 0; i < f.pa.length; i++) {
                double p = Ease.progress(t, f.reveal, f.pt[i]);
                if (p >= 1) continue;
                double e = BURST.at(p);
                float x = (float) (cx + Math.cos(f.pa[i]) * f.pd[i] * u * e);
                float y = (float) (cy + (Math.sin(f.pa[i]) * f.pd[i] + 40) * u * e);
                float s = (float) (3 * u * Ease.lerp(1, .3, e));
                rect(pb, m, x - s * 2.2f, y - s * 2.2f, x + s * 2.2f, y + s * 2.2f, FxDraw.argb(f.rc, (1 - e) * .25));
                rect(pb, m, x - s, y - s, x + s, y + s, FxDraw.argb(lighten(f.rc, .35), 1 - e));
            }
            FxDraw.end(pb);
            FxDraw.reset();
        }

        // the card: rise (overshoot), spin, then fly to its slot
        double tc = Math.min(t, out);
        double rp = RISE.at(Ease.progress(tc, 0, 350 * f.k));
        double ry = Ease.keys(rp, RISE_AT, RISE_Y) * u;
        double rs = Ease.keys(rp, RISE_AT, RISE_S);
        double ra = Math.min(1, Ease.keys(rp, RISE_AT, RISE_A));
        FLIP_V[1] = f.turns * .8;
        FLIP_V[2] = f.turns;
        double ang = Ease.keys(FLIP.at(Ease.progress(tc, 300 * f.k, 500 * f.k)), FLIP_AT, FLIP_V);
        double fp = FLY.at(Ease.progress(t, out, f.fly));
        if (f.bookSlot == Integer.MIN_VALUE) f.bookSlot = bookSlot(mc);
        float[] slot = slotPos(mc, f.bookSlot, sw, sh, cx, cy);
        boolean visibleSlot = slot[2] > 0;
        float x = (float) Ease.lerp(cx, slot[0], fp), y = (float) (Ease.lerp(cy + ry, slot[1], fp));
        double scale = rs * Ease.lerp(1, .12, fp);
        double alpha = ra * Ease.lerp(1, visibleSlot ? .9 : 0, fp);
        if (fp < 1 && alpha > .01) card(g, f, x, y, cw, ch, (float) scale, (float) ang, (float) alpha, t, u);

        // slot bump when it lands
        double bp = Ease.progress(t, out + f.fly - 20, 220);
        if (visibleSlot && bp > 0 && bp < 1) {
            g.flush();
            FxDraw.additive();
            double a = Math.sin(bp * Math.PI);
            float s = (float) (9 + 3 * a);
            FxDraw.tex(m, FxTextures.GLOW, slot[0] - s * 2, slot[1] - s * 2, slot[0] + s * 2, slot[1] + s * 2, FxDraw.light(f.rc, a * .9));
            FxDraw.reset();
        }

        if (revealed) label(g, mc.font, f, sw, cy + ch / 2f + 8 * (float) u, t, out);
        ps.popPose();
        sounds(mc, f, t);
    }

    private static void sounds(Minecraft mc, Flip f, double t) {
        if (frozen != null) return;
        if (!f.flipSound && t >= 300 * f.k && t < f.out) {
            f.flipSound = true;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(FxSounds.FLIP, 1.15f, .7f));
        }
        if (!f.revealSound && t >= f.reveal && f.revealed()) {
            f.revealSound = true;
            float pitch = (float) FxClientInit.perRarity(FxClientInit.CHIME_PITCH, f.def.rarity());
            mc.getSoundManager().play(SimpleSoundInstance.forUI(FxSounds.REVEAL, pitch, .9f));
            if (f.def.rarity().ordinal() >= Rarity.EPIC.ordinal())
                mc.getSoundManager().play(SimpleSoundInstance.forUI(FxSounds.REVEAL_BIG, 1f, f.def.rarity() == Rarity.LEGENDARY ? .9f : .45f));
        }
    }

    /** The card in 3D: a Y rotation under a real perspective divide (the model-view matrix carries w), so the spin reads as depth. */
    private static void card(GuiGraphics g, Flip f, float x, float y, int cw, int ch, float scale, float ang, float alpha, double t, double u) {
        g.flush();
        Minecraft mc = Minecraft.getInstance();
        boolean face = Math.cos(Math.toRadians(ang)) < 0;
        painting = f;
        paintW = cw;
        paintH = ch;
        // The back is painted once overall (keyed on the painter itself). The front is BOOK-UI's cached face plus its live
        // holo, copied flat into our target every frame (~0.1 ms: the face is a cached quad, only the holo moves) and
        // spun as ONE quad: drawn straight under the perspective pose, its coplanar layers z-fought into stripes.
        if (FxCard.USE_BOOK_RENDERER) {
            // the holo follows the spin: the light swings across the card as it turns, then sways gently at rest
            float swing = (float) Math.sin(Math.toRadians(ang)), rest = (float) Math.min(1, Math.max(0, (t - f.reveal) / 600));
            lightX = .5f + .45f * swing * (1 - rest) + .12f * rest * (float) Math.sin(t / 900.0);
            strength = 1 - .45f * rest;
        }
        int tex = !face ? CardTarget.BACK.get(mc, PAINT_BACK, false, cw, ch, PAINT_BACK)
                : CardTarget.FRONT.get(mc, f.faceKey, FxCard.USE_BOOK_RENDERER, cw, ch, PAINT_FRONT);
        if (Math.abs(scale - 1) < 1e-3) { // at rest: land on whole pixels so the card is exactly 1:1 crisp
            double gs = mc.getWindow().getGuiScale();
            x = (float) ((Math.round((x - cw / 2f) * gs) + cw * gs / 2) / gs);
            y = (float) ((Math.round((y - ch / 2f) * gs) + ch * gs / 2) / gs);
        }
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(x, y, 0);
        Vector3f c = ps.last().pose().transformPosition(0, 0, 0, C);
        float d = (float) (700 * u * scale);
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        // T(c) * P * T(-c), P puts -z/d into w: a perspective divide centred on the card
        mv.mul(PERSP.translation(c).mul(DIVIDE.identity().m23(-1f / d)).translate(-c.x, -c.y, -c.z));
        RenderSystem.applyModelViewMatrix();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        ps.scale(scale, scale, scale);
        ps.mulPose(Q.rotationY((float) Math.toRadians(face ? ang + 180 : ang)));
        ps.translate(-cw / 2f, -ch / 2f, 0);
        FxDraw.normal();
        FxDraw.texId(ps.last().pose(), tex, 0, 0, cw, ch, FxDraw.argb(0xFFFFFF, alpha));
        FxDraw.reset();
        // white light sweep across the face, once, right after the reveal
        double sp = Ease.EASE_IN_OUT.at(Ease.progress(t, f.reveal + 120 * f.k, 600));
        if (face && f.revealed() && sp > 0 && sp < 1) {
            FxDraw.additive();
            double c0 = Ease.lerp(-.4, 1.4, sp), hw = .22, dx = .906, dy = .423, len = cw * dx + ch * dy;
            for (int i = 0; i < 4; i++) // corners TL, TR, BR, BL
                US[i] = (float) ((((i == 1 || i == 2 ? cw : 0) * dx + (i >= 2 ? ch : 0) * dy) / len - c0) / (2 * hw) + .5);
            FxDraw.texUv(ps.last().pose(), FxTextures.BAND, 0, 0, cw, ch, US[0], .5f, US[1], .5f, US[2], .5f, US[3], .5f,
                    FxDraw.light(0xFFFFFF, .35 * alpha));
            FxDraw.reset();
        }
        RenderSystem.enableDepthTest();
        mv.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableCull();
        ps.popPose();
    }

    private static void label(GuiGraphics g, Font font, Flip f, float sw, float top, double t, double out) {
        double in = Ease.EASE_OUT.at(Ease.progress(t, f.reveal + 60 * f.k, 220));
        double a = in * (1 - Ease.progress(t, out, 150));
        if (a < .02) return;
        float y = (float) (top + 8 * (1 - in));
        f.texts(font, (int) (sw * .6f));
        int ka = Math.max(4, (int) (a * 255));
        PoseStack ps = g.pose();
        g.drawString(font, f.kicker, (int) (sw / 2 - font.width(f.kicker) / 2f), (int) y, ka << 24 | f.rc, true);
        ps.pushPose();
        ps.translate(sw / 2, y + 12, 0);
        ps.scale(2, 2, 1);
        g.drawString(font, f.name, -font.width(f.name) / 2, 0, ka << 24 | 0xFFFFFF, true);
        ps.popPose();
        y += 33;
        for (int i = 0; i < f.perk.size(); i++) { // the effect, a size up so it reads at a glance
            FormattedCharSequence line = f.perk.get(i);
            ps.pushPose();
            ps.translate(sw / 2, y, 0);
            ps.scale(PERK_SCALE, PERK_SCALE, 1);
            g.drawString(font, line, -font.width(line) / 2, 0, ka << 24 | 0xEEEEEE, true);
            ps.popPose();
            y += 10 * PERK_SCALE + 1;
        }
    }

    private static final float PERK_SCALE = 1.25f;

    /** Where the Habits book sits: hotbar 0..8, offhand 40, or -1 (not on screen). */
    private static int bookSlot(Minecraft mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getItem(i).is(CoreRegistries.BOOK.get())) return i;
        return inv.offhand.get(0).is(CoreRegistries.BOOK.get()) ? 40 : -1;
    }

    /** Where the card goes once added: into the book's slot when the book is on screen ([2] = 1), else it sinks into the
     * screen centre and fades, as if into the book. */
    private static float[] slotPos(Minecraft mc, int slot, int sw, int sh, float cx, float cy) {
        float y = sh - 11;
        if (slot >= 0 && slot < 9) return slot(sw / 2f - 80 + slot * 20, y, 1);
        if (slot == 40) {
            boolean left = mc.player.getMainArm() == HumanoidArm.RIGHT;
            return slot(left ? sw / 2f - 91 - 18 : sw / 2f + 91 + 18, y, 1);
        }
        return slot(cx, cy, 0);
    }

    private static float[] slot(float x, float y, float onScreen) {
        SLOT[0] = x;
        SLOT[1] = y;
        SLOT[2] = onScreen;
        return SLOT;
    }

    private static void rect(BufferBuilder b, Matrix4f m, float x0, float y0, float x1, float y1, int argb) {
        b.addVertex(m, x0, y0, 0).setColor(argb);
        b.addVertex(m, x0, y1, 0).setColor(argb);
        b.addVertex(m, x1, y1, 0).setColor(argb);
        b.addVertex(m, x1, y0, 0).setColor(argb);
    }

    /** Ring with a bright core of width {@code w} and a soft halo {@code halo} wide on both sides. */
    private static void ring(Matrix4f m, float cx, float cy, float r, float w, float halo, int rgb, double a) {
        int core = lighten(rgb, .45);
        BufferBuilder b = FxDraw.colored(VertexFormat.Mode.QUADS);
        int n = 96;
        for (int band = 0; band < 3; band++) {
            int col = band == 1 ? core : rgb;
            // band 0: fade in to the core, 1: the core, 2: fade out
            double a0 = band == 0 ? 0 : band == 1 ? a : a * .6, a1 = band == 0 ? a * .6 : band == 1 ? a : 0;
            float r0 = r + (band == 0 ? -w / 2 - halo : band == 1 ? -w / 2 : w / 2);
            float r1 = r + (band == 0 ? -w / 2 : band == 1 ? w / 2 : w / 2 + halo);
            for (int i = 0; i < n; i++) {
                double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
                float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0), c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1);
                b.addVertex(m, cx + c0 * r0, cy + s0 * r0, 0).setColor(FxDraw.argb(col, a0));
                b.addVertex(m, cx + c0 * r1, cy + s0 * r1, 0).setColor(FxDraw.argb(col, a1));
                b.addVertex(m, cx + c1 * r1, cy + s1 * r1, 0).setColor(FxDraw.argb(col, a1));
                b.addVertex(m, cx + c1 * r0, cy + s1 * r0, 0).setColor(FxDraw.argb(col, a0));
            }
        }
        FxDraw.end(b);
    }

    static int lighten(int rgb, double k) {
        int r = rgb >> 16 & 255, gr = rgb >> 8 & 255, b = rgb & 255;
        r += (int) ((255 - r) * k);
        gr += (int) ((255 - gr) * k);
        b += (int) ((255 - b) * k);
        return r << 16 | gr << 8 | b;
    }
}
