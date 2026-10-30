package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Lapis Scholar: the orb's xp repairs the held tool first and only the rest reaches Mending and the player.
 * Hooked at the head of repairPlayerItems, not in playerTouch: Clumps cancels playerTouch and calls
 * repairPlayerItems itself. Its recursive calls with leftover xp find the tool already repaired, so they are no-ops. */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
    @ModifyVariable(method = "repairPlayerItems", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int habits$lapisScholar(int xp, ServerPlayer player) {
        return MiningHooks.lapisRepair(player, xp);
    }
}
