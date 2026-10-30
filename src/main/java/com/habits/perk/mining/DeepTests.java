package com.habits.perk.mining;

import com.habits.Habits;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.habits.perk.mining.MiningTests.abs;
import static com.habits.perk.mining.MiningTests.fill;
import static com.habits.perk.mining.MiningTests.player;

/** Mining spec batch: one GameTest per mechanic family (rules, area, overflow, speed, moments). */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class DeepTests {
    private DeepTests() {}

    static int items(ServerLevel l, BlockPos at, Item item) {
        return l.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(2), e -> e.getItem().is(item))
                .stream().mapToInt(e -> e.getItem().getCount()).sum();
    }

    @GameTest(template = "empty")
    public static void dropAndEffectRules(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(0, 2, 0), "mining/nylium_keeper", 10);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        BlockPos ny = abs(h, 2, 2, 0);
        l.setBlock(ny, Blocks.CRIMSON_NYLIUM.defaultBlockState(), 3);
        p.gameMode.destroyBlock(ny);
        h.assertTrue(items(l, ny, Items.CRIMSON_NYLIUM) >= 1, "Lv10 nylium keeper always drops the nylium");

        ServerPlayer q = player(h, new BlockPos(0, 6, 0), "mining/redstone_rush", 10);
        q.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        BlockPos ore = abs(h, 2, 6, 0);
        l.setBlock(ore, Blocks.REDSTONE_ORE.defaultBlockState(), 3);
        q.gameMode.destroyBlock(ore);
        h.assertTrue(q.hasEffect(MobEffects.MOVEMENT_SPEED), "redstone ore gave Speed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void revealRuleMarksBlocks(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 2, 1), "mining/geode_sense", 10);
        BlockPos bud = abs(h, 3, 2, 3);
        h.getLevel().setBlock(bud, Blocks.BUDDING_AMETHYST.defaultBlockState(), 3);
        MiningNet.last = null;
        DeepPerks.reveal(p, MiningMechanics.hit(p, "mining/geode_sense"), "mining/geode_sense");
        Scanner.finish(p);
        h.assertTrue(MiningNet.last != null && MiningNet.last.pos().contains(bud), "budding amethyst marked");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void earthbreakerClearsThreeByThree(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int y = 3;
        ServerPlayer p = player(h, new BlockPos(0, y, 1), "mining/earthbreaker", 10);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        p.setYRot(-90); // look +X
        p.setXRot(0);
        BlockPos a = abs(h, 2, y, 0);
        fill(l, a, a.offset(0, 2, 2), Blocks.STONE.defaultBlockState());
        p.gameMode.destroyBlock(a.offset(0, 1, 1));
        Work.drain(p);
        int air = 0;
        for (BlockPos b : BlockPos.betweenClosed(a, a.offset(0, 2, 2))) if (l.getBlockState(b).isAir()) air++;
        h.assertValueEqual(air, 9, "Lv10 clears the 3x3 face");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void rockCrusherEatsOverflow(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(0, 2, 0), "mining/rock_crusher", 10);
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) inv.setItem(i, new ItemStack(Items.DIRT, 64));
        inv.selected = 0;
        inv.setItem(0, new ItemStack(Items.IRON_PICKAXE));
        BlockPos c = abs(h, 2, 2, 0);
        l.setBlock(c, Blocks.COBBLESTONE.defaultBlockState(), 3);
        p.gameMode.destroyBlock(c);
        h.assertValueEqual(items(l, c, Items.COBBLESTONE), 0, "cobblestone that did not fit was crushed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void lastLegsSpeedsUpWornTools(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(0, 2, 0), "mining/last_legs", 10);
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(pick.getMaxDamage() - 5);
        p.setItemInHand(InteractionHand.MAIN_HAND, pick);
        BlockPos at = abs(h, 2, 2, 0);
        var s = Blocks.STONE.defaultBlockState();
        float ratio = p.getDigSpeed(s, at) / MiningTests.baseline(p, "mining/last_legs", s, at);
        h.assertTrue(Math.abs(ratio - 3f) < 0.01, "Lv10 worn tool mines 3x, got " + ratio);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void oreComboMomentFires(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(0, 2, 0), "mining/ore_blood", 10);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        boolean[] seen = {false};
        Triggers.Spy spy = (sp, type, ctx) -> {
            if (sp == p && type == TriggerType.CUSTOM && Habits.id("mining/ore_combo").equals(ctx.target())) seen[0] = true;
        };
        Triggers.SPIES.add(spy);
        try {
            BlockPos a = abs(h, 2, 2, 0);
            l.setBlock(a, Blocks.COAL_ORE.defaultBlockState(), 3);
            l.setBlock(a.above(), Blocks.IRON_ORE.defaultBlockState(), 3);
            p.setHealth(10);
            p.gameMode.destroyBlock(a);
            p.gameMode.destroyBlock(a.above());
            h.assertTrue(seen[0], "two ore kinds back to back fired ore_combo");
            h.assertTrue(p.getHealth() >= 15.9f, "Lv10 ore blood healed 3 per ore, health " + p.getHealth());
        } finally {
            Triggers.SPIES.remove(spy);
        }
        h.succeed();
    }

    /** Worldbreaker (Legendary mining class): Lv10 clears a 3x3x3 cube from one break, and does nothing without the class. */
    @GameTest(template = "empty")
    public static void worldbreakerDigsACubeOnlyForTheClass(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int y = 3;
        ServerPlayer none = player(h, new BlockPos(0, y, 1), "class/mining/worldbreaker", 10);
        ServerPlayer p = player(h, new BlockPos(0, y, 5), "class/mining/worldbreaker", 10);
        p.setData(com.habits.core.CoreRegistries.SPECS, new com.habits.core.Specs(java.util.List.of(com.habits.core.Category.WARRIOR,
                com.habits.core.Category.MINING), false, java.util.List.of(Habits.id("class/mining/worldbreaker"))));
        int[] cleared = new int[2];
        int i = 0;
        for (ServerPlayer q : java.util.List.of(none, p)) {
            q.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            q.setYRot(-90); // look +X
            q.setXRot(0);
            BlockPos a = abs(h, 2, y, q == p ? 4 : 0);
            fill(l, a, a.offset(2, 2, 2), Blocks.STONE.defaultBlockState());
            q.gameMode.destroyBlock(a.offset(0, 1, 1));
            Work.drain(q);
            for (BlockPos b : BlockPos.betweenClosed(a, a.offset(2, 2, 2))) if (l.getBlockState(b).isAir()) cleared[i]++;
            i++;
        }
        h.assertValueEqual(cleared[0], 1, "no class: just the one block");
        h.assertValueEqual(cleared[1], 27, "Lv10 Worldbreaker clears a 3x3x3 cube");
        h.succeed();
    }
}
