package com.habits.perk.movement;

import com.habits.danger.DangerApi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** Frontier Lord (Mythical): danger's hooks. Cuts zone damage scaling, adds a second elite sigil, fires elite_far_kill. */
final class FrontierLord implements DangerApi.Hooks {
    @Override
    public double scalingCut(Player victim) {
        if (!Mv.ok(victim)) return 0;
        MoveState s = MoveState.of(victim);
        return s.has(Mech.FRONTIER_LORD) ? s.v(Mech.FRONTIER_LORD) / 100.0 : 0;
    }

    @Override
    public double extraSigilChance(ServerPlayer killer) {
        if (!Mv.ok(killer)) return 0;
        MoveState s = MoveState.of(killer);
        return s.has(Mech.FRONTIER_LORD) ? s.p(Mech.FRONTIER_LORD, "sigil") / 100.0 : 0;
    }

    @Override
    public void eliteKilled(ServerPlayer killer, int affixes, boolean far) {
        if (far) Mv.fire(killer, "elite_far_kill", affixes); // the skill's min sets how many affixes count
    }
}
