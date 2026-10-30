package com.habits.perk.ranged.mixin;

import com.habits.perk.ranged.Shots;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shot state rides along in the projectile's save, so perks survive chunk unloads and server restarts. */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void habits$saveShot(CompoundTag tag, CallbackInfo ci) {
        Shots.save((Projectile) (Object) this, tag);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void habits$loadShot(CompoundTag tag, CallbackInfo ci) {
        Shots.load((Projectile) (Object) this, tag);
    }
}
