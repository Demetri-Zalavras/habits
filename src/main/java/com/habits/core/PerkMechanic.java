package com.habits.core;

import net.minecraft.world.entity.player.Player;

/**
 * A custom perk mechanic (homing arrows, vein mining, double jump...). Register once from your subpackage init with
 * {@link PerkMechanics#register}. Most mechanics are plain event listeners that ask
 * {@link HabitsApi#mechanicValue(Player, net.minecraft.resources.ResourceLocation)} for the player's value; {@link #tick}
 * is a convenience for per-tick mechanics so nobody writes their own owned-skill scan.
 */
public interface PerkMechanic {
    /** What the level value means, one short phrase ("bend radius in blocks"). Shown in logs and /habits debug. */
    String valueMeaning();

    /**
     * Called every player tick (PlayerTickEvent.Post), on BOTH sides, only while the player has an active skill with this
     * mechanic. {@code hit} is the best one ({@link HabitsApi#mechanicHit}). Check {@code player.level().isClientSide()}.
     */
    default void tick(Player player, HabitsApi.PerkHit hit) {}
}
