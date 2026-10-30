package com.habits.perk.survival.mixin;

import com.habits.perk.survival.SurvivalHooks;
import com.mojang.datafixers.util.Pair;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

/** Vanishing Act max: a player who starts tracking you while you are invisible gets no armor in the pairing data. */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {
    @Shadow @Final private Entity entity;

    @ModifyArg(method = "sendPairingData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/game/ClientboundSetEquipmentPacket;<init>(ILjava/util/List;)V"), index = 1)
    private List<Pair<EquipmentSlot, ItemStack>> habits$hideArmor(List<Pair<EquipmentSlot, ItemStack>> list) {
        return SurvivalHooks.hideArmor((LivingEntity) entity, list);
    }
}
