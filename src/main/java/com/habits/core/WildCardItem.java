package com.habits.core;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** The Wild Card (from the Mythical Wild Card skill). Right-click opens the picker client-side; the pick comes back as
 * {@link Payloads.UseWildCard} and {@link HabitsApi#useWildCard} spends it. */
public class WildCardItem extends Item {
    public WildCardItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) ClientHooks.each(ClientHooks::openWildCard);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public Component getName(ItemStack stack) {
        return super.getName(stack).copy().withColor(Rarity.MYTHICAL.color);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.habits.wild_card.tip").withColor(0xA0A0A0));
    }
}
