package com.habits.perk.utility.mixin;

import com.habits.perk.utility.UtilityHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** penny_pincher, client half: the label shows "Too Expensive!" at the same raised cap the server uses. */
@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {
    @ModifyConstant(method = "renderLabels", constant = @Constant(intValue = 40))
    private int habits$pennyCap(int cap) {
        return UtilityHooks.anvilCap(Minecraft.getInstance().player, ((AnvilScreen) (Object) this).getMenu().getSlot(0).getItem());
    }
}
