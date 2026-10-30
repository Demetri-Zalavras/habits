package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Client: NIGHT_OWL adds to the brightness (gamma) the lightmap uses. One static float read per lightmap update. */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
    // ordinal 0 is the darkness effect scale, ordinal 1 is options.gamma()
    @ModifyExpressionValue(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 1))
    private float habits$nightOwl(float gamma) {
        return gamma + MoveHooks.nightGamma;
    }
}
