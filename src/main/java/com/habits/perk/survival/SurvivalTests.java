package com.habits.perk.survival;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.PerkMechanics;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import com.habits.core.TargetKind;
import com.habits.core.TriggerType;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Survival GameTests. FakePlayers are invulnerable and never tick, so tests build the NeoForge events by hand and call our
 * handlers directly (the same code the event bus runs). Chance perks are checked statistically with wide margins.
 */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class SurvivalTests {
    private SurvivalTests() {}

    @GameTest(template = "empty")
    public static void bedTriggersRollOncePerDay(GameTestHelper h) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-bed"));
        h.assertTrue(Life.firstToday(p, 0), "first blocked sleep rolls");
        h.assertFalse(Life.firstToday(p, 0), "spamming the bed the same day does not");
        h.assertTrue(Life.firstToday(p, 1), "a day nap is counted apart");
        Life.forget(p);
        h.assertTrue(Life.firstToday(p, 0), "forgotten on logout");
        Life.forget(p);
        h.succeed();
    }

    static ServerPlayer player(GameTestHelper h, String skill, int level) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-surv"));
        p.moveTo(h.absoluteVec(new Vec3(1.5, 2, 1.5)));
        p.setData(CoreRegistries.COLLECTION, level <= 0 ? HabitsCollection.EMPTY
                : HabitsCollection.EMPTY.with(Habits.id(skill), new SkillProgress(level, 0, true)));
        return p;
    }

    static LivingIncomingDamageEvent hit(net.minecraft.world.entity.LivingEntity victim, net.minecraft.world.damagesource.DamageSource src, float amount) {
        LivingIncomingDamageEvent e = new LivingIncomingDamageEvent(victim, new DamageContainer(src, amount));
        Defense.incoming(e);
        return e;
    }

    /** Gills Lv10: air drains 4x slower underwater, and drowning hurts half as much. */
    @GameTest(template = "empty")
    public static void gillsSlowsAirAndDrowning(GameTestHelper h) {
        ServerPlayer p = player(h, "gills", 10);
        int used = 0;
        for (int i = 0; i < 400; i++) {
            var e = new net.neoforged.neoforge.event.entity.living.LivingBreatheEvent(p, false, 1, 0);
            Defense.breathe(e);
            used += e.getConsumeAirAmount();
        }
        h.assertValueEqual(used, 100, "air used over 400 ticks");
        var refill = new net.neoforged.neoforge.event.entity.living.LivingBreatheEvent(p, true, 1, 4);
        Defense.breathe(refill);
        h.assertValueEqual(refill.getRefillAirAmount(), 4, "breathing refill untouched");
        var e = hit(p, p.damageSources().drown(), 10);
        h.assertTrue(Math.abs(e.getAmount() - 5) < 0.01, "drown damage halved, got " + e.getAmount());
        ServerPlayer none = player(h, "gills", 0);
        var e0 = new net.neoforged.neoforge.event.entity.living.LivingBreatheEvent(none, false, 1, 0);
        Defense.breathe(e0);
        h.assertValueEqual(e0.getConsumeAirAmount(), 1, "no skill, vanilla air loss");
        h.succeed();
    }

    /** Slime Heart: an oozed ally slime fights while its ally time lasts, then poofs away. A wild slime stays. */
    @GameTest(template = "empty")
    public static void slimeHeartAllyDespawnsWhenTimeEnds(GameTestHelper h) {
        ServerPlayer p = player(h, "slime_heart", 10);
        HabitsApi.PerkHit ooze = Sk.SLIME_HEART.hit(p);
        h.assertTrue(ooze != null, "Slime Heart active");
        Life.oozeSlime(p, ooze);
        var ally = h.getEntities(EntityType.SLIME).stream().filter(Life::isAlly).findFirst().orElseThrow();
        h.assertFalse(ally.hurt(p.damageSources().playerAttack(p), 1), "players can't hurt the ally");
        var wild = h.spawn(EntityType.SLIME, new BlockPos(3, 2, 3));
        Runnable brainTick = () -> {
            for (var s : java.util.List.of(ally, wild)) {
                s.tickCount = 20; // the ally brain runs every 10 ticks
                Life.allyTick(new net.neoforged.neoforge.event.tick.EntityTickEvent.Post(s));
            }
        };
        brainTick.run();
        h.assertTrue(ally.isAlive(), "the ally lives while its time lasts");
        ally.getPersistentData().putLong(Life.ALLY_UNTIL, h.getLevel().getGameTime() - 1);
        brainTick.run();
        h.assertTrue(ally.isRemoved(), "the ally is gone once its time ends");
        h.assertTrue(wild.isAlive(), "control: a wild slime is untouched");
        wild.discard();
        h.succeed();
    }

    // ------------------------------------------------------------------ catalog

    @GameTest(template = "empty")
    public static void naturalHealerRefundsOnlyWhatTheHealCost(GameTestHelper h) {
        ServerPlayer p = player(h, "natural_healer", 1);
        p.getFoodData().setFoodLevel(20);
        p.setHealth(5);
        float cut = (float) Sk.p(Sk.NATURAL_HEALER.hit(p), "exhaustion_cut") / 100;
        p.heal(1); // not FoodData's regen (an effect, a perk, another mod): untouched, no refund
        h.assertTrue(p.getHealth() == 6 && !Sk.data(p).contains("healRefund"), "a non-regen heal is ignored, got " + p.getHealth());
        p.getFoodData().setSaturation(2f);
        for (int i = 0; i < 10; i++) p.getFoodData().tick(p); // a saturation heal with 2 saturation left costs 2 exhaustion
        h.assertTrue(p.getHealth() > 6, "regen healed");
        h.assertTrue(Math.abs(Sk.data(p).getFloat("healRefund") - 2f * cut) < 1e-4, "refund scales with the heal");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void soulKeeperStoresNothingUnderKeepInventory(GameTestHelper h) {
        ServerPlayer p = player(h, "soul_keeper", 10);
        p.totalExperience = 1000;
        var e = new net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent(p, null, 0); // keepInventory: nothing drops
        Life.xpDrop(e);
        h.assertFalse(Sk.data(p).contains("keptXp"), "keepInventory already keeps the XP");
        Life.xpDrop(new net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent(p, null, 100));
        int kept = Sk.data(p).getInt("keptXp");
        h.assertTrue(kept > 0 && kept <= 1000, "kept at most all of it, got " + kept);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void respawnTopsUpLateMaxHealthOnlyIfUnhurt(GameTestHelper h) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-respawn"));
        var d = Sk.data(p);
        long now = p.level().getGameTime();
        p.setHealth(20);
        d.putFloat("respawnHp", 20);
        d.putLong("respawnUntil", now + 60);
        var bonus = ResourceLocation.fromNamespaceAndPath("habits", "test_bonus");
        p.getAttribute(Attributes.MAX_HEALTH).addOrUpdateTransientModifier(new AttributeModifier(bonus, 4, AttributeModifier.Operation.ADD_VALUE));
        Ticks.respawnTopUp(p, d, now);
        h.assertTrue(p.getHealth() == 24, "late bonus filled, got " + p.getHealth());
        p.setHealth(15);
        Ticks.respawnTopUp(p, d, now + 20);
        h.assertTrue(p.getHealth() == 15 && !d.contains("respawnUntil"), "a hit ends the top-up");
        d.putFloat("respawnTarget", 22); // End exit or login: back to the saved health, not to max
        Life.topUp(p);
        Ticks.respawnTopUp(p, d, p.level().getGameTime());
        h.assertTrue(p.getHealth() == 22, "filled to the saved health, got " + p.getHealth());
        p.getAttribute(Attributes.MAX_HEALTH).removeModifier(bonus);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void saturationScrapStillHeals(GameTestHelper h) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-scrap"));
        p.setHealth(10);
        var fd = p.getFoodData();
        fd.setFoodLevel(20);
        fd.setSaturation(0.008f); // the stuck save: full bar, a scrap of saturation, perks keeping exhaustion low
        for (int t = 0; t < 81; t++) {
            fd.setExhaustion(0);
            fd.tick(p);
        }
        h.assertTrue(p.getHealth() >= 11, "the plain heal runs, got " + p.getHealth());
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void everySkillLoadsWithItsMechanic(GameTestHelper h) {
        int n = 0;
        for (Sk s : Sk.values()) {
            String name = s.skill.getPath();
            SkillDef def = SkillRegistry.get(s.skill);
            h.assertTrue(def != null, "skill " + name + " loaded");
            h.assertTrue(def.perk().is(s.id), name + " uses its mechanic");
            h.assertTrue(PerkMechanics.get(s.id) != null, s.id + " registered");
            n++;
        }
        h.assertValueEqual(n, 141, "survival mechanics");
        h.succeed();
    }

    /** User rule: every damage type a player can take has a skill. dry_out only hits water mobs, so it is the one exception. */
    @GameTest(template = "empty")
    public static void everyDamageTypeHasASkill(GameTestHelper h) {
        var access = h.getLevel().registryAccess();
        StringBuilder missing = new StringBuilder();
        for (var key : access.registryOrThrow(Registries.DAMAGE_TYPE).registryKeySet()) {
            ResourceLocation id = key.location();
            if (!id.getNamespace().equals("minecraft") || id.getPath().equals("dry_out")) continue;
            boolean found = false;
            for (TriggerType t : new TriggerType[]{TriggerType.DAMAGE_TAKEN, TriggerType.DIE})
                for (SkillDef d : SkillRegistry.byTrigger(t))
                    if (d.trigger().match().isPresent() && d.trigger().match().get().test(id, TargetKind.DAMAGE_TYPE, access)) found = true;
            if (id.getPath().equals("out_of_world")) found |= SkillRegistry.get(Habits.id("void_bounce")) != null;
            if (!found) missing.append(id).append(' ');
        }
        h.assertTrue(missing.isEmpty(), "damage types with no skill: " + missing);
        h.succeed();
    }

    // ------------------------------------------------------------------ top picks: effect + Lv1 vs Lv10

    @GameTest(template = "empty")
    public static void pincushionCatchesMoreAtLv10(GameTestHelper h) {
        int lo = catches(h, 1), hi = catches(h, 10);
        // Lv1 4%, Lv10 45% of 400
        h.assertTrue(lo < 40, "Lv1 catches few: " + lo);
        h.assertTrue(hi > 130 && hi < 240, "Lv10 catches about 180: " + hi);
        h.succeed();
    }

    static int catches(GameTestHelper h, int level) {
        ServerPlayer p = player(h, "pincushion", level);
        Skeleton sk = new Skeleton(EntityType.SKELETON, h.getLevel());
        int caught = 0;
        for (int i = 0; i < 400; i++) {
            Arrow a = new Arrow(EntityType.ARROW, h.getLevel());
            if (hit(p, h.getLevel().damageSources().arrow(a, sk), 4).isCanceled()) caught++;
        }
        int arrows = p.getInventory().countItem(Items.ARROW);
        h.assertValueEqual(arrows, caught, "every caught arrow is in the inventory");
        return caught;
    }

    @GameTest(template = "empty")
    public static void returnToSenderReflectsAndDoubles(GameTestHelper h) {
        int lo = reflects(h, 1, null), hi = reflects(h, 10, null);
        h.assertTrue(lo < 45, "Lv1 about 5% of 400: " + lo);
        h.assertTrue(hi > 150 && hi < 250, "Lv10 about 50% of 400: " + hi);
        Arrow[] last = new Arrow[1];
        reflects(h, 10, last);
        ServerPlayer p = (ServerPlayer) last[0].getOwner();
        h.assertTrue(p != null, "reflected arrow belongs to the player");
        Zombie z = new Zombie(EntityType.ZOMBIE, h.getLevel());
        var e = hit(z, h.getLevel().damageSources().arrow(last[0], p), 5);
        h.assertValueEqual(e.getAmount(), 10f, "reflected shot hits twice as hard");
        h.succeed();
    }

    static int reflects(GameTestHelper h, int level, Arrow[] keep) {
        ServerPlayer p = player(h, "return_to_sender", level);
        Skeleton sk = new Skeleton(EntityType.SKELETON, h.getLevel());
        sk.moveTo(p.position().add(10, 0, 0));
        int n = 0;
        for (int i = 0; i < 400; i++) {
            Arrow a = new Arrow(EntityType.ARROW, h.getLevel());
            a.setOwner(sk);
            a.moveTo(p.position());
            a.setDeltaMovement(-1, 0, 0);
            ProjectileImpactEvent e = new ProjectileImpactEvent(a, new EntityHitResult(p));
            Defense.impact(e);
            if (e.isCanceled()) {
                n++;
                h.assertTrue(a.getOwner() == p && a.getDeltaMovement().x > 0, "flies back at the skeleton, owned by the player");
                if (keep != null) keep[0] = a;
            }
        }
        return n;
    }

    @GameTest(template = "empty")
    public static void rocketJumperCutsOwnBlast(GameTestHelper h) {
        ServerPlayer p1 = player(h, "rocket_jumper", 1), p10 = player(h, "rocket_jumper", 10);
        float a1 = hit(p1, h.getLevel().damageSources().explosion(p1, p1), 20).getAmount();
        float a10 = hit(p10, h.getLevel().damageSources().explosion(p10, p10), 20).getAmount();
        h.assertTrue(Math.abs(a1 - 14) < 0.01, "Lv1 -30%: " + a1);
        h.assertTrue(Math.abs(a10 - 1) < 0.01, "Lv10 -95%: " + a10);
        ServerPlayer none = player(h, "rocket_jumper", 0);
        h.assertValueEqual(hit(none, h.getLevel().damageSources().explosion(none, none), 20).getAmount(), 20f, "not owned: full damage");
        h.succeed();
    }

    @GameTest(template = "survival_arena")
    public static void lavaWalkerCoolsLavaAndReverts(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int x = 5; x < 14; x++) for (int z = 5; z < 14; z++) h.setBlock(new BlockPos(x, 1, z), Blocks.LAVA);
        int[] counts = new int[3];
        int[] levels = {1, 5, 10};
        for (int i = 0; i < 3; i++) {
            ServerPlayer p = player(h, "lava_walker", levels[i]);
            p.moveTo(h.absoluteVec(new Vec3(9.5, 2, 9.5)));
            p.setOnGround(true);
            Ticks.lavaWalk(p);
            int n = 0;
            for (int x = 5; x < 14; x++) for (int z = 5; z < 14; z++) if (level.getBlockState(h.absolutePos(new BlockPos(x, 1, z))).is(Blocks.BASALT)) n++;
            counts[i] = n;
            TempBlocks.get(level).blocks.replaceAll((k, v) -> new TempBlocks.Entry(v.original(), v.placed(), 0));
            TempBlocks.revertDue(level);
        }
        h.assertValueEqual(counts[0], 0, "Lv1 has no walking");
        h.assertValueEqual(counts[1], 9, "Lv5 radius 1");
        h.assertValueEqual(counts[2], 12, "Lv10 radius 3, capped by the per-tick budget of 12");
        h.assertTrue(level.getBlockState(h.absolutePos(new BlockPos(9, 1, 9))).is(Blocks.LAVA), "basalt melted back");
        ServerPlayer p1 = player(h, "lava_walker", 1), p10 = player(h, "lava_walker", 10);
        h.assertTrue(Math.abs(hit(p1, level.damageSources().lava(), 10).getAmount() - 9) < 0.01, "Lv1 lava -10%");
        h.assertTrue(Math.abs(hit(p10, level.damageSources().lava(), 10).getAmount() - 2.5) < 0.01, "Lv10 lava -75%");
        h.succeed();
    }

    @GameTest(template = "survival_arena")
    public static void wallPhaserPopsFurtherAtLv10(GameTestHelper h) {
        for (int x = 1; x < 16; x++) for (int y = 1; y < 14; y++) for (int z = 1; z < 16; z++) h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
        h.setBlock(new BlockPos(11, 6, 8), Blocks.AIR);
        h.setBlock(new BlockPos(11, 7, 8), Blocks.AIR);
        Vec3 start = h.absoluteVec(new Vec3(8.5, 6, 8.5));
        ServerPlayer p1 = player(h, "wall_phaser", 1);
        p1.moveTo(start);
        var e1 = hit(p1, h.getLevel().damageSources().inWall(), 1);
        h.assertTrue(p1.position().distanceTo(start) < 0.01, "Lv1 searches 1 block: stays");
        h.assertTrue(Math.abs(e1.getAmount() - 0.8f) < 0.01, "Lv1 -20%");
        ServerPlayer p10 = player(h, "wall_phaser", 10);
        p10.moveTo(start);
        var e10 = hit(p10, h.getLevel().damageSources().inWall(), 1);
        // FakePlayer teleports are no-ops (no connection), so ask the search directly where it sends the player
        h.assertTrue(Defense.popOut(p1, 1) == null, "Lv1: nothing within 1 block");
        h.assertValueEqual(Defense.popOut(p10, 6), h.absolutePos(new BlockPos(11, 6, 8)), "Lv10 finds the pocket");
        h.assertTrue(e10.isCanceled(), "Lv10 immune");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void thunderstruckChargesHits(GameTestHelper h) {
        ServerPlayer p1 = player(h, "thunderstruck", 1), p10 = player(h, "thunderstruck", 10);
        var bolt = h.getLevel().damageSources().lightningBolt();
        h.assertTrue(Math.abs(hit(p1, bolt, 5).getAmount() - 4) < 0.01, "Lv1 -20%");
        h.assertTrue(hit(p10, bolt, 5).isCanceled(), "Lv10 immune");
        h.assertValueEqual(Sk.data(p1).getInt("thunder"), 1, "Lv1 one charged hit");
        h.assertValueEqual(Sk.data(p10).getInt("thunder"), 5, "Lv10 five charged hits");
        Zombie z = new Zombie(EntityType.ZOMBIE, h.getLevel());
        z.moveTo(p10.position().add(1, 0, 0));
        hit(z, h.getLevel().damageSources().playerAttack(p10), 3);
        h.assertValueEqual(Sk.data(p10).getInt("thunder"), 4, "a melee hit spends a charge");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void voidBounceSavesAndCostsLevels(GameTestHelper h) {
        int[] spent = new int[2];
        int[] levels = {1, 10};
        for (int i = 0; i < 2; i++) {
            ServerPlayer p = player(h, "void_bounce", levels[i]);
            Vec3 safe = h.absoluteVec(new Vec3(1.5, 2, 1.5));
            var d = Sk.data(p);
            d.putDouble("safeX", safe.x); d.putDouble("safeY", safe.y); d.putDouble("safeZ", safe.z);
            d.putString("safeDim", h.getLevel().dimension().location().toString());
            p.experienceLevel = 40;
            p.moveTo(safe.x, h.getLevel().getMinBuildHeight() - 20, safe.z);
            var e = hit(p, h.getLevel().damageSources().fellOutOfWorld(), 4);
            h.assertTrue(e.isCanceled(), "Lv" + levels[i] + " saved");
            spent[i] = 40 - p.experienceLevel;
            p.moveTo(safe.x, h.getLevel().getMinBuildHeight() - 20, safe.z);
            h.assertFalse(hit(p, h.getLevel().damageSources().fellOutOfWorld(), 4).isCanceled(), "cooldown");
            h.assertValueEqual(d.getLong("voidReady") - h.getLevel().getGameTime(), levels[i] == 1 ? 60L * 60 * 20 : 5L * 60 * 20, "cooldown length");
        }
        h.assertValueEqual(spent[0], 30, "Lv1 costs 30 levels");
        h.assertValueEqual(spent[1], 5, "Lv10 costs 5 levels");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void lastStandLeavesOneHealth(GameTestHelper h) {
        for (int lv : new int[]{1, 10}) {
            ServerPlayer p = player(h, "last_stand", lv);
            p.setHealth(20);
            var e = new LivingDamageEvent.Pre(p, new DamageContainer(h.getLevel().damageSources().generic(), 50));
            Defense.pre(e);
            h.assertValueEqual(e.getNewDamage(), 19f, "Lv" + lv + " leaves 1 health");
            var again = new LivingDamageEvent.Pre(p, new DamageContainer(h.getLevel().damageSources().generic(), 50));
            Defense.pre(again);
            h.assertValueEqual(again.getNewDamage(), 50f, "then on cooldown");
            h.assertValueEqual(Sk.data(p).getLong("lastStandReady") - h.getLevel().getGameTime(), (lv == 1 ? 30L : 3L) * 60 * 20, "cooldown");
        }
        h.succeed();
    }

    @GameTest(template = "survival_arena")
    public static void blinkSnackerAims(GameTestHelper h) {
        int lo = aimed(h, 1), hi = aimed(h, 10);
        h.assertTrue(lo > 20 && lo < 90, "Lv1 about 25% of 200: " + lo);
        h.assertValueEqual(hi, 200, "Lv10 always aimed");
        h.succeed();
    }

    static int aimed(GameTestHelper h, int level) {
        ServerPlayer p = player(h, "blink_snacker", level);
        p.setXRot(-90); // straight up: open sky, away from the other tests' blocks
        Vec3 want = Food.aim(p, Sk.p(Sk.BLINK_SNACKER.hit(p), "range"));
        h.assertTrue(want.y - p.getEyeY() > (level == 10 ? 11 : 7), "range grows with level (the arena roof caps Lv10): " + (want.y - p.getEyeY()));
        int n = 0;
        for (int i = 0; i < 200; i++) {
            Sk.data(p).remove("chorusTick");
            var e = new EntityTeleportEvent.ChorusFruit(p, p.getX() + 3, p.getY(), p.getZ() - 3);
            Food.chorus(e);
            if (Math.abs(e.getTargetY() - want.y) < 0.01) n++;
        }
        return n;
    }

    @GameTest(template = "empty")
    public static void bitterTasteFlipsOncePerBottle(GameTestHelper h) {
        int lo = flips(h, 1), hi = flips(h, 10);
        h.assertTrue(lo < 50, "Lv1 about 10% of 300: " + lo);
        h.assertTrue(hi > 200, "Lv10 about 80% of 300: " + hi);
        ServerPlayer p = player(h, "bitter_taste", 10);
        ItemStack s = PotionContents.createItemStack(Items.POTION, Potions.POISON);
        Food.start(new LivingEntityUseItemEvent.Start(p, s, 32));
        var first = s.get(DataComponents.POTION_CONTENTS);
        for (int i = 0; i < 20; i++) Food.start(new LivingEntityUseItemEvent.Start(p, s, 32));
        h.assertValueEqual(s.get(DataComponents.POTION_CONTENTS), first, "restarting the drink never rerolls");
        h.succeed();
    }

    static int flips(GameTestHelper h, int level) {
        ServerPlayer p = player(h, "bitter_taste", level);
        int n = 0;
        for (int i = 0; i < 300; i++) {
            ItemStack s = PotionContents.createItemStack(Items.POTION, Potions.WEAKNESS);
            Food.start(new LivingEntityUseItemEvent.Start(p, s, 32));
            if (s.get(DataComponents.POTION_CONTENTS).is(Potions.STRENGTH)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ cost budget

    // ------------------------------------------------------------------ mixins (each call below goes through vanilla code)

    @GameTest(template = "empty")
    public static void snowBornWalksOnPowderSnowAtMax(GameTestHelper h) {
        h.assertFalse(net.minecraft.world.level.block.PowderSnowBlock.canEntityWalkOnPowderSnow(player(h, "snow_born", 1)), "Lv1 sinks in powder snow");
        h.assertTrue(net.minecraft.world.level.block.PowderSnowBlock.canEntityWalkOnPowderSnow(player(h, "snow_born", 10)), "Lv10 walks on powder snow");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void webAndBushSlowLessViaMixin(GameTestHelper h) {
        near(h, stuckMove(h, "web_spinner", 0, Blocks.COBWEB), 0.25, "vanilla cobweb");
        near(h, stuckMove(h, "web_spinner", 1, Blocks.COBWEB), 0.4, "Web Spinner Lv1 (-20% slow)");
        near(h, stuckMove(h, "web_spinner", 10, Blocks.COBWEB), 1, "Web Spinner Lv10 (no slow)");
        near(h, stuckMove(h, "bramble_walker", 0, Blocks.SWEET_BERRY_BUSH), 0.8, "vanilla berry bush");
        near(h, stuckMove(h, "bramble_walker", 1, Blocks.SWEET_BERRY_BUSH), 0.86, "Bramble Walker Lv1 (-30% slow)");
        near(h, stuckMove(h, "bramble_walker", 10, Blocks.SWEET_BERRY_BUSH), 1, "Bramble Walker Lv10 (no slow)");
        h.succeed();
    }

    static void near(GameTestHelper h, double got, double want, String what) {
        h.assertTrue(Math.abs(got - want) < 1e-3, what + ": moved " + got + ", want " + want);
    }

    /** How far a 1-block step goes after standing in the block (the block's entityInside calls makeStuckInBlock). */
    static double stuckMove(GameTestHelper h, String skill, int level, net.minecraft.world.level.block.Block block) {
        ServerPlayer p = player(h, skill, level);
        double x = p.getX();
        block.defaultBlockState().entityInside(h.getLevel(), p.blockPosition(), p);
        p.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(1, 0, 0));
        return p.getX() - x;
    }

    @GameTest(template = "empty")
    public static void hardHatAnvilDoesNotChipAtMax(GameTestHelper h) {
        h.assertTrue(anvilAfterHit(h, 1).is(Blocks.CHIPPED_ANVIL), "Lv1: the anvil chips");
        h.assertTrue(anvilAfterHit(h, 10).is(Blocks.ANVIL), "Lv10: the anvil stays whole");
        h.succeed();
    }

    /** A 30-block fall always rolls a chip (5% + 5% per block); the anvil hits the player first. */
    static net.minecraft.world.level.block.state.BlockState anvilAfterHit(GameTestHelper h, int level) {
        ServerPlayer p = player(h, "hard_hat", level);
        BlockPos pos = h.absolutePos(new BlockPos(2, 4, 2));
        h.getLevel().setBlock(pos, Blocks.ANVIL.defaultBlockState(), 3);
        var anvil = net.minecraft.world.entity.item.FallingBlockEntity.fall(h.getLevel(), pos, Blocks.ANVIL.defaultBlockState());
        anvil.setHurtsEntities(2, 40);
        hit(p, h.getLevel().damageSources().anvil(anvil), 10);
        anvil.causeFallDamage(30, 1, h.getLevel().damageSources().fall());
        var s = anvil.getBlockState();
        anvil.discard();
        return s;
    }

    @GameTest(template = "empty")
    public static void vanishingActHidesArmorAtMax(GameTestHelper h) {
        h.assertTrue(pairedHelmet(h, 1, true).is(Items.DIAMOND_HELMET), "Lv1 invisible: other players still see armor");
        h.assertTrue(pairedHelmet(h, 10, false).is(Items.DIAMOND_HELMET), "Lv10 visible: armor shows");
        h.assertTrue(pairedHelmet(h, 10, true).isEmpty(), "Lv10 invisible: armor hidden");
        h.succeed();
    }

    /** The helmet a player who starts tracking this one is told about. */
    static ItemStack pairedHelmet(GameTestHelper h, int level, boolean invisible) {
        ServerPlayer p = player(h, "vanishing_act", level);
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        p.setInvisible(invisible);
        java.util.List<net.minecraft.network.protocol.Packet<?>> sent = new java.util.ArrayList<>();
        new net.minecraft.server.level.ServerEntity(h.getLevel(), p, 1, true, x -> {})
                .sendPairingData(p, new net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor<>(sent::add));
        for (var pk : sent)
            if (pk instanceof net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket eq)
                for (var pair : eq.getSlots()) if (pair.getFirst() == net.minecraft.world.entity.EquipmentSlot.HEAD) return pair.getSecond();
        throw new net.minecraft.gametest.framework.GameTestAssertException("no helmet in the pairing data");
    }

    @GameTest(template = "empty")
    public static void goldStandardBartersFasterAndForgives(GameTestHelper h) {
        h.assertValueEqual(admireTicks(h, 0), 119L, "vanilla admire time");
        h.assertValueEqual(admireTicks(h, 1), 107L, "Lv1 admires 10% shorter");
        h.assertValueEqual(admireTicks(h, 10), 60L, "Lv10 admires 50% shorter");
        h.assertValueEqual(angerTicks(h, 0), 600L, "vanilla anger time");
        h.assertValueEqual(angerTicks(h, 1), 540L, "Lv1 forgives 10% sooner");
        h.assertValueEqual(angerTicks(h, 10), 300L, "Lv10 forgives 50% sooner");
        h.succeed();
    }

    static long admireTicks(GameTestHelper h, int level) {
        ServerPlayer p = player(h, "gold_standard", level);
        var pig = h.spawn(EntityType.PIGLIN, new Vec3(2.5, 2, 2.5));
        pig.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.NEAREST_VISIBLE_PLAYER, p);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.GOLD_INGOT));
        net.minecraft.world.entity.monster.piglin.PiglinAi.mobInteract(pig, p, net.minecraft.world.InteractionHand.MAIN_HAND);
        long t = pig.getBrain().getTimeUntilExpiry(net.minecraft.world.entity.ai.memory.MemoryModuleType.ADMIRING_ITEM);
        pig.discard();
        return t;
    }

    static long angerTicks(GameTestHelper h, int level) {
        ServerPlayer p = player(h, "gold_standard", level);
        var pig = h.spawn(EntityType.PIGLIN, new Vec3(2.5, 2, 2.5));
        net.minecraft.world.entity.monster.piglin.PiglinAi.angerNearbyPiglins(p, false);
        long t = pig.getBrain().getTimeUntilExpiry(net.minecraft.world.entity.ai.memory.MemoryModuleType.ANGRY_AT);
        pig.discard();
        return t;
    }

    // the arena, not "empty" (3x3x3): only the template's chunks are force-loaded, and a zombie 10 blocks out can land in an unloaded one
    @GameTest(template = "survival_arena")
    public static void senseOutlineIsOnlyForTheOwner(GameTestHelper h) {
        Zombie z = h.spawn(EntityType.ZOMBIE, new Vec3(1.5, 2, 11.5)); // 10 blocks from the player
        ServerPlayer lo = player(h, "spider_sense", 1), hi = player(h, "spider_sense", 10);
        SurvivalNet.last = null;
        Ticks.glowEnemies(lo, Sk.SPIDER_SENSE.v(lo), 30);
        h.assertTrue(SurvivalNet.last == null || !SurvivalNet.last.ids().contains(z.getId()), "Lv1 (4 blocks) does not reach it");
        Ticks.glowEnemies(hi, Sk.SPIDER_SENSE.v(hi), 30);
        h.assertTrue(SurvivalNet.last != null && SurvivalNet.last.ids().contains(z.getId()), "Lv10 (24 blocks) outlines it");
        h.assertFalse(z.isCurrentlyGlowing(), "no vanilla Glowing, so other players see nothing");
        z.discard();
        h.succeed();
    }

    /**
     * Performance budget (user rule: invisible in a profiler). Times the hottest paths: the damage handler on a mob (runs on
     * every hit in the world), the damage handler on a player who owns every survival skill, and the per-tick handler for
     * that player. Budgets are ~20x what a dev machine measured (78 ns, 1.0 us, 1.5 us), so a slow CI box still passes while a regression to
     * per-hit scans or allocation storms fails.
     */
    @GameTest(template = "survival_arena", timeoutTicks = 400)
    public static void costBudget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer p = player(h, "pincushion", 0);
        HabitsCollection all = HabitsCollection.EMPTY;
        for (Sk s : Sk.values()) all = all.with(s.skill, new SkillProgress(10, 0, true));
        p.setData(CoreRegistries.COLLECTION, all);
        p.experienceLevel = 0; // keep Void Bounce out of it
        Zombie z = new Zombie(EntityType.ZOMBIE, level);
        z.moveTo(h.absoluteVec(new Vec3(3.5, 2, 3.5)));
        var mobHit = level.damageSources().mobAttack(z);
        var playerHit = level.damageSources().playerAttack(p);
        for (int i = 0; i < 20000; i++) { hit(z, playerHit, 1); hit(p, mobHit, 1); } // warm up the JIT

        int n = 100000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) hit(z, playerHit, 1);
        double mobNs = (System.nanoTime() - t0) / (double) n;

        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) { p.invulnerableTime = 0; hit(p, mobHit, 1); }
        double ownerNs = (System.nanoTime() - t0) / (double) n;

        int ticks = 20000;
        PlayerTickEvent.Post tick = new PlayerTickEvent.Post(p);
        t0 = System.nanoTime();
        for (int i = 0; i < ticks; i++) { p.tickCount++; Ticks.tick(tick); }
        double tickNs = (System.nanoTime() - t0) / (double) ticks;

        com.mojang.logging.LogUtils.getLogger().info("[habits] survival cost: mob hit {} ns, owner hit {} ns, owner tick {} ns", (int) mobNs, (int) ownerNs, (int) tickNs);
        h.assertTrue(mobNs < 2_000, "hit on a mob (player attacker, every perk owned) under 2 us: " + mobNs);
        h.assertTrue(ownerNs < 20_000, "hit on an all-skills player under 20 us: " + ownerNs);
        h.assertTrue(tickNs < 30_000, "all-skills player tick under 30 us, averaged over the once-a-second work: " + tickNs);
        h.succeed();
    }

    // ------------------------------------------------------------------ Ninth Life

    static void safeAt(ServerPlayer p, Vec3 at) {
        var d = Sk.data(p);
        d.putDouble("safeX", at.x);
        d.putDouble("safeY", at.y);
        d.putDouble("safeZ", at.z);
        d.putString("safeDim", p.level().dimension().location().toString());
    }

    /**
     * Owned with Last Stand, Last Stand goes first (1 health, no teleport, Ninth Life untouched). Once Last Stand is on
     * cooldown the death goes through to Ninth Life: back on safe ground with Lv10's 10 hearts, then its 15 min cooldown.
     * /kill is never undone.
     */
    @GameTest(template = "empty")
    public static void ninthLifeBacksUpLastStand(GameTestHelper h) {
        ServerPlayer p = player(h, "ninth_life", 10);
        p.setData(CoreRegistries.COLLECTION, HabitsApi.collection(p).with(Habits.id("last_stand"), new SkillProgress(1, 0, true)));
        Vec3 safe = h.absoluteVec(new Vec3(3.5, 2, 3.5));
        safeAt(p, safe);
        var src = h.getLevel().damageSources().generic();
        p.setHealth(10);
        var first = new LivingDamageEvent.Pre(p, new DamageContainer(src, 50));
        Defense.pre(first);
        h.assertValueEqual(first.getNewDamage(), 9f, "Last Stand catches the first killing blow");
        h.assertValueEqual(Sk.data(p).getLong("ninthReady"), 0L, "Ninth Life not spent");
        var second = new LivingDamageEvent.Pre(p, new DamageContainer(src, 50));
        Defense.pre(second);
        h.assertValueEqual(second.getNewDamage(), 50f, "Last Stand on cooldown");
        p.setHealth(0);
        var death = new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(p, src);
        Life.death(death);
        h.assertTrue(death.isCanceled(), "Ninth Life cancels the death");
        h.assertValueEqual(p.getHealth(), 20f, "Lv10 comes back with 10 hearts");
        h.assertTrue(p.position().distanceTo(safe) < 0.01, "put back on safe ground, at " + p.position());
        h.assertValueEqual(Sk.data(p).getLong("ninthReady") - h.getLevel().getGameTime(), 15L * 60 * 20, "Lv10 cooldown 15 min");
        p.setHealth(0);
        var again = new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(p, src);
        Life.death(again);
        h.assertFalse(again.isCanceled(), "then on cooldown");
        ServerPlayer q = player(h, "ninth_life", 1);
        q.setHealth(0);
        var kill = new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(q, h.getLevel().damageSources().genericKill());
        Life.death(kill);
        h.assertFalse(kill.isCanceled(), "/kill is never undone");
        q.setHealth(0);
        var lv1 = new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(q, src);
        Life.death(lv1);
        h.assertTrue(lv1.isCanceled() && q.getHealth() == 4f, "Lv1 comes back with 2 hearts, got " + q.getHealth());
        h.succeed();
    }

    /** The clutch_frontier card event: starts at half a heart in deep danger, fires 30 s later if still alive, once. */
    @GameTest(template = "empty")
    public static void clutchFrontierFiresAfter30Seconds(GameTestHelper h) {
        ServerPlayer p = player(h, "ninth_life", 0);
        com.habits.trigger.Triggers.TEST_PLAYERS.add(p.getUUID());
        int[] fired = new int[1];
        com.habits.trigger.Triggers.Spy spy = (who, type, ctx) -> {
            if (who == p && ctx.target() != null && ctx.target().getPath().equals("clutch_frontier")) fired[0]++;
        };
        com.habits.trigger.Triggers.SPIES.add(spy);
        try {
            var d = Sk.data(p);
            Ticks.clutch(p, d, 1000, false);
            h.assertFalse(d.contains("clutchSince"), "half a heart in the safe zone starts nothing");
            Ticks.clutch(p, d, 1000, true);
            Ticks.clutch(p, d, 1599, false);
            h.assertValueEqual(fired[0], 0, "not yet at 29.95 s");
            Ticks.clutch(p, d, 1600, false);
            Ticks.clutch(p, d, 2200, false);
            h.assertValueEqual(fired[0], 1, "fires once at 30 s");
        } finally {
            com.habits.trigger.Triggers.SPIES.remove(spy);
            com.habits.trigger.Triggers.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ Phylactery

    /**
     * Lv10: 4 shields grow in one per 15 s; each blocks a whole mob hit, falls go through; the first lost shield starts a
     * 15 s regrow clock. The card drops from the two Twilight Forest bosses.
     */
    @GameTest(template = "empty")
    public static void phylacteryShieldsBlockHits(GameTestHelper h) {
        SkillDef def = SkillRegistry.get(Habits.id("phylactery"));
        var access = h.getLevel().registryAccess();
        h.assertTrue(def.trigger().type() == TriggerType.KILL_ENTITY
                && def.trigger().match().get().test(ResourceLocation.parse("twilightforest:ur_ghast"), TargetKind.ENTITY, access)
                && def.trigger().match().get().test(ResourceLocation.parse("twilightforest:snow_queen"), TargetKind.ENTITY, access)
                && !def.trigger().match().get().test(ResourceLocation.parse("twilightforest:lich"), TargetKind.ENTITY, access), "found by the Ur-Ghast and the Snow Queen");
        ServerPlayer p = player(h, "phylactery", 10);
        var lich = Sk.PHYLACTERY.hit(p);
        var d = Sk.data(p);
        long t = 1000;
        for (int i = 0; i < 6; i++) Ticks.phylactery(d, t + i * 300L, lich);
        h.assertValueEqual(d.getInt("phyShields"), 4, "Lv10 holds 4 shields");
        Zombie z = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new Vec3(2.5, 2, 2.5));
        var mob = h.getLevel().damageSources().mobAttack(z);
        for (int i = 0; i < 4; i++) h.assertTrue(hit(p, mob, 6).isCanceled(), "shield " + (i + 1) + " blocks");
        h.assertFalse(hit(p, mob, 6).isCanceled(), "no shields left");
        h.assertValueEqual(d.getLong("phyNext") - h.getLevel().getGameTime(), 15L * 20, "regrow clock started by the first lost shield");
        long now = h.getLevel().getGameTime();
        Ticks.phylactery(d, now + 299, lich);
        h.assertValueEqual(d.getInt("phyShields"), 0, "not back yet");
        Ticks.phylactery(d, now + 300, lich);
        h.assertValueEqual(d.getInt("phyShields"), 1, "one back after 15 s");
        h.assertFalse(hit(p, h.getLevel().damageSources().fall(), 6).isCanceled(), "falls are not hits");
        h.assertValueEqual(d.getInt("phyShields"), 1, "the fall kept the shield");
        h.succeed();
    }
}
