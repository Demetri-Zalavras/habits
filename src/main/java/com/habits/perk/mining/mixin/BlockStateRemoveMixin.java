package com.habits.perk.mining.mixin;

import com.habits.perk.mining.Doors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Doorkeeper: a door leaving its spot (break, explosion, /setblock) drops its locked mark. Server only (LevelChunk). */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateRemoveMixin {
    @Inject(method = "onRemove", at = @At("HEAD"))
    private void habits$doorRemoved(Level level, BlockPos pos, BlockState newState, boolean movedByPiston, CallbackInfo ci) {
        Doors.removed((BlockState) (Object) this, level, pos, newState);
    }
}
