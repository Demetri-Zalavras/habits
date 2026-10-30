package com.habits.danger;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * The calls other packages may make into danger. One hook set, filled by perk.movement (Frontier Lord); danger reads it
 * where zone scaling lands on a player and where an elite's loot is rolled. Server thread only.
 */
public final class DangerApi {
    private DangerApi() {}

    public interface Hooks {
        /** Share 0..1 of the zone damage scaling taken off hits on this player (0.5 = a 3x mob hits like 2x). */
        default double scalingCut(Player victim) { return 0; }

        /** Chance 0..1 that an elite this player killed gives a second sigil. */
        default double extraSigilChance(ServerPlayer killer) { return 0; }

        /** An elite with {@code affixes} affixes died to this player; {@code far} = the last Frontier tier or the End. */
        default void eliteKilled(ServerPlayer killer, int affixes, boolean far) {}
    }

    static volatile Hooks hooks = new Hooks() {};

    public static void setHooks(Hooks h) {
        hooks = h;
    }
}
