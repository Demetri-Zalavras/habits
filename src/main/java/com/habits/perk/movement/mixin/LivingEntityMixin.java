package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** SPIDER_CLIMB (any wall is climbable while sneaking), CHAIN_CLIMBER / CHIMNEY_CLIMBER (through the same hook),
 * ICE_SKATER (ice grips like ground) and FIREMAN_POLE (fast ladder slide). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @ModifyReturnValue(method = "onClimbable", at = @At("RETURN"))
    private boolean habits$wallClimb(boolean vanilla) {
        return MoveHooks.climbable((LivingEntity) (Object) this, vanilla);
    }

    @ModifyReturnValue(method = "handleOnClimbable", at = @At("RETURN"))
    private Vec3 habits$pole(Vec3 v) {
        return MoveHooks.pole((LivingEntity) (Object) this, v);
    }

    @ModifyExpressionValue(method = "travel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getFriction(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)F"))
    private float habits$iceGrip(float vanilla) {
        return MoveHooks.friction((LivingEntity) (Object) this, vanilla);
    }
}
