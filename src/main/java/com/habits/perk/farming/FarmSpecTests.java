package com.habits.perk.farming;

import com.habits.Habits;
import com.habits.trigger.Triggers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.habits.perk.farming.FarmingTests.countWheat;
import static com.habits.perk.farming.FarmingTests.field;
import static com.habits.perk.farming.FarmingTests.hit;
import static com.habits.perk.farming.FarmingTests.own;
import static com.habits.perk.farming.FarmingTests.player;
import static com.habits.perk.farming.FarmingTests.ripeWheat;

/** Farming spec batch: one check per mechanic group (fields, soil, ranch, apiary, kitchen). */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class FarmSpecTests {
    private FarmSpecTests() {}

    /** Fields: Thousand Hands harvests the connected patch, capped by level. */
    @GameTest(template = "empty")
    public static void thousandHandsHarvestsThePatch(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        for (int lv : new int[]{1, 10}) {
            field(h, ripeWheat());
            ServerPlayer p = player(h, new BlockPos(4, 2, 4));
            own(p, "farming/thousand_hands", lv);
            int max = (int) hit("farming/thousand_hands", lv).value();
            Fields.thousandHands(p, l, h.absolutePos(new BlockPos(4, 1, 4)), ripeWheat(), max);
            BlockJobs.drain(p);
            h.assertValueEqual(countWheat(h), Math.max(1, 81 - max), "Lv" + lv + " wheat left");
        }
        h.succeed();
    }

    /** Soil: a tracked water cauldron keeps farmland wet through vanilla's own random tick (the FarmBlock mixin). */
    @GameTest(template = "empty")
    public static void sprinklerWatersFarmland(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos cauldron = h.absolutePos(new BlockPos(1, 1, 1));
        h.setBlock(new BlockPos(1, 1, 1), Blocks.WATER_CAULDRON);
        FarmSaved.get(l).add(cauldron, FarmSaved.SPRINKLER, 6, 0);
        try {
            BlockPos near = new BlockPos(7, 1, 1), far = new BlockPos(8, 1, 1);
            for (BlockPos q : new BlockPos[]{near, far}) {
                h.setBlock(q, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 0));
                h.getBlockState(q).randomTick(l, h.absolutePos(q), l.random);
            }
            h.assertTrue(h.getBlockState(near).getValue(FarmBlock.MOISTURE) == FarmBlock.MAX_MOISTURE, "6 blocks away gets wet");
            h.assertTrue(!h.getBlockState(far).is(Blocks.FARMLAND) || h.getBlockState(far).getValue(FarmBlock.MOISTURE) == 0, "7 blocks away stays dry");
            h.setBlock(new BlockPos(1, 1, 1), Blocks.CAULDRON);
            h.assertTrue(!Soil.sprinkled(l, h.absolutePos(near)), "an empty cauldron waters nothing");
        } finally {
            FarmSaved.get(l).remove(cauldron);
        }
        h.succeed();
    }

    /** Ranch: Feed Bag puts the herd in love, one food each, and leaves one for the clicked animal. */
    @GameTest(template = "empty")
    public static void feedBagFeedsTheHerd(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(2, 1, 2));
        own(p, "farming/feed_bag", 10);
        Cow a = h.spawn(EntityType.COW, new BlockPos(2, 1, 2)), b = h.spawn(EntityType.COW, new BlockPos(3, 1, 3)), c = h.spawn(EntityType.COW, new BlockPos(1, 1, 3));
        ItemStack wheat = new ItemStack(Items.WHEAT, 2);
        Ranch.feedBag(p, a, wheat, hit("farming/feed_bag", 10).value());
        int inLove = (b.isInLove() ? 1 : 0) + (c.isInLove() ? 1 : 0);
        h.assertValueEqual(inLove, 1, "only one other cow fed with 2 wheat");
        h.assertValueEqual(wheat.getCount(), 1, "one wheat left for the clicked cow");
        h.succeed();
    }

    /** Apiary: Honey Bottler fills spare bottles from the bag, never the one in hand. */
    @GameTest(template = "empty")
    public static void honeyBottlerFillsSpares(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        ItemStack held = new ItemStack(Items.GLASS_BOTTLE, 1), spare = new ItemStack(Items.GLASS_BOTTLE, 3);
        p.getInventory().items.set(0, held);
        p.getInventory().items.set(1, spare);
        Apiary.moreBottles(p, held, 2);
        h.assertValueEqual(held.getCount(), 1, "hand bottle untouched");
        h.assertValueEqual(spare.getCount(), 1, "two spares used");
        h.assertValueEqual(p.getInventory().countItem(Items.HONEY_BOTTLE), 2, "two honey bottles");
        h.succeed();
    }

    /** Kitchen: Leftovers hands the last bread back. */
    @GameTest(template = "empty")
    public static void leftoversKeepsTheBread(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        own(p, "farming/leftovers", 10);
        boolean kept = false;
        for (int i = 0; i < 200 && !kept; i++) {
            var e = new LivingEntityUseItemEvent.Finish(p, new ItemStack(Items.BREAD), 0, ItemStack.EMPTY);
            Kitchen.onEaten(e);
            kept = e.getResultStack().is(Items.BREAD);
        }
        h.assertTrue(kept, "Lv10 (30%) kept a bread within 200 meals");
        h.succeed();
    }

    /** Farming classes: Hearthcook's meal buffs and Green Sovereign's replant, both only with the class held. */
    @GameTest(template = "empty")
    public static void classPassivesMealAndReplant(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 2, 3));
        own(p, "class/farming/hearthcook", 10);
        p.setData(com.habits.core.CoreRegistries.COLLECTION, com.habits.core.HabitsApi.collection(p)
                .with(Habits.id("class/farming/green_sovereign"), new com.habits.core.SkillProgress(10, 0, true)));
        Kitchen.onEaten(new LivingEntityUseItemEvent.Finish(p, new ItemStack(Items.BREAD), 0, ItemStack.EMPTY));
        h.assertFalse(p.hasEffect(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST), "no buff without the class");
        p.setData(com.habits.core.CoreRegistries.SPECS, new com.habits.core.Specs(java.util.List.of(com.habits.core.Category.RANGER,
                com.habits.core.Category.FARMING), false, java.util.List.of(Habits.id("class/farming/hearthcook"), Habits.id("class/farming/green_sovereign"))));
        com.habits.core.HabitsApi.refreshPerks(p); // the perk cache is keyed on the collection, not the specs
        Kitchen.onEaten(new LivingEntityUseItemEvent.Finish(p, new ItemStack(Items.BREAD), 0, ItemStack.EMPTY));
        var str = p.getEffect(net.minecraft.world.effect.MobEffects.DAMAGE_BOOST);
        h.assertTrue(str != null && str.getAmplifier() == 1 && str.getDuration() == 600, "Lv10: Strength II for 30 s");
        h.setBlock(new BlockPos(1, 1, 1), Blocks.FARMLAND);
        h.setBlock(new BlockPos(1, 2, 1), ripeWheat());
        h.assertTrue(Farm.breakAs(p, h.absolutePos(new BlockPos(1, 2, 1))), "harvested");
        h.succeedWhen(() -> {
            net.minecraft.world.level.block.state.BlockState w = h.getBlockState(new BlockPos(1, 2, 1));
            h.assertTrue(w.is(Blocks.WHEAT) && w.getValue(net.minecraft.world.level.block.CropBlock.AGE) == 4, "replanted at age 4, got " + w);
            com.habits.trigger.Triggers.TEST_PLAYERS.remove(p.getUUID());
        });
    }

    /** Piaro's Way: ripe harvest triggers (hand or skill) count for farmers; hoes gain +1 damage per 500, capped at +6. */
    @GameTest(template = "empty")
    public static void piarosWay(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        h.assertTrue(!Piaro.farmer(p), "no pick, no Piaro");
        p.setData(com.habits.core.CoreRegistries.SPECS, new com.habits.core.Specs(java.util.List.of(com.habits.core.Category.WARRIOR,
                com.habits.core.Category.FARMING), false));
        Triggers.TEST_PLAYERS.add(p.getUUID());
        for (int i = 0; i < 1250; i++) com.habits.trigger.TriggerEvents.rollBreak(p,
                Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE, 7), p.blockPosition(), null);
        com.habits.trigger.TriggerEvents.rollBreak(p, Blocks.WHEAT.defaultBlockState(), p.blockPosition(), null); // unripe: no count
        h.assertTrue(Piaro.harvested(p) == 1250 && Piaro.bonus(1250) == 2.5 && Piaro.bonus(99999) == FarmCfg.PIARO_CAP.get(), "count and cap");
        var z = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_HOE));
        var e = new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(z,
                new net.neoforged.neoforge.common.damagesource.DamageContainer(h.getLevel().damageSources().playerAttack(p), 2));
        Piaro.onHurt(e);
        h.assertTrue(Math.abs(e.getAmount() - 4.5f) < 0.01, "hoe +2.5, got " + e.getAmount());
        z.discard();
        h.succeed();
    }

    /** Rated dishes: rated items split off the plain stack; Legendary food gets +4 nutrition and one buff. */
    @GameTest(template = "empty")
    public static void ratedDishes(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        p.getInventory().add(new ItemStack(Items.BREAD, 10));
        h.assertTrue(Dish.takePlain(p, Items.BREAD, 3) == 3 && p.getInventory().countItem(Items.BREAD) == 7, "three plain loaves taken");
        ItemStack bread = new ItemStack(Items.BREAD, 3);
        Dish.apply(p, bread, com.habits.perk.crafting.Rating.Tier.LEGENDARY);
        var food = bread.get(net.minecraft.core.component.DataComponents.FOOD);
        h.assertTrue(food.nutrition() == 5 + 4 && food.effects().size() == 1, "legendary bread: +4 nutrition, one buff, got " + food);
        h.assertTrue(com.habits.perk.crafting.Rating.data(bread) != null && Dish.takePlain(p, Items.BREAD, 99) == 7, "rated bread is not plain");
        h.succeed();
    }
}
