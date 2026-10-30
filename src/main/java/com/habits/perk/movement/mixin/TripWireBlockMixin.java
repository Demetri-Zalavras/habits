package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TripWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TOMB_RAIDER: tells the isIgnoringBlockTriggers hook which tripwire is checking entities. */
@Mixin(TripWireBlock.class)
public abstract class TripWireBlockMixin {
    @Inject(method = "checkPressed", at = @At("HEAD"))
    private void habits$trapAt(Level level, BlockPos pos, CallbackInfo ci) {
        MoveHooks.checkingTrap(pos);
    }

    @Inject(method = "checkPressed", at = @At("RETURN"))
    private void habits$trapDone(CallbackInfo ci) {
        MoveHooks.checkingTrap(null);
    }
}
