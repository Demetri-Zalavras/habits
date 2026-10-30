package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TOMB_RAIDER: tells the isIgnoringBlockTriggers hook which plate is counting entities. */
@Mixin(BasePressurePlateBlock.class)
public abstract class BasePressurePlateBlockMixin {
    @Inject(method = "checkPressed", at = @At("HEAD"))
    private void habits$trapAt(Entity entity, Level level, BlockPos pos, BlockState state, int signal, CallbackInfo ci) {
        MoveHooks.checkingTrap(pos);
    }

    @Inject(method = "checkPressed", at = @At("RETURN"))
    private void habits$trapDone(CallbackInfo ci) {
        MoveHooks.checkingTrap(null);
    }
}
