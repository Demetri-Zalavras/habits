package com.habits.perk.combat.mixin;

import com.habits.perk.combat.Hooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Disarm's second trigger: reeling in a mob that holds an item. */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @Inject(method = "pullEntity(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void habits$reeled(Entity e, CallbackInfo ci) {
        Hooks.reeled((FishingHook) (Object) this, e);
    }
}
