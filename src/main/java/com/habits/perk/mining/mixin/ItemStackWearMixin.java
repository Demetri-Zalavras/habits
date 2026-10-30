package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

/** Goldsmith (golden tools wear 1/mult as fast) and Tireless Tools (a tool about to break is saved instead). Every
 * tool wear path ends in this overload. */
@Mixin(ItemStack.class)
public abstract class ItemStackWearMixin {
    private static final String HURT = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V";

    @ModifyVariable(method = HURT, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int habits$goldWear(int amount, int sameAmount, ServerLevel level, LivingEntity user, Consumer<Item> onBreak) {
        return user == null ? amount : MiningHooks.goldWear((ItemStack) (Object) this, amount, user);
    }

    /** Vanilla compares the stored damage with max right after storing it: a saved tool reports an unreachable max,
     * so the break branch is skipped. */
    @WrapOperation(method = HURT, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getMaxDamage()I"))
    private int habits$tireless(ItemStack stack, Operation<Integer> original, @Local(argsOnly = true) LivingEntity user) {
        int max = original.call(stack);
        return user != null && stack.getDamageValue() >= max && MiningHooks.saveTool(stack, user) ? Integer.MAX_VALUE : max;
    }
}
