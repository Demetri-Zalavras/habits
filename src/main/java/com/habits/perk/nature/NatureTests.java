package com.habits.perk.nature;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import com.habits.trigger.Triggers;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cod;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Nature GameTests: one per mechanic group, each proving the effect happens. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class NatureTests {
    private NatureTests() {}

    static ServerPlayer player(GameTestHelper h, BlockPos rel, String... skills) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-nature"));
        Triggers.TEST_PLAYERS.add(p.getUUID());
        BlockPos a = h.absolutePos(rel);
        p.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
        HabitsCollection c = HabitsCollection.EMPTY;
        for (String s : skills) c = c.with(Habits.id("nature/" + s), new SkillProgress(10, 0, true));
        p.setData(CoreRegistries.COLLECTION, c);
        return p;
    }

    static HabitsApi.PerkHit hit(String skill, int level) {
        SkillDef def = SkillRegistry.get(Habits.id("nature/" + skill));
        return new HabitsApi.PerkHit(def, level, def.perkValue(level));
    }

    static boolean has(List<ItemEntity> drops, net.minecraft.world.item.Item item) {
        return drops.stream().anyMatch(d -> d.getItem().is(item));
    }

    /** Block drop mechanics: sapling stash, coral collector, truffle snout. */
    @GameTest(template = "empty")
    public static void woodsDropsAddFinds(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, BlockPos.ZERO, "sapling_stash", "coral_collector", "truffle_snout");
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        List<ItemEntity> leaves = new ArrayList<>(), coral = new ArrayList<>(), dirt = new ArrayList<>();
        for (int i = 0; i < 60; i++) Woods.drops(p, l, at, Blocks.BIRCH_LEAVES.defaultBlockState(), leaves, null);
        h.assertTrue(has(leaves, Items.BIRCH_SAPLING), "sapling stash drops the leaves' own sapling");
        Woods.drops(p, l, at, Blocks.BRAIN_CORAL_BLOCK.defaultBlockState(), coral, null);
        h.assertTrue(has(coral, Items.BRAIN_CORAL_BLOCK), "Lv10 coral collector keeps live coral");
        for (int i = 0; i < 60; i++) Woods.drops(p, l, at, Blocks.PODZOL.defaultBlockState(), dirt, null);
        h.assertTrue(!dirt.isEmpty(), "truffle snout finds something in podzol");
        h.succeed();
    }

    /** Catch mechanics: a school of fish comes along with the catch. */
    @GameTest(template = "empty")
    public static void schoolNetsNearbyFish(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) for (int y = 1; y <= 2; y++) h.setBlock(new BlockPos(x, y, z), Blocks.WATER);
        ServerPlayer p = player(h, new BlockPos(2, 4, 6), "school");
        FishingHook hook = new FishingHook(p, l, 0, 0);
        BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
        hook.moveTo(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        for (int i = 0; i < 3; i++) h.spawn(EntityType.COD, new BlockPos(1 + i, 1, 2));
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Items.COD)));
        Waters.catches(p, l, hook, drops);
        h.assertValueEqual(drops.size(), 4, "the catch plus three schooled cod");
        h.assertTrue(l.getEntitiesOfClass(Cod.class, hook.getBoundingBox().inflate(8)).isEmpty(), "the schooled cod are gone");
        h.succeed();
    }

    /** Animals: the spirit of your last tamed animal, and tracked game glowing while you sneak. */
    @GameTest(template = "empty")
    public static void spiritAndTracker(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1), "spirit_animal", "game_tracker");
        Nat.data(p).putString("habits_spirit", "minecraft:wolf");
        Wild.spiritAnimal(p, hit("spirit_animal", 10));
        h.assertTrue(p.hasEffect(MobEffects.DAMAGE_BOOST), "a wolf spirit gives Strength");
        Cow cow = h.spawn(EntityType.COW, new BlockPos(3, 1, 3));
        p.setShiftKeyDown(true);
        Wild.gameTracker(p, hit("game_tracker", 10));
        h.assertTrue(cow.hasEffect(MobEffects.GLOWING), "a wild cow glows for the tracker");
        h.succeed();
    }

    /** Weather: the rainmaker flips the weather, the rainbow pays out when rain clears. */
    @GameTest(template = "empty")
    public static void weatherFlipsAndRainbow(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        // the level data flips at once; the visible rain level eases over the next ticks
        boolean was = l.getLevelData().isRaining();
        Skies.flipWeather(l, 1200);
        boolean flipped = l.getLevelData().isRaining();
        Skies.flipWeather(l, 1200);
        h.assertTrue(flipped != was, "rainmaker flips the weather");
        ServerPlayer p = player(h, new BlockPos(1, 1, 1), "rainbow");
        Skies.clearedUp(p);
        h.assertTrue(p.hasEffect(MobEffects.LUCK), "rainbow gives Luck when the rain clears");
        h.succeed();
    }

    /** Ticks on the water: lily pads grow under your path and sink again. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void lilyStepPads(GameTestHelper h) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) h.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
        h.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
        ServerPlayer p = player(h, new BlockPos(2, 2, 2), "lily_step");
        p.setOnGround(true);
        Woods.lilyStep(p, hit("lily_step", 1));
        h.assertBlockPresent(Blocks.LILY_PAD, new BlockPos(1, 2, 2));
        // Lv1 pads last 3 seconds
        h.runAfterDelay(100, () -> {
            h.assertBlockNotPresent(Blocks.LILY_PAD, new BlockPos(1, 2, 2));
            h.succeed();
        });
    }

    /** Class passives: Beastcaller's pack bond buffs your pet (only while you hold the class), Stormherald's bolt hurts. */
    @GameTest(template = "empty")
    public static void classPassivesWork(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        var beast = Habits.id("class/nature/beastcaller");
        ServerPlayer p = player(h, BlockPos.ZERO);
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(beast, new SkillProgress(10, 0, true)));
        p.setData(CoreRegistries.SPECS, new com.habits.core.Specs(List.of(com.habits.core.Category.WARRIOR, com.habits.core.Category.NATURE), false, List.of(beast)));
        net.minecraft.world.entity.animal.Wolf wolf = h.spawn(EntityType.WOLF, new BlockPos(2, 1, 2));
        wolf.tame(p);
        HabitsApi.PerkHit bond = Nat.hit(p, "pack_bond");
        h.assertTrue(bond != null, "the class passive is active");
        p.tickCount = 40;
        Callings.packBond(p, bond);
        h.assertTrue(wolf.hasEffect(MobEffects.DAMAGE_BOOST), "pet gets Strength");
        p.setData(CoreRegistries.SPECS, new com.habits.core.Specs(List.of(com.habits.core.Category.WARRIOR, com.habits.core.Category.NATURE), false));
        HabitsApi.refreshPerks(p);
        h.assertTrue(Nat.hit(p, "pack_bond") == null, "off without the class");
        Cow cow = h.spawn(EntityType.COW, new BlockPos(4, 1, 4));
        float before = cow.getHealth();
        SkillDef storm = SkillRegistry.get(Habits.id("class/nature/stormherald"));
        Callings.strike(l, p, cow, new HabitsApi.PerkHit(storm, 1, storm.perkValue(1)));
        h.assertTrue(cow.getHealth() < before && cow.isOnFire(), "the bolt hurts and burns");
        h.succeed();
    }
}
