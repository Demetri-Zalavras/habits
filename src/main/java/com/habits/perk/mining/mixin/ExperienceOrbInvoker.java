package com.habits.perk.mining.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** GameTest access to the orb's private repair pass, the call Clumps makes (MiningMixinTests.lapisScholarClumpsPath). */
@Mixin(ExperienceOrb.class)
public interface ExperienceOrbInvoker {
    @Invoker("repairPlayerItems")
    int habits$repairPlayerItems(ServerPlayer player, int xp);
}
