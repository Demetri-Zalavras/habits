package com.habits.client.fx;

import com.habits.core.CardData;
import com.habits.core.CardItems;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;

/**
 * The player's level in a card's skill, drawn in the stack-count corner of the slot (top-left when the stack shows a
 * count). Nothing when the skill is not owned yet. GUI only, no allocation per slot.
 */
final class CardLevelDecorator implements IItemDecorator {
    private static final String[] LEVELS = new String[11];

    static {
        for (int i = 0; i < LEVELS.length; i++) LEVELS[i] = String.valueOf(i);
    }

    private CardLevelDecorator() {}

    static void register(RegisterItemDecorationsEvent e) {
        e.register(CoreRegistries.CARD.get(), new CardLevelDecorator());
    }

    @Override
    public boolean render(GuiGraphics g, Font font, ItemStack stack, int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        CardData d = CardItems.dataOf(stack);
        if (d == null || mc.player == null) return false;
        int level = HabitsApi.level(mc.player, d.skill());
        if (level <= 0) return false;
        String s = LEVELS[Math.min(level, 10)];
        boolean corner = stack.getCount() == 1;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200); // above the item, like vanilla's count
        g.drawString(font, s, corner ? x + 17 - font.width(s) : x, corner ? y + 9 : y, 0xFFFFFFFF, true); // white like the vanilla count: a rarity colour sank into the frame
        g.pose().popPose();
        return false;
    }
}
