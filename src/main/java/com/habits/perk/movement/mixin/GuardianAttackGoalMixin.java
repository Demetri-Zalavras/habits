package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** MONUMENT_DIVER: guardian (and elder guardian) beams take longer to fire at the owner. Server only. */
@Mixin(targets = "net.minecraft.world.entity.monster.Guardian$GuardianAttackGoal")
public abstract class GuardianAttackGoalMixin {
    @Shadow
    @Final
    private Guardian guardian;

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/monster/Guardian;getAttackDuration()I"))
    private int habits$beamCharge(int vanilla) {
        return MoveHooks.beamTime(guardian.getActiveAttackTarget(), vanilla);
    }
}
