package com.habits.perk.building.mixin;

import com.habits.perk.building.client.QuickHands;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Quick Hands: shortens the 4 tick repeat delay set at the start of every right-click. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow private int rightClickDelay;

    @Inject(method = "startUseItem", at = @At("RETURN"))
    private void habits$quickHands(CallbackInfo ci) {
        rightClickDelay = QuickHands.delay((Minecraft) (Object) this, rightClickDelay);
    }
}
