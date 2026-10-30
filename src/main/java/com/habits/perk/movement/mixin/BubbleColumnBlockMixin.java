package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** JESUS_WALK: a bubble column does not push a player Water Walk holds on its surface (both sides). */
@Mixin(BubbleColumnBlock.class)
public abstract class BubbleColumnBlockMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void habits$surfaceHeld(BlockState state, Level level, BlockPos pos, Entity entity, CallbackInfo ci) {
        if (MoveHooks.surfaceHeld(entity, state, level, pos)) ci.cancel();
    }
}
