package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** DEEP_DARK_DELVER: wardens hear the owner over a shorter range. Server only. */
@Mixin(targets = "net.minecraft.world.entity.monster.warden.Warden$VibrationUser")
public abstract class WardenVibrationUserMixin {
    @Shadow
    @Final
    private PositionSource positionSource;

    @ModifyReturnValue(method = "canReceiveVibration", at = @At("RETURN"))
    private boolean habits$hearing(boolean vanilla, @Local(argsOnly = true) ServerLevel level, @Local(argsOnly = true) BlockPos at,
                                   @Local(argsOnly = true) GameEvent.Context ctx) {
        return vanilla && MoveHooks.wardenHears(positionSource, level, at, ctx, ((VibrationSystem.User) this).getListenerRadius());
    }
}
