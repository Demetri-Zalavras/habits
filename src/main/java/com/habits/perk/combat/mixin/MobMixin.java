package com.habits.perk.combat.mixin;

import com.habits.perk.combat.Hooks;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Daze (Concussion, Exterminator flip, Charge Breaker stun): a dazed mob skips its whole AI step, brain mobs included. */
@Mixin(Mob.class)
public abstract class MobMixin {
    @Inject(method = "serverAiStep()V", at = @At("HEAD"), cancellable = true)
    private void habits$daze(CallbackInfo ci) {
        if (Hooks.dazed((Mob) (Object) this)) ci.cancel();
    }
}
