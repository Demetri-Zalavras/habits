package com.habits.perk.movement;

import com.habits.Habits;
import com.habits.core.SkillRegistry;
import com.habits.danger.Elites;
import com.habits.danger.Scaling;
import com.habits.progression.Drops;
import com.habits.progression.ProgressionApi;
import com.habits.progression.SigilData;
import com.habits.trigger.Triggers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Mythical movement cards: Blink and Frontier Lord. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class MythicMoveTests {
    private MythicMoveTests() {}

    /** Dash key with Blink: through a wall onto the floor beyond, 10 s cooldown at Lv1; no spot inside walls or lava. */
    @GameTest(template = "empty")
    public static void blinkPassesWallsOntoSafeFloor(GameTestHelper h) {
        for (int x = 0; x <= 5; x++) {
            h.setBlock(x, 0, 1, Blocks.STONE);
            for (int y = 1; y <= 3; y++) h.setBlock(x, y, 1, x == 1 && y < 3 ? Blocks.STONE : Blocks.AIR);
        }
        ServerPlayer p = MovementTests.player(h, new Vec3(0.5, 1, 1.5), 1, Mech.BLINK);
        p.setYRot(-90); // facing +x
        p.setXRot(0);
        MoveState s = MovementTests.state(p);
        long now = p.level().getGameTime();
        // a fake player's connection ignores teleports, so check the landing spot the dash would use
        Vec3 spot = Actions.blinkSpot(p, s.v(Mech.BLINK), (int) s.p(Mech.BLINK, "floor"));
        Vec3 want = h.absoluteVec(new Vec3(4.5, 1, 1.5));
        h.assertTrue(spot != null && spot.distanceTo(want) < 1e-6, "lands 4 blocks on, past the wall: " + spot + " want " + want);
        h.assertTrue(Actions.dash(p, s), "blink fires");
        h.assertTrue(p.getDeltaMovement().horizontalDistance() < 1e-6, "no dash burst while Blink is on");
        h.assertValueEqual(s.nextDash - now, 200L, "Lv1 cooldown 10 s");
        h.assertFalse(Actions.dash(p, s), "cooldown blocks a second blink");

        ServerPlayer q = MovementTests.player(h, new Vec3(0.5, 1, 1.5), 1, Mech.BLINK);
        q.setYRot(-90);
        q.setXRot(0);
        h.assertTrue(Actions.blinkSpot(q, 2, 8) != null, "open floor 2 blocks on");
        h.setBlock(2, 1, 1, Blocks.STONE);
        h.setBlock(2, 2, 1, Blocks.STONE);
        h.assertTrue(Actions.blinkSpot(q, 2, 8) == null, "never into a wall");
        h.setBlock(2, 2, 1, Blocks.AIR);
        h.setBlock(2, 1, 1, Blocks.LAVA);
        h.assertTrue(Actions.blinkSpot(q, 2, 8) == null, "never into lava");
        Mv.TEST_PLAYERS.remove(p.getUUID());
        Mv.TEST_PLAYERS.remove(q.getUUID());
        h.succeed();
    }

    /** long_flight carries the glide distance, so Blink's min of 10,000 only passes a long enough flight. */
    @GameTest(template = "empty")
    public static void blinkFoundByTenThousandBlockGlide(GameTestHelper h) {
        ServerPlayer p = MovementTests.player(h, new Vec3(1.5, 1, 1.5));
        Triggers.TEST_PLAYERS.add(p.getUUID());
        double min = SkillRegistry.get(Mech.BLINK.skillId).trigger().min();
        double[] got = {-1};
        Triggers.Spy spy = (who, type, ctx) -> {
            if (who == p && Habits.id("long_flight").equals(ctx.target())) got[0] = ctx.amount();
        };
        Triggers.SPIES.add(spy);
        try {
            MoveState s = MovementTests.state(p);
            p.setOnGround(true);
            s.flightDist = 10500;
            Trackers.second(p, s, 100);
            h.assertTrue(got[0] == 10500 && got[0] >= min, "a 10,500 block glide counts: " + got[0] + " vs min " + min);
            s.flightDist = 5000;
            Trackers.second(p, s, 120);
            h.assertTrue(got[0] == 5000 && got[0] < min, "a 5,000 block glide does not: " + got[0]);
        } finally {
            Triggers.SPIES.remove(spy);
            Triggers.TEST_PLAYERS.remove(p.getUUID());
            Mv.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }

    static Zombie zombie(GameTestHelper h, double dist) {
        Zombie z = EntityType.ZOMBIE.create(h.getLevel());
        Vec3 at = h.absoluteVec(new Vec3(1.5, 1, 1.5));
        z.moveTo(at.x, at.y, at.z, 0, 0);
        z.setNoAi(true);
        if (dist >= 0) {
            z.addTag(Scaling.TEST_TAG);
            z.getPersistentData().putDouble(Scaling.TEST_DIST, dist);
        }
        h.getLevel().addFreshEntity(z);
        return z;
    }

    static float incoming(ServerPlayer p, Zombie from) {
        var e = new LivingIncomingDamageEvent(p, new DamageContainer(p.damageSources().mobProjectile(from, from), 10));
        NeoForge.EVENT_BUS.post(e);
        return e.getAmount();
    }

    /** Lv10 halves the zone's extra damage; 3-affix elites in Frontier III fire elite_far_kill and sometimes give 2 sigils. */
    @GameTest(template = "empty")
    public static void frontierLordTamesZoneAndElites(GameTestHelper h) {
        ServerPlayer p = MovementTests.player(h, new Vec3(1.5, 1, 1.5), 10, Mech.FRONTIER_LORD);
        ServerPlayer plain = MovementTests.player(h, new Vec3(1.5, 1, 1.5));
        Zombie far = zombie(h, 5000);
        float m = far.getPersistentData().getFloat(Scaling.MARK);
        h.assertTrue(m > 1, "zombie scaled: " + m);
        float hit = incoming(plain, far), cut = incoming(p, far);
        h.assertTrue(Math.abs(hit - 10 * m) < 1e-3, "no perk: full scaling " + hit);
        h.assertTrue(Math.abs(cut - 10 * (1 + (m - 1) * 0.5f)) < 1e-3, "Lv10 halves the extra: " + cut);

        Triggers.TEST_PLAYERS.add(p.getUUID());
        Drops.TEST_PLAYERS.add(p.getUUID());
        int[] fired = {0};
        Triggers.Spy spy = (who, type, ctx) -> {
            if (who == p && Habits.id("elite_far_kill").equals(ctx.target()) && ctx.amount() == 3) fired[0]++;
        };
        Triggers.SPIES.add(spy);
        int kills = 16;
        try {
            int three = Elites.Affix.FIERY.bit() | Elites.Affix.FROZEN.bit() | Elites.Affix.SHOCKING.bit();
            for (int i = 0; i < kills; i++) {
                Zombie e = zombie(h, -1);
                Elites.make(e, three, 3); // Frontier III
                e.hurt(h.getLevel().damageSources().playerAttack(p), 1000);
            }
            Zombie near = zombie(h, -1);
            Elites.make(near, three, 1); // Frontier I: not far enough
            near.hurt(h.getLevel().damageSources().playerAttack(p), 1000);
        } finally {
            Triggers.SPIES.remove(spy);
            Triggers.TEST_PLAYERS.remove(p.getUUID());
            Drops.TEST_PLAYERS.remove(p.getUUID());
            Mv.TEST_PLAYERS.remove(p.getUUID());
            Mv.TEST_PLAYERS.remove(plain.getUUID());
        }
        h.assertValueEqual(fired[0], kills, "elite_far_kill per Frontier III kill only");
        int stored = 0;
        for (SigilData kind : SigilData.ALL) stored += ProgressionApi.sigilCount(p, kind);
        // 17 kills, at least one extra at 50% (all 16 far kills missing it: 1 in 65,000)
        h.assertTrue(stored > kills + 1, "second sigils: " + stored + " from " + (kills + 1) + " elites");
        h.succeed();
    }
}
