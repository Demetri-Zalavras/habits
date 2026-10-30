package com.habits.client.movement;

import com.habits.perk.movement.MovePayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** World's Edge picker: one button per visited structure. Picking sends the index; the server re-checks everything. */
final class TravelScreen extends Screen {
    private final MovePayloads.TravelList list;

    TravelScreen(MovePayloads.TravelList list) {
        super(Component.translatable("habits.movement.travel_title"));
        this.list = list;
    }

    @Override
    protected void init() {
        int y = 40, w = 220;
        for (int i = 0; i < list.labels().size() && y < height - 30; i++, y += 22) {
            String[] parts = list.labels().get(i).split("\\|");
            int idx = i;
            Component label = parts.length >= 4
                    ? Component.translatable("habits.movement.travel_entry", pretty(parts[0]), parts[2], parts[3])
                    : Component.literal(list.labels().get(i));
            addRenderableWidget(Button.builder(label, b -> {
                MovementClient.send(MovePayloads.TRAVEL, idx);
                onClose();
            }).bounds(width / 2 - w / 2, y, w, 20).build()).active = list.minutesLeft() == 0;
        }
    }

    /** "minecraft:ancient_city" to "Ancient City". */
    static String pretty(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        StringBuilder sb = new StringBuilder();
        for (String word : path.split("[_/]")) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return sb.toString();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(font, title, width / 2, 15, 0xFFFFFF);
        Component sub = list.labels().isEmpty() ? Component.translatable("habits.movement.travel_none")
                : list.minutesLeft() > 0 ? Component.translatable("habits.movement.travel_wait", list.minutesLeft()) : null;
        if (sub != null) g.drawCenteredString(font, sub, width / 2, 27, 0xAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
