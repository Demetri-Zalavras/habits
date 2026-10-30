package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** PORTAL_HOPPER: the nether portal wait. Server only. */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {
    @ModifyReturnValue(method = "getPortalTransitionTime", at = @At("RETURN"))
    private int habits$portalHopper(int vanilla, @Local(argsOnly = true) Entity entity) {
        return MoveHooks.portalTime(entity, vanilla);
    }
}
