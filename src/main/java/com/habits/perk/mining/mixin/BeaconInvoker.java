package com.habits.perk.mining.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** GameTest access to the beacon's private effect pass (MiningMixinTests.beaconBuilderRange). */
@Mixin(BeaconBlockEntity.class)
public interface BeaconInvoker {
    @Invoker("applyEffects")
    static void habits$applyEffects(Level level, BlockPos pos, int beaconLevel, Holder<MobEffect> primary, Holder<MobEffect> secondary) {
        throw new AssertionError();
    }
}
