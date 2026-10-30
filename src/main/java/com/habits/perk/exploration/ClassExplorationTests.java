package com.habits.perk.exploration;

import com.habits.Habits;
import com.habits.core.Category;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.Specs;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/** Exploration class mechanics: one test per class (Tomb Delver, Wayfarer, Worldseer), on the shipped class skills. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class ClassExplorationTests {
    private ClassExplorationTests() {}

    static ResourceLocation id(String name) {
        return Habits.id("exploration/" + name);
    }

    static ResourceLocation skill(String path) {
        return Habits.id("class/exploration/" + path);
    }

    /** A Lv10 player holding {@code clazz} with these skills of it. */
    static ServerPlayer classPlayer(GameTestHelper h, String clazz, String... skills) {
        Map<ResourceLocation, Integer> m = new java.util.HashMap<>();
        m.put(skill(clazz), 10);
        for (String s : skills) m.put(skill(s), 10);
        ServerPlayer p = ExplorationTests.player(h, m);
        p.setData(CoreRegistries.SPECS, new Specs(List.of(Category.WARRIOR, Category.EXPLORATION), false, List.of(skill(clazz))));
        return p;
    }

    @GameTest(template = "empty")
    public static void delverFightsBothWaysAndRerollsLoot(GameTestHelper h) {
        ServerPlayer p = classPlayer(h, "delver", "delver_lockpick");
        ResourceLocation generic = ResourceLocation.withDefaultNamespace("generic");
        h.assertTrue(Zones.factor(p, id("delver"), "dealt", generic) == 1, "outside: nothing");
        Zones.TEST_INSIDE.add(p.getUUID());
        try {
            h.assertTrue(Math.abs(Zones.factor(p, id("delver"), "dealt", generic) - 1.25) < 1e-6, "Lv10: +25% dealt");
            h.assertTrue(Math.abs(Zones.factor(p, id("delver"), "taken", generic) - 0.75) < 1e-6, "Lv10: -25% taken");
        } finally {
            Zones.TEST_INSIDE.remove(p.getUUID());
        }
        ServerPlayer outsider = ExplorationTests.player(h, Map.of(skill("delver"), 10));
        h.assertTrue(HabitsApi.mechanicHit(outsider, id("delver")) == null, "no class held: the passive does nothing");
        BlockPos pos = new BlockPos(2, 1, 2);
        h.setBlock(pos, Blocks.CHEST);
        ((ChestBlockEntity) h.getBlockEntity(pos)).setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
        int before = p.getInventory().items.stream().mapToInt(s -> s.getCount()).sum();
        h.assertTrue(Finds.rollAgain(p, h.absolutePos(pos), BuiltInLootTables.SIMPLE_DUNGEON) > 0, "lockpick rolls the table again");
        h.assertTrue(p.getInventory().items.stream().mapToInt(s -> s.getCount()).sum() > before, "loot went to the inventory");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void wayfarerRestsOnTheRoad(GameTestHelper h) {
        ServerPlayer p = classPlayer(h, "wayfarer");
        ResourceLocation leg = id("wayfarer");
        var hit = HabitsApi.mechanicHit(p, leg);
        h.assertTrue(hit != null && hit.value() == 30, "Lv10: a rest every 30 blocks");
        p.setHealth(10);
        p.getFoodData().setFoodLevel(10);
        Travel.step(p, hit, leg, Travel.REWARDS.get(leg));
        p.moveTo(p.getX() + 35, p.getY(), p.getZ());
        h.assertTrue(Travel.step(p, hit, leg, Travel.REWARDS.get(leg)) == 1, "35 blocks: one rest");
        h.assertTrue(p.getHealth() > 10 && p.getFoodData().getFoodLevel() > 10, "healed and fed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void worldseerSensesUnopenedLoot(GameTestHelper h) {
        ServerPlayer p = classPlayer(h, "worldseer", "worldseer_treasure_sense");
        BlockPos pos = new BlockPos(3, 1, 3);
        h.setBlock(pos, Blocks.BARREL);
        ((net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity) h.getBlockEntity(pos)).setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
        h.setBlock(new BlockPos(4, 1, 4), Blocks.CHEST); // no loot table: not shown
        h.assertValueEqual(Finds.senseLoot(p, 16), 1, "one unopened loot container");
        Finds.newStructure(p); // map + sense together, must not throw with nothing to find
        h.succeed();
    }
}
