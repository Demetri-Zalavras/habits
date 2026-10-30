package com.habits.core;

import com.habits.Habits;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

/** Core GameTests (scaffold smoke). FakePlayerFactory players: makeMockServerPlayerInLevel breaks on synced attachments.
 * Each package keeps its own GameTests in its own package. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class CoreTests {
    private CoreTests() {}

    static ServerPlayer player(GameTestHelper h) {
        return FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-test"));
    }

    // Core tests never pin a catalog skill's perk, chance or id: catalog agents own those. Skills are picked by shape.

    /** Some loaded skill on the normal level curve (any will do; Mythical levels faster). */
    static SkillDef anySkill() {
        return first(Rarity.COMMON);
    }

    static SkillDef first(Rarity r) {
        for (SkillDef d : SkillRegistry.all()) if (d.rarity() == r) return d;
        throw new IllegalStateException("no " + r + " skill in the catalog");
    }

    /** First skill of this trigger type passing {@code ok}, or null. */
    @Nullable
    static SkillDef find(TriggerType type, Predicate<SkillDef> ok) {
        for (SkillDef d : SkillRegistry.byTrigger(type)) if (ok.test(d)) return d;
        return null;
    }

    @GameTest(template = "empty")
    public static void skillsLoadAndIndex(GameTestHelper h) {
        h.assertFalse(SkillRegistry.all().isEmpty(), "skills loaded");
        for (SkillDef d : SkillRegistry.all()) {
            h.assertTrue(SkillRegistry.get(d.id()) == d, "byId " + d.id());
            h.assertTrue(SkillRegistry.byTrigger(d.trigger().type()).contains(d), "byTrigger " + d.id());
            h.assertTrue(SkillRegistry.byCategory(d.category()).contains(d), "byCategory " + d.id());
        }
        var access = h.getLevel().registryAccess();
        IdMatch m = IdMatch.parse("#minecraft:base_stone_overworld,minecraft:cobblestone");
        h.assertTrue(m.test(ResourceLocation.withDefaultNamespace("deepslate"), TargetKind.BLOCK, access), "deepslate in tag");
        h.assertTrue(m.test(ResourceLocation.withDefaultNamespace("cobblestone"), TargetKind.BLOCK, access), "cobblestone listed");
        h.assertFalse(m.test(ResourceLocation.withDefaultNamespace("dirt"), TargetKind.BLOCK, access), "dirt not");
        h.assertTrue(IdMatch.parse("#minecraft:is_fire").test(ResourceLocation.withDefaultNamespace("lava"),
                Registries.DAMAGE_TYPE, access), "lava is fire");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void collectionAttachmentReads(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef stat = null, mech = null;
        for (SkillDef d : SkillRegistry.all()) {
            if (stat == null && d.perk().type().isPresent()) stat = d;
            if (mech == null && d.perk().mechanic().isPresent()) mech = d;
        }
        h.assertTrue(stat != null, "no skill with a stat perk type in the catalog");
        {
            PerkType type = stat.perk().type().get();
            ResourceLocation id = stat.id();
            h.assertValueEqual(HabitsApi.level(p, id), 0, "fresh level");
            p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(id, new SkillProgress(10, 0, true)));
            h.assertValueEqual(HabitsApi.level(p, id), 10, "level");
            h.assertValueEqual(HabitsApi.perkValue(p, type), stat.perkValue(10), "Lv10 value of " + id);
            p.setData(CoreRegistries.COLLECTION, HabitsApi.collection(p).with(id, new SkillProgress(10, 0, false)));
            h.assertFalse(HabitsApi.perkActive(p, id), "toggled off");
            h.assertValueEqual(HabitsApi.perkValue(p, type), 0.0, "off adds nothing");
        }
        h.assertTrue(CardItems.skillOf(CardItems.create(anySkill())) != null, "card round trip");
        // a mechanic skill loads even before its mechanic is registered, and reads its per-level value
        h.assertTrue(mech != null, "no skill with a perk mechanic in the catalog");
        {
            ResourceLocation mechanic = mech.perk().mechanic().get();
            p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY);
            h.assertValueEqual(HabitsApi.mechanicValue(p, mechanic), 0.0, "not owned");
            p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(mech.id(), new SkillProgress(10, 0, true)));
            h.assertValueEqual(HabitsApi.mechanicValue(p, mechanic), mech.perkValue(10), "Lv10 value of " + mech.id());
        }
        h.succeed();
    }

    /** A matching action unlocks a skill at Lv1 (guaranteed) and a non-matching one does not; an owned skill gains XP,
     * held as pending until a level-up or a flush writes it. */
    @GameTest(template = "empty")
    public static void matchingEventUnlocksAndXpLevels(GameTestHelper h) {
        ServerPlayer p = player(h), q = player(h);
        SkillXp.TEST_PLAYERS.add(p.getUUID());
        SkillXp.TEST_PLAYERS.add(q.getUUID());
        // the realistic event: stone broken with a pickaxe, so tool-gated skills (item filter) still qualify
        var ctx = TriggerContext.of(Blocks.STONE).withItem(net.minecraft.world.item.Items.IRON_PICKAXE);
        var dirt = TriggerContext.of(Blocks.DIRT).withItem(net.minecraft.world.item.Items.IRON_PICKAXE);
        SkillDef stone = find(TriggerType.BREAK_BLOCK, d -> d.trigger().min() <= 1
                && HabitsApi.matches(d.trigger(), ctx, p) && !HabitsApi.matches(d.trigger(), dirt, p));
        h.assertTrue(stone != null, "no block-break skill matching stone (with a pickaxe) but not dirt in the catalog");
        HabitsApi.onTrigger(q, TriggerType.BREAK_BLOCK, dirt);
        h.assertValueEqual(HabitsApi.level(q, stone.id()), 0, "dirt does not unlock " + stone.id());
        HabitsApi.onTrigger(p, TriggerType.BREAK_BLOCK, ctx);
        h.assertValueEqual(HabitsApi.collection(p).get(stone.id()), new SkillProgress(1, 0, true), "one stone break unlocks it at Lv1");
        SkillDef dmg = find(TriggerType.DAMAGE_TAKEN, d -> d.trigger().match().isPresent());
        h.assertTrue(dmg != null, "no damage-taken skill with a match in the catalog");
        HabitsApi.onTrigger(q, TriggerType.DAMAGE_TAKEN, TriggerContext.of(Habits.id("nope")));
        h.assertValueEqual(HabitsApi.level(q, dmg.id()), 0, "wrong damage type unlocks nothing");

        int need = HabitsApi.collection(p).get(stone.id()).needed(stone.rarity());
        h.assertFalse(SkillXp.onAction(p, stone, 50), "50 XP: no level-up");
        h.assertValueEqual(SkillXp.pending(p, stone.id()), 50, "held as pending");
        h.assertValueEqual(HabitsApi.collection(p).get(stone.id()).progress(), 0, "not written yet");
        h.assertTrue(SkillXp.onAction(p, stone, need - 50), "reaching the need levels up");
        h.assertValueEqual(HabitsApi.collection(p).get(stone.id()), new SkillProgress(2, 0, true), "Lv2, pending written");
        SkillXp.onAction(p, stone, 30);
        SkillXp.flush(p);
        h.assertValueEqual(HabitsApi.collection(p).get(stone.id()).progress(), 30, "flush writes pending XP");
        SkillXp.TEST_PLAYERS.remove(p.getUUID());
        SkillXp.TEST_PLAYERS.remove(q.getUUID());
        h.succeed();
    }

    /** A won card never lands in the empty selected hotbar slot (the fist the player fights with); it takes any other
     * free slot, and drops at the feet only when no other slot is free. */
    @GameTest(template = "empty")
    public static void wonCardSkipsTheEmptyHand(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.moveTo(h.absoluteVec(new Vec3(1.5, 1, 1.5)));
        var inv = p.getInventory();
        inv.selected = 0;
        for (int i = 1; i < inv.items.size(); i++) if (i != 20) inv.items.set(i, new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
        h.assertTrue(HabitsApi.dropCard(p, anySkill(), null) == null, "card went into the inventory");
        h.assertTrue(inv.getSelected().isEmpty(), "the selected hand stays empty");
        h.assertTrue(CardItems.skillOf(inv.items.get(20)) == anySkill(), "card took the other free slot");

        inv.items.set(20, new ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
        ItemEntity dropped = HabitsApi.dropCard(p, anySkill(), null);
        h.assertTrue(dropped != null, "only the hand is free: the card drops at the feet");
        h.assertTrue(inv.getSelected().isEmpty(), "the selected hand still stays empty");
        dropped.discard();
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void addCardLevelBoundaries(GameTestHelper h) {
        for (Rarity r : new Rarity[]{Rarity.COMMON, Rarity.MYTHICAL}) levelBoundaries(h, player(h), first(r).id(), r);
        h.succeed();
    }

    /** Adds cards one by one: each level takes exactly its rarity's cards, and Lv10 refuses more. */
    private static void levelBoundaries(GameTestHelper h, ServerPlayer p, ResourceLocation id, Rarity rarity) {
        h.assertFalse(HabitsApi.addCard(p, Habits.id("not_a_skill")), "unknown refused");
        int cards = 0;
        for (int level = 1; level <= Leveling.MAX_LEVEL; level++) {
            for (int i = 0; i < Leveling.cardsToNext(level - 1, rarity); i++) {
                h.assertValueEqual(HabitsApi.level(p, id), level - 1, "before Lv" + level + " card " + i);
                h.assertTrue(HabitsApi.addCard(p, id), "card accepted");
                cards++;
            }
            h.assertValueEqual(HabitsApi.collection(p).get(id), new SkillProgress(level, 0, true), "Lv" + level);
            h.assertValueEqual(cards, Leveling.totalCards(level, rarity), "cards to Lv" + level);
        }
        h.assertValueEqual(cards, rarity == Rarity.MYTHICAL ? 15 : 55, rarity + " cards to Lv10");
        h.assertFalse(HabitsApi.addCard(p, id), "Lv10 refuses");
        h.assertValueEqual(HabitsApi.level(p, id), 10, "still 10");
    }

    @GameTest(template = "empty")
    public static void bookGateAndMaxed(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = anySkill();
        ItemStack cards = CardItems.create(def);
        cards.setCount(3);
        p.setItemInHand(InteractionHand.MAIN_HAND, cards);
        HabitsApi.addCardInHand(p, InteractionHand.MAIN_HAND);
        h.assertValueEqual(cards.getCount(), 3, "no book: card kept");
        h.assertValueEqual(HabitsApi.level(p, def.id()), 0, "no book: not added");
        p.getInventory().setItem(20, new ItemStack(CoreRegistries.BOOK.get())); // off the hotbar: the card goes in the main hand
        HabitsApi.addCardInHand(p, InteractionHand.MAIN_HAND);
        h.assertValueEqual(cards.getCount(), 2, "with book: consumed");
        h.assertValueEqual(HabitsApi.level(p, def.id()), 1, "with book: Lv1");
        HabitsApi.addCardInHand(p, InteractionHand.MAIN_HAND);
        h.assertValueEqual(cards.getCount(), 1, "second card consumed");
        h.assertValueEqual(HabitsApi.collection(p).get(def.id()), new SkillProgress(1, Leveling.XP_PER_CARD, true), "second card added");
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(def.id(), new SkillProgress(10, 0, true)));
        HabitsApi.addCardInHand(p, InteractionHand.MAIN_HAND);
        h.assertValueEqual(cards.getCount(), 1, "maxed: card kept");
        h.succeed();
    }

    /** Wild Card: a Legendary unlock fires habits:legendary_card (its trigger); with the perk (fixture at 100%) an
     * action's XP is doubled; using a Wild Card item needs the book and an owned skill below Lv10, and adds one card. */
    @GameTest(template = "empty")
    public static void wildCardTriggerPerkAndUse(GameTestHelper h) {
        SkillDef common = first(Rarity.COMMON), legendary = first(Rarity.LEGENDARY);
        ServerPlayer p = player(h);
        int[] fired = new int[1];
        com.habits.trigger.Triggers.Spy spy = (who, type, ctx) -> { if (who == p && HabitsApi.LEGENDARY_CARD.equals(ctx.target())) fired[0]++; };
        com.habits.trigger.Triggers.TEST_PLAYERS.add(p.getUUID());
        com.habits.trigger.Triggers.SPIES.add(spy);
        try {
            HabitsApi.giveCard(p, common, null);
            h.assertValueEqual(fired[0], 0, "a Common card: no event");
            HabitsApi.giveCard(p, legendary, null);
            h.assertValueEqual(fired[0], 1, "a Legendary card fires legendary_card");
        } finally {
            com.habits.trigger.Triggers.SPIES.remove(spy);
            com.habits.trigger.Triggers.TEST_PLAYERS.remove(p.getUUID());
        }

        SkillDef wild = SkillRegistry.get(HabitsApi.WILD_CARD);
        h.assertTrue(wild != null && wild.rarity() == Rarity.MYTHICAL, "Mythical wild_card skill loaded");
        SkillDef fixture = SkillRegistry.testFixture(new SkillDef(Habits.id("test/wild_card"), wild.rarity(), wild.category(), wild.trigger(),
                wild.icon(), wild.art(), new PerkSpec(wild.perk().type(), wild.perk().mechanic(), wild.perk().params(), 0, 0,
                java.util.Optional.of(java.util.Collections.nCopies(10, 100.0))), wild.name(), wild.foundBy(), wild.perkText()));
        ServerPlayer q = player(h);
        q.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(fixture.id(), new SkillProgress(1, 0, true))
                .with(common.id(), new SkillProgress(2, 0, true)));
        h.assertTrue(HabitsApi.wildDouble(q), "fixture at 100%: the action's XP is doubled");
        h.assertFalse(HabitsApi.wildDouble(p), "no Wild Card skill: never doubled");
        q.getInventory().clearContent();
        ItemStack hand = new ItemStack(CoreRegistries.WILD_CARD.get(), 3);
        q.setItemInHand(InteractionHand.MAIN_HAND, hand);
        h.assertFalse(HabitsApi.useWildCard(q, common.id()), "no book");
        q.getInventory().setItem(20, new ItemStack(CoreRegistries.BOOK.get()));
        h.assertFalse(HabitsApi.useWildCard(q, legendary.id()), "not owned");
        h.assertTrue(HabitsApi.useWildCard(q, common.id()), "owned below Lv10");
        h.assertValueEqual(hand.getCount(), 2, "one Wild Card spent");
        h.assertValueEqual(HabitsApi.collection(q).get(common.id()), new SkillProgress(2, Leveling.XP_PER_CARD, true), "counted as one card");
        q.setData(CoreRegistries.COLLECTION, HabitsApi.collection(q).with(common.id(), new SkillProgress(10, 0, true)));
        h.assertFalse(HabitsApi.useWildCard(q, common.id()), "maxed refused");
        h.assertValueEqual(hand.getCount(), 2, "kept when refused");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void togglePerk(GameTestHelper h) {
        ServerPlayer p = player(h);
        ResourceLocation id = anySkill().id();
        HabitsApi.togglePerk(p, id);
        h.assertTrue(HabitsApi.collection(p).skills().isEmpty(), "unowned toggle does nothing");
        HabitsApi.addCard(p, id);
        HabitsApi.togglePerk(p, id);
        h.assertFalse(HabitsApi.perkActive(p, id), "off");
        h.assertValueEqual(HabitsApi.level(p, id), 1, "level kept");
        HabitsApi.togglePerk(p, id);
        h.assertTrue(HabitsApi.perkActive(p, id), "on again");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void collectionPersistsAndSurvivesDeath(GameTestHelper h) {
        ServerPlayer p = player(h);
        ResourceLocation id = anySkill().id();
        HabitsCollection col = HabitsCollection.EMPTY.with(id, new SkillProgress(3, 2, false));
        p.setData(CoreRegistries.COLLECTION, col);
        var tag = p.serializeAttachments(h.getLevel().registryAccess());
        h.assertTrue(tag != null && tag.contains(Habits.MODID + ":collection"), "collection saved");
        HabitsCollection loaded = HabitsCollection.CODEC.parse(NbtOps.INSTANCE, tag.get(Habits.MODID + ":collection")).getOrThrow();
        h.assertValueEqual(loaded, col, "save round trip");
        ServerPlayer respawned = player(h);
        respawned.copyAttachmentsFrom(p, true);
        h.assertValueEqual(HabitsApi.collection(respawned), col, "kept on death");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void firstJoinBook(GameTestHelper h) {
        ServerPlayer p = player(h);
        h.assertTrue(CoreInit.giveFirstBook(p), "first join gives the book");
        h.assertTrue(p.getInventory().contains(s -> s.is(CoreRegistries.BOOK.get())), "book in inventory");
        h.assertFalse(CoreInit.giveFirstBook(p), "only once");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void droppedCardSurvivesHazards(GameTestHelper h) {
        ServerPlayer p = player(h);
        Vec3 at = h.absoluteVec(new Vec3(1.5, 1.5, 1.5));
        ItemEntity card = new ItemEntity(h.getLevel(), at.x, at.y, at.z, CardItems.create(anySkill()));
        h.getLevel().addFreshEntity(card);
        var src = h.getLevel().damageSources();
        h.assertTrue(card.fireImmune(), "fire immune");
        h.assertFalse(card.hurt(src.lava(), 100), "lava");
        h.assertFalse(card.hurt(src.inFire(), 100), "fire");
        h.assertFalse(card.hurt(src.onFire(), 100), "burning");
        h.assertFalse(card.hurt(src.explosion(null, null), 100), "explosion");
        h.assertFalse(card.hurt(src.source(DamageTypes.CACTUS), 100), "cactus");
        h.assertTrue(card.isAlive(), "still there");
        h.assertValueEqual(card.lifespan, CoreInit.CARD_LIFESPAN.get() * 20, "longer despawn");
        // a plain item still burns, so the checks above are not vacuous
        ItemEntity stick = new ItemEntity(h.getLevel(), card.getX(), card.getY(), card.getZ(), new ItemStack(net.minecraft.world.item.Items.STICK));
        h.getLevel().addFreshEntity(stick);
        h.assertTrue(stick.hurt(src.lava(), 100) && !stick.isAlive(), "a stick burns");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void customSkillsIndexedByEvent(GameTestHelper h) {
        int checked = 0;
        for (TriggerType t : new TriggerType[]{TriggerType.CUSTOM, TriggerType.CUSTOM_UNITS}) {
            for (SkillDef d : SkillRegistry.byTrigger(t)) {
                var m = d.trigger().match();
                if (m.isEmpty() || !m.get().tags().isEmpty()) {
                    h.assertTrue(SkillRegistry.candidates(t, Habits.id("no_such_event")).contains(d), "catch-all " + d.id());
                    continue;
                }
                for (ResourceLocation ev : m.get().ids()) h.assertTrue(SkillRegistry.candidates(t, ev).contains(d), d.id() + " under " + ev);
                h.assertFalse(SkillRegistry.candidates(t, Habits.id("no_such_event")).contains(d), d.id() + " not under other events");
                checked++;
            }
        }
        h.assertTrue(checked > 0, "no CUSTOM skill with an event id in the catalog");
        h.assertTrue(SkillRegistry.candidates(TriggerType.BREAK_BLOCK, null) == SkillRegistry.byTrigger(TriggerType.BREAK_BLOCK), "non-custom types unchanged");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void swingAirIsGatedAndRateLimited(GameTestHelper h) {
        ServerPlayer p = player(h);
        List<TriggerContext> seen = new java.util.ArrayList<>();
        com.habits.trigger.Triggers.Spy spy = (sp, type, ctx) -> { if (sp == p && type == TriggerType.CUSTOM) seen.add(ctx); };
        com.habits.trigger.Triggers.TEST_PLAYERS.add(p.getUUID());
        com.habits.trigger.Triggers.SPIES.add(spy);
        try {
            h.assertTrue(CoreInit.swingAir(p), "first swing fires");
            h.assertValueEqual(seen.size(), 1, "one trigger");
            h.assertValueEqual(seen.get(0).target(), CoreInit.SWING_AIR, "event id");
            h.assertFalse(CoreInit.swingAir(p), "same tick: faster than the attack cooldown");
            p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            h.assertFalse(CoreInit.swingAir(p), "spectators never fire");
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        } finally {
            com.habits.trigger.Triggers.SPIES.remove(spy);
        }
        long cadence = Math.max(1, Math.round(p.getCurrentItemAttackStrengthDelay()));
        h.runAfterDelay(cadence + 1, () -> {
            h.assertTrue(CoreInit.swingAir(p), "fires again after the cooldown");
            com.habits.trigger.Triggers.TEST_PLAYERS.remove(p.getUUID());
            h.succeed();
        });
    }

    /** Worlds saved while print variants existed hold card stacks with {@code "variant": "full_art"} (or reverse_holo,
     * normal) in the card component. They must load, stack with and count as the one normal print. */
    @GameTest(template = "empty")
    public static void oldVariantCardsLoadAsTheNormalPrint(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = anySkill();
        p.getInventory().setItem(20, new ItemStack(CoreRegistries.BOOK.get())); // off the hotbar: the card goes in the main hand
        var ops = h.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ItemStack fresh = CardItems.create(def);
        List<ItemStack> loaded = new java.util.ArrayList<>();
        for (String v : new String[]{"normal", "reverse_holo", "full_art"}) {
            var tag = (net.minecraft.nbt.CompoundTag) ItemStack.CODEC.encodeStart(ops, fresh).getOrThrow();
            tag.getCompound("components").getCompound(CoreRegistries.CARD_DATA.getId().toString()).putString("variant", v);
            ItemStack old = ItemStack.CODEC.parse(ops, tag).getOrThrow(); // throws if the old key broke decoding
            h.assertTrue(ItemStack.isSameItemSameComponents(old, fresh), "old " + v + " card is the normal print");
            loaded.add(old);
        }
        // the three old prints level like three normal cards: unlock, then Lv2
        for (ItemStack card : loaded) {
            p.setItemInHand(InteractionHand.MAIN_HAND, card);
            HabitsApi.addCardInHand(p, InteractionHand.MAIN_HAND);
            h.assertTrue(card.isEmpty(), "old card consumed");
        }
        h.assertValueEqual(HabitsApi.collection(p).get(def.id()), new SkillProgress(2, 0, true), "three old cards = Lv2");
        h.succeed();
    }

    private static int cardsIn(ServerPlayer p, SkillDef def) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (CardItems.skillOf(s) == def) n += s.getCount();
        return n;
    }

    @GameTest(template = "empty")
    public static void wonCardGoesToInventoryElseFeet(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = anySkill();
        Vec3 far = h.absoluteVec(new Vec3(3.5, 1.5, 3.5));
        h.assertTrue(HabitsApi.dropCard(p, def, far) == null, "room in the inventory: no item entity");
        h.assertValueEqual(cardsIn(p, def), 1, "card is in the inventory");
        for (int i = 0; i < p.getInventory().items.size(); i++) p.getInventory().items.set(i, new ItemStack(net.minecraft.world.item.Items.STONE, 64));
        ItemEntity e = HabitsApi.dropCard(p, def, far);
        h.assertTrue(e != null && e.isAlive(), "full inventory: dropped");
        h.assertTrue(e.position().distanceTo(p.position()) < 0.01, "at the player's feet, not at the action");
        h.assertTrue(CardItems.skillOf(e.getItem()) == def, "the dropped item is the card");
        e.discard();
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void cardAddedPayloadFields(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = anySkill();
        ResourceLocation id = def.id();
        p.getInventory().setItem(20, new ItemStack(CoreRegistries.BOOK.get()));
        h.assertValueEqual(HabitsApi.consumeCard(p, CardItems.create(def)),
                new Payloads.CardAdded(id, 1, 0, 2 * Leveling.XP_PER_CARD, true, false), "unlock: new skill");
        h.assertValueEqual(HabitsApi.consumeCard(p, CardItems.create(def)),
                new Payloads.CardAdded(id, 1, Leveling.XP_PER_CARD, 2 * Leveling.XP_PER_CARD, false, false), "progress: Lv1 (1/2)");
        h.assertValueEqual(HabitsApi.consumeCard(p, CardItems.create(def)),
                new Payloads.CardAdded(id, 2, 0, 3 * Leveling.XP_PER_CARD, false, true), "level up: Lv2");
        // the action bar stays quiet for a new skill (the flip shows it) and speaks for progress and level-ups
        h.assertTrue(HabitsApi.actionBar(new Payloads.CardAdded(id, 1, 0, 2 * Leveling.XP_PER_CARD, true, false), def) == null, "new: no line");
        var progress = HabitsApi.actionBar(new Payloads.CardAdded(id, 1, Leveling.XP_PER_CARD, 2 * Leveling.XP_PER_CARD, false, false), def);
        h.assertTrue(progress != null && progress.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("habits.msg.added"), "progress: added line");
        var up = HabitsApi.actionBar(new Payloads.CardAdded(id, 2, 0, 3 * Leveling.XP_PER_CARD, false, true), def);
        h.assertTrue(up != null && up.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("habits.msg.level_up"), "level up: level-up line");
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(id, new SkillProgress(10, 0, true)));
        ItemStack maxed = CardItems.create(def);
        h.assertTrue(HabitsApi.consumeCard(p, maxed) == null && maxed.getCount() == 1, "maxed: nothing sent, card kept");
        p.getInventory().setItem(20, ItemStack.EMPTY);
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY);
        h.assertTrue(HabitsApi.consumeCard(p, CardItems.create(def)) == null, "no book: nothing sent");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void unseenOnUnlockClearedByMarkSeen(GameTestHelper h) {
        ServerPlayer p = player(h);
        var it = SkillRegistry.all().iterator();
        SkillDef a = it.next(), b = it.next();
        h.assertTrue(HabitsApi.newestUnseen(p) == null, "fresh: nothing new");
        HabitsApi.addCard(p, a.id());
        h.assertTrue(HabitsApi.isUnseen(p, a.id()), "unlock marks unseen");
        HabitsApi.addCard(p, b.id());
        h.assertValueEqual(HabitsApi.newestUnseen(p), b.id(), "newest is the last unlocked");
        HabitsApi.markSeen(p, b.id());
        h.assertFalse(HabitsApi.isUnseen(p, b.id()), "MarkSeen clears it");
        h.assertValueEqual(HabitsApi.newestUnseen(p), a.id(), "the older one is next");
        HabitsApi.addCard(p, a.id());
        HabitsApi.markSeen(p, a.id());
        HabitsApi.addCard(p, a.id());
        h.assertFalse(HabitsApi.isUnseen(p, a.id()), "more cards of an owned skill do not mark it again");
        HabitsApi.markSeen(p, Habits.id("not_a_skill"));
        // saved and kept on death like the collection
        HabitsApi.addCard(p, b.id());
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY);
        HabitsApi.addCard(p, b.id());
        ServerPlayer respawned = player(h);
        respawned.copyAttachmentsFrom(p, true);
        h.assertTrue(HabitsApi.isUnseen(respawned, b.id()), "kept on death");
        var tag = p.serializeAttachments(h.getLevel().registryAccess());
        h.assertTrue(tag != null && tag.contains(Habits.MODID + ":unseen"), "saved");
        h.succeed();
    }
}
