package com.habits.perk.crafting;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Entry points for the crafting mixins (the mixin package may hold mixins only). */
public final class CraftHooks {
    private CraftHooks() {}

    /** {@code Item#onCraftedBy}: the stack is the real result, still full on a shift-click. */
    public static void crafted(Player p, ItemStack s) {
        Smith.crafted(p, s);
    }
}
