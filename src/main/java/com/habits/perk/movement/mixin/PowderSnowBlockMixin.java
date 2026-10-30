package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.PowderSnowBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** SNOWSHOES: owners walk on top of powder snow without leather boots. */
@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockMixin {
    @ModifyReturnValue(method = "canEntityWalkOnPowderSnow", at = @At("RETURN"))
    private static boolean habits$snowshoes(boolean vanilla, @Local(argsOnly = true) Entity entity) {
        return MoveHooks.snowWalk(entity, vanilla);
    }
}
