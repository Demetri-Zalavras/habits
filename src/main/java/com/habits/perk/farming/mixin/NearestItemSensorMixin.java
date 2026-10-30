package com.habits.perk.farming.mixin;

import com.habits.perk.farming.FarmHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.sensing.NearestItemSensor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ALLAY_PAL: a pal's allay sees items further away (same search as vanilla, wider box). */
@Mixin(NearestItemSensor.class)
public abstract class NearestItemSensorMixin {
    @Inject(method = "doTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;)V", at = @At("HEAD"), cancellable = true)
    private void habits$allayRange(ServerLevel level, Mob mob, CallbackInfo ci) {
        if (FarmHooks.allaySense(level, mob)) ci.cancel();
    }
}
