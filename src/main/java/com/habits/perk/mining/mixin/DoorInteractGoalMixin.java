package com.habits.perk.mining.mixin;

import com.habits.perk.mining.Doors;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Doorkeeper: zombies (BreakDoorGoal) and raiders (OpenDoorGoal) never start on a door a Doorkeeper placed. */
@Mixin(DoorInteractGoal.class)
public abstract class DoorInteractGoalMixin {
    @Shadow
    protected Mob mob;
    @Shadow
    protected BlockPos doorPos;

    @ModifyReturnValue(method = "canUse", at = @At("RETURN"))
    private boolean habits$lockedDoor(boolean vanilla) {
        return vanilla && !Doors.mobBlocked(mob, mob.level(), doorPos);
    }
}
