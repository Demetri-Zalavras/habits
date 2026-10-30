package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.TridentItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** THUNDER_SPIN: riptide works out of water and rain for owners (with a cooldown set on the dry launch). */
@Mixin(TridentItem.class)
public abstract class TridentItemMixin {
    @ModifyExpressionValue(method = "use", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isInWaterOrRain()Z"))
    private boolean habits$dryUse(boolean vanilla, @Local(argsOnly = true) Player player) {
        return MoveHooks.dryRiptide(player, vanilla, false);
    }

    @ModifyExpressionValue(method = "releaseUsing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isInWaterOrRain()Z"))
    private boolean habits$dryRelease(boolean vanilla, @Local(argsOnly = true) LivingEntity user) {
        return MoveHooks.dryRiptide(user, vanilla, true);
    }
}
