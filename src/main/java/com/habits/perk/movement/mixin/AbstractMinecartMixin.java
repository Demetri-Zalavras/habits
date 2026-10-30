package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** RAIL_BARON: a higher rail speed cap for the rider, and no plain-track slowdown from coast_level. Server only. */
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartMixin {
    @ModifyReturnValue(method = "getMaxSpeedWithRail", at = @At("RETURN"))
    private double habits$railBaron(double vanilla) {
        return MoveHooks.cartCap(((AbstractMinecart) (Object) this).getFirstPassenger(), vanilla);
    }

    @WrapWithCondition(method = "moveAlongTrack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/vehicle/AbstractMinecart;applyNaturalSlowdown()V"))
    private boolean habits$coast(AbstractMinecart cart) {
        return !MoveHooks.coasts(cart);
    }
}
