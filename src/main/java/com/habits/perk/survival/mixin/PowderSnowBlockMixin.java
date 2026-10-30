package com.habits.perk.survival.mixin;

import com.habits.perk.survival.SurvivalHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Snow Born max: walk on powder snow. Runs on both sides, so client movement and server checks agree. */
@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockMixin {
    @ModifyReturnValue(method = "canEntityWalkOnPowderSnow", at = @At("RETURN"))
    private static boolean habits$snowBorn(boolean walk, @Local(argsOnly = true) Entity entity) {
        return walk || SurvivalHooks.snowWalk(entity);
    }
}
