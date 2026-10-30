package com.habits.perk.mixin;

import com.habits.perk.StatHooks;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TRADE_DISCOUNT. Special prices reset when trading stops, so this applies once per trading session. */
@Mixin(Villager.class)
public abstract class VillagerMixin {
    @Inject(method = "updateSpecialPrices", at = @At("TAIL"))
    private void habits$tradeDiscount(Player player, CallbackInfo ci) {
        StatHooks.tradeDiscount(((Villager) (Object) this).getOffers(), player);
    }
}
