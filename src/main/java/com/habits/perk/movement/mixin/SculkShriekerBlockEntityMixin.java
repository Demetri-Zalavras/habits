package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** DEEP_DARK_DELVER: shriekers never react to the owner (no shriek, no warning level, no warden). Server only. */
@Mixin(SculkShriekerBlockEntity.class)
public abstract class SculkShriekerBlockEntityMixin {
    @ModifyReturnValue(method = "tryGetPlayer", at = @At("RETURN"))
    private static ServerPlayer habits$ghost(ServerPlayer vanilla) {
        return MoveHooks.shriekerTarget(vanilla);
    }
}
