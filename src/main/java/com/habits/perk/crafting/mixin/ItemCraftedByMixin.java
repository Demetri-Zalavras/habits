package com.habits.perk.crafting.mixin;

import com.habits.perk.crafting.CraftHooks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gear ratings and Masterworks: vanilla calls this on the real crafted stack, before a shift-click moves it. */
@Mixin(Item.class)
public abstract class ItemCraftedByMixin {
    @Inject(method = "onCraftedBy", at = @At("HEAD"))
    private void habits$crafted(ItemStack stack, Level level, Player player, CallbackInfo ci) {
        CraftHooks.crafted(player, stack);
    }
}
