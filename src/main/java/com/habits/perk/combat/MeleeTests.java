package com.habits.perk.combat;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.SkillProgress;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Melee mechanics: each top pick proves its effect and its Lv1 vs Lv10 difference, plus trigger and timing checks.
 * Fake players never tick, so the tests drive {@link Hooks#endTick} and {@link Ticks#tick} by hand. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class MeleeTests {
    private MeleeTests() {}

    static final String ARENA = "melee_arena";

    static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-melee"));
        Vec3 at = h.absoluteVec(new Vec3(8.5, 1, 4.5));
        p.moveTo(at.x, at.y, at.z, 0, 0); // facing +z
        p.setYHeadRot(0);
        p.setOnGround(true);
        Triggers.TEST_PLAYERS.add(p.getUUID());
        return p;
    }

    static void done(GameTestHelper h, ServerPlayer p) {
        Triggers.TEST_PLAYERS.remove(p.getUUID());
        unspy(p);
        Hooks.forget(p);
        h.succeed();
    }

    /** Dragonslayer Lv10: an arrow you shoot with a boss within 64 blocks homes on it; Lv9 arrows fly straight. */
    @GameTest(template = "empty")
    public static void dragonslayerLv10ArrowsHomeOnBosses(GameTestHelper h) {
        var wither = h.spawnWithNoFreeWill(EntityType.WITHER, new Vec3(1.5, 3, 1.5));
        for (int lvl : new int[]{9, 10}) {
            ServerPlayer p = player(h);
            give(p, "dragonslayer", lvl);
            Arrow a = new Arrow(h.getLevel(), p, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
            Special.onProjectileJoin(new net.neoforged.neoforge.event.entity.EntityJoinLevelEvent(a, h.getLevel(), false));
            boolean homes = Special.HOMING.removeIf(m -> m.pr() == a);
            h.assertValueEqual(homes, lvl >= 10, "Lv" + lvl + " arrow homes on the Wither");
            Triggers.TEST_PLAYERS.remove(p.getUUID());
        }
        wither.discard();
        h.succeed();
    }

    static void give(ServerPlayer p, String skill, int level) {
        HabitsCollection c = HabitsApi.collection(p).with(Habits.id(skill), new SkillProgress(level, 0, true));
        p.setData(CoreRegistries.COLLECTION, c);
    }

    static void clearSkills(ServerPlayer p) {
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY);
    }

    static Zombie zombie(GameTestHelper h, double x, double z) {
        Zombie zo = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(x, 1, z));
        zo.setOnGround(true);
        return zo;
    }

    static <T extends LivingEntity> T tank(T e) {
        e.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        e.setHealth(1000);
        return e;
    }

    /** Full attack cooldown (a fake player never ticks it up; the field is public through the access transformer). */
    static void charge(Player p) {
        p.attackStrengthTicker = 200;
    }

    /** What core fires when the client swings at nothing. */
    static void airSwing(ServerPlayer p) {
        Triggers.fire(p, TriggerType.CUSTOM, TriggerContext.of(Hooks.SWING_AIR));
    }

    static void swing(ServerPlayer p, LivingEntity t, boolean crit) {
        charge(p);
        p.setSprinting(false);
        if (crit) {
            p.fallDistance = 2;
            p.setOnGround(false);
        } else {
            p.fallDistance = 0;
            p.setOnGround(true);
        }
        t.invulnerableTime = 0;
        p.attack(t);
        p.fallDistance = 0;
        p.setOnGround(true);
        Hooks.endTick();
    }

    static boolean hurt(LivingEntity e) {
        return e.getHealth() < e.getMaxHealth();
    }

    /** Spies are shared with every test running in parallel (and utility's lucky-roll spy): only ever remove our own. */
    record PlayerSpy(ServerPlayer p, String event, List<TriggerContext> got) implements Triggers.Spy {
        @Override
        public void fired(ServerPlayer pl, TriggerType type, TriggerContext ctx) {
            if (pl == p && (type == TriggerType.CUSTOM || type == TriggerType.CUSTOM_UNITS) && ctx.target() != null
                    && ctx.target().getPath().equals(event)) got.add(ctx);
        }
    }

    static List<TriggerContext> spy(ServerPlayer p, String event) {
        PlayerSpy s = new PlayerSpy(p, event, new ArrayList<>());
        Triggers.SPIES.add(s);
        return s.got();
    }

    static void unspy(ServerPlayer p) {
        Triggers.SPIES.removeIf(s -> s instanceof PlayerSpy ps && ps.p() == p);
    }

    // ---------------------------------------------------------------- top picks

    @GameTest(template = ARENA)
    public static void chainCritJumps(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        int[] got = new int[2];
        for (int run = 0; run < 2; run++) {
            int lv = run == 0 ? 1 : 10;
            clearSkills(p);
            give(p, "chain_crit", lv);
            Zombie main = tank(zombie(h, 8.5, 6));
            List<Zombie> others = List.of(tank(zombie(h, 8.5, 8.5)), tank(zombie(h, 8.5, 11)), tank(zombie(h, 8.5, 13.5)));
            swing(p, main, true);
            h.assertTrue(hurt(main), "main target hit");
            for (Zombie z : others) if (hurt(z)) got[run]++;
            main.discard();
            others.forEach(Zombie::discard);
        }
        h.assertValueEqual(got[0], 1, "Lv1 arcs once");
        h.assertValueEqual(got[1], 3, "Lv10 arcs to every mob in range");
        done(h, p);
    }

    @GameTest(template = ARENA)
    public static void meteorShockwaveRadius(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MACE));
        boolean[] far = new boolean[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            give(p, "meteor", run == 0 ? 1 : 10);
            Zombie main = tank(zombie(h, 8.5, 6));
            Zombie near = tank(zombie(h, 9.5, 6));
            Zombie farZ = tank(zombie(h, 8.5, 11));
            charge(p);
            p.fallDistance = 4;
            p.setOnGround(false);
            p.attack(main);
            Hooks.endTick();
            h.assertTrue(hurt(near), "shockwave hits a mob next to the target");
            far[run] = hurt(farZ);
            List.of(main, near, farZ).forEach(Zombie::discard);
        }
        h.assertFalse(far[0], "Lv1 shockwave (R2) misses a mob 5 blocks away");
        h.assertTrue(far[1], "Lv10 shockwave (R7) reaches it");
        done(h, p);
    }

    @GameTest(template = ARENA)
    public static void skyfallBounceAndNoFallDamage(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MACE));
        double[] vy = new double[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            give(p, "skyfall", run == 0 ? 1 : 10);
            Zombie main = tank(zombie(h, 8.5, 6));
            charge(p);
            p.fallDistance = 4;
            p.setOnGround(false);
            p.attack(main);
            Hooks.endTick();
            vy[run] = p.getDeltaMovement().y;
            LivingFallEvent fall = new LivingFallEvent(p, 10, 1);
            NeoForge.EVENT_BUS.post(fall);
            h.assertValueEqual(fall.getDamageMultiplier(), 0f, "the landing after a bounce does no fall damage");
            main.discard();
        }
        h.assertTrue(vy[0] > 0.6 && vy[0] < 0.85, "Lv1 bounces about 3 blocks, vy=" + vy[0]);
        h.assertTrue(vy[1] > 1.3 && vy[1] < 1.6, "Lv10 bounces about 12 blocks, vy=" + vy[1]);
        done(h, p);
    }

    @GameTest(template = ARENA)
    public static void haymakerLaunchDistance(GameTestHelper h) {
        ServerPlayer p = player(h);
        double[] speed = new double[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            // a card won on the last swing goes into the first free slot, the empty main hand, and Haymaker needs a bare fist
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            give(p, "haymaker", run == 0 ? 1 : 10);
            Zombie z = tank(zombie(h, 8.5, 6));
            swing(p, z, true);
            speed[run] = z.getDeltaMovement().horizontalDistance();
            h.assertTrue(Tracked.has(z, "slam"), "a launched mob is watched for impacts");
            z.discard();
        }
        h.assertTrue(Math.abs(speed[0] - 3 / 8.0) < 0.05, "Lv1 launch speed " + speed[0]);
        h.assertTrue(Math.abs(speed[1] - 12 / 8.0) < 0.05, "Lv10 launch speed " + speed[1]);
        done(h, p);
    }

    @GameTest(template = ARENA)
    public static void heaveCarryLimitAndThrow(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setShiftKeyDown(true);
        give(p, "heave", 1);
        Zombie z = zombie(h, 8.5, 6);
        Chicken c = h.spawn(EntityType.CHICKEN, new Vec3(9.5, 1, 6));
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(p, InteractionHand.MAIN_HAND, z));
        h.assertFalse(z.isPassenger(), "Lv1 can't lift a 20 health zombie");
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(p, InteractionHand.MAIN_HAND, c));
        h.assertTrue(c.getVehicle() == p, "Lv1 lifts a chicken");
        h.assertTrue(c.isNoAi(), "carried mob is calm");
        airSwing(p);
        double lv1 = c.getDeltaMovement().length();
        h.assertFalse(c.isPassenger(), "an air swing throws it");
        h.assertFalse(c.isNoAi(), "AI back after the throw");

        clearSkills(p);
        give(p, "heave", 10);
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(p, InteractionHand.MAIN_HAND, z));
        h.assertTrue(z.getVehicle() == p, "Lv10 lifts a zombie");
        airSwing(p);
        h.assertFalse(z.isPassenger(), "thrown");
        double lv10 = z.getDeltaMovement().length();
        h.assertTrue(lv10 > lv1 * 2.5, "Lv10 throws much farther: " + lv1 + " vs " + lv10);
        p.setShiftKeyDown(false);
        done(h, p);
    }

    /** Heave only grabs mobs: a sneak-click on a chest with an empty main hand opens it even with something in the
     * offhand (vanilla skips the block then). */
    @GameTest(template = ARENA)
    public static void heaveSneakClickOpensContainer(GameTestHelper h) {
        ServerPlayer p = player(h);
        net.minecraft.core.BlockPos chest = h.absolutePos(new net.minecraft.core.BlockPos(8, 1, 6));
        h.getLevel().setBlockAndUpdate(chest.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        h.getLevel().setBlockAndUpdate(chest, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TORCH));
        p.setShiftKeyDown(true);
        // FakePlayer.openMenu is a no-op, so the chest's use result stands in for the opened screen
        java.util.function.Supplier<net.minecraft.world.InteractionResult> click = () -> p.gameMode.useItemOn(p, h.getLevel(), ItemStack.EMPTY,
                InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(chest), net.minecraft.core.Direction.NORTH, chest, false));
        h.assertFalse(click.get().consumesAction(), "without Heave: vanilla, the chest stays shut");
        give(p, "heave", 1);
        h.assertTrue(click.get().consumesAction(), "with Heave the sneak-click opens the chest");
        p.setShiftKeyDown(false);
        done(h, p);
    }

    @GameTest(template = ARENA)
    public static void wallSlamImpact(GameTestHelper h) {
        ServerPlayer p = player(h);
        List<TriggerContext> fired = spy(p, "wall_slam");
        float[] lost = new float[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            int lv = run == 0 ? 1 : 10;
            give(p, "wall_slam", lv);
            Zombie z = zombie(h, 8.5, 6);
            HabitsApi.PerkHit hit = M.hit(p, Mech.WALL_SLAM);
            Slam.track(p, z, (float) hit.value(), 0, 20);
            z.setDeltaMovement(0.6, 0, 0);
            z.horizontalCollision = false;
            Tracked.tick();
            z.horizontalCollision = true;
            Tracked.tick();
            lost[run] = z.getMaxHealth() - z.getHealth();
            z.discard();
        }
        h.assertValueEqual(lost[0], 2f, "Lv1 slam damage");
        h.assertValueEqual(lost[1], 8f, "Lv10 slam damage");
        h.assertValueEqual(fired.size(), 2, "each slam fires the wall_slam trigger");
        unspy(p);
        done(h, p);
    }

    @GameTest(template = ARENA)
    public static void graveCallerAlliesCapAndLoyalty(GameTestHelper h) {
        ServerPlayer p = player(h);
        int[] count = new int[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            give(p, "grave_caller", run == 0 ? 1 : 10);
            Zombie dead = zombie(h, 8.5, 8);
            for (int i = 0; i < 6; i++) Allies.raise(p, dead, M.hit(p, Mech.GRAVE_CALLER));
            List<Zombie> allies = h.getLevel().getEntitiesOfClass(Zombie.class, new AABB(h.absolutePos(net.minecraft.core.BlockPos.ZERO)).inflate(20), Allies::isAlly);
            count[run] = allies.size();
            Zombie a = allies.get(0);
            Chicken chick = h.spawn(EntityType.CHICKEN, new Vec3(8.5, 1, 7));
            for (int i = 0; i < 200 && a.getTarget() == null; i++) a.targetSelector.tick();
            h.assertTrue(a.getTarget() == dead, "an ally's own target goal picks the hostile, not the player or the chicken");
            chick.discard();
            a.setTarget(p);
            h.assertTrue(a.getTarget() == dead, "an ally never targets a player");
            a.setTarget(dead);
            h.assertTrue(a.getTarget() == dead, "an ally targets hostiles");
            h.assertFalse(a.hurt(p.damageSources().playerAttack(p), 5), "players can't hurt an ally");
            allies.forEach(Zombie::discard);
            dead.discard();
        }
        h.assertValueEqual(count[0], 1, "Lv1 keeps 1 ally");
        h.assertValueEqual(count[1], 4, "Lv10 keeps 4 allies");
        done(h, p);
    }

    @GameTest(template = ARENA)
    public static void fangStrikeCount(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        int[] fangs = new int[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            give(p, "fang_strike", run == 0 ? 1 : 10);
            Zombie z = tank(zombie(h, 8.5, 7));
            AABB box = new AABB(h.absolutePos(net.minecraft.core.BlockPos.ZERO)).inflate(30);
            for (int i = 0; i < 60 && fangs[run] == 0; i++) {
                PS.of(p).fangReady = 0;
                swing(p, z, true);
                if (h.getLevel().getEntitiesOfClass(EvokerFangs.class, box).isEmpty()) continue;
                for (int k = 0; k < 15; k++) Hooks.endTick(); // fangs spawn one per line per tick
                fangs[run] = h.getLevel().getEntitiesOfClass(EvokerFangs.class, box).size();
            }
            h.getLevel().getEntitiesOfClass(EvokerFangs.class, box).forEach(EvokerFangs::discard);
            z.discard();
        }
        h.assertValueEqual(fangs[0], 3, "Lv1 summons 3 fangs");
        h.assertValueEqual(fangs[1], 12, "Lv10 summons 12 fangs");
        done(h, p);
    }

    @GameTest(template = ARENA)
    public static void deflectorChanceAndAim(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        Skeleton sk = h.spawnWithNoFreeWill(EntityType.SKELETON, new Vec3(8.5, 1, 14));
        int[] swats = new int[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            give(p, "deflector", run == 0 ? 1 : 10);
            p.getRandom().setSeed(42);
            for (int i = 0; i < 200; i++) {
                Arrow a = new Arrow(h.getLevel(), sk, new ItemStack(Items.ARROW), null);
                a.setPos(p.getX(), p.getY() + 1, p.getZ() + 0.5);
                a.setDeltaMovement(0, 0, -1.5);
                if (Special.deflect(p, a)) {
                    swats[run]++;
                    h.assertTrue(a.getOwner() == p, "swatted arrow belongs to the player");
                    h.assertTrue(a.getDeltaMovement().z > 0.5, "and flies back at the shooter");
                }
            }
        }
        h.assertTrue(swats[0] > 4 && swats[0] < 35, "Lv1 swats about 8%: " + swats[0]);
        h.assertTrue(swats[1] > 75 && swats[1] < 125, "Lv10 swats about 50%: " + swats[1]);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        h.assertFalse(Special.deflect(p, new Arrow(h.getLevel(), sk, new ItemStack(Items.ARROW), null)), "needs a sword or axe");
        sk.discard();
        done(h, p);
    }

    @GameTest(template = ARENA)
    public static void sonicBladeEveryNthCrit(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        boolean[] boomed = new boolean[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            give(p, "sonic_blade", run == 0 ? 1 : 10);
            PS.of(p).crits = 0;
            PS.of(p).sonicReady = 0;
            Zombie a = tank(zombie(h, 8.5, 6));
            Zombie far = tank(zombie(h, 8.5, 12));
            for (int i = 0; i < 3; i++) swing(p, a, true);
            boomed[run] = hurt(far);
            a.discard();
            far.discard();
        }
        h.assertFalse(boomed[0], "Lv1 needs 10 crits for a boom");
        h.assertTrue(boomed[1], "Lv10 booms on the 3rd crit, through the first mob");
        done(h, p);
    }

    // ---------------------------------------------------------------- triggers, toggle, fakes

    @GameTest(template = ARENA)
    public static void meleeKillAndCritTriggers(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_SWORD));
        List<TriggerContext> kills = spy(p, "melee_kill"), crits = spy(p, "crit"), hits = spy(p, "melee_hit");
        Zombie z = zombie(h, 8.5, 6);
        z.setHealth(1);
        swing(p, z, true);
        h.assertValueEqual(crits.size(), 1, "crit fired");
        h.assertValueEqual(hits.size(), 1, "melee_hit fired");
        h.assertValueEqual(kills.size(), 1, "melee_kill fired");
        h.assertValueEqual(String.valueOf(kills.get(0).entity()), "minecraft:zombie", "kill entity");
        h.assertValueEqual(String.valueOf(kills.get(0).item()), "minecraft:netherite_sword", "kill item");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Zombie z2 = tank(zombie(h, 8.5, 6));
        swing(p, z2, false);
        h.assertValueEqual(String.valueOf(hits.get(hits.size() - 1).item()), "minecraft:air", "empty hand reports air");
        unspy(p);
        z2.discard();
        done(h, p);
    }

    @GameTest(template = ARENA)
    public static void toggledOffAndFakePlayersDoNothing(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        give(p, "chain_crit", 10);
        p.setData(CoreRegistries.COLLECTION, HabitsApi.collection(p).with(Habits.id("chain_crit"), new SkillProgress(10, 0, false)));
        Zombie main = tank(zombie(h, 8.5, 6)), other = tank(zombie(h, 8.5, 8));
        swing(p, main, true);
        h.assertFalse(hurt(other), "toggled off: no arc");
        give(p, "chain_crit", 10);
        Triggers.TEST_PLAYERS.remove(p.getUUID());
        swing(p, main, true);
        h.assertFalse(hurt(other), "a real fake player gets no perks");
        Triggers.TEST_PLAYERS.add(p.getUUID());
        swing(p, main, true);
        h.assertTrue(hurt(other), "enabled again: arcs");
        main.discard();
        other.discard();
        done(h, p);
    }

    // ---------------------------------------------------------------- mixin and swing-at-air rebuilds

    @GameTest(template = ARENA)
    public static void swordplayMissBreaksCombo(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        int[] after = new int[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            give(p, "swordplay", run == 0 ? 1 : 10);
            int n = run == 0 ? 5 : 3;
            Zombie z = tank(zombie(h, 8.5, 6));
            float before = z.getHealth();
            swing(p, z, false);
            float plain = before - z.getHealth();
            for (int i = 1; i < n - 1; i++) swing(p, z, false); // one hit short of the free slash
            airSwing(p);
            for (int k = 1; k <= 10 && after[run] == 0; k++) {
                before = z.getHealth();
                swing(p, z, false);
                if (before - z.getHealth() > plain * 1.2f) after[run] = k;
            }
            z.discard();
        }
        h.assertValueEqual(after[0], 5, "Lv1: after a miss the slash needs 5 fresh hits");
        h.assertValueEqual(after[1], 3, "Lv10: after a miss the slash needs 3 fresh hits");
        done(h, p);
    }

    @GameTest(template = ARENA)
    public static void whirlwindOnAirSwing(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setShiftKeyDown(true);
        boolean[] far = new boolean[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            give(p, "whirlwind", run == 0 ? 1 : 10);
            PS.of(p).whirlReady = 0;
            Zombie near = tank(zombie(h, 8.5, 6)), farZ = tank(zombie(h, 8.5, 8.5));
            airSwing(p);
            h.assertTrue(hurt(near), "a sneak swing at the air spins");
            far[run] = hurt(farZ);
            near.discard();
            farZ.discard();
        }
        p.setShiftKeyDown(false);
        h.assertFalse(far[0], "Lv1 spin (R2) misses a mob 4 blocks away");
        h.assertTrue(far[1], "Lv10 spin (R5) reaches it");
        done(h, p);
    }

    @GameTest(template = ARENA)
    public static void shieldBashOnAirSwing(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        p.setShiftKeyDown(true);
        double[] speed = new double[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            give(p, "shield_bash", run == 0 ? 1 : 10);
            Zombie z = tank(zombie(h, 8.5, 6.5));
            airSwing(p);
            h.assertTrue(hurt(z), "a sneak swing at the air bashes the mob in front");
            speed[run] = z.getDeltaMovement().horizontalDistance();
            z.discard();
        }
        p.setShiftKeyDown(false);
        h.assertTrue(Math.abs(speed[0] - 3 / 8.0) < 0.05, "Lv1 shove speed " + speed[0]);
        h.assertTrue(Math.abs(speed[1] - 10 / 8.0) < 0.05, "Lv10 shove speed " + speed[1]);
        done(h, p);
    }

    /** MobMixin: a dazed hoglin (brain AI) skips its AI step; Concussion Lv1 dazes 0.5 s, Lv10 2.5 s. */
    @GameTest(template = ARENA)
    public static void dazeStopsBrainMobs(GameTestHelper h) {
        ServerPlayer p = player(h);
        boolean[] still = new boolean[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            give(p, "concussion", run == 0 ? 1 : 10);
            Hoglin hg = h.spawn(EntityType.HOGLIN, new Vec3(8.5, 1, 8));
            hg.setImmuneToZombification(true);
            hg.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET, p);
            OnHit.daze(hg, M.ticks(M.p(M.hit(p, Mech.CONCUSSION), "seconds")));
            h.assertTrue(hg.getTarget() == null, "a dazed brain mob forgets its target");
            hg.setNoActionTime(0);
            hg.aiStep();
            h.assertValueEqual(hg.getNoActionTime(), 0, "a dazed hoglin skips its AI step");
            for (int i = 0; i < 20; i++) Tracked.tick();
            hg.aiStep();
            still[run] = hg.getNoActionTime() == 0;
            hg.discard();
        }
        h.assertFalse(still[0], "Lv1 daze (0.5 s) is over after 1 s");
        h.assertTrue(still[1], "Lv10 daze (2.5 s) still holds after 1 s");
        done(h, p);
    }

    /** MeleeItemStackMixin: a pickaxe on its last point survives Improviser hits; Lv10 hits harder than Lv1. */
    @GameTest(template = ARENA)
    public static void improviserNoWear(GameTestHelper h) {
        ServerPlayer p = player(h);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(pick.getMaxDamage() - 1);
        p.setItemInHand(InteractionHand.MAIN_HAND, pick);
        float[] drop = new float[2];
        for (int run = 0; run < 2; run++) {
            clearSkills(p);
            give(p, "improviser", run == 0 ? 1 : 10);
            Zombie z = tank(zombie(h, 8.5, 6));
            swing(p, z, false);
            drop[run] = z.getMaxHealth() - z.getHealth();
            h.assertTrue(p.getMainHandItem() == pick && pick.getDamageValue() == pick.getMaxDamage() - 1, "no wear from the hit");
            z.discard();
        }
        h.assertTrue(drop[1] > drop[0], "Lv10 hits harder: " + drop[0] + " vs " + drop[1]);
        clearSkills(p);
        Zombie z = tank(zombie(h, 8.5, 6));
        swing(p, z, false);
        // the stack itself, not the slot: a card won from the hit (ITEM_BREAK, say) goes into the freed hand slot
        h.assertTrue(pick.isEmpty(), "without Improviser the same hit breaks it");
        z.discard();
        done(h, p);
    }

    /** FishingHookMixin: Disarm's second trigger fires on the reel, not when the bobber lands. */
    @GameTest(template = ARENA)
    public static void disarmTriggerOnReel(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
        List<TriggerContext> got = spy(p, "disarm");
        Zombie z = zombie(h, 8.5, 7.5);
        z.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        FishingHook hook = new FishingHook(p, h.getLevel(), 0, 0);
        h.getLevel().addFreshEntity(hook);
        hook.setHookedEntity(z); // public through the access transformer (a flown bobber is at the mercy of the test world)
        h.assertTrue(got.isEmpty(), "hooking alone doesn't count");
        hook.retrieve(p.getMainHandItem());
        h.assertValueEqual(got.size(), 1, "reeling it in fires Disarm");
        z.discard();
        done(h, p);
    }

    // ---------------------------------------------------------------- performance

    /** Median ms per hit: [0] the work inside the damage events (runs on every hit), [1] the end-of-tick work the hit
     * queued (area damage, spawns; only when a perk procs). 30 tanky zombies packed around the target so every area
     * effect finds victims; crits, smashes and cooldowns are forced so every perk procs on every hit. */
    static double[] timeHits(ServerPlayer p, List<Mob> mobs, Mob t, int warm, int n) {
        var src = p.damageSources().playerAttack(p);
        long[] sync = new long[n], deferred = new long[n];
        for (int i = 0; i < warm + n; i++) {
            long t0 = System.nanoTime();
            PS s = PS.of(p);
            s.attackTick = Hooks.now(p);
            s.mainTarget = t.getId();
            s.critTick = s.attackTick;
            s.critTarget = t.getId();
            s.fangReady = s.sonicReady = s.stormReady = s.whirlReady = 0;
            p.fallDistance = 4; // keeps the mace smash path live (Skyfall resets it)
            float amt = Damage.scale(t, src, p, 6);
            OnHit.hit(p, t, amt);
            long t1 = System.nanoTime();
            Hooks.endTick();
            if (i >= warm) {
                sync[i - warm] = t1 - t0;
                deferred[i - warm] = System.nanoTime() - t1;
            }
            for (Mob m : mobs) {
                m.setHealth(1000);
                m.invulnerableTime = 0;
            }
        }
        // median, not mean: every perk procs on every hit, so hits cost the same and a GC pause is the only outlier
        java.util.Arrays.sort(sync);
        java.util.Arrays.sort(deferred);
        return new double[]{sync[n / 2] / 1e6, deferred[n / 2] / 1e6};
    }

    static ItemStack handFor(Mech m) {
        return switch (m) {
            case CLEAVE, HEADHUNTER, SUNDER, EARTHSPLITTER, GUILLOTINE, ARMOR_CRUSHER -> new ItemStack(Items.IRON_AXE);
            case METEOR, SKYFALL, GALE_STRIKE, GRAVITY_WELL -> new ItemStack(Items.MACE);
            case HAYMAKER, BRAWLER, HEAVE -> ItemStack.EMPTY;
            case UNDERTOW -> new ItemStack(Items.TRIDENT);
            case HOME_RUN -> new ItemStack(Items.IRON_SHOVEL);
            case SPLINTERS -> new ItemStack(Items.WOODEN_SWORD);
            default -> new ItemStack(Items.IRON_SWORD);
        };
    }

    /** Budgets, every melee mechanic alone at Lv10: under 0.05 ms per hit inside the damage events, under 0.25 ms for
     * the area work one proc queues (capped at {@link M#MAX_AOE} mobs). The per-player tick stays under 0.05 ms.
     * All 76 at once is measured and logged for the docs. */
    @GameTest(template = ARENA, timeoutTicks = 1200, batch = "melee_perf") // alone: Tracked.clear() would drop other tests' queued work
    public static void perfBudget(GameTestHelper h) {
        ServerPlayer p = player(h);
        List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < 30; i++) mobs.add(tank(zombie(h, 3 + (i % 6) * 2, 6 + (i / 6) * 2)));
        Mob t = mobs.get(14);
        p.fallDistance = 4; // mace smash path
        p.setOnGround(false);
        double worst = 0, worstProc = 0;
        Mech worstM = null, worstProcM = null;
        StringBuilder top = new StringBuilder();
        // JIT warm-up with everything on, so the first mechanic measured isn't timed cold
        for (Mech m : Mech.values()) give(p, m.skill, 10);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MACE));
        timeHits(p, mobs, t, 0, 500);
        Tracked.clear();
        for (Mech m : Mech.values()) {
            clearSkills(p);
            give(p, m.skill, 10);
            p.setItemInHand(InteractionHand.MAIN_HAND, handFor(m));
            double[] ms = timeHits(p, mobs, t, 50, 400), again = timeHits(p, mobs, t, 0, 400);
            ms[0] = Math.min(ms[0], again[0]); // best of two passes: one GC pause shouldn't fail the budget
            ms[1] = Math.min(ms[1], again[1]);
            if (ms[0] + ms[1] > 0.015) top.append(m.name().toLowerCase()).append('=')
                    .append(String.format("%.3f+%.3f", ms[0], ms[1])).append(' ');
            if (ms[0] > worst) {
                worst = ms[0];
                worstM = m;
            }
            if (ms[1] > worstProc) {
                worstProc = ms[1];
                worstProcM = m;
            }
            Tracked.clear();
        }
        clearSkills(p);
        for (Mech m : Mech.values()) give(p, m.skill, 10);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        double[] allMs = timeHits(p, mobs, t, 100, 1000);
        String all = String.format("%.4f in events + %.4f queued", allMs[0], allMs[1]);
        long tickTotal = 0;
        int n = 2000;
        for (int i = 0; i < 200 + n; i++) {
            long t0 = System.nanoTime();
            Ticks.tick(p);
            p.tickCount++;
            if (i >= 200) tickTotal += System.nanoTime() - t0;
        }
        double perTick = tickTotal / 1e6 / n;
        // mixin hooks, worst case: something tracked (so no early-out), and a player who owns Improviser
        OnHit.lose(mobs.get(0), 100);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        boolean sink = false;
        long m0 = System.nanoTime();
        for (int i = 0; i < 200_000; i++) sink ^= Hooks.dazed(mobs.get(i % 30)) | Hooks.noHitWear(pick, p);
        double perMixin = (System.nanoTime() - m0) / 1e6 / 400_000;
        com.mojang.logging.LogUtils.getLogger().info("melee mixin hooks: {} ms per call ({})", String.format("%.6f", perMixin), sink);
        var log = com.mojang.logging.LogUtils.getLogger();
        log.info("melee perf: worst in-event {} = {} ms/hit; worst queued proc {} = {} ms; all 76 at Lv10 = {} ms/hit; player tick = {} ms",
                worstM, String.format("%.4f", worst), worstProcM, String.format("%.4f", worstProc), all, String.format("%.4f", perTick));
        log.info("melee perf over 0.015 ms/hit (in events + queued): {}", top);
        mobs.forEach(Mob::discard);
        Tracked.clear();
        p.fallDistance = 0;
        h.assertTrue(perTick < 0.05, "per-tick budget: " + perTick + " ms");
        h.assertTrue(perMixin < 0.001, "mixin hook budget: " + perMixin + " ms");
        h.assertTrue(worst < 0.05, "in-event budget, worst " + worstM + ": " + worst + " ms");
        h.assertTrue(worstProc < 0.25, "queued proc budget, worst " + worstProcM + ": " + worstProc + " ms");
        done(h, p);
    }

    /** boss_hit_cap 25: a player hit of 4x a Wither's max health takes at most 25% of it; 0 leaves the hit alone. */
    @GameTest(template = "empty")
    public static void bossHitCapLimitsOneHit(GameTestHelper h) {
        var cap = com.habits.perk.PerkInit.BOSS_HIT_CAP;
        double was = cap.get();
        ServerPlayer p = player(h);
        try {
            cap.set(25.0);
            var w = h.spawnWithNoFreeWill(EntityType.WITHER, new Vec3(1.5, 3, 1.5));
            float max = w.getMaxHealth(); // danger scaling may have raised it
            w.hurt(h.getLevel().damageSources().playerAttack(p), max * 4);
            h.assertTrue(w.isAlive() && w.getHealth() >= max * 0.75f && w.getHealth() < max, "capped hit, health " + w.getHealth() + "/" + max);
            w.discard();
            cap.set(0.0);
            var w2 = h.spawnWithNoFreeWill(EntityType.WITHER, new Vec3(1.5, 3, 1.5));
            w2.hurt(h.getLevel().damageSources().playerAttack(p), w2.getMaxHealth() * 4);
            h.assertTrue(!w2.isAlive() || w2.getHealth() <= 0, "uncapped hit kills, health " + w2.getHealth());
            w2.discard();
        } finally {
            cap.set(was);
        }
        done(h, p);
    }
}
