package com.habits.perk.survival.mixin;

import com.habits.perk.survival.client.SurvivalClient;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Pumpkin Head: the carved pumpkin overlay (the first texture overlay in renderCameraOverlays) fades with the level. */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @ModifyArg(method = "renderCameraOverlays", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/gui/Gui;renderTextureOverlay(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/resources/ResourceLocation;F)V"), index = 2)
    private float habits$pumpkin(float alpha) {
        return SurvivalClient.pumpkinAlpha(alpha);
    }
}
