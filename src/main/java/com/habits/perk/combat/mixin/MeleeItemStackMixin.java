package com.habits.perk.combat.mixin;

import com.habits.perk.combat.Hooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Improviser: a non-weapon takes no wear from hitting (Player.attack's only wear-on-hit call). */
@Mixin(ItemStack.class)
public abstract class MeleeItemStackMixin {
    @Inject(method = "postHurtEnemy(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"), cancellable = true)
    private void habits$improviser(LivingEntity target, Player attacker, CallbackInfo ci) {
        if (Hooks.noHitWear((ItemStack) (Object) this, attacker)) ci.cancel();
    }
}
