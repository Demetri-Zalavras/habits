package com.habits.perk.farming.mixin;

import com.habits.perk.farming.FarmHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** GHOST_RIDER. The strider hook: fluid collision (LiquidBlock's stable top) and travel both ask this. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @ModifyReturnValue(method = "canStandOnFluid", at = @At("RETURN"))
    private boolean habits$ghostRider(boolean original, FluidState fluid) {
        return original || FarmHooks.standsOn((LivingEntity) (Object) this, fluid);
    }
}
