package com.habits.perk.farming.mixin;

import com.habits.perk.farming.FarmHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** SHOULDER_LOOKOUT (client): the outline is drawn only on this client, so nobody else sees a glowing mob. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @ModifyReturnValue(method = "shouldEntityAppearGlowing", at = @At("RETURN"))
    private boolean habits$lookout(boolean original, Entity entity) {
        return original || FarmHooks.lookoutGlow(entity);
    }
}
