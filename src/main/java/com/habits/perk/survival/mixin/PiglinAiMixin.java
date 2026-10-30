package com.habits.perk.survival.mixin;

import com.habits.perk.survival.SurvivalHooks;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Gold Standard: piglins barter (admire) faster and stay angry at you for less time. */
@Mixin(PiglinAi.class)
public abstract class PiglinAiMixin {
    @ModifyConstant(method = "admireGoldItem", constant = @Constant(longValue = 119L))
    private static long habits$admire(long ticks, @Local(argsOnly = true) LivingEntity piglin) {
        return SurvivalHooks.admire(piglin, ticks);
    }

    /** Both 600-tick memories: ANGRY_AT and, with universal anger, UNIVERSAL_ANGER. */
    @ModifyConstant(method = "setAngerTarget", constant = @Constant(longValue = 600L))
    private static long habits$forgive(long ticks, @Local(argsOnly = true) LivingEntity target) {
        return SurvivalHooks.forgive(target, ticks);
    }
}
