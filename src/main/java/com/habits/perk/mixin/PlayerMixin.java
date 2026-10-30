package com.habits.perk.mixin;

import com.habits.perk.StatHooks;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** HUNGER_SAVE. */
@Mixin(Player.class)
public abstract class PlayerMixin {
    @ModifyVariable(method = "causeFoodExhaustion", at = @At("HEAD"), argsOnly = true)
    private float habits$hungerSave(float exhaustion) {
        return StatHooks.exhaustion((Player) (Object) this, exhaustion);
    }
}
