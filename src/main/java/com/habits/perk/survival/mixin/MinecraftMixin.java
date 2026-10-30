package com.habits.perk.survival.mixin;

import com.habits.perk.survival.SurvivalHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Spider Sense / Spotlight / Hex Ward: outlines only this player sees. One empty-map check per entity per frame. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @ModifyReturnValue(method = "shouldEntityAppearGlowing", at = @At("RETURN"))
    private boolean habits$senseGlow(boolean glow, @Local(argsOnly = true) Entity entity) {
        return glow || SurvivalHooks.glowing(entity);
    }
}
