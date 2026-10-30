package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Client: RUNNERS_LUNGS (sprint at any hunger), PORTAL_HOPPER (no portal nausea at calm_level) and STEADY_STRIDE
 * (the 0.2 input scale while using an item). */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @ModifyReturnValue(method = "hasEnoughFoodToStartSprinting", at = @At("RETURN"))
    private boolean habits$emptyBelly(boolean vanilla) {
        return MoveHooks.sprintAnyFood((LocalPlayer) (Object) this, vanilla);
    }

    @ModifyConstant(method = "aiStep", constant = @Constant(floatValue = 0.2F))
    private float habits$steadyStride(float vanilla) {
        return MoveHooks.useSlow((LocalPlayer) (Object) this, vanilla);
    }

    @ModifyVariable(method = "handleConfusionTransitionEffect", at = @At("HEAD"), argsOnly = true)
    private boolean habits$calmPortal(boolean confusion) {
        return MoveHooks.portalNausea((LocalPlayer) (Object) this, confusion);
    }
}
