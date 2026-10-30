package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** PIGLIN_DISGUISE: piglins see the owner as wearing gold. Server only. */
@Mixin(PiglinAi.class)
public abstract class PiglinAiMixin {
    @ModifyReturnValue(method = "isWearingGold", at = @At("RETURN"))
    private static boolean habits$disguise(boolean vanilla, @Local(argsOnly = true) LivingEntity entity) {
        return MoveHooks.wearsGold(entity, vanilla);
    }
}
