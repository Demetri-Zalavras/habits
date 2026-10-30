package com.habits.perk.mining.mixin;

import com.habits.perk.mining.Doors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Doorkeeper: villagers, piglins and raiders can't open a door a Doorkeeper placed (InteractWithDoor, OpenDoorGoal). */
@Mixin(DoorBlock.class)
public abstract class DoorBlockMixin {
    @Inject(method = "setOpen", at = @At("HEAD"), cancellable = true)
    private void habits$mobOpen(Entity entity, Level level, BlockState state, BlockPos pos, boolean open, CallbackInfo ci) {
        if (open && Doors.mobBlocked(entity, level, pos)) ci.cancel();
    }
}
