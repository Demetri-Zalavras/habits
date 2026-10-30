package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Snowplow Lv10: walk on powder snow without leather boots (runs on both sides, the client predicts movement). */
@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockMixin {
    @ModifyReturnValue(method = "canEntityWalkOnPowderSnow", at = @At("RETURN"))
    private static boolean habits$powderWalk(boolean walk, @Local(argsOnly = true) Entity entity) {
        return walk || MiningHooks.powderWalk(entity);
    }
}
