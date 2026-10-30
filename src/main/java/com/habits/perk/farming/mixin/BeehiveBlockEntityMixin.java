package com.habits.perk.farming.mixin;

import com.habits.perk.farming.FarmHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** BEEKEEPER. After vanilla's own +1 honey for a bee coming home with nectar. */
@Mixin(BeehiveBlockEntity.class)
public abstract class BeehiveBlockEntityMixin {
    @Inject(method = "releaseOccupant", at = @At("RETURN"))
    private static void habits$busyHive(Level level, BlockPos pos, BlockState state, BeehiveBlockEntity.Occupant occupant, List<Entity> out,
                                        BeehiveBlockEntity.BeeReleaseStatus status, BlockPos flower, CallbackInfoReturnable<Boolean> cir) {
        if (status == BeehiveBlockEntity.BeeReleaseStatus.HONEY_DELIVERED && cir.getReturnValueZ()) FarmHooks.beeReturned(level, pos);
    }
}
