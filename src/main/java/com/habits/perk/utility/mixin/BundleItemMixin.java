package com.habits.perk.utility.mixin;

import com.habits.perk.utility.UtilityHooks;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BundleItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** bundle_up: the two bundle clicks know the player; they set the capacity BundleContentsMixin reads. */
@Mixin(BundleItem.class)
public abstract class BundleItemMixin {
    @Inject(method = {"overrideStackedOnOther", "overrideOtherStackedOnMe"}, at = @At("HEAD"))
    private void habits$bundleBegin(CallbackInfoReturnable<Boolean> cir, @Local(argsOnly = true) Player player) {
        UtilityHooks.bundleBegin(player);
    }

    @Inject(method = {"overrideStackedOnOther", "overrideOtherStackedOnMe"}, at = @At("RETURN"))
    private void habits$bundleEnd(CallbackInfoReturnable<Boolean> cir) {
        UtilityHooks.bundleEnd();
    }
}
