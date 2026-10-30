package com.habits.perk.survival.mixin;

import com.habits.perk.survival.SurvivalHooks;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Web Spinner / Bramble Walker: cobweb and berry bush slowdown. Runs on both sides. */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @ModifyVariable(method = "makeStuckInBlock", at = @At("HEAD"), argsOnly = true)
    private Vec3 habits$unstuck(Vec3 multiplier, @Local(argsOnly = true) BlockState state) {
        return SurvivalHooks.stuck((Entity) (Object) this, state, multiplier);
    }
}
