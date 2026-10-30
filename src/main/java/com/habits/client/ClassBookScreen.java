package com.habits.client;

import com.habits.core.Category;
import com.habits.core.Payloads;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

/** Class book confirm (core, client only): one button per pick the book can replace (or one "become" button when it
 * replaces nothing), plus cancel. A click sends {@link Payloads.UseClassBook}; the server re-checks everything. */
public final class ClassBookScreen extends Screen {
    private static final int W = 220;
    private final Payloads.OpenClassBook book;
    private Component className = Component.empty(), line = Component.empty();

    public ClassBookScreen(Payloads.OpenClassBook book) {
        super(Component.translatable("screen.habits.class_book"));
        this.book = book;
    }

    @Override
    protected void init() {
        SkillDef c = SkillRegistry.get(book.clazz());
        if (c == null) { onClose(); return; }
        className = c.displayName().copy().withColor(c.rarity().color);
        line = book.replace().isEmpty()
                ? Component.translatable("screen.habits.class_book.joins", c.category().displayName())
                : Component.translatable("screen.habits.class_book.choose", c.category().displayName());
        int x = (width - W) / 2, y = height / 2 - 10;
        if (book.replace().isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("screen.habits.class_book.become", className), b -> send(null))
                    .bounds(x, y, W, 20).build());
            y += 24;
        }
        for (Category r : book.replace()) {
            addRenderableWidget(Button.builder(Component.translatable("screen.habits.class_book.replace", r.displayName()), b -> send(r))
                    .bounds(x, y, W, 20).build());
            y += 24;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + W / 4, y + 6, W / 2, 20).build());
    }

    private void send(Category replace) {
        PacketDistributor.sendToServer(new Payloads.UseClassBook(book.clazz(), Optional.ofNullable(replace)));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawCenteredString(font, Component.translatable("screen.habits.class_book.title", className), width / 2, height / 2 - 52, 0xFFFFFF);
        g.drawCenteredString(font, line, width / 2, height / 2 - 34, 0xA0A0A0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
