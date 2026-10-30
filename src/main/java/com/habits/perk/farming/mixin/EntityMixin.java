package com.habits.perk.farming.mixin;

import com.habits.perk.farming.FarmHooks;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GHOST_RIDER tier 3: the mount standing in the lava's top half does not catch fire. */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "lavaHurt", at = @At("HEAD"), cancellable = true)
    private void habits$lavaProofMount(CallbackInfo ci) {
        if (FarmHooks.lavaProof((Entity) (Object) this)) ci.cancel();
    }
}
