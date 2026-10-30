package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Icicle Drop: stalactites you drop credit their hits to you. */
@Mixin(PointedDripstoneBlock.class)
public abstract class PointedDripstoneBlockMixin {
    @ModifyReturnValue(method = "getFallDamageSource", at = @At("RETURN"))
    private DamageSource habits$credit(DamageSource src, @Local(argsOnly = true) Entity entity) {
        return MiningHooks.icicleSource(entity, src);
    }
}
