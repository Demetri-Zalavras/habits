package com.habits.perk.farming.mixin;

import com.habits.perk.farming.FarmHooks;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** LEASH_MASTER. Runs only past vanilla's 10 block snap distance. */
@Mixin(Mob.class)
public abstract class MobMixin {
    @Inject(method = "leashTooFarBehaviour", at = @At("HEAD"), cancellable = true)
    private void habits$longLeash(CallbackInfo ci) {
        if (FarmHooks.keepLeash((Mob) (Object) this)) ci.cancel();
    }
}
