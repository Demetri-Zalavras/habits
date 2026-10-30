package com.habits.perk.mixin;

import com.habits.perk.StatHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

/** DURABILITY_SAVE. Every tool and armor wear path ends in this overload. */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @ModifyVariable(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int habits$durabilitySave(int amount, int sameAmount, ServerLevel level, LivingEntity user, Consumer<Item> onBreak) {
        return user == null ? amount : StatHooks.durability((ItemStack) (Object) this, amount, user);
    }
}
