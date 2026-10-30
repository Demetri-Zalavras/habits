package com.habits.perk.movement;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsCollection;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Each top-pick mechanic does its thing and Lv10 beats Lv1; plus a per-tick time budget with every skill at Lv10. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class MovementTests {
    private MovementTests() {}

    static ServerPlayer player(GameTestHelper h, Vec3 local, Mech... skills) {
        return player(h, local, 1, skills);
    }

    static ServerPlayer player(GameTestHelper h, Vec3 local, int level, Mech... skills) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-move"));
        Mv.TEST_PLAYERS.add(p.getUUID());
        p.moveTo(h.absoluteVec(local));
        setLevel(p, level, skills);
        return p;
    }

    static void setLevel(ServerPlayer p, int level, Mech... skills) {
        HabitsCollection c = HabitsCollection.EMPTY;
        for (Mech m : skills) c = c.with(m.skillId, new SkillProgress(level, 0, true));
        p.setData(CoreRegistries.COLLECTION, c);
    }

    static MoveState state(ServerPlayer p) {
        return MoveState.of(p);
    }

    /** sky_high (sky_watcher) fires on reaching the world top, and again only after a minute below it. */
    @GameTest(template = "empty")
    public static void skyHighNeedsAMinuteAway(GameTestHelper h) {
        ServerPlayer p = player(h, new Vec3(1.5, 1, 1.5));
        com.habits.trigger.Triggers.TEST_PLAYERS.add(p.getUUID());
        int[] n = new int[1];
        com.habits.trigger.Triggers.Spy spy = (who, type, ctx) -> {
            if (who == p && com.habits.Habits.id("sky_high").equals(ctx.target())) n[0]++;
        };
        com.habits.trigger.Triggers.SPIES.add(spy);
        try {
            MoveState s = state(p);
            double x = p.getX(), low = p.getY(), z = p.getZ();
            long[] at = {100, 120, 140, 160, 1340, 1360};
            double[] y = {300, low, 300, low, 300, 300};
            int[] want = {1, 1, 1, 1, 2, 2}; // bounce at 140 is too soon; 1340 is 1200 ticks after the last top second (140)
            for (int i = 0; i < at.length; i++) {
                p.moveTo(x, y[i], z);
                Trackers.second(p, s, at[i]);
                h.assertValueEqual(n[0], want[i], "sky_high count at tick " + at[i]);
            }
        } finally {
            com.habits.trigger.Triggers.SPIES.remove(spy);
            com.habits.trigger.Triggers.TEST_PLAYERS.remove(p.getUUID());
            Mv.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void skillsLoadWithMechanics(GameTestHelper h) {
        for (Mech m : Mech.ALL) {
            var def = SkillRegistry.get(m.skillId);
            h.assertTrue(def != null, "skill loaded: " + m.skill);
            h.assertTrue(def.perk().is(m.id), "skill uses its mechanic: " + m.skill);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void dashBurstsAndCoolsDown(GameTestHelper h) {
        double[] speed = new double[2];
        for (int i = 0; i < 2; i++) {
            ServerPlayer p = player(h, new Vec3(1.5, 1, 1.5), i == 0 ? 1 : 10, Mech.DASH);
            p.setOnGround(true);
            MoveState s = state(p);
            h.assertTrue(Actions.dash(p, s), "dash fires");
            Vec3 v = p.getDeltaMovement();
            speed[i] = Math.hypot(v.x, v.z);
            h.assertTrue(s.iframesUntil > p.level().getGameTime(), "i-frames set");
            h.assertFalse(Actions.dash(p, s), "cooldown blocks a second dash");
            long nag = s.nextDashNag;
            h.assertTrue(nag > p.level().getGameTime(), "a blocked dash says it is not ready");
            Actions.dash(p, s);
            h.assertTrue(s.nextDashNag == nag, "spamming the tap does not repeat the click");
        }
        // the ground burst is the push param (Lv1 unchanged); Lv10 is capped so no axis hits the motion packet clamp
        h.assertTrue(Math.abs(speed[0] - 3.0 / 2.2) < 1e-9 && Math.abs(speed[1] - DashMath.CAP) < 1e-9, "ground push " + speed[0] + ", " + speed[1]);
        h.assertTrue(speed[1] > speed[0] * 2, "Lv10 dash much stronger: " + speed[0] + " vs " + speed[1]);
        ServerPlayer none = player(h, new Vec3(1.5, 1, 1.5));
        h.assertFalse(Actions.dash(none, state(none)), "no skill, no dash");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void dashIsTheSameSpeedInEveryDirection(GameTestHelper h) {
        for (boolean ground : new boolean[]{true, false}) {
            double first = -1;
            for (float yaw : new float[]{0, 30, 45, 90, 135, -45}) {
                ServerPlayer p = player(h, new Vec3(1.5, ground ? 1 : 4, 1.5), 10, Mech.DASH);
                p.setYRot(yaw);
                p.setOnGround(ground);
                h.assertTrue(Actions.dash(p, state(p)), "dash fires");
                Vec3 v = p.getDeltaMovement();
                double speed = Math.hypot(v.x, v.z);
                // the motion packet clamps each axis to 3.9; staying under it is what keeps diagonals equal
                h.assertTrue(Math.abs(v.x) <= 3.9 && Math.abs(v.z) <= 3.9, "no axis over the packet cap at yaw " + yaw + ": " + v);
                if (first < 0) first = speed;
                h.assertTrue(Math.abs(speed - first) < 1e-6, (ground ? "ground" : "air") + " speed at yaw " + yaw + ": " + speed + " vs " + first);
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void doubleJumpCountsAndStrength(GameTestHelper h) {
        ServerPlayer lo = player(h, new Vec3(1.5, 2, 1.5), 1, Mech.DOUBLE_JUMP);
        lo.setOnGround(false);
        MoveState s1 = state(lo);
        h.assertTrue(Actions.doubleJump(lo, s1), "Lv1 first air jump");
        double v1 = lo.getDeltaMovement().y;
        h.assertFalse(Actions.doubleJump(lo, s1), "Lv1 only one");
        ServerPlayer hi = player(h, new Vec3(1.5, 2, 1.5), 10, Mech.DOUBLE_JUMP);
        hi.setOnGround(false);
        MoveState s10 = state(hi);
        h.assertTrue(Actions.doubleJump(hi, s10) && Actions.doubleJump(hi, s10), "Lv10 two air jumps");
        h.assertFalse(Actions.doubleJump(hi, s10), "but not three");
        h.assertTrue(hi.getDeltaMovement().y > v1, "Lv10 jumps higher");
        hi.setOnGround(true);
        h.assertFalse(Actions.doubleJump(hi, s10), "no double jump from the ground");
        ServerTick.everyTick(hi, s10, 1);
        hi.setOnGround(false);
        h.assertTrue(Actions.doubleJump(hi, s10), "landing resets the count");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void wallJumpNeedsAWall(GameTestHelper h) {
        h.setBlock(new BlockPos(0, 2, 1), Blocks.STONE);
        h.setBlock(new BlockPos(0, 3, 1), Blocks.STONE);
        double[] up = new double[2];
        for (int i = 0; i < 2; i++) {
            ServerPlayer p = player(h, new Vec3(1.31, 2.2, 1.5), i == 0 ? 1 : 10, Mech.WALL_JUMP);
            p.setOnGround(false);
            MoveState s = state(p);
            h.assertTrue(Actions.wallJump(p, s), "kick off the wall");
            Vec3 v = p.getDeltaMovement();
            h.assertTrue(v.x > 0, "pushed away from the wall (+x): " + v);
            up[i] = v.y;
            s.nextWallKick = 0;
            if (i == 0) h.assertFalse(Actions.wallJump(p, s), "Lv1: one kick per airtime");
            else h.assertTrue(Actions.wallJump(p, s), "Lv10: kick again");
        }
        h.assertTrue(up[1] > up[0], "Lv10 kicks harder");
        ServerPlayer open = player(h, new Vec3(1.5, 12, 1.5), 10, Mech.WALL_JUMP);
        open.setOnGround(false);
        h.assertFalse(Actions.wallJump(open, state(open)), "no wall, no kick");
        h.succeed();
    }

    /** The client sends WALL_JUMP whenever a wall is near; with no kick left the jump must still double jump. */
    @GameTest(template = "empty")
    public static void wallJumpFallsBackToDoubleJump(GameTestHelper h) {
        h.setBlock(new BlockPos(0, 2, 1), Blocks.STONE);
        h.setBlock(new BlockPos(0, 3, 1), Blocks.STONE);
        ServerPlayer p = player(h, new Vec3(1.31, 2.2, 1.5), 1, Mech.WALL_JUMP, Mech.DOUBLE_JUMP);
        p.setOnGround(false);
        MoveState s = state(p);
        Actions.act(p, MovePayloads.WALL_JUMP, 0);
        h.assertTrue(s.wallKicks == 1 && s.airJumps == 0, "first press kicks off the wall");
        Actions.act(p, MovePayloads.WALL_JUMP, 0);
        h.assertTrue(s.airJumps == 1, "no kick left (cooldown, Lv1 limit): the press double jumps");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void spiderClimbIsFaster(GameTestHelper h) {
        double[] dy = new double[2];
        for (int i = 0; i < 2; i++) {
            BlockPos at = new BlockPos(i * 2, 1, 1);
            h.setBlock(at.west(), Blocks.STONE);
            h.setBlock(at, Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, net.minecraft.core.Direction.EAST));
            ServerPlayer p = player(h, Vec3.atBottomCenterOf(at), i == 0 ? 1 : 10, Mech.SPIDER_CLIMB);
            h.assertTrue(p.onClimbable(), "on the ladder");
            p.horizontalCollision = true;
            p.setDeltaMovement(0, 0.1, 0);
            Physics.apply(p, state(p));
            dy[i] = p.getDeltaMovement().y;
        }
        h.assertTrue(Math.abs(dy[0] - 0.24) < 1e-6, "Lv1 climbs 0.24: " + dy[0]);
        h.assertTrue(Math.abs(dy[1] - 0.6) < 1e-6, "Lv10 climbs 0.6: " + dy[1]);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void gliderSlowsFallAndSavesLegs(GameTestHelper h) {
        double[] fall = new double[2];
        for (int i = 0; i < 2; i++) {
            ServerPlayer p = player(h, new Vec3(1.5, 2.5, 1.5), i == 0 ? 1 : 10, Mech.GLIDER_KICK);
            p.setOnGround(false);
            MoveState s = state(p);
            s.keys = MovePayloads.K_JUMP;
            p.setDeltaMovement(0, -1.5, 0);
            Physics.apply(p, s);
            fall[i] = p.getDeltaMovement().y;
            // server: a slow, visible descent with jump held clears fall damage
            p.fallDistance = 12;
            s.dy = fall[i];
            ServerTick.everyTick(p, s, 1);
            h.assertValueEqual(p.fallDistance, 0f, "glide clears fall distance");
            s.dy = -2;
            p.fallDistance = 12;
            ServerTick.everyTick(p, s, 2);
            h.assertValueEqual(p.fallDistance, 12f, "a fast fall is not a glide");
        }
        h.assertTrue(Math.abs(fall[0] + 0.42) < 1e-6 && Math.abs(fall[1] + 0.09) < 1e-6, "Lv1 -0.42, Lv10 -0.09: " + fall[0] + ", " + fall[1]);
        h.succeed();
    }

    /** Leap of Faith: the soft-landing cooldown is saved with the player, so a relog (fresh session state, attachment
     * reloaded from its saved form) does not hand out a second free landing. */
    @GameTest(template = "empty")
    public static void leapOfFaithCooldownSurvivesRelog(GameTestHelper h) {
        ServerPlayer p = player(h, new Vec3(1.5, 1, 1.5), 1, Mech.LEAP_OF_FAITH);
        LivingFallEvent first = new LivingFallEvent(p, 10, 1);
        WorldEvents.land(p, state(p), first, 10, Blocks.STONE.defaultBlockState());
        h.assertValueEqual(first.getDamageMultiplier(), 0f, "the first hard landing is soft");
        var ops = h.getLevel().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        var saved = MoveData.CODEC.encodeStart(ops, p.getData(MoveData.TYPE)).getOrThrow();
        MoveState.SERVER.remove(p.getUUID()); // logout drops the session state
        ServerPlayer back = player(h, new Vec3(1.5, 1, 1.5), 1, Mech.LEAP_OF_FAITH);
        back.setData(MoveData.TYPE, MoveData.CODEC.parse(ops, saved).getOrThrow());
        LivingFallEvent second = new LivingFallEvent(back, 10, 1);
        WorldEvents.land(back, state(back), second, 10, Blocks.STONE.defaultBlockState());
        h.assertValueEqual(second.getDamageMultiplier(), 1f, "after a relog the cooldown still runs");
        h.succeed();
    }

    /** Own batch: a radius-7 slam reaches neighbouring test plots, whose mobs can use up the 16-hit cap (Lv10 once hit 1 of 2). */
    @GameTest(template = "empty", batch = "movement_slam")
    public static void groundSlamRadiusGrows(GameTestHelper h) {
        int[] hit = new int[2];
        for (int i = 0; i < 2; i++) {
            Zombie near = h.spawn(EntityType.ZOMBIE, new Vec3(1.5 + i * 0.01, 1, 2.8));
            Zombie far = h.spawn(EntityType.ZOMBIE, new Vec3(1.5, 1, 5.5));
            near.setNoAi(true);
            far.setNoAi(true);
            ServerPlayer p = player(h, new Vec3(1.5, 1, 1.5), i == 0 ? 1 : 10, Mech.GROUND_SLAM);
            float nearHp = near.getHealth(), farHp = far.getHealth();
            LivingFallEvent e = new LivingFallEvent(p, 10, 1);
            WorldEvents.land(p, state(p), e, 10, Blocks.STONE.defaultBlockState());
            hit[i] = (near.getHealth() < nearHp ? 1 : 0) + (far.getHealth() < farHp ? 1 : 0);
            h.assertTrue(e.getDamageMultiplier() < 1, "hitting mobs softens the landing");
            near.discard();
            far.discard();
        }
        h.assertValueEqual(hit[0], 1, "Lv1 radius 2 hits only the close zombie");
        h.assertValueEqual(hit[1], 2, "Lv10 radius 7 hits both");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void wingsOfWindLaunches(GameTestHelper h) {
        double[] vy = new double[2];
        for (int i = 0; i < 2; i++) {
            ServerPlayer p = player(h, new Vec3(1.5, 1, 1.5), i == 0 ? 1 : 10, Mech.WINGS_OF_WIND);
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            p.getFoodData().setFoodLevel(20);
            p.setOnGround(true);
            p.setShiftKeyDown(true);
            MoveState s = state(p);
            for (int t = 0; t < 19; t++) ServerTick.wings(p, s, t);
            h.assertTrue(p.getDeltaMovement().y <= 0.01, "not before the hold time");
            ServerTick.wings(p, s, 19);
            vy[i] = p.getDeltaMovement().y;
            h.assertTrue(vy[i] > 0.5, "launched up: " + vy[i]);
            h.assertTrue(p.getFoodData().getFoodLevel() < 20, "costs food");
            p.setDeltaMovement(Vec3.ZERO);
            for (int t = 20; t < 40; t++) ServerTick.wings(p, s, t);
            h.assertTrue(p.getDeltaMovement().y < 0.01 || s.launchedAt > 0, "cooldown blocks an instant relaunch");
        }
        h.assertTrue(vy[1] > vy[0] * 2, "Lv10 launches far higher: " + vy[0] + " vs " + vy[1]);
        ServerPlayer bare = player(h, new Vec3(1.5, 1, 1.5), 10, Mech.WINGS_OF_WIND);
        bare.setOnGround(true);
        bare.setShiftKeyDown(true);
        for (int t = 0; t < 40; t++) ServerTick.wings(bare, state(bare), t);
        h.assertTrue(bare.getDeltaMovement().y <= 0.01, "no elytra, no launch");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void pathfinderMarkersByLevel(GameTestHelper h) {
        ServerPlayer p = player(h, new Vec3(1.5, 1, 1.5), 1, Mech.PATHFINDER);
        BlockPos bed = h.absolutePos(new BlockPos(2, 1, 2));
        p.setRespawnPosition(h.getLevel().dimension(), bed, 0, true, false);
        MoveState s = state(p);
        h.assertValueEqual(Scans.compose(p, s).kinds().length, 0, "Lv1: compass bar only, no markers");
        h.assertFalse(Actions.waypoint(p, s, true), "Lv1 can't save waypoints");
        setLevel(p, 3, Mech.PATHFINDER);
        s = state(p);
        var m = Scans.compose(p, s);
        h.assertTrue(m.kinds().length == 1 && m.kinds()[0] == MovePayloads.M_BED && m.xyz()[0] == bed.asLong(), "Lv3 shows the bed");
        setLevel(p, 10, Mech.PATHFINDER);
        s = state(p);
        for (int i = 0; i < 12; i++) {
            p.moveTo(h.absoluteVec(new Vec3(1.5, 1, 1.5)).add(i * 2, 0, 0));
            h.assertTrue(Actions.waypoint(p, s, true), "Lv10 saves waypoints");
        }
        int wp = 0;
        for (byte k : Scans.compose(p, s).kinds()) if (k == MovePayloads.M_WAYPOINT) wp++;
        h.assertValueEqual(wp, 10, "capped at 10 waypoints");
        setLevel(p, 10);
        h.assertValueEqual(Scans.compose(p, state(p)).kinds().length, 0, "toggled off / not owned shows nothing");
        h.succeed();
    }

    /** Losing the last movement skill stops the scans, so the server must still send one empty marker list. */
    @GameTest(template = "empty")
    public static void losingLastSkillClearsMarkers(GameTestHelper h) {
        ServerPlayer p = player(h, new Vec3(1.5, 1, 1.5), 3, Mech.PATHFINDER);
        p.setRespawnPosition(h.getLevel().dimension(), h.absolutePos(new BlockPos(2, 1, 2)), 0, true, false);
        MoveState s = state(p);
        Scans.markers(p, s);
        h.assertTrue(s.markerHash != 0, "the bed marker was sent");
        setLevel(p, 3);
        ServerTick.tick(p);
        h.assertFalse(s.any, "no movement skill left");
        h.assertValueEqual(s.markerHash, 0, "an empty list was sent, so the client holds no markers");
        h.succeed();
    }

    /** All 106 skills at Lv10 on one player: the server per-player tick (incl. its staggered 5/20/100-tick work) must
     * average well under the budget. 50 microseconds a player is 0.1% of a 50 ms tick. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void perTickBudgetAllSkillsLv10(GameTestHelper h) {
        ServerPlayer p = player(h, new Vec3(1.5, 1, 1.5), 10, Mech.ALL);
        p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        p.setOnGround(true);
        MoveState s = state(p);
        h.assertTrue(s.any && s.has(Mech.KEY_TURNER), "all owned");
        int n = 2000;
        for (int i = 0; i < 200; i++) ServerTick.tick(p); // warm up
        long[] ns = new long[n];
        for (int i = 0; i < n; i++) {
            if ((i & 7) == 0) p.moveTo(p.getX() + ((i & 8) == 0 ? 0.3 : -0.3), p.getY(), p.getZ()); // keep the movement paths busy
            long a = System.nanoTime();
            ServerTick.tick(p);
            ns[i] = System.nanoTime() - a;
        }
        // average: median of 5 blocks of 400 ticks (each holds the same mix of 5/20/100-tick work), so one GC pause
        // can't sink it; the 5 s scan: the 99.5th percentile, still one of the 20 scan ticks but not a lone GC outlier
        double[] blocks = new double[5];
        for (int b = 0; b < 5; b++) for (int i = b * 400; i < b * 400 + 400; i++) blocks[b] += ns[i] / 1000.0 / 400;
        java.util.Arrays.sort(blocks);
        long[] sorted = ns.clone();
        java.util.Arrays.sort(sorted);
        double serverUs = blocks[2], worstUs = sorted[(int) (0.995 * (n - 1))] / 1000.0;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) Physics.apply(p, s);
        double physicsUs = (System.nanoTime() - t0) / 1000.0 / n;
        ServerPlayer idle = player(h, new Vec3(1.5, 1, 1.5));
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) ServerTick.tick(idle);
        double idleUs = (System.nanoTime() - t0) / 1000.0 / n;
        LogUtils.getLogger().info("[habits movement perf] server tick {} us (worst {} us), physics {} us, no-skill player {} us (avg of {})",
                String.format("%.2f", serverUs), String.format("%.0f", worstUs), String.format("%.2f", physicsUs), String.format("%.2f", idleUs), n);
        for (String part : new String[]{"compose", "highlights", "gold", "wrecks", "traps", "raiders"}) {
            long w = 0, sum = 0;
            for (int i = 0; i < 20; i++) {
                long a = System.nanoTime();
                switch (part) {
                    case "compose" -> Scans.compose(p, s);
                    case "highlights" -> Scans.highlights(p, s);
                    case "gold" -> Scans.blocks(p.serverLevel(), p.blockPosition(), 48, st -> st.is(net.neoforged.neoforge.common.Tags.Blocks.ORES_GOLD), 8);
                    case "wrecks" -> Scans.structures(p.serverLevel(), p.blockPosition(), 512, Scans.WRECKS);
                    case "traps" -> Scans.blocks(p.serverLevel(), p.blockPosition(), 24, Scans::trap, 64);
                    default -> p.level().getEntitiesOfClass(net.minecraft.world.entity.raid.Raider.class, p.getBoundingBox().inflate(160, 64, 160));
                }
                long d = System.nanoTime() - a;
                w = Math.max(w, d);
                sum += d;
            }
            LogUtils.getLogger().info("[habits movement perf] {} avg {} us worst {} us", part, sum / 20000, w / 1000);
        }
        h.assertTrue(serverUs < 50, "server tick under 50 us, was " + serverUs);
        h.assertTrue(worstUs < 3000, "worst single tick (the 5 s scan) under 3 ms, was " + worstUs);
        h.assertTrue(physicsUs < 20, "physics under 20 us, was " + physicsUs);
        h.assertTrue(idleUs < 10, "a player without movement skills costs under 10 us, was " + idleUs);
        h.succeed();
    }
}
