package com.habits.client.fx;

import com.habits.core.CardItems;
import com.habits.core.Payloads;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Gain feed: rows on the left edge at mid screen for cards that added themselves (auto-add) and for sigils. Newest on
 * top, up to {@code feed_rows}; each slides in, stays {@code feed_seconds}, then fades and slides back out. A repeat of
 * the same skill (or sigil kind) updates its row and moves it to the top. Text is built when a gain arrives, never per frame.
 */
public final class GainFeed {
    private static final int ROW_H = 20, STEP = ROW_H + 2, ICON = 16, PAD = 3, COL_GAP = 8, EDGE = 4;
    private static final int SLIDE_MS = 280, OUT_MS = 450, BUMP_MS = 450;
    private static final int GREY = 0xD8D8D8, GOLD = 0xFFD54A, BAR_MIN_W = 60;
    private static final Ease.Bezier BACK_OUT = new Ease.Bezier(.34, 1.4, .64, 1);

    private static final class Row {
        final Object key;
        final ItemStack icon;
        final int color;
        FormattedCharSequence name, detail;
        int nameW, detailW, detailColor, count;
        /** XP rows: progress to the next level 0..1, drawn as a bar under the name; -1 for no bar. */
        float bar = -1;
        long born, bumped = Long.MIN_VALUE;
        float y = Float.NaN;

        Row(Object key, ItemStack icon, int color) {
            this.key = key;
            this.icon = icon;
            this.color = color;
        }
    }

    /** Newest first. */
    private static final List<Row> ROWS = new ArrayList<>();
    private static long lastFrame;

    private GainFeed() {}

    /** A card that added itself (core's CardGained). */
    static void card(Payloads.CardAdded e) {
        SkillDef def = e.def();
        if (def == null) return;
        Row r = row(e.skill(), () -> CardItems.create(def), def.rarity().color);
        if (r == null) return;
        Font font = Minecraft.getInstance().font;
        r.name = def.displayName().getVisualOrderText();
        r.nameW = font.width(r.name);
        Component d = e.wasNew() ? Component.translatable("habits.feed.new")
                : e.leveledUp() ? Component.translatable("habits.feed.level_up", e.level())
                : Component.translatable("habits.feed.progress", e.level(), e.progress(), e.needed());
        r.detail = d.getVisualOrderText();
        r.detailW = font.width(r.detail);
        r.detailColor = e.wasNew() || e.leveledUp() ? GOLD : GREY;
        r.bar = -1;
        r.count = 0;
    }

    /** XP for a skill (batched by core): "+N XP" summed while the row lives, a bar to the next level and "LvX Y%". */
    static void xp(Payloads.XpGained e) {
        SkillDef def = com.habits.core.SkillRegistry.get(e.skill());
        if (def == null || e.needed() <= 0) return;
        Row r = row(e.skill(), () -> CardItems.create(def), def.rarity().color);
        if (r == null) return;
        Font font = Minecraft.getInstance().font;
        if (r.bar < 0) r.count = 0; // was a card row (new / level up): start the sum fresh
        r.count += e.gained();
        r.bar = Math.min(1, e.progress() / (float) e.needed());
        r.name = def.displayName().getVisualOrderText();
        r.nameW = Math.max(font.width(r.name), BAR_MIN_W);
        r.detail = Component.translatable("habits.feed.xp", r.count, e.level(), Math.round(r.bar * 100)).getVisualOrderText();
        r.detailW = font.width(r.detail);
        r.detailColor = GREY;
    }

    /** A sigil gain row: {@code icon} small, "+count Rarity Type" in the rarity colour. Repeats of the same rarity and
     * type add up in one row. Client thread. */
    public static void sigil(ItemStack icon, Component type, Rarity rarity, int count) {
        Row r = row(rarity.name() + ':' + type.getString(), icon::copy, rarity.color);
        if (r == null) return;
        r.count += count;
        r.name = Component.translatable("habits.feed.sigil", r.count, rarity.displayName(), type).getVisualOrderText();
        r.nameW = Minecraft.getInstance().font.width(r.name);
        r.detail = FormattedCharSequence.EMPTY;
        r.detailW = 0;
    }

    /** The live row for {@code key} moved to the top (bumped), or a new one; null when the feed is off. */
    private static Row row(Object key, java.util.function.Supplier<ItemStack> icon, int color) {
        if (!FxClientInit.FEED.get()) return null;
        long now = FlipHud.now();
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            if (!r.key.equals(key) || now - r.born >= lifeMs()) continue; // a fading row is not reused
            ROWS.remove(i);
            ROWS.add(0, r);
            r.born = now - SLIDE_MS; // already in: no second slide
            r.bumped = now;
            return r;
        }
        Row r = new Row(key, icon.get(), color);
        r.born = now;
        ROWS.add(0, r);
        while (ROWS.size() > FxClientInit.FEED_ROWS.get()) ROWS.remove(ROWS.size() - 1);
        return r;
    }

    private static long lifeMs() {
        return Math.round(FxClientInit.FEED_SECONDS.get() * 1000);
    }

    static void clear() {
        ROWS.clear();
    }

    static void render(GuiGraphics g, DeltaTracker dt) {
        if (ROWS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        long now = FlipHud.now(), life = lifeMs();
        for (int i = ROWS.size() - 1; i >= 0; i--) if (now - ROWS.get(i).born >= life + OUT_MS) ROWS.remove(i);
        if (ROWS.isEmpty() || mc.player == null) return;
        // ease rows toward their slot; a pinned clock (dev shots) snaps them
        long frameMs = now - lastFrame;
        lastFrame = now;
        float k = frameMs <= 0 || frameMs > 500 ? 1 : (float) (1 - Math.exp(-frameMs / 70.0));

        int nameCol = 0, detailCol = 0;
        for (int i = 0; i < ROWS.size(); i++) {
            nameCol = Math.max(nameCol, ROWS.get(i).nameW);
            detailCol = Math.max(detailCol, ROWS.get(i).detailW);
        }
        int w = PAD + ICON + PAD + 1 + nameCol + (detailCol > 0 ? COL_GAP + detailCol : 0) + PAD + 2;
        float top = g.guiHeight() / 2f - FxClientInit.FEED_ROWS.get() * STEP / 2f;
        Font font = mc.font;

        // same as FlipHud: start from z 0 on a fresh depth buffer so ~50 HUD layers below cannot push this past the far plane
        g.pose().pushPose();
        g.pose().translate(0, 0, -g.pose().last().pose().m32());
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            float target = top + i * STEP;
            r.y = Float.isNaN(r.y) ? target : r.y + (target - r.y) * k;
            double in = BACK_OUT.at(Ease.progress(now, r.born, SLIDE_MS));
            double out = Ease.EASE_IN_OUT.at(Ease.progress(now, r.born + life, OUT_MS));
            float x = (float) (Ease.lerp(-w - EDGE, EDGE, in) - out * (w + EDGE));
            double alpha = 1 - out;
            g.pose().pushPose();
            g.pose().translate(x, r.y, 0);
            g.fill(0, 0, w, ROW_H, FxDraw.argb(0x0E0F14, .72 * alpha));
            g.fill(0, 0, 2, ROW_H, FxDraw.argb(r.color, .95 * alpha)); // rarity edge
            double bump = 1 - Ease.progress(now, r.bumped, BUMP_MS);
            if (bump > 0) g.fill(0, 0, w, ROW_H, FxDraw.argb(r.color, .22 * bump * alpha));
            g.renderItem(r.icon, PAD + 1, (ROW_H - ICON) / 2);
            int a = (int) (alpha * 255);
            if (a >= 4) { // the font draws alpha below 4 opaque
                int ty = (ROW_H - 8) / 2 - (r.bar >= 0 ? 3 : 0), tx = PAD + ICON + PAD + 1;
                g.drawString(font, r.name, tx, ty, a << 24 | r.color, true);
                if (r.detailW > 0) g.drawString(font, r.detail, tx + nameCol + COL_GAP, ty, a << 24 | r.detailColor, true);
                if (r.bar >= 0) { // progress to the next level, under the name
                    int by = ROW_H - 6;
                    g.fill(tx, by, tx + nameCol, by + 2, FxDraw.argb(0x000000, .6 * alpha));
                    g.fill(tx, by, tx + Math.round(nameCol * r.bar), by + 2, FxDraw.argb(r.color, .95 * alpha));
                }
            }
            g.pose().popPose();
        }
        g.pose().popPose();
    }
}
