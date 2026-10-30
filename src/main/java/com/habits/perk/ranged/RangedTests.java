package com.habits.perk.ranged;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import com.habits.trigger.Triggers;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ranged GameTests in a 24x12x40 air lane (structure {@code ranged_lane}). Each test owns its own batch so arrows never
 * meet another test's mobs. The lane runs along +z at x = 12; the floor top is y = 2.
 */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class RangedTests {
    private RangedTests() {}

    static final String LANE = "ranged_lane";

    static ServerPlayer archer(GameTestHelper h, Vec3 rel) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-ranged"));
        Triggers.TEST_PLAYERS.add(p.getUUID());
        Vec3 at = h.absoluteVec(rel);
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setDeltaMovement(Vec3.ZERO);
        return p;
    }

    static void own(ServerPlayer p, String skill, int level) {
        p.setData(CoreRegistries.COLLECTION, HabitsApi.collection(p).with(Habits.id(skill), new SkillProgress(level, 0, true)));
    }

    static HabitsApi.PerkHit hit(ServerPlayer p, String mechanic) {
        return HabitsApi.mechanicHit(p, Habits.id(mechanic));
    }

    static void floor(GameTestHelper h, int x0, int x1, int z0, int z1, Block b) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) h.setBlock(x, 1, z, b);
    }

    static Husk husk(GameTestHelper h, double x, double z) {
        return h.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(x, 2, z));
    }

    static Arrow shoot(GameTestHelper h, ServerPlayer p, Vec3 dir, float speed) {
        Arrow a = new Arrow(h.getLevel(), p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        a.shoot(dir.x, dir.y, dir.z, speed, 0);
        h.getLevel().addFreshEntity(a);
        return a;
    }

    static void done(GameTestHelper h, ServerPlayer p) {
        Triggers.TEST_PLAYERS.remove(p.getUUID());
        Status.PLAYERS.remove(p.getUUID());
        h.succeed();
    }

    static boolean hurt(Entity e) {
        return e instanceof net.minecraft.world.entity.LivingEntity l && l.getHealth() < l.getMaxHealth();
    }

    /** An arrow asks protection mods whether it may break glass by posting a BreakEvent; that check rolls no cards. */
    @GameTest(template = "empty")
    public static void breakPermissionCheckRollsNoCards(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(1.5, 2, 1.5));
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        int[] fired = new int[1];
        Triggers.Spy spy = (who, type, ctx) -> { if (who == p) fired[0]++; };
        Triggers.SPIES.add(spy);
        try {
            BlockPos glass = h.absolutePos(new BlockPos(2, 2, 2));
            h.getLevel().setBlock(glass, Blocks.GLASS.defaultBlockState(), 3);
            h.assertTrue(Impacts.allowed(p, glass, Blocks.GLASS.defaultBlockState()), "nothing protects the test plot");
            h.assertValueEqual(fired[0], 0, "the permission check fired a trigger");
        } finally {
            Triggers.SPIES.remove(spy);
        }
        done(h, p);
    }

    /** Point Blank Lv10: the archer gets Speed II for the JSON's 0.5 s (10 ticks), not 10 s. */
    @GameTest(template = "empty")
    public static void pointBlankSpeedIsSeconds(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(1.5, 2, 1.5));
        own(p, "point_blank", 10);
        Husk victim = h.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5, 2, 1.5));
        Hits.pointBlank(hit(p, "point_blank"), p, victim, false);
        var speed = p.getEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED);
        h.assertTrue(speed != null && speed.getDuration() == 10 && speed.getAmplifier() == 1, "Speed II for 10 ticks, got " + speed);
        done(h, p);
    }

    // ------------------------------------------------------------------ Homing Shot

    /** Lv10: a straight shot bends into a husk 3 blocks off the line, turning no faster than 30 degrees a tick. */
    @GameTest(template = LANE, batch = "ranged_homing10")
    public static void homingLv10BendsIntoHusk(GameTestHelper h) {
        floor(h, 8, 16, 0, 39, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        own(p, "homing_shot", 10);
        Husk z = husk(h, 15.5, 14.5);
        Arrow a = shoot(h, p, new Vec3(0, 0, 1), 3);
        Shots.Shot s = Shots.get(a);
        h.assertTrue(s != null && s.homeR == 6, "Lv10 homing radius 6, got " + (s == null ? "no shot" : s.homeR));
        Vec3[] last = {a.getDeltaMovement()};
        double maxTurn = Math.toRadians(30) + 0.06; // turn cap plus gravity's own bend
        h.onEachTick(() -> {
            if (a.isRemoved() || a.getDeltaMovement().lengthSqr() < 0.5) return;
            Vec3 v = a.getDeltaMovement();
            double ang = Math.acos(Math.max(-1, Math.min(1, v.normalize().dot(last[0].normalize()))));
            if (ang > maxTurn) h.fail("turned " + Math.toDegrees(ang) + " degrees in one tick");
            last[0] = v;
        });
        h.succeedWhen(() -> {
            h.assertTrue(hurt(z), "husk 3 blocks off the line was hit");
            done(h, p);
        });
    }

    /** Lv1 (radius 1): the same shot flies past the husk. */
    @GameTest(template = LANE, batch = "ranged_homing1")
    public static void homingLv1MissesWideHusk(GameTestHelper h) {
        floor(h, 8, 16, 0, 39, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        own(p, "homing_shot", 1);
        Husk z = husk(h, 15.5, 14.5);
        shoot(h, p, new Vec3(0, 0, 1), 3);
        h.runAfterDelay(30, () -> {
            h.assertFalse(hurt(z), "Lv1 must not reach a husk 3 blocks off the line");
            done(h, p);
        });
    }

    /** Never targets villagers, even right next to the line. */
    @GameTest(template = LANE, batch = "ranged_homing_villager")
    public static void homingSkipsVillagers(GameTestHelper h) {
        floor(h, 6, 18, 0, 39, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        own(p, "homing_shot", 10);
        Villager v = h.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(14.5, 2, 14.5));
        shoot(h, p, new Vec3(0, 0, 1), 3);
        h.runAfterDelay(30, () -> {
            h.assertFalse(hurt(v), "villager never targeted");
            done(h, p);
        });
    }

    /** Sneaking while firing turns homing off for that shot. */
    @GameTest(template = LANE, batch = "ranged_homing_sneak")
    public static void homingOffWhenSneaking(GameTestHelper h) {
        floor(h, 6, 18, 0, 39, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        own(p, "homing_shot", 10);
        p.setShiftKeyDown(true);
        Husk z = husk(h, 15.5, 14.5);
        Arrow a = shoot(h, p, new Vec3(0, 0, 1), 3);
        h.assertTrue(Shots.get(a) != null && Shots.get(a).homeR == 0, "sneak shot has no homing");
        h.runAfterDelay(30, () -> {
            h.assertFalse(hurt(z), "sneak shot did not bend");
            done(h, p);
        });
    }

    /** Budget: 100 homing arrows that find nothing (the worst case: every one scans) cost well under 1 ms per tick. */
    @GameTest(template = LANE, batch = "ranged_perf", timeoutTicks = 100)
    public static void homingHundredArrowsBudget(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        own(p, "homing_shot", 10);
        List<Entity> made = new ArrayList<>();
        for (int i = 0; i < 40; i++) made.add(husk(h, 2 + (i % 20), 1 + (i / 20))); // behind the arrows: never in the cone
        for (int i = 0; i < 100; i++) {
            Vec3 at = h.absoluteVec(new Vec3(4 + (i % 10) * 1.6, 3 + (i / 10) * 0.8, 6));
            p.moveTo(at.x, at.y, at.z);
            made.add(shoot(h, p, new Vec3(0, 0.05, 1), 3));
        }
        h.assertTrue(Shots.TICKING.size() >= 100, "100 homing shots ticking, got " + Shots.TICKING.size());
        for (int i = 0; i < 5; i++) Shots.tickAll(); // warm up
        int runs = 40;
        long[] ns = new long[runs];
        for (int i = 0; i < runs; i++) {
            for (Entity e : made) if (e instanceof Arrow a) a.tickCount = 0; // force the scan branch every run
            long t0 = System.nanoTime();
            Shots.tickAll();
            ns[i] = System.nanoTime() - t0;
        }
        java.util.Arrays.sort(ns);
        double ms = ns[runs / 2] / 1e6; // median: a GC pause in one run doesn't count
        LogUtils.getLogger().info("[habits ranged] 100 scanning homing arrows: {} ms per server tick", String.format("%.3f", ms));
        h.assertTrue(ms < 1.0, "100 homing arrows cost " + ms + " ms per tick (budget 1 ms)");
        made.forEach(Entity::discard);
        done(h, p);
    }

    // ------------------------------------------------------------------ Ricochet

    static void walls(GameTestHelper h) {
        floor(h, 6, 18, 0, 20, Blocks.GLASS);
        for (int x = 6; x <= 18; x++) for (int y = 2; y <= 10; y++) {
            h.setBlock(x, y, 7, Blocks.STONE);
            h.setBlock(x, y, 14, Blocks.STONE);
        }
    }

    static void ricochet(GameTestHelper h, int level, int expect) {
        walls(h);
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 10.5));
        own(p, "ricochet", level);
        Arrow a = shoot(h, p, new Vec3(0, 0.08, 1), 3);
        Shots.Shot s = Shots.get(a);
        h.runAfterDelay(40, () -> {
            h.assertTrue(s != null && s.bounces == expect, "Lv" + level + " bounces " + (s == null ? -1 : s.bounces) + ", want " + expect);
            a.discard();
            done(h, p);
        });
    }

    @GameTest(template = LANE, batch = "ranged_ricochet1")
    public static void ricochetLv1BouncesOnce(GameTestHelper h) {
        ricochet(h, 1, 1);
    }

    @GameTest(template = LANE, batch = "ranged_ricochet10")
    public static void ricochetLv10BouncesFour(GameTestHelper h) {
        ricochet(h, 10, 4);
    }

    // ------------------------------------------------------------------ Split Shot

    static void split(GameTestHelper h, int level, int shards) {
        floor(h, 6, 18, 14, 26, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        own(p, "split_shot", level);
        Husk victim = husk(h, 12.5, 20.5);
        List<Husk> others = new ArrayList<>();
        double[][] at = {{14.5, 20.5}, {10.5, 20.5}, {12.5, 22.5}, {12.5, 18.5}, {14.5, 22.5}, {10.5, 18.5}};
        for (double[] xz : at) others.add(husk(h, xz[0], xz[1]));
        Arrow a = new Arrow(h.getLevel(), p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        Hits.split(hit(p, "split_shot"), p, a, victim);
        long made = Shots.SHOTS.values().stream().filter(s -> s.owner == p && s.shard).count();
        h.assertTrue(made == shards, "Lv" + level + " made " + made + " shards, want " + shards);
        h.succeedWhen(() -> {
            h.assertTrue(others.stream().anyMatch(RangedTests::hurt), "a shard hit a neighbour");
            done(h, p);
        });
    }

    @GameTest(template = LANE, batch = "ranged_split1")
    public static void splitShotLv1OneShard(GameTestHelper h) {
        split(h, 1, 1);
    }

    @GameTest(template = LANE, batch = "ranged_split10")
    public static void splitShotLv10FiveShards(GameTestHelper h) {
        split(h, 10, 5);
    }

    // ------------------------------------------------------------------ Rain of Arrows

    static void rain(GameTestHelper h, int level, int expect) {
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        own(p, "rain_of_arrows", level);
        Arrow a = shoot(h, p, new Vec3(0, Math.tan(Math.toRadians(85)), 1), 3);
        h.succeedWhen(() -> {
            long n = Shots.SHOTS.values().stream().filter(s -> s.owner == p && s.shard).count();
            h.assertTrue(n == expect, "Lv" + level + " rain " + n + ", want " + expect);
            Shots.SHOTS.values().stream().filter(s -> s.owner == p).map(s -> s.pr).toList().forEach(Entity::discard);
            a.discard();
            done(h, p);
        });
    }

    @GameTest(template = LANE, batch = "ranged_rain1", timeoutTicks = 200, skyAccess = true)
    public static void rainLv1ThreeArrows(GameTestHelper h) {
        rain(h, 1, 3);
    }

    @GameTest(template = LANE, batch = "ranged_rain10", timeoutTicks = 200, skyAccess = true)
    public static void rainLv10FifteenArrows(GameTestHelper h) {
        rain(h, 10, 15);
    }

    // ------------------------------------------------------------------ Pearl Swap

    static void pearl(GameTestHelper h, int level, int minStun, int maxStun) {
        floor(h, 8, 16, 0, 20, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 4.5));
        own(p, "pearl_swap", level);
        Husk z = husk(h, 12.5, 12.5);
        ThrownEnderpearl pearl = new ThrownEnderpearl(h.getLevel(), p);
        pearl.shoot(0, 0.05, 1, 1.5f, 0);
        h.getLevel().addFreshEntity(pearl);
        h.succeedWhen(() -> {
            MobEffectInstance slow = z.getEffect(MobEffects.MOVEMENT_SLOWDOWN);
            h.assertTrue(z.getZ() < h.absoluteVec(new Vec3(0, 0, 7)).z, "husk swapped back to the thrower's spot");
            h.assertTrue(slow != null && slow.getDuration() >= minStun - 2 && slow.getDuration() <= maxStun,
                    "Lv" + level + " stun " + (slow == null ? 0 : slow.getDuration()) + " ticks");
            done(h, p);
        });
    }

    @GameTest(template = LANE, batch = "ranged_pearl1")
    public static void pearlSwapLv1ShortStun(GameTestHelper h) {
        pearl(h, 1, 1, 10);
    }

    @GameTest(template = LANE, batch = "ranged_pearl10")
    public static void pearlSwapLv10LongStun(GameTestHelper h) {
        pearl(h, 10, 50, 60);
    }

    // ------------------------------------------------------------------ Gale Thrower

    static void gale(GameTestHelper h, int level, boolean pushed) {
        floor(h, 8, 16, 14, 30, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        own(p, "gale_thrower", level);
        Husk z = husk(h, 12.5, 23.9);
        Vec3 at = h.absoluteVec(new Vec3(12.5, 2.5, 20.5));
        WindCharge wc = new WindCharge(p, h.getLevel(), at.x, at.y, at.z);
        h.getLevel().addFreshEntity(wc);
        Shots.Shot s = Shots.get(wc);
        h.assertTrue(s != null, "wind charge tracked");
        double before = z.getDeltaMovement().horizontalDistance();
        Impacts.wind(s, wc, at);
        double after = z.getDeltaMovement().horizontalDistance();
        h.assertTrue(pushed == after > before + 0.05, "Lv" + level + " husk 3.4 blocks out pushed=" + (after > before + 0.05));
        done(h, p);
    }

    @GameTest(template = LANE, batch = "ranged_gale1")
    public static void galeLv1SmallBlast(GameTestHelper h) {
        gale(h, 1, false);
    }

    @GameTest(template = LANE, batch = "ranged_gale10")
    public static void galeLv10WideBlast(GameTestHelper h) {
        gale(h, 10, true);
    }

    // ------------------------------------------------------------------ Stormcaller

    static void storm(GameTestHelper h, int level, int expectHurt) {
        floor(h, 6, 18, 14, 30, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        own(p, "stormcaller", level);
        List<Husk> mobs = List.of(husk(h, 12.5, 20.5), husk(h, 15.5, 20.5), husk(h, 12.5, 24.5), husk(h, 9.5, 20.5));
        Hits.storm(hit(p, "stormcaller"), p, mobs.get(0));
        long n = mobs.stream().filter(RangedTests::hurt).count();
        h.assertTrue(n == expectHurt, "Lv" + level + " lightning hit " + n + " mobs, want " + expectHurt);
        h.assertTrue(h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.LightningBolt.class, mobs.get(0).getBoundingBox().inflate(20))
                .stream().allMatch(b -> b.getBlocksSetOnFire() == 0), "bolts are visual only");
        done(h, p);
    }

    @GameTest(template = LANE, batch = "ranged_storm1")
    public static void stormLv1NoChain(GameTestHelper h) {
        storm(h, 1, 1);
    }

    @GameTest(template = LANE, batch = "ranged_storm10")
    public static void stormLv10ChainsThree(GameTestHelper h) {
        storm(h, 10, 4);
    }

    // ------------------------------------------------------------------ Grapple Hook

    static void grapple(GameTestHelper h, int level, boolean pulls) {
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        own(p, "grapple_hook", level);
        FishingHook hook = new FishingHook(p, h.getLevel(), 0, 0);
        Vec3 at = h.absoluteVec(new Vec3(12.5, 4, 14.5)); // 12 blocks out, 2 up
        hook.moveTo(at.x, at.y, at.z);
        hook.setOnGround(true);
        Throws.reel(p, hook);
        double v = p.getDeltaMovement().horizontalDistance();
        h.assertTrue(pulls == v > 0.5, "Lv" + level + " grapple speed " + v);
        if (pulls) h.assertTrue(p.getDeltaMovement().y > 0 && p.getDeltaMovement().z > 0, "pulled up and toward the bobber");
        p.fishing = null;
        done(h, p);
    }

    @GameTest(template = LANE, batch = "ranged_grapple1")
    public static void grappleLv1OutOfRange(GameTestHelper h) {
        grapple(h, 1, false);
    }

    @GameTest(template = LANE, batch = "ranged_grapple10")
    public static void grappleLv10Pulls(GameTestHelper h) {
        grapple(h, 10, true);
    }

    // ------------------------------------------------------------------ Bullseye

    /** The preview path lands where a real arrow lands; Lv1 stops at 10 blocks, Lv10 reaches the floor. */
    @GameTest(template = LANE, batch = "ranged_bullseye")
    public static void bullseyeMatchesRealArrow(GameTestHelper h) {
        floor(h, 8, 16, 0, 39, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 1.5));
        ServerLevel level = h.getLevel();
        Arrow a = new Arrow(level, p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        a.shoot(0, 0.35, 1, 1.4f, 0);
        Vec3 start = a.position(), v = a.getDeltaMovement();
        var def = SkillRegistry.get(Habits.id("bullseye"));
        Trajectory.Result lv1 = Trajectory.simulate(level, a, start, v, def.perkValue(1), 1.5, 40, null);
        Trajectory.Result lv10 = Trajectory.simulate(level, a, start, v, def.perkValue(10), 1.5, 40, null);
        h.assertTrue(lv1.length() <= def.perkValue(1) + 1e-6 && !lv1.hitBlock(), "Lv1 preview stops at " + def.perkValue(1) + " blocks");
        h.assertTrue(lv10.hitBlock(), "Lv10 preview reaches the floor");
        level.addFreshEntity(a);
        Shots.Shot s = Shots.get(a);
        h.succeedWhen(() -> {
            h.assertTrue(s != null && s.landed, "arrow landed");
            double off = a.position().distanceTo(lv10.end());
            h.assertTrue(off < 0.6, "preview landing off by " + off + " blocks");
            done(h, p);
        });
    }

    // ------------------------------------------------------------------ Breeze Breaker (mixins)

    /** Fresh unspawned arrows registered as shots: how many of {@code n} break through at this level. */
    static int breakRolls(GameTestHelper h, ServerPlayer p, int n) {
        int hits = 0;
        for (int i = 0; i < n; i++) {
            Arrow a = new Arrow(h.getLevel(), p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            Shots.shard(p, a);
            if (Impacts.breaks(a)) hits++;
            Shots.SHOTS.remove(a);
        }
        return hits;
    }

    /** Lv1 breaks about 10% of shots (rolled once per projectile), Lv10 all of them. */
    @GameTest(template = LANE, batch = "ranged_breaker_roll")
    public static void breezeBreakerLv1RarelyLv10Always(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        own(p, "breeze_breaker", 1);
        int lv1 = breakRolls(h, p, 400);
        h.assertTrue(lv1 >= 12 && lv1 <= 80, "Lv1 broke " + lv1 + " of 400, expected about 40");
        own(p, "breeze_breaker", 10);
        h.assertValueEqual(breakRolls(h, p, 50), 50, "Lv10 breaks every shot");
        done(h, p);
    }

    /** Lv10: a real arrow hurts an enderman (EnderManMixin) instead of making it teleport, and the arrow stops. */
    @GameTest(template = LANE, batch = "ranged_breaker_enderman")
    public static void breezeBreakerLv10HitsEnderman(GameTestHelper h) {
        floor(h, 8, 16, 0, 39, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(12.5, 2.5, 2.5));
        own(p, "breeze_breaker", 10);
        var ender = h.spawnWithNoFreeWill(EntityType.ENDERMAN, new Vec3(12.5, 2, 10.5));
        Vec3 at = ender.position();
        Arrow a = shoot(h, p, new Vec3(0, 0.02, 1), 3);
        h.succeedWhen(() -> {
            h.assertTrue(hurt(ender), "enderman took the arrow");
            h.assertTrue(ender.position().distanceTo(at) < 0.5, "enderman did not teleport");
            h.assertTrue(a.isRemoved(), "arrow stopped in the enderman");
            done(h, p);
        });
    }

    /** Lv10: a breeze doesn't bounce the arrow back (BreezeMixin), it takes the hit. */
    @GameTest(template = LANE, batch = "ranged_breaker_breeze")
    public static void breezeBreakerLv10HitsBreeze(GameTestHelper h) {
        floor(h, 8, 16, 0, 39, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(12.5, 2.5, 2.5));
        own(p, "breeze_breaker", 10);
        var breeze = h.spawnWithNoFreeWill(EntityType.BREEZE, new Vec3(12.5, 2, 10.5));
        shoot(h, p, new Vec3(0, 0.02, 1), 3);
        h.succeedWhen(() -> {
            h.assertTrue(hurt(breeze), "breeze took the arrow");
            done(h, p);
        });
    }

    /** A raised shield blocks the arrow without the perk and not at Lv10 (LivingEntityMixin). */
    @GameTest(template = LANE, batch = "ranged_breaker_shield")
    public static void breezeBreakerLv10PassesShields(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        Vec3 g = h.absoluteVec(new Vec3(12.5, 2, 10.5));
        Player guard = new Player(h.getLevel(), BlockPos.containing(g), 0, new GameProfile(UUID.randomUUID(), "habits-guard")) {
            @Override public boolean isSpectator() { return false; }
            @Override public boolean isCreative() { return false; }
            @Override public boolean isBlocking() { return true; }
        };
        guard.moveTo(g.x, g.y, g.z, 0, 0); // facing +z, toward the arrow below
        Arrow a = new Arrow(h.getLevel(), p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        a.setPos(g.x, g.y + 1, g.z + 2);
        Shots.shard(p, a);
        var src = h.getLevel().damageSources().arrow(a, p);
        h.assertTrue(guard.isDamageSourceBlocked(src), "without the perk the shield blocks");
        own(p, "breeze_breaker", 10);
        Shots.SHOTS.remove(a);
        Shots.shard(p, a);
        h.assertFalse(guard.isDamageSourceBlocked(src), "Lv10 goes through the shield");
        Shots.SHOTS.remove(a);
        done(h, p);
    }

    // ------------------------------------------------------------------ Shot save (ProjectileMixin) + pierce (AT)

    /** A saved arrow loads back with its perks: Lv1 vs Lv10 homing radius survives a save and load. */
    static void saveLoad(GameTestHelper h, int level, double radius) {
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 2.5));
        own(p, "homing_shot", level);
        Arrow a = shoot(h, p, new Vec3(0, 0, 1), 3);
        Shots.Shot s = Shots.get(a);
        h.assertTrue(s != null, "fired arrow has a shot");
        s.covers = 2;
        Shots.setPierce(a, 3);
        h.assertValueEqual((int) a.getPierceLevel(), 3, "pierce set through the access transformer");
        var tag = a.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        h.assertTrue(tag.contains(Shots.SAVE_KEY), "ProjectileMixin wrote the shot into the save");
        a.discard();
        // a fake owner is never in the level, so the mixin's load sees an offline owner and loads a plain arrow
        Arrow copy = EntityType.ARROW.create(h.getLevel());
        copy.load(tag);
        h.assertTrue(Shots.get(copy) == null, "offline owner: plain arrow");
        Shots.load(copy, tag, p);
        Shots.Shot back = Shots.get(copy);
        Shots.SHOTS.remove(copy);
        Shots.TICKING.removeIf(x -> x == back);
        h.assertTrue(back != null && back.owner == p, "loaded arrow got its shot back");
        h.assertTrue(back.homeR == radius && back.covers == 2 && back.origin.distanceTo(s.origin) < 1e-6,
                "homing radius " + back.homeR + ", covers " + back.covers + " after load");
        done(h, p);
    }

    @GameTest(template = LANE, batch = "ranged_save1")
    public static void shotSaveLv1(GameTestHelper h) {
        saveLoad(h, 1, 1);
    }

    @GameTest(template = LANE, batch = "ranged_save10")
    public static void shotSaveLv10(GameTestHelper h) {
        saveLoad(h, 10, 6);
    }

    // ------------------------------------------------------------------ Trickshot fetch

    /** Trickshot item fetch brings loose items to the archer but leaves never-pickup items (displays, frozen cards). */
    @GameTest(template = LANE, batch = "ranged_fetch")
    public static void fetchSkipsNeverPickup(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 1.5));
        Vec3 at = h.absoluteVec(new Vec3(12.5, 3, 20.5));
        var loose = new net.minecraft.world.entity.item.ItemEntity(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.PAPER));
        var frozen = new net.minecraft.world.entity.item.ItemEntity(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.PAPER));
        loose.setNoGravity(true);
        frozen.setNoGravity(true);
        frozen.setNeverPickUp();
        h.getLevel().addFreshEntity(loose);
        h.getLevel().addFreshEntity(frozen);
        Arrow a = new Arrow(h.getLevel(), p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        a.setPos(at);
        Shots.Shot s = Shots.shard(p, a);
        s.fetch = true;
        Shots.flight(s);
        Shots.SHOTS.remove(a);
        h.assertTrue(loose.position().distanceTo(p.position()) < 1, "loose item fetched");
        h.assertTrue(frozen.position().distanceTo(at) < 0.01, "never-pickup item stays put");
        h.assertTrue(frozen.hasPickUpDelay(), "never-pickup item keeps its pickup delay");
        loose.discard();
        frozen.discard();
        done(h, p);
    }

    // ------------------------------------------------------------------ Falling Star

    /** Lv10: every 4th full-draw arrow is marked; a second after it lands the star hurts the husk but not the villager or the floor. */
    @GameTest(template = "empty", batch = "ranged_falling_star", timeoutTicks = 100)
    public static void fallingStarMarksAndStrikes(GameTestHelper h) {
        h.setBlock(2, 1, 2, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(0.5, 2, 0.5));
        own(p, "falling_star", 10);
        boolean[] marked = new boolean[5];
        for (int i = 0; i < 5; i++) {
            Arrow a = new Arrow(h.getLevel(), p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
            a.setCritArrow(i != 0); // the first is a weak shot: it never counts
            a.shoot(0, 1, 0, 1, 0);
            h.getLevel().addFreshEntity(a);
            marked[i] = Shots.get(a).star;
            if (i < 4) a.discard();
        }
        h.assertTrue(!marked[0] && !marked[1] && !marked[2] && !marked[3] && marked[4], "only the 4th full-draw shot is marked");
        Husk z = husk(h, 2.5, 2.5);
        Villager v = h.spawnWithNoFreeWill(EntityType.VILLAGER, new Vec3(1.5, 2, 2.5));
        Arrow last = h.getEntities(EntityType.ARROW).stream().filter(a -> Shots.get(a) != null && Shots.get(a).star).findFirst().orElseThrow();
        FallingStar.landed(Shots.get(last), h.absoluteVec(new Vec3(2.5, 2, 2.5)));
        last.discard();
        h.runAfterDelay(10, () -> h.assertFalse(hurt(z), "the star waits a second"));
        h.runAfterDelay(25, () -> {
            h.assertTrue(z.isDeadOrDying(), "Lv10's 24 damage star kills the 20 health husk, health " + z.getHealth());
            h.assertFalse(hurt(v), "villagers are never hit, health " + v.getHealth() + " by " + v.getLastDamageSource());
            h.assertBlock(new BlockPos(2, 1, 2), b -> b == Blocks.STONE, "no block damage");
            done(h, p);
        });
    }

    /** The Falling Star card event: a hit 100+ blocks from the launch point fires arrow_hit_100, a 30-block hit doesn't. */
    @GameTest(template = "empty", batch = "ranged_falling_star")
    public static void arrowHit100Fires(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(0.5, 2, 0.5));
        Husk z = husk(h, 2.5, 2.5);
        List<String> got = new ArrayList<>();
        Triggers.Spy spy = (who, type, ctx) -> { if (who == p && ctx.target() != null) got.add(ctx.target().getPath()); };
        Triggers.SPIES.add(spy);
        try {
            for (double d : new double[]{40, 120}) {
                Arrow a = shoot(h, p, new Vec3(0, 1, 0), 1);
                Hits.arrowHit(new Shots.Shot(p, a, z.position().add(d, 0, 0)), a, z);
                a.discard();
            }
        } finally {
            Triggers.SPIES.remove(spy);
        }
        h.assertValueEqual(got.stream().filter("arrow_hit_100"::equals).count(), 1L, "one 100-block hit, events " + got);
        h.assertValueEqual(got.stream().filter("arrow_hit_30"::equals).count(), 2L, "both count as 30-block hits");
        done(h, p);
    }
}
