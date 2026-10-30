package com.habits.client.fx;

import com.habits.client.book.CardRenderer;
import com.habits.core.SkillDef;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The card the flip shows. Uses BOOK-UI's {@link CardRenderer} when {@link #USE_BOOK_RENDERER} is on, otherwise a plain
 * stand-in so FX is testable before the binder lands.
 */
final class FxCard {
    /** The single switch: true = BOOK-UI's CardRenderer, false = the stand-in below. */
    static boolean USE_BOOK_RENDERER = true;

    private static final ResourceLocation PLANKS = ResourceLocation.withDefaultNamespace("textures/block/oak_planks.png");
    private static final int GOLD = 0xFFC9A24A;

    private FxCard() {}

    /** Card front at (0, 0), size w x h. */
    static void front(GuiGraphics g, SkillDef def, int level, int w, int h, float alpha) {
        if (USE_BOOK_RENDERER) {
            CardRenderer.render(g, def, level, 0, 0, w, alpha, def.rarity().foil(), true);
            return;
        }
        int rc = def.rarity().color;
        up(g);
        g.fill(-1, -1, w + 1, h + 1, FxDraw.argb(0, alpha));
        up(g);
        g.fill(0, 0, w, h, FxDraw.argb(rc, alpha));
        up(g);
        g.fill(3, 3, w - 3, h - 3, FxDraw.argb(0, alpha));
        up(g);
        g.fill(4, 4, w - 4, h - 4, FxDraw.argb(0x1D1B22, alpha));
        Font font = Minecraft.getInstance().font;
        // name, scaled down to fit
        Component name = def.displayName();
        float ns = Math.min(1f, (w - 14f) / Math.max(1, font.width(name)));
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(w / 2f, 8, 1);
        ps.scale(ns, ns, 1);
        g.drawString(font, name, -font.width(name) / 2, 0, FxDraw.argb(0xFFFFFF, Math.max(alpha, .02)), true);
        ps.popPose();
        // art window: the skill's block, darkened, with the icon big in the middle
        int ax0 = 7, ay0 = 20, ax1 = w - 7, ay1 = (int) (h * 0.70f);
        up(g);
        g.fill(ax0 - 1, ay0 - 1, ax1 + 1, ay1 + 1, FxDraw.argb(0, alpha));
        TextureAtlasSprite art = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper()
                .getParticleIcon(BuiltInRegistries.BLOCK.get(def.art()).defaultBlockState());
        g.flush();
        up(g);
        FxDraw.normal();
        FxDraw.tile(g.pose().last().pose(), art.atlasLocation(), ax0, ay0, ax1, ay1, art.getU0(), art.getV0(), art.getU1(),
                art.getV1(), 16, FxDraw.argb(0x6B6B6B, alpha));
        FxDraw.reset();
        int aw = ax1 - ax0, ah = ay1 - ay0;
        float k = Math.min(aw, ah) * 0.62f / 16f;
        item(g, new ItemStack(BuiltInRegistries.ITEM.get(def.icon())), ax0 + aw / 2f, ay0 + ah / 2f, k);
        // category + pips
        Component cat = def.category().displayName();
        ps.pushPose();
        ps.translate(w / 2f, ay1 + 5, 1);
        ps.scale(.75f, .75f, 1);
        g.drawString(font, cat, -font.width(cat) / 2, 0, FxDraw.argb(0xA8A3B5, Math.max(alpha, .02)), false);
        ps.popPose();
        int pip = Math.max(3, (w - 24) / 14), gap = 2, total = 10 * pip + 9 * gap, px = (w - total) / 2, py = h - 10;
        for (int i = 0; i < 10; i++) {
            int x = px + i * (pip + gap);
            up(g);
            g.fill(x, py, x + pip, py + pip, FxDraw.argb(i < level ? rc : 0x3A3742, alpha));
        }
    }

    /** Card back at (0, 0). */
    static void back(GuiGraphics g, int w, int h, float alpha) {
        if (USE_BOOK_RENDERER) {
            CardRenderer.renderBack(g, 0, 0, w, alpha);
            return;
        }
        up(g);
        g.fill(-1, -1, w + 1, h + 1, FxDraw.argb(0, alpha));
        g.flush();
        up(g);
        FxDraw.normal();
        FxDraw.tile(g.pose().last().pose(), PLANKS, 0, 0, w, h, 0, 0, 1, 1, 32, FxDraw.argb(0xCCCCCC, alpha));
        FxDraw.reset();
        frame(g, 3, 3, w - 3, h - 3, FxDraw.argb(GOLD, alpha), 2);
        frame(g, 5, 5, w - 5, h - 5, FxDraw.argb(0, alpha), 1);
        // diamond emblem with a book
        PoseStack ps = g.pose();
        int d = (int) (w * 0.33f);
        ps.pushPose();
        ps.translate(w / 2f, h / 2f, 1);
        ps.mulPose(Axis.ZP.rotationDegrees(45));
        up(g);
        g.fill(-d / 2 - 2, -d / 2 - 2, d / 2 + 2, d / 2 + 2, FxDraw.argb(0, alpha));
        up(g);
        g.fill(-d / 2, -d / 2, d / 2, d / 2, FxDraw.argb(GOLD, alpha));
        up(g);
        g.fill(-d / 2 + 3, -d / 2 + 3, d / 2 - 3, d / 2 - 3, FxDraw.argb(0x2A1A0E, alpha));
        ps.popPose();
        ps.translate(0, 0, 4);
        item(g, new ItemStack(Items.BOOK), w / 2f, h / 2f, d * 0.7f / 16f);
    }

    /** Each layer one unit nearer: coplanar fills z-fight once the card is drawn under perspective. */
    private static void up(GuiGraphics g) {
        g.pose().translate(0, 0, 1);
    }

    private static void frame(GuiGraphics g, int x0, int y0, int x1, int y1, int c, int t) {
        up(g);
        g.fill(x0, y0, x1, y0 + t, c);
        up(g);
        g.fill(x0, y1 - t, x1, y1, c);
        up(g);
        g.fill(x0, y0 + t, x0 + t, y1 - t, c);
        up(g);
        g.fill(x1 - t, y0 + t, x1, y1 - t, c);
    }

    /** An item centered at (cx, cy), k x its 16 px size, kept on the card plane (renderItem pushes it 150 forward). */
    static void item(GuiGraphics g, ItemStack stack, float cx, float cy, float k) {
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(cx - 8 * k, cy - 8 * k, 0);
        ps.scale(k, k, k);
        ps.translate(0, 0, -150 + 2f / k);
        g.renderItem(stack, 0, 0);
        ps.popPose();
    }
}
