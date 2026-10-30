package com.habits.perk.utility.mixin;

import com.habits.perk.utility.client.UtilityClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** rummager: tints chest slots holding something you carry. One boolean check per slot when the skill is off. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin {
    @Shadow @Final protected AbstractContainerMenu menu;

    @Inject(method = "renderSlot", at = @At("RETURN"))
    private void habits$rummagerTint(GuiGraphics g, Slot slot, CallbackInfo ci) {
        UtilityClient.rummagerTint(g, menu, slot);
    }
}
