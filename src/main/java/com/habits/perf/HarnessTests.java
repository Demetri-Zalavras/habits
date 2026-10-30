package com.habits.perf;

import com.habits.Habits;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** The harness player (PerfHarness and PlaySim) must reach the triggers a plain FakePlayer never does. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class HarnessTests {
    private HarnessTests() {}

    @GameTest(template = "empty")
    public static void harnessPlayerTakesDamageAndAwardsStats(GameTestHelper h) {
        ServerPlayer p = new PlaySim.SimPlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-harness"));
        p.setGameMode(GameType.SURVIVAL);
        Triggers.TEST_PLAYERS.add(p.getUUID());
        Set<TriggerType> seen = new HashSet<>();
        Triggers.Spy spy = (who, type, ctx) -> { if (who == p) seen.add(type); };
        Triggers.SPIES.add(spy);
        try {
            float hp = p.getHealth();
            p.hurt(h.getLevel().damageSources().generic(), 3);
            h.assertTrue(p.getHealth() < hp, "harness player takes damage");
            h.assertTrue(seen.contains(TriggerType.DAMAGE_TAKEN), "damage_taken fired");
            p.awardStat(Stats.JUMP, 1);
            h.assertValueEqual(p.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP)), 1, "stat award recorded");
        } finally {
            Triggers.SPIES.remove(spy);
            Triggers.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }
}
