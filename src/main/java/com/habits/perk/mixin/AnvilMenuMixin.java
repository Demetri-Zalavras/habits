package com.habits.perk.mixin;

import com.habits.perk.StatHooks;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ANVIL_DISCOUNT. ItemCombinerMenu.player is opened by the access transformer. */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
    @Shadow @Final private DataSlot cost;

    @Inject(method = "createResult", at = @At("TAIL"))
    private void habits$anvilDiscount(CallbackInfo ci) {
        cost.set(StatHooks.anvilCost(((AnvilMenu) (Object) this).player, cost.get()));
    }
}
