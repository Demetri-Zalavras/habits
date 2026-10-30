package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Web Cutter: cobwebs slow you value% less; at 100% they don't hold you at all (both sides, movement). */
@Mixin(WebBlock.class)
public abstract class WebBlockMixin {
    @WrapOperation(method = "entityInside", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;makeStuckInBlock(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/Vec3;)V"))
    private void habits$webSlow(Entity entity, BlockState state, Vec3 slow, Operation<Void> original) {
        Vec3 v = MiningHooks.webSlow(entity, slow);
        if (v != null) original.call(entity, state, v);
    }
}
