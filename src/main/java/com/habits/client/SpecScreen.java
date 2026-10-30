package com.habits.client;

import com.habits.core.Category;
import com.habits.core.Payloads;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import com.habits.core.Specs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Specialization picker (core, client only), two steps: a combat style, then a profession (with {@code combat_specs} off:
 * two different professions; one already held is kept and marked, so only the second is asked). One card per category with
 * its icon, colour, blurb, skill count by rarity and its rarest skills. Click a card, then Confirm; the second Confirm
 * sends {@link Payloads.PickSpecs}. Esc does nothing: the server reopens it on login until a pick is made. Cards are
 * laid out at a fixed size, scaled down to fit the height, and page with the arrows or the wheel when too wide.
 */
public final class SpecScreen extends Screen {
    private static final int CW = 150, PAD = 7, GAP = 8, TOP = 34, BOTTOM = 32, LINE = 10, TOP_SKILLS = 4;
    private static final int BG = 0xE0101018, BORDER = 0x50FFFFFF, LABEL = 0xFFB4B4BE, MUTED = 0xFF7F7F8C, TEXT = 0xFFE8E8EE, GOLD = 0xFFFFC040, WARN = 0xFFE05050;

    /** Set by the server's OpenSpecPick, cleared only by the pick (or leaving the world). Opens once the player has been in
     * the world {@link #SETTLE_TICKS} with no other screen up, and comes back after any other mod's screen replaces it
     * (the level loading screen, a join-time quest book or guide). */
    private static boolean pending;
    static final int SETTLE_TICKS = 100;

    public static void request() {
        pending = true;
    }

    public static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || pending && !Specs.needsPick(mc.player)) pending = false; // picked (synced), here or by the server
        else if (pending && mc.screen == null && mc.player.tickCount >= SETTLE_TICKS && mc.getOverlay() == null) mc.setScreen(new SpecScreen());
    }

    /** The first step's pick: the combat style, or with {@code combat_specs} off the first profession. */
    private @Nullable Category combat = heldProfession(), selected;
    /** The first profession was already held (no Back to change it). */
    private final boolean held = combat != null;
    private Category[] shown = Specs.combatSpecs() ? combatStyles() : professions();
    private int first;
    private Button confirm, back, prev, next;
    private float scale = 1;
    private int cardH, visible, x0, y0;

    public SpecScreen() {
        super(Component.translatable("screen.habits.spec.combat"));
    }

    private static Category[] combatStyles() {
        return Arrays.stream(Category.values()).filter(c -> c.combat).toArray(Category[]::new);
    }

    /** With {@code combat_specs} off, the one profession a player already holds (outside a respec): the first step is done. */
    private static @Nullable Category heldProfession() {
        Minecraft mc = Minecraft.getInstance();
        if (Specs.combatSpecs() || mc.player == null) return null;
        Specs s = Specs.of(mc.player);
        return !s.respec() && s.picks().size() == 1 && !s.picks().getFirst().combat ? s.picks().getFirst() : null;
    }

    private static Category[] professions() {
        return Arrays.stream(Category.values()).filter(c -> !c.combat).toArray(Category[]::new);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected void init() {
        cardH = naturalHeight();
        scale = Math.min(1f, (height - TOP - BOTTOM) / (float) cardH);
        int step = Math.round((CW + GAP) * scale);
        visible = Math.max(1, Math.min(shown.length, (width - 40) / step));
        first = Math.clamp(first, 0, shown.length - visible);
        x0 = (width - (visible * step - Math.round(GAP * scale))) / 2;
        y0 = TOP + Math.max(0, (height - TOP - BOTTOM - Math.round(cardH * scale)) / 2);
        confirm = addRenderableWidget(Button.builder(Component.empty(), b -> confirm()).bounds(width / 2 - 75, height - 26, 150, 20).build());
        back = addRenderableWidget(Button.builder(Component.translatable("screen.habits.spec.back"), b -> back())
                .bounds(width / 2 - 135, height - 26, 56, 20).build());
        prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> page(-1)).bounds(4, height / 2 - 10, 16, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal(">"), b -> page(1)).bounds(width - 20, height / 2 - 10, 16, 20).build());
        refresh();
    }

    /** DevShots: clicks this card, then Confirm when {@code confirm} (moves to the profession step). */
    void shotPick(Category c, boolean confirm) {
        selected = c;
        refresh();
        if (confirm) confirm();
    }

    private void refresh() {
        confirm.active = selected != null;
        confirm.setMessage(selected == null ? Component.translatable("screen.habits.spec.confirm")
                : Component.translatable("screen.habits.spec.confirm").append(": ").append(selected.displayName()));
        back.visible = combat != null && !held;
        prev.visible = first > 0;
        next.visible = first + visible < shown.length;
    }

    private void page(int d) {
        first = Math.clamp(first + d, 0, shown.length - visible);
        refresh();
    }

    private void confirm() {
        if (selected == null) return;
        if (combat == null) {
            combat = selected;
            selected = null;
            shown = professions();
            first = 0;
            rebuildWidgets();
            return;
        }
        PacketDistributor.sendToServer(new Payloads.PickSpecs(combat, selected));
        pending = false;
        onClose();
    }

    private void back() {
        selected = combat;
        combat = null;
        shown = Specs.combatSpecs() ? combatStyles() : professions();
        first = 0;
        rebuildWidgets();
    }

    private List<FormattedCharSequence> blurb(Category c) {
        return font.split(Component.translatable("spec.habits." + c.getSerializedName()), CW - 2 * PAD);
    }

    /** Unscaled card height: the tallest card's content (same for both steps so the layout doesn't jump). */
    private int naturalHeight() {
        int lines = 0;
        for (Category c : Category.values()) lines = Math.max(lines, blurb(c).size());
        return PAD + 30 + lines * 9 + 6 + 14 + Rarity.values().length * LINE + 4 + 14 + TOP_SKILLS * LINE + 14 + PAD;
    }

    private int cardAt(double mx, double my) {
        int step = Math.round((CW + GAP) * scale), w = Math.round(CW * scale);
        if (my < y0 || my > y0 + cardH * scale) return -1;
        for (int i = 0; i < visible; i++) {
            int x = x0 + i * step;
            if (mx >= x && mx < x + w) return first + i;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int i = cardAt(mx, my);
        if (i < 0) return false;
        if (shown[i] == combat) return true; // the first profession can't be picked twice
        selected = shown[i];
        refresh();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        page(sy > 0 ? -1 : sy < 0 ? 1 : 0);
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        Component title = Component.translatable(Specs.combatSpecs() ? combat == null ? "screen.habits.spec.combat" : "screen.habits.spec.profession"
                : combat == null ? "screen.habits.spec.profession_first" : "screen.habits.spec.profession_second");
        g.drawCenteredString(font, title, width / 2, 8, GOLD);
        boolean respec = minecraft.player != null && Specs.of(minecraft.player).respec();
        g.drawCenteredString(font, Component.translatable(respec ? "screen.habits.spec.respec_warn" : Specs.combatSpecs() ? "screen.habits.spec.hint" : "screen.habits.spec.hint_two"),
                width / 2, 20, respec ? WARN : MUTED);
        int hover = cardAt(mx, my);
        int step = Math.round((CW + GAP) * scale);
        for (int i = 0; i < visible; i++) {
            int idx = first + i;
            g.pose().pushPose();
            g.pose().translate(x0 + i * step, y0, 0);
            g.pose().scale(scale, scale, 1);
            card(g, shown[idx], idx == hover);
            g.pose().popPose();
        }
    }

    private void card(GuiGraphics g, Category cat, boolean hover) {
        int c = 0xFF000000 | cat.tabColor, h = cardH;
        boolean sel = cat == selected;
        g.fill(0, 0, CW, h, BG);
        g.fillGradient(0, 0, CW, 40, (sel ? 0x70000000 : 0x40000000) | cat.tabColor, cat.tabColor);
        g.fill(0, 0, CW, 3, c);
        g.renderOutline(0, 0, CW, h, sel ? c : hover ? 0xA0FFFFFF : BORDER);
        if (sel) g.renderOutline(1, 1, CW - 2, h - 2, c);

        List<SkillDef> skills = SkillRegistry.byCategory(cat).stream().filter(d -> !d.special()).toList();
        int x = PAD, y = PAD;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(1.5f, 1.5f, 1);
        g.renderItem(new ItemStack(cat.icon()), 0, 0);
        g.pose().popPose();
        g.drawString(font, cat.displayName(), x + 30, y + 3, c, true);
        g.drawString(font, Component.translatable("screen.habits.spec.skills", skills.size()), x + 30, y + 14, LABEL, false);
        y += 30;
        for (FormattedCharSequence line : blurb(cat)) {
            g.drawString(font, line, x, y, LABEL, false);
            y += 9;
        }
        y += 6;
        y = section(g, "CARDS", y);
        for (Rarity r : Rarity.values()) {
            long n = skills.stream().filter(d -> d.rarity() == r).count();
            g.drawString(font, r.displayName(), x, y, 0xFF000000 | r.color, false);
            g.drawString(font, String.valueOf(n), CW - PAD - font.width(String.valueOf(n)), y, n == 0 ? MUTED : TEXT, false);
            y += LINE;
        }
        y += 4;
        y = section(g, "HIGHLIGHTS", y);
        int yy = y;
        for (SkillDef d : skills.stream().sorted(Comparator.comparing(SkillDef::rarity).reversed()).limit(TOP_SKILLS).toList()) {
            g.drawString(font, font.plainSubstrByWidth(d.displayName().getString(), CW - 2 * PAD), x, yy, 0xFF000000 | d.rarity().color, false);
            yy += LINE;
        }
        if (sel) g.drawCenteredString(font, "SELECTED", CW / 2, h - 12, c);
        if (cat == combat) { // the first profession: taken, not pickable again
            g.pose().pushPose();
            g.pose().translate(0, 0, 300); // over the item icon
            g.fill(1, 1, CW - 1, h - 1, 0xA0000000);
            g.drawCenteredString(font, Component.translatable("screen.habits.spec.picked"), CW / 2, h - 12, c);
            g.pose().popPose();
        }
    }

    private int section(GuiGraphics g, String name, int y) {
        g.hLine(PAD, CW - PAD - 1, y, 0x28FFFFFF);
        g.drawString(font, name, PAD, y + 3, MUTED, false);
        return y + 14;
    }
}
