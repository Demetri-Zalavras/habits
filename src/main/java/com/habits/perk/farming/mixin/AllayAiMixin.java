package com.habits.perk.farming.mixin;

import com.habits.perk.farming.FarmHooks;
import net.minecraft.world.entity.animal.allay.AllayAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** ALLAY_PAL: lets an allay walk to an item its (wider) sensor found. Normal allays never sense past 32, so nothing else changes. */
@Mixin(AllayAi.class)
public abstract class AllayAiMixin {
    @ModifyConstant(method = "initIdleActivity", constant = @Constant(intValue = 32))
    private static int habits$walkFurther(int vanilla) {
        return FarmHooks.ALLAY_MAX_WALK;
    }
}
