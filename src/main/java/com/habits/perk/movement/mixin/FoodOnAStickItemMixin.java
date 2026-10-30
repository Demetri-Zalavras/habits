package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.FoodOnAStickItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** PIG_JOCKEY: a pig boost can skip the carrot on a stick wear. Server only (use() passes on the client). */
@Mixin(FoodOnAStickItem.class)
public abstract class FoodOnAStickItemMixin {
    @WrapOperation(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;hurtAndConvertOnBreak(ILnet/minecraft/world/level/ItemLike;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack habits$stickSaver(ItemStack stack, int amount, ItemLike into, LivingEntity user, EquipmentSlot slot, Operation<ItemStack> op) {
        return MoveHooks.stickSaved(user) ? stack : op.call(stack, amount, into, user, slot);
    }
}
