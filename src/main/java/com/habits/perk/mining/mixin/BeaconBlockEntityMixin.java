package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Beacon Builder: beacons you placed reach further; Lv10 adds a level to the primary effect. */
@Mixin(BeaconBlockEntity.class)
public abstract class BeaconBlockEntityMixin {
    @ModifyArg(method = "applyEffects", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;inflate(D)Lnet/minecraft/world/phys/AABB;"))
    private static double habits$range(double range, @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos) {
        return range + MiningHooks.beaconBonus(level, pos);
    }

    @ModifyArg(method = "applyEffects", index = 2, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/effect/MobEffectInstance;<init>(Lnet/minecraft/core/Holder;IIZZ)V"))
    private static int habits$amp(int amp, @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos) {
        return amp + MiningHooks.beaconAmp(level, pos);
    }
}
