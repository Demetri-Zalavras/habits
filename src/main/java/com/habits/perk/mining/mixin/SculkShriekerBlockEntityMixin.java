package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Muffled Tools Lv10: shriekers never warn, so they never call the warden on you. */
@Mixin(SculkShriekerBlockEntity.class)
public abstract class SculkShriekerBlockEntityMixin {
    @Inject(method = "tryShriek", at = @At("HEAD"), cancellable = true)
    private void habits$noWarden(ServerLevel level, ServerPlayer player, CallbackInfo ci) {
        if (MiningHooks.noWarden(player)) ci.cancel();
    }
}
