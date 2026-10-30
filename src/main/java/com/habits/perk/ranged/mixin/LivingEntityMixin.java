package com.habits.perk.ranged.mixin;

import com.habits.perk.ranged.Impacts;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Breeze Breaker: a raised shield doesn't stop our breaking projectile. Runs only when vanilla says "blocked". */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "isDamageSourceBlocked", at = @At("RETURN"), cancellable = true)
    private void habits$shieldBreak(DamageSource src, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && Impacts.breaks(src.getDirectEntity())) cir.setReturnValue(false);
    }
}
