package com.habits.perk.survival.mixin;

import com.habits.perk.survival.SurvivalHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hard Hat max: an anvil that hit you does not chip. Only runs when vanilla already rolled a chip. */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
    @WrapOperation(method = "causeFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/AnvilBlock;damage(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState habits$noChip(BlockState state, Operation<BlockState> original) {
        return SurvivalHooks.keepAnvil((FallingBlockEntity) (Object) this) ? state : original.call(state);
    }
}
