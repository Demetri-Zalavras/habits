package com.habits.perk.survival.mixin;

import com.habits.perk.survival.SurvivalHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Natural Healer: marks FoodData's own regen heals so other heals (effects, perks, other mods) are left alone. */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @Shadow private int foodLevel;
    @Shadow private float saturationLevel;

    /** A scrap of saturation keeps the full-bar heal running at almost nothing: drop it so the plain heal takes over. */
    @Inject(method = "tick", at = @At("HEAD"))
    private void habits$saturationScrap(Player player, CallbackInfo ci) {
        if (SurvivalHooks.dropSaturationScrap(player, foodLevel, saturationLevel)) saturationLevel = 0;
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;heal(F)V"))
    private void habits$naturalRegen(Player player, float amount, Operation<Void> heal) {
        SurvivalHooks.naturalRegen = true;
        try {
            heal.call(player, amount);
        } finally {
            SurvivalHooks.naturalRegen = false;
        }
    }
}
