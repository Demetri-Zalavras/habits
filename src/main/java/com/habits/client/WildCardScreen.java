package com.habits.client;

import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.Payloads;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Wild Card picker (core, client only): every owned skill below Lv10, filtered by a search box, a page at a time.
 * A click sends {@link Payloads.UseWildCard}; the server re-checks everything. Stays open while a Wild Card is left in
 * either hand (rows refresh when the collection syncs) and closes when they run out. */
public final class WildCardScreen extends Screen {
    private static final int ROW = 22, WIDTH = 220;
    private EditBox search;
    private int page;

    public WildCardScreen() {
        super(Component.translatable("screen.habits.wild_card"));
    }

    private List<SkillDef> choices() {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<SkillDef> out = new ArrayList<>();
        HabitsApi.collection(minecraft.player).skills().forEach((id, p) -> {
            SkillDef d = SkillRegistry.get(id);
            if (d != null && p.level() > 0 && !p.maxed() && com.habits.core.Specs.allows(minecraft.player, d) && d.displayName().getString().toLowerCase(Locale.ROOT).contains(q)) out.add(d);
        });
        out.sort(Comparator.comparing(d -> d.displayName().getString()));
        return out;
    }

    private int perPage() {
        return Math.max(1, (height - 90) / ROW);
    }

    @Override
    protected void init() {
        String q = search == null ? "" : search.getValue();
        search = new EditBox(font, (width - WIDTH) / 2, 32, WIDTH, 18, Component.translatable("screen.habits.wild_card.search"));
        search.setValue(q);
        search.setHint(Component.translatable("screen.habits.wild_card.search"));
        search.setResponder(s -> { page = 0; rebuildWidgets(); });
        addRenderableWidget(search);
        setInitialFocus(search);
        List<SkillDef> all = choices();
        int per = perPage(), pages = Math.max(1, (all.size() + per - 1) / per);
        page = Math.min(page, pages - 1);
        int x = (width - WIDTH) / 2, y = 56;
        for (SkillDef d : all.subList(page * per, Math.min(all.size(), (page + 1) * per))) {
            SkillProgress p = HabitsApi.collection(minecraft.player).get(d.id());
            Component label = d.displayName().copy().withColor(d.rarity().color)
                    .append(Component.translatable("screen.habits.wild_card.row", p.level(), p.progress(), p.needed(d.rarity())).withColor(0xA0A0A0));
            addRenderableWidget(Button.builder(label, b -> {
                PacketDistributor.sendToServer(new Payloads.UseWildCard(d.id()));
            }).bounds(x, y, WIDTH, ROW - 2).build());
            y += ROW;
        }
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = (page + pages - 1) % pages; rebuildWidgets(); })
                    .bounds(x, height - 28, 20, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> { page = (page + 1) % pages; rebuildWidgets(); })
                    .bounds(x + WIDTH - 20, height - 28, 20, 20).build());
        }
        empty = all.isEmpty();
        shownCol = HabitsApi.collection(minecraft.player);
        pageLine = Component.translatable("screen.habits.wild_card.page", page + 1, pages);
    }

    private boolean empty;
    @Nullable private HabitsCollection shownCol;

    @Override
    public void tick() {
        super.tick();
        var pl = minecraft.player;
        if (!pl.getMainHandItem().is(CoreRegistries.WILD_CARD.get()) && !pl.getOffhandItem().is(CoreRegistries.WILD_CARD.get())) onClose();
        else if (HabitsApi.collection(pl) != shownCol) rebuildWidgets(); // a card landed: new levels, maxed rows gone
    }

    private Component pageLine = Component.empty();

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        if (empty) g.drawCenteredString(font, Component.translatable("screen.habits.wild_card.none"), width / 2, 64, 0xA0A0A0);
        else g.drawCenteredString(font, pageLine, width / 2, height - 22, 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
