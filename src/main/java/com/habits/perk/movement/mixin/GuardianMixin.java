package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** MONUMENT_DIVER: the client beam animation charges as slowly as the server's attack (GuardianAttackGoalMixin). */
@Mixin(Guardian.class)
public abstract class GuardianMixin {
    @ModifyExpressionValue(method = {"aiStep", "getAttackAnimationScale"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/monster/Guardian;getAttackDuration()I"))
    private int habits$beamAnimation(int vanilla) {
        return MoveHooks.beamTime(((Guardian) (Object) this).getActiveAttackTarget(), vanilla);
    }
}
