package com.habits.core;

import com.habits.Habits;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonPrimitive;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.List;

/** Specializations: off-spec cards neither drop nor work; a respec pick wipes cards outside the new picks. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class SpecTests {
    private SpecTests() {}

    /** A combat style and a profession, neither of them {@code not}. */
    private static List<Category> picksWithout(Category not) {
        Category combat = Arrays.stream(Category.values()).filter(c -> c.combat && c != not).findFirst().orElseThrow();
        Category prof = Arrays.stream(Category.values()).filter(c -> !c.combat && c != not).findFirst().orElseThrow();
        return List.of(combat, prof);
    }

    @GameTest(template = "empty")
    public static void offSpecCardsNeitherDropNorWork(GameTestHelper h) {
        ServerPlayer p = CoreTests.player(h);
        var ctx = TriggerContext.of(Blocks.STONE).withItem(net.minecraft.world.item.Items.IRON_PICKAXE)
                .withPos(h.absoluteVec(new Vec3(1.5, 1.5, 1.5)));
        SkillDef stone = CoreTests.find(TriggerType.BREAK_BLOCK, d -> d.trigger().min() <= 1 && d.trigger().chance() >= 1e-3
                && HabitsApi.matches(d.trigger(), ctx, p));
        h.assertTrue(stone != null, "no block-break skill matching stone");
        p.setData(CoreRegistries.SPECS, new Specs(picksWithout(stone.category()), false));
        int n = (int) Math.ceil(30 / Math.max(stone.trigger().chance() * CoreInit.DROP_RATE.get(), 1e-9));
        for (int i = 0; i < Math.min(n, 50_000); i++) HabitsApi.onTrigger(p, TriggerType.BREAK_BLOCK, ctx);
        h.assertValueEqual(HabitsApi.level(p, stone.id()), 0, "off-spec skill never drops");
        HabitsApi.addCard(p, stone.id());
        h.assertFalse(HabitsApi.perkActive(p, stone.id()), "off-spec perk is off");
        List<Category> other = picksWithout(stone.category());
        p.setData(CoreRegistries.SPECS, new Specs(stone.category().combat ? List.of(stone.category(), other.get(1))
                : List.of(other.get(0), stone.category()), false));
        HabitsApi.refreshPerks(p);
        h.assertTrue(HabitsApi.perkActive(p, stone.id()), "in-spec perk is on");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void respecPickWipesOffSpecCards(GameTestHelper h) {
        ServerPlayer p = CoreTests.player(h);
        SkillDef warrior = SkillRegistry.byCategory(Category.WARRIOR).getFirst();
        SkillDef mining = SkillRegistry.byCategory(Category.MINING).getFirst();
        SkillDef farming = SkillRegistry.byCategory(Category.FARMING).getFirst();
        for (SkillDef d : List.of(warrior, mining, farming)) HabitsApi.addCard(p, d.id());
        h.assertFalse(Specs.pick(p, Category.MINING, Category.WARRIOR), "a profession is not a combat style");
        h.assertTrue(Specs.pick(p, Category.WARRIOR, Category.MINING), "first pick");
        h.assertValueEqual(HabitsApi.level(p, farming.id()), 1, "first pick keeps off-spec cards");
        h.assertFalse(Specs.pick(p, Category.RANGER, Category.FARMING), "no pick without a respec");
        p.setData(CoreRegistries.SPECS, new Specs(Specs.of(p).picks(), true));
        h.assertTrue(Specs.allows(p, Category.MINING), "old picks stay active until the new pick");
        h.assertTrue(Specs.pick(p, Category.WARRIOR, Category.FARMING), "respec pick");
        h.assertValueEqual(HabitsApi.level(p, warrior.id()), 1, "kept: still picked");
        h.assertValueEqual(HabitsApi.level(p, farming.id()), 1, "kept: newly picked");
        h.assertValueEqual(HabitsApi.level(p, mining.id()), 0, "wiped: left behind");
        h.assertTrue(p.getTags().contains("habits_farming") && !p.getTags().contains("habits_mining"), "quest stage tags follow the picks");
        SkillDef reward = SkillXp.reward(p, Rarity.RARE);
        h.assertTrue(reward != null && Specs.allows(p, reward), "quest reward XP goes to the picks");
        h.assertValueEqual(Category.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive("combat")).getOrThrow(), Category.WARRIOR,
                "old charms with combat load as warrior");
        h.succeed();
    }

    /** combat_specs off: two different professions are the pick; an old save (combat + profession) loads, drops the combat
     * pick and asks only for the second profession; a class book of a third profession swaps the second (or fills it);
     * a respec keeps inactive combat cards. */
    @GameTest(template = "empty")
    public static void professionOnlyWhenCombatSpecsOff(GameTestHelper h) {
        var on = CoreInit.COMBAT_SPECS;
        boolean was = on.get();
        ServerPlayer p = CoreTests.player(h);
        SkillDef warrior = SkillRegistry.byCategory(Category.WARRIOR).stream().filter(d -> !d.classPassive() && d.classId().isEmpty()).findFirst().orElseThrow();
        SkillDef mining = SkillRegistry.byCategory(Category.MINING).stream().filter(d -> !d.classPassive() && d.classId().isEmpty()).findFirst().orElseThrow();
        try {
            on.set(false);
            h.assertFalse(Specs.pick(p, Category.WARRIOR, Category.FARMING), "no combat pick");
            h.assertFalse(Specs.pick(p, Category.FARMING, Category.FARMING), "not the same profession twice");
            h.assertTrue(Specs.pick(p, Category.FARMING, Category.NATURE), "two professions");
            h.assertValueEqual(Specs.of(p).picks(), List.of(Category.FARMING, Category.NATURE), "both picked");
            h.assertFalse(Specs.needsPick(p), "two professions are a full pick");
            h.assertTrue(p.getTags().containsAll(List.of("habits_farming", "habits_nature")), "quest stage tags for both");
            // an old save: combat + profession
            p.setData(CoreRegistries.SPECS, Specs.CODEC.parse(JsonOps.INSTANCE,
                    com.google.gson.JsonParser.parseString("{\"picks\":[\"warrior\",\"mining\"]}")).getOrThrow());
            HabitsApi.addCard(p, warrior.id());
            HabitsApi.addCard(p, mining.id());
            Specs.dropCombatPick(p);
            h.assertValueEqual(Specs.of(p).picks(), List.of(Category.MINING), "old combat pick dropped");
            h.assertTrue(Specs.needsPick(p), "the picker asks for the second profession");
            h.assertFalse(Specs.pick(p, Category.FARMING, Category.NATURE), "the held profession stays");
            h.assertTrue(Specs.pick(p, Category.MINING, Category.CRAFTING), "second profession added");
            h.assertValueEqual(Specs.of(p).picks(), List.of(Category.MINING, Category.CRAFTING), "mining kept first");
            h.assertValueEqual(HabitsApi.level(p, mining.id()), 1, "mining card kept");
            h.assertValueEqual(HabitsApi.level(p, warrior.id()), 1, "combat card kept");
            h.assertFalse(HabitsApi.perkActive(p, warrior.id()), "combat perk is off");
            h.assertFalse(Specs.offered().contains(Category.WARRIOR), "no combat charms roll");
            SkillDef farmer = ClassTests.fixture("class_farmer_two", Rarity.RARE, Category.FARMING, java.util.Optional.empty(), true,
                    java.util.Optional.empty(), List.of());
            h.assertTrue(Specs.swappedBase(p, farmer) == Category.CRAFTING, "a third profession's book replaces the second");
            h.assertTrue(Specs.becomeClass(p, farmer) == null, "became the class");
            h.assertValueEqual(Specs.of(p).picks(), List.of(Category.MINING, Category.FARMING), "crafting swapped for farming");
            h.assertValueEqual(HabitsApi.level(p, mining.id()), 1, "first profession's card kept");
            p.setData(CoreRegistries.SPECS, new Specs(Specs.of(p).picks(), true, Specs.of(p).classes()));
            h.assertTrue(Specs.pick(p, Category.MINING, Category.NATURE), "respec pick");
            h.assertValueEqual(HabitsApi.level(p, warrior.id()), 1, "respec keeps the inactive combat card");
            SkillDef crafter = ClassTests.fixture("class_crafter_two", Rarity.RARE, Category.CRAFTING, java.util.Optional.empty(), true,
                    java.util.Optional.empty(), List.of());
            h.assertValueEqual(Specs.replaceable(p, crafter), List.of(Category.MINING, Category.NATURE), "screen offers both professions");
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ClassBookItem.create(crafter.id()));
            ClassBookItem.confirm(p, crafter.id(), Category.MINING);
            h.assertTrue(p.getMainHandItem().isEmpty(), "book used up");
            h.assertValueEqual(Specs.of(p).picks(), List.of(Category.CRAFTING, Category.NATURE), "mining swapped for crafting, nature kept");
            SkillDef explorer = ClassTests.fixture("class_explorer_two", Rarity.RARE, Category.EXPLORATION, java.util.Optional.empty(), true,
                    java.util.Optional.empty(), List.of());
            p.setData(CoreRegistries.SPECS, new Specs(List.of(Category.MINING), false));
            h.assertTrue(Specs.swappedBase(p, explorer) == null && Specs.becomeClass(p, explorer) == null, "one profession: the book fills the second");
            h.assertValueEqual(Specs.of(p).picks(), List.of(Category.MINING, Category.EXPLORATION), "mining kept, exploration added");
            on.set(true);
            h.assertTrue(Specs.needsPick(p), "combat specs back on: the picker asks for a combat style");
        } finally {
            on.set(was);
        }
        h.succeed();
    }
}
