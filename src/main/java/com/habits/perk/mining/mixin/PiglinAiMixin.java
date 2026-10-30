package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Golden Truce: mining gold or opening chests near piglins does not anger them. */
@Mixin(PiglinAi.class)
public abstract class PiglinAiMixin {
    @Inject(method = "angerNearbyPiglins", at = @At("HEAD"), cancellable = true)
    private static void habits$truce(Player player, boolean angerOnlyIfCanSee, CallbackInfo ci) {
        if (MiningHooks.calmPiglins(player)) ci.cancel();
    }
}
