package com.habits.perk.exploration;

import com.habits.Habits;
import com.habits.core.Category;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.PerkSpec;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import com.habits.core.TriggerSpec;
import com.habits.core.TriggerType;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** One GameTest per exploration engine: zone aura, structure fights, fresh chests, discovery, travel, digs and eyes. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class ExplorationTests {
    private ExplorationTests() {}

    static ServerPlayer player(GameTestHelper h, Map<ResourceLocation, Integer> skills) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-explo"));
        X.TEST_PLAYERS.add(p.getUUID());
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        HabitsCollection c = HabitsCollection.EMPTY;
        for (var e : skills.entrySet()) c = c.with(e.getKey(), new SkillProgress(e.getValue(), 0, true));
        p.setData(CoreRegistries.COLLECTION, c);
        return p;
    }

    static ResourceLocation skill(String name) {
        return Habits.id("exploration/" + name);
    }

    /** A test-only skill on an exploration mechanic with its own params and values. */
    static ResourceLocation fixture(String name, String mechanic, Map<String, String> params, List<Double> values) {
        PerkSpec perk = new PerkSpec(Optional.empty(), Optional.of(skill(mechanic)), params, 0, 0, Optional.of(values));
        TriggerSpec never = new TriggerSpec(TriggerType.CUSTOM, Optional.empty(), Optional.empty(), Optional.empty(), 0, 0);
        return SkillRegistry.testFixture(new SkillDef(Habits.id("test/explo_" + name), Rarity.COMMON, Category.EXPLORATION, never,
                ResourceLocation.withDefaultNamespace("stone"), ResourceLocation.withDefaultNamespace("stone"), perk,
                Optional.empty(), Optional.empty(), Optional.empty())).id();
    }

    static List<Double> flat(double v) {
        return java.util.Collections.nCopies(10, v);
    }

    @GameTest(template = "empty")
    public static void zoneAuraGivesEffectOnlyInZone(GameTestHelper h) {
        ResourceLocation in = fixture("zone_in", "summit_wind", Map.of("effect", "minecraft:speed", "min_y", "-1000", "linger", "3"), flat(2));
        ServerPlayer p = player(h, Map.of(in, 10));
        Zones.aura(p, HabitsApi.mechanicHit(p, skill("summit_wind")));
        var eff = p.getEffect(MobEffects.MOVEMENT_SPEED);
        h.assertTrue(eff != null && eff.getAmplifier() == 1 && eff.getDuration() == 80, "speed II for linger + 1 s, got " + eff);
        ResourceLocation out = fixture("zone_out", "summit_wind", Map.of("effect", "minecraft:haste", "min_y", "1000"), flat(1));
        ServerPlayer q = player(h, Map.of(out, 10));
        Zones.aura(q, HabitsApi.mechanicHit(q, skill("summit_wind")));
        h.assertTrue(!q.hasEffect(MobEffects.DIG_SPEED), "no effect below min_y");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void structureFightsScaleOnlyInside(GameTestHelper h) {
        ServerPlayer p = player(h, Map.of(skill("old_walls"), 10, skill("dungeon_slayer"), 10));
        ResourceLocation generic = ResourceLocation.withDefaultNamespace("generic");
        h.assertTrue(Zones.factor(p, skill("old_walls"), "taken", generic) == 1, "outside: no cut");
        Zones.TEST_INSIDE.add(p.getUUID());
        try {
            h.assertTrue(Math.abs(Zones.factor(p, skill("old_walls"), "taken", generic) - 0.7) < 1e-6, "Lv10 old walls: -30%");
            h.assertTrue(Math.abs(Zones.factor(p, skill("dungeon_slayer"), "dealt", generic) - 1.4) < 1e-6, "Lv10 slayer: +40%");
            h.assertTrue(Zones.factor(p, skill("dungeon_slayer"), "taken", generic) == 1, "slayer never cuts damage taken");
        } finally {
            Zones.TEST_INSIDE.remove(p.getUUID());
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void freshLootChestPaysOncePerPlayer(GameTestHelper h) {
        ServerPlayer p = player(h, Map.of(skill("chest_scholar"), 10, skill("breather"), 10));
        BlockPos pos = new BlockPos(2, 1, 2);
        h.setBlock(pos, Blocks.CHEST);
        ((ChestBlockEntity) h.getBlockEntity(pos)).setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
        p.setHealth(4);
        h.assertTrue(Finds.freshChest(p, h.absolutePos(pos)), "first open counts");
        h.assertTrue(p.totalExperience == 30, "Lv10 chest scholar: 30 XP, got " + p.totalExperience);
        h.assertTrue(p.getHealth() == 12, "Lv10 breather heals 8, got " + p.getHealth());
        h.assertTrue(!Finds.freshChest(p, h.absolutePos(pos)), "second open does not");
        h.assertTrue(!Finds.freshChest(p, h.absolutePos(new BlockPos(0, 1, 0))), "not a loot container");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void biomeWalkInPaysOncePerWindow(GameTestHelper h) {
        ServerPlayer p = player(h, Map.of(skill("field_notes"), 10));
        ResourceLocation other = ResourceLocation.withDefaultNamespace("the_void");
        Finds.discover(p); // first look: just logged in, records the biome
        h.assertTrue(p.totalExperience == 0, "logging in is not a walk-in");
        Finds.BIOME_NOW.put(p.getUUID(), other);
        Finds.discover(p);
        h.assertTrue(p.totalExperience == 40, "Lv10 field notes: 40 XP, got " + p.totalExperience);
        Finds.BIOME_NOW.put(p.getUUID(), other);
        Finds.discover(p);
        h.assertTrue(p.totalExperience == 40, "same biome again inside 10 minutes pays nothing");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void travelPaysPerDistanceAndSkipsTeleports(GameTestHelper h) {
        ServerPlayer p = player(h, Map.of(skill("trail_rations"), 10)); // 60 blocks per hunger point
        ResourceLocation id = skill("trail_rations");
        var hit = HabitsApi.mechanicHit(p, id);
        p.getFoodData().setFoodLevel(10);
        Travel.step(p, hit, id, Travel.REWARDS.get(id));
        p.moveTo(p.getX() + 35, p.getY(), p.getZ());
        Travel.step(p, hit, id, Travel.REWARDS.get(id));
        h.assertTrue(p.getFoodData().getFoodLevel() == 10, "35 blocks: nothing yet");
        p.moveTo(p.getX() + 35, p.getY(), p.getZ());
        h.assertTrue(Travel.step(p, hit, id, Travel.REWARDS.get(id)) == 1 && p.getFoodData().getFoodLevel() == 11, "70 blocks: one point");
        p.moveTo(p.getX() + 500, p.getY(), p.getZ());
        h.assertTrue(Travel.step(p, hit, id, Travel.REWARDS.get(id)) == 0, "a teleport does not count");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void digsAndEyesPayOut(GameTestHelper h) {
        ResourceLocation sift = fixture("sift", "sand_sifter", Map.of("block", "minecraft:sand", "items", "minecraft:gold_nugget"), flat(100));
        ServerPlayer p = player(h, Map.of(skill("patient_hands"), 10, skill("eye_keeper"), 10, sift, 1));
        BlockPos pos = h.absolutePos(new BlockPos(2, 1, 2));
        Finds.brushed(p, h.getLevel(), pos);
        h.assertTrue(p.totalExperience == 15, "Lv10 patient hands: 15 XP, got " + p.totalExperience);
        h.assertTrue(!Finds.sift(p, pos, ResourceLocation.withDefaultNamespace("stone")), "stone is not sifted");
        h.assertTrue(Finds.sift(p, pos, ResourceLocation.withDefaultNamespace("sand")), "sand always sifts at 100%");
        h.assertTrue(!h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2), e -> e.getItem().is(Items.GOLD_NUGGET)).isEmpty(), "nugget popped");
        EyeOfEnder eye = new EyeOfEnder(h.getLevel(), p.getX(), p.getY(), p.getZ());
        eye.surviveAfterDeath = false;
        h.assertTrue(Finds.keep(eye, p), "Lv10 eye keeper: the eye always survives");
        h.succeed();
    }
}
