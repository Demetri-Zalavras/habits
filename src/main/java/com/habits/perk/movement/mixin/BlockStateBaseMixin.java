package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** PATHLESS: leaves and bamboo have no collision for the owner. JESUS_WALK: any water source block (plain, kelp,
 * seagrass, bubble column, waterlogged) gets a floor at its surface. Hot path: one instanceof pair and one fluid compare. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
    @Shadow
    public abstract Block getBlock();

    @ModifyReturnValue(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"))
    private VoxelShape habits$collision(VoxelShape vanilla, @Local(argsOnly = true) BlockGetter level, @Local(argsOnly = true) BlockPos pos,
                                        @Local(argsOnly = true) CollisionContext ctx) {
        VoxelShape v = MoveHooks.leafy(getBlock()) ? MoveHooks.leafPass(pos, ctx, vanilla) : vanilla;
        return MoveHooks.waterFloor((BlockState) (Object) this, level, pos, ctx, v);
    }

    /** TOMB_RAIDER: a plate or tripwire leaving its spot drops its player-placed mark. Server only (LevelChunk). */
    @Inject(method = "onRemove", at = @At("HEAD"))
    private void habits$trapRemoved(Level level, BlockPos pos, BlockState newState, boolean movedByPiston, CallbackInfo ci) {
        MoveHooks.trapRemoved((BlockState) (Object) this, level, pos, newState);
    }
}
