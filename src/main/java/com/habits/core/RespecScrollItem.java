package com.habits.core;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** Scroll of Unlearning: right-click to pick specializations again ({@link Specs#startRespec}). Consumed on use. */
public class RespecScrollItem extends Item {
    public RespecScrollItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) {
            if (!Specs.startRespec(sp)) {
                sp.displayClientMessage(Component.translatable("habits.msg.respec_nothing"), true);
                return InteractionResultHolder.fail(stack);
            }
            stack.consume(1, player);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public Component getName(ItemStack stack) {
        return super.getName(stack).copy().withColor(Rarity.LEGENDARY.color);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.habits.respec_scroll.tip").withColor(0xA0A0A0));
        lines.add(Component.translatable("item.habits.respec_scroll.warn").withColor(0xE05050));
    }
}
