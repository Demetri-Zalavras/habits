package com.habits.core;

import com.habits.Habits;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Auto-add drops: won cards add themselves unless the book toggle is off or the skill is maxed. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class AutoAddTests {
    private AutoAddTests() {}

    /** A ServerPlayer still being built has no connection; reading Habits data then must not create (and sync) it. */
    @GameTest(template = "empty")
    public static void readsBeforeLoginCreateNothing(GameTestHelper h) {
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "habits_prelogin"),
                net.minecraft.server.level.ClientInformation.createDefault());
        h.assertTrue(p.connection == null, "no connection yet");
        h.assertTrue(HabitsApi.collection(p) == HabitsCollection.EMPTY, "empty collection");
        h.assertTrue(com.habits.progression.Effects.data(p) == com.habits.progression.ProgressionData.EMPTY, "empty progression");
        h.assertFalse(p.hasData(CoreRegistries.COLLECTION), "collection not created");
        h.succeed();
    }

    private static ServerPlayer player(GameTestHelper h, boolean autoAdd) {
        ServerPlayer p = CoreTests.player(h);
        p.setData(CoreRegistries.AUTO_ADD, autoAdd);
        return p;
    }

    private static int cards(ServerPlayer p) {
        return p.getInventory().countItem(CoreRegistries.CARD.get());
    }

    @GameTest(template = "empty")
    public static void autoAddOnLevelsWithoutAnItem(GameTestHelper h) {
        ServerPlayer p = player(h, true);
        SkillDef def = CoreTests.anySkill();
        h.assertTrue(HabitsApi.giveCard(p, def, null), "added");
        h.assertValueEqual(HabitsApi.level(p, def.id()), 1, "new skill at Lv1");
        h.assertTrue(HabitsApi.isUnseen(p, def.id()), "NEW badge in the book");
        HabitsApi.giveCard(p, def, null);
        HabitsApi.giveCard(p, def, null);
        h.assertValueEqual(HabitsApi.level(p, def.id()), 2, "same leveling as right-click (2 cards to Lv2)");
        h.assertValueEqual(cards(p), 0, "no card in the inventory");
        h.assertTrue(h.getEntities(EntityType.ITEM).isEmpty(), "no item entity");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void maxedCardsSkipped(GameTestHelper h) {
        ServerPlayer p = player(h, true);
        SkillDef def = CoreTests.anySkill();
        h.assertFalse(HabitsApi.skipMaxed(p, def), "not maxed: given");
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(def.id(), new SkillProgress(Leveling.MAX_LEVEL, 0, true)));
        h.assertTrue(HabitsApi.skipMaxed(p, def), "maxed: skipped");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void togglePayloadSetsIt(GameTestHelper h) {
        ServerPlayer p = CoreTests.player(h);
        h.assertFalse(HabitsApi.autoAdd(p), "fake players default off");
        p.setData(CoreRegistries.AUTO_ADD, true);
        h.assertTrue(HabitsApi.autoAdd(p), "set on");
        HabitsApi.setAutoAdd(p, false);
        h.assertFalse(HabitsApi.autoAdd(p), "payload turned it off");
        HabitsApi.setAutoAdd(p, true);
        h.assertTrue(HabitsApi.autoAdd(p), "payload turned it on");
        p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        HabitsApi.setAutoAdd(p, false);
        h.assertTrue(HabitsApi.autoAdd(p), "spectators cannot change it");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void rightClickStillAddsWithAutoAddOn(GameTestHelper h) {
        ServerPlayer p = player(h, true);
        SkillDef def = CoreTests.anySkill();
        p.getInventory().add(new ItemStack(CoreRegistries.BOOK.get()));
        ItemStack card = CardItems.create(def);
        card.setCount(2);
        Payloads.CardAdded a = HabitsApi.consumeCard(p, card);
        h.assertTrue(a != null && a.wasNew(), "right-click unlock (the flip payload)");
        h.assertValueEqual(card.getCount(), 1, "one card used");
        h.assertValueEqual(HabitsApi.level(p, def.id()), 1, "Lv1");
        h.succeed();
    }
}
