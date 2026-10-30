package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SpongeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Drain: sponges you placed search deeper and soak more blocks; Lv10 they stay dry. */
@Mixin(SpongeBlock.class)
public abstract class SpongeBlockMixin {
    @ModifyExpressionValue(method = "removeWaterBreadthFirstSearch", at = @At(value = "CONSTANT", args = "intValue=6"))
    private int habits$depth(int vanilla, @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos) {
        return MiningHooks.spongeDepth(level, pos, vanilla);
    }

    @ModifyExpressionValue(method = "removeWaterBreadthFirstSearch", at = @At(value = "CONSTANT", args = "intValue=65"))
    private int habits$limit(int vanilla, @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos) {
        return MiningHooks.spongeLimit(level, pos, vanilla);
    }

    @WrapOperation(method = "tryAbsorbWater", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean habits$dry(Level level, BlockPos pos, BlockState wet, int flags, Operation<Boolean> original) {
        return MiningHooks.spongeStaysDry(level, pos) || original.call(level, pos, wet, flags);
    }
}
