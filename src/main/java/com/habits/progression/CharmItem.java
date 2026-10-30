package com.habits.progression;

import com.habits.core.Rarity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A charm: equip it in the Charms tab of the Habits book. Its rolled lines live in {@link ProgRegistries#CHARM_DATA}. */
public class CharmItem extends Item {
    public CharmItem(Properties props) {
        super(props);
    }

    public static ItemStack create(CharmData data) {
        ItemStack s = new ItemStack(ProgRegistries.CHARM.get());
        s.set(ProgRegistries.CHARM_DATA.get(), data);
        return s;
    }

    @Nullable
    public static CharmData dataOf(ItemStack s) {
        return s.is(ProgRegistries.CHARM.get()) ? s.get(ProgRegistries.CHARM_DATA.get()) : null;
    }

    @Override
    public Component getName(ItemStack stack) {
        CharmData d = dataOf(stack);
        return d == null ? super.getName(stack) : d.name();
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        CharmData d = dataOf(stack);
        return d != null && d.rarity().foil();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        CharmData d = dataOf(stack);
        if (d == null) return;
        for (CharmData.Line l : d.lines()) lines.add(d.lineText(l).withStyle(s -> s.withColor(Rarity.COMMON.color).withItalic(false)));
        lines.add(Component.translatable("charm.habits.hint").withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));
    }
}
