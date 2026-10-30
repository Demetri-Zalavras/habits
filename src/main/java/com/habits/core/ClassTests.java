package com.habits.core;

import com.habits.Habits;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Class books, titles and skill forks: the core rules, on hand-made fixtures. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class ClassTests {
    private ClassTests() {}

    private static final TriggerSpec NEVER = new TriggerSpec(TriggerType.CUSTOM, Optional.empty(), Optional.empty(), Optional.empty(), 0, 0);
    private static final ResourceLocation STONE = ResourceLocation.withDefaultNamespace("stone");

    static SkillDef fixture(String path, Rarity r, Category c, Optional<ResourceLocation> clazz, boolean passive,
                                    Optional<SkillDef.TitleSpec> title, List<SkillDef.ForkStep> forks) {
        PerkSpec perk = new PerkSpec(Optional.of(PerkType.MINING_SPEED), Optional.empty(), Map.of(), 10, 0, Optional.empty());
        return SkillRegistry.testFixture(new SkillDef(Habits.id("test/" + path), r, c, NEVER, STONE, STONE, perk,
                Optional.empty(), Optional.empty(), Optional.empty(), clazz, passive, title, forks));
    }

    @GameTest(template = "empty")
    public static void classBookResetsGatesAndLegendaryIsUnique(GameTestHelper h) {
        SkillDef knight = fixture("class_knight", Rarity.RARE, Category.WARRIOR, Optional.empty(), true, Optional.empty(), List.of());
        SkillDef oath = fixture("knight_oath", Rarity.RARE, Category.WARRIOR, Optional.of(knight.id()), false, Optional.empty(), List.of());
        SkillDef saint = fixture("class_saint", Rarity.LEGENDARY, Category.WARRIOR, Optional.empty(), true, Optional.empty(), List.of());
        ServerPlayer a = CoreTests.player(h), b = CoreTests.player(h);
        h.assertValueEqual(Specs.becomeClass(a, knight), "habits.msg.class_pick_first", "needs picks first");
        for (ServerPlayer p : List.of(a, b)) p.setData(CoreRegistries.SPECS, new Specs(List.of(Category.WARRIOR, Category.MINING), false));
        ResourceLocation mined = SkillRegistry.byCategory(Category.MINING).getFirst().id();
        ResourceLocation warred = SkillRegistry.byCategory(Category.WARRIOR).stream().filter(d -> !d.special()).findFirst().orElseThrow().id();
        HabitsApi.addCard(a, mined);
        HabitsApi.addCard(a, warred);
        h.assertFalse(Specs.allows(a, oath), "exclusive skill locked without the class");
        h.assertTrue(Specs.becomeClass(a, knight) == null, "became Knight");
        h.assertValueEqual(HabitsApi.collection(a).skills().keySet(), java.util.Set.of(knight.id(), mined),
                "only the Warrior base reset (the class starts at Lv1); the Mining pick stays");
        h.assertTrue(Specs.allows(a, oath), "exclusive skill open to the class");
        h.assertTrue(Specs.becomeClass(a, saint) == null, "a claims the Legendary");
        h.assertFalse(Specs.allows(a, oath), "switching on the same base drops the old class");
        h.assertValueEqual(Specs.becomeClass(b, saint), "habits.msg.class_taken", "one holder per world");
        a.setData(CoreRegistries.SPECS, new Specs(List.of(Category.WARRIOR, Category.MINING), true, Specs.of(a).classes()));
        Specs.pick(a, Category.RANGER, Category.MINING); // leaves Warrior: the Legendary goes back
        h.assertTrue(Specs.becomeClass(b, saint) == null, "free again");
        Specs.reset(b);

        // a class book of an unpicked base swaps the pick of its kind: Mining (profession) becomes Farming
        SkillDef farmer = fixture("class_farmer", Rarity.RARE, Category.FARMING, Optional.empty(), true, Optional.empty(), List.of());
        ServerPlayer c = CoreTests.player(h);
        c.setData(CoreRegistries.SPECS, new Specs(List.of(Category.WARRIOR, Category.MINING), false));
        HabitsApi.addCard(c, mined);
        HabitsApi.addCard(c, warred);
        h.assertTrue(Specs.swappedBase(c, farmer) == Category.MINING, "farming would replace mining");
        h.assertTrue(Specs.becomeClass(c, farmer) == null, "no farming pick needed");
        h.assertValueEqual(Specs.of(c).picks(), List.of(Category.WARRIOR, Category.FARMING), "mining swapped for farming");
        h.assertValueEqual(HabitsApi.collection(c).skills().keySet(), java.util.Set.of(farmer.id(), warred),
                "mining skills gone, warrior skills kept");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void titleEarnedOnConditionAndIgnoresLockout(GameTestHelper h) {
        SkillDef title = fixture("title_veteran", Rarity.RARE, Category.BUILDING, Optional.empty(), false,
                Optional.of(new SkillDef.TitleSpec(Optional.empty(), Optional.empty(), 1, Optional.empty(), 3, 0)), List.of());
        ServerPlayer p = CoreTests.player(h);
        p.setData(CoreRegistries.SPECS, new Specs(List.of(Category.WARRIOR, Category.MINING), false));
        h.assertFalse(Titles.holds(p, HabitsApi.collection(p), title.title().get()), "no levels yet");
        SkillDef mining = SkillRegistry.byCategory(Category.MINING).stream().filter(d -> !d.special() && d.rarity() == Rarity.COMMON).findFirst().orElseThrow();
        for (int i = 0; i < 6; i++) HabitsApi.addCard(p, mining.id()); // Lv3
        h.assertTrue(Titles.holds(p, HabitsApi.collection(p), title.title().get()), "3 total levels");
        Titles.grant(p, title);
        h.assertValueEqual(HabitsApi.level(p, title.id()), Leveling.MAX_LEVEL, "earned at Lv10");
        h.assertTrue(HabitsApi.perkActive(p, title.id()), "works outside the picks");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void forkOptionScalesThePerk(GameTestHelper h) {
        SkillDef def = fixture("forked", Rarity.EPIC, Category.MINING, Optional.empty(), false, Optional.empty(),
                List.of(new SkillDef.ForkStep(5, List.of(new SkillDef.ForkOption("steady", 1, Map.of()),
                        new SkillDef.ForkOption("double", 2, Map.of("radius", "3"))))));
        ServerPlayer p = CoreTests.player(h);
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(def.id(), new SkillProgress(4, 0, true)));
        h.assertFalse(Forks.set(p, def.id(), 5, "double"), "not reached yet");
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(def.id(), new SkillProgress(5, 0, true)));
        h.assertValueEqual(Forks.apply(p, def).perkValue(5), 10.0, "unforked until picked");
        h.assertTrue(Forks.set(p, def.id(), 5, "double"), "picked");
        SkillDef forked = Forks.apply(p, def);
        h.assertValueEqual(forked.perkValue(5), 20.0, "scaled");
        h.assertValueEqual(forked.perk().param("radius", ""), "3", "param override");
        h.succeed();
    }
}
