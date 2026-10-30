package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Beacon Builder: conduits you placed reach further (both the search box and the distance check). */
@Mixin(ConduitBlockEntity.class)
public abstract class ConduitBlockEntityMixin {
    @ModifyArg(method = "applyEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;inflate(D)Lnet/minecraft/world/phys/AABB;"))
    private static double habits$box(double range, @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos) {
        return range + MiningHooks.beaconBonus(level, pos);
    }

    @ModifyArg(method = "applyEffects", index = 1, at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;closerThan(Lnet/minecraft/core/Vec3i;D)Z"))
    private static double habits$reach(double range, @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos) {
        return range + MiningHooks.beaconBonus(level, pos);
    }
}
