package com.habits.core;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** The skill card. Right-click adds it to the collection (needs the book in inventory). Inventory look: FX agent,
 * through IClientItemExtensions registered in client.fx (this class stays render-free). Cards are rare, so a dropped
 * card ignores fire and lava (fireResistant), explosions and cactus, and lasts longer on the ground. */
public class CardItem extends Item {
    public CardItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer sp) HabitsApi.addCardInHand(sp, hand);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public Component getName(ItemStack stack) {
        SkillDef def = CardItems.skillOf(stack);
        return def == null ? super.getName(stack) : def.displayName().copy().withColor(def.rarity().color);
    }

    @Override
    public boolean canBeHurtBy(ItemStack stack, DamageSource source) {
        return !source.is(DamageTypeTags.IS_EXPLOSION) && !source.is(DamageTypes.CACTUS);
    }

    @Override
    public int getEntityLifespan(ItemStack stack, Level level) {
        return CoreInit.CARD_LIFESPAN.get() * 20;
    }
}
