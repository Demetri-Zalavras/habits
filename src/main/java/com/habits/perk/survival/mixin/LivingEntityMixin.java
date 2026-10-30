package com.habits.perk.survival.mixin;

import com.habits.perk.survival.SurvivalHooks;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

/** Vanishing Act max: equipment changes broadcast to other players leave the armor out while invisible. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @ModifyArg(method = "handleEquipmentChanges", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundSetEquipmentPacket;<init>(ILjava/util/List;)V"), index = 1)
    private List<Pair<EquipmentSlot, ItemStack>> habits$hideArmor(List<Pair<EquipmentSlot, ItemStack>> list) {
        return SurvivalHooks.hideArmor((LivingEntity) (Object) this, list);
    }
}
