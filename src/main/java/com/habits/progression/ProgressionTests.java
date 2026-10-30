package com.habits.progression;

import com.habits.Habits;
import com.habits.core.Category;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Sigil and charm GameTests. Skills are picked by shape, never by id (catalog agents own ids). */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class ProgressionTests {
    private ProgressionTests() {}

    static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-prog"));
        Vec3 at = h.absoluteVec(new Vec3(1.5, 2, 1.5));
        p.setPos(at.x, at.y, at.z);
        return p;
    }

    static SkillDef find(Predicate<SkillDef> ok) {
        for (SkillDef d : SkillRegistry.all()) if (ok.test(d)) return d;
        throw new IllegalStateException("no catalog skill of the needed shape");
    }

    static void own(ServerPlayer p, SkillDef def, int level) {
        p.setData(CoreRegistries.COLLECTION, HabitsApi.collection(p).with(def.id(), new SkillProgress(level, 0, true)));
    }

    static int count(ServerPlayer p, Predicate<ItemStack> ok) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (ok.test(p.getInventory().getItem(i))) n += p.getInventory().getItem(i).getCount();
        return n;
    }

    /** Puts {@code n} sigils of that kind in the book's store (no feedback) and returns the kind. */
    static SigilData store(ServerPlayer p, SigilType t, Rarity r, int n) {
        SigilData s = new SigilData(t, r);
        p.setData(ProgRegistries.DATA, Effects.data(p).withStored(s, n));
        return s;
    }

    /** Puts a charm at the front of the book's bag (no feedback); its index is 0. */
    static CharmData bag(ServerPlayer p, CharmData c) {
        p.setData(ProgRegistries.DATA, Effects.data(p).withBagged(c));
        return c;
    }

    static int stored(ServerPlayer p) {
        int n = 0;
        for (SigilData s : SigilData.ALL) n += Effects.data(p).stored(s);
        return n;
    }

    @GameTest(template = "empty")
    public static void potentAndReachChangeRealPerkReads(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef stat = find(d -> d.perk().type().isPresent() && d.perkValue(1) > 0);
        own(p, stat, 1);
        double before = HabitsApi.perkValue(p, stat.perk().type().get());
        h.assertValueEqual(before, stat.perkValue(1), "unsocketed value");
        h.assertTrue(Actions.socket(p, stat.id(), 0, store(p, SigilType.POTENT, Rarity.LEGENDARY, 1)), "socketed");
        double pct = ProgressionInit.sigilPercent(SigilType.POTENT, Rarity.LEGENDARY);
        h.assertTrue(Math.abs(HabitsApi.perkValue(p, stat.perk().type().get()) - before * (1 + pct / 100)) < 1e-9,
                "Potent scales the read value of " + stat.id());

        // Reach grows a mechanic's radius param through the same read path
        SkillDef mech = find(d -> d.perk().mechanic().isPresent() && SigilType.REACH.fits(d));
        String key = mech.perk().params().keySet().stream().filter(PMath::isRangeKey).findFirst().orElseThrow();
        own(p, mech, 1);
        h.assertTrue(Actions.socket(p, mech.id(), 0, store(p, SigilType.REACH, Rarity.COMMON, 1)), "reach socketed");
        var hit = HabitsApi.mechanicHit(p, mech.perk().mechanic().get());
        h.assertTrue(hit != null, "mechanic active");
        double f = 1 + ProgressionInit.sigilPercent(SigilType.REACH, Rarity.COMMON) / 100;
        h.assertValueEqual(hit.skill().perk().param(key, "?"), PMath.scaleParam(mech.perk().param(key, "?"), f), "Reach scaled " + key + " of " + mech.id());
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void potentShortensLowerIsBetterValues(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = SkillRegistry.get(Habits.id("last_stand"));
        own(p, def, 1);
        h.assertTrue(Actions.socket(p, def.id(), 0, store(p, SigilType.POTENT, Rarity.LEGENDARY, 1)), "socketed");
        double pct = ProgressionInit.sigilPercent(SigilType.POTENT, Rarity.LEGENDARY);
        double boosted = Effects.def(p, def).perkValue(1);
        h.assertTrue(Math.abs(boosted - def.perkValue(1) / (1 + pct / 100)) < 1e-9, "cooldown shrinks, got " + boosted);
        h.succeed();
    }

    static double swiftFactor(Rarity r) {
        return PMath.cooldownFactor(ProgressionInit.sigilPercent(SigilType.SWIFT, r), ProgressionInit.SWIFT_CAP.get());
    }

    @GameTest(template = "empty")
    public static void swiftAndPotentShortenWaitValue(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = SkillRegistry.get(Habits.id("last_stand"));
        h.assertTrue(SigilType.SWIFT.fits(def), "Swift fits a card whose value is a cooldown");
        own(p, def, 4);
        h.assertTrue(Actions.socket(p, def.id(), 0, store(p, SigilType.SWIFT, Rarity.EPIC, 1)), "swift socketed");
        double swift = Effects.def(p, def).perkValue(4);
        h.assertTrue(Math.abs(swift - def.perkValue(4) * swiftFactor(Rarity.EPIC)) < 1e-9, "Swift shortens, got " + swift);
        h.assertTrue(Actions.socket(p, def.id(), 1, store(p, SigilType.POTENT, Rarity.EPIC, 1)), "potent socketed");
        double both = Effects.def(p, def).perkValue(4);
        double want = def.perkValue(4) * swiftFactor(Rarity.EPIC) / (1 + ProgressionInit.sigilPercent(SigilType.POTENT, Rarity.EPIC) / 100);
        h.assertTrue(Math.abs(both - want) < 1e-9, "Swift and Potent both shorten, got " + both);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void swiftShortensParamWaits(GameTestHelper h) {
        ServerPlayer p = player(h);
        for (String[] k : new String[][]{{"pearl_pitcher", "cooldown"}, {"abyssal_eye", "interval"}}) {
            SkillDef def = SkillRegistry.get(Habits.id(k[0]));
            own(p, def, 1);
            h.assertTrue(Actions.socket(p, def.id(), 0, store(p, SigilType.SWIFT, Rarity.EPIC, 1)), "swift socketed on " + k[0]);
            h.assertValueEqual(Effects.def(p, def).perk().param(k[1], "?"), PMath.scaleParam(def.perk().param(k[1], "?"), swiftFactor(Rarity.EPIC)),
                    "Swift shortened " + k[0] + " " + k[1]);
            h.assertTrue(Effects.def(p, def).perkValue(1) == def.perkValue(1), "value untouched on " + k[0]);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void potentStopsAtValueMax(GameTestHelper h) {
        ServerPlayer p = player(h);
        double f = 1 + ProgressionInit.sigilPercent(SigilType.POTENT, Rarity.LEGENDARY) / 100;
        // pack_rat: past 54 slots the pack drops items; glider_kick: past 100 the glide climbs forever
        for (String path : List.of("pack_rat", "glider_kick")) {
            SkillDef def = SkillRegistry.get(Habits.id(path));
            double max = Double.parseDouble(def.perk().param("value_max", "?"));
            own(p, def, 10);
            h.assertTrue(Actions.socket(p, def.id(), 0, store(p, SigilType.POTENT, Rarity.LEGENDARY, 1)), "socketed " + path);
            var hit = HabitsApi.mechanicHit(p, def.perk().mechanic().get());
            h.assertTrue(hit != null && hit.value() == max, path + " Lv10 stops at " + max + ", got " + (hit == null ? null : hit.value()));
            h.assertValueEqual(Effects.def(p, def).perkValue(1), Math.min(max, def.perkValue(1) * f), path + " Lv1 still boosted");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void socketRefusesMismatchedType(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = find(d -> !SigilType.SWIFT.fits(d));
        own(p, def, 10);
        SigilData swift = store(p, SigilType.SWIFT, Rarity.RARE, 1);
        h.assertFalse(Actions.socket(p, def.id(), 0, swift), "swift refused on a card with no cooldown");
        h.assertValueEqual(Effects.data(p).stored(swift), 1, "sigil kept");
        h.assertFalse(Actions.socket(p, def.id(), 0, new SigilData(SigilType.POTENT, Rarity.RARE)), "a sigil not in the store is refused");
        h.assertTrue(Effects.data(p).isEmpty(), "nothing socketed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void socketCountByLevel(GameTestHelper h) {
        int[] want = {0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4};
        for (int lv = 0; lv <= 10; lv++) h.assertValueEqual(ProgressionApi.socketCount(lv), want[lv], "sockets at Lv" + lv);
        ServerPlayer p = player(h);
        SkillDef def = find(d -> true);
        own(p, def, 3);
        SigilData lucky = store(p, SigilType.LUCKY, Rarity.COMMON, 1);
        h.assertFalse(Actions.socket(p, def.id(), 1, lucky), "second socket locked at Lv3");
        own(p, def, 4);
        h.assertTrue(Actions.socket(p, def.id(), 1, lucky), "second socket open at Lv4");
        h.assertValueEqual(ProgressionApi.sockets(p, def.id()).size(), 2, "two sockets listed");
        h.succeed();
    }

    // book: the binder's per-frame readers agree with the list API and the effect math
    @GameTest(template = "empty")
    public static void bookReaders(GameTestHelper h) {
        int[] open = {1, 4, 7, 10};
        for (int i = 0; i < 4; i++) h.assertValueEqual(ProgressionApi.socketLevel(i), open[i], "socket " + i + " level");
        h.assertValueEqual(ProgressionApi.socketLevel(4), -1, "no fifth socket");
        h.assertValueEqual(ProgressionApi.nextCharmSlotAt(0), 25, "next slot at 25");
        h.assertValueEqual(ProgressionApi.nextCharmSlotAt(30), 50, "then at 50");
        h.assertValueEqual(ProgressionApi.nextCharmSlotAt(100), -1, "all open at 100");
        ServerPlayer p = player(h);
        SkillDef def = find(d -> d.perkValue(1) > 0);
        own(p, def, 1);
        h.assertTrue(ProgressionApi.boosted(p, def) == def, "nothing socketed: same def");
        h.assertTrue(Actions.socket(p, def.id(), 0, store(p, SigilType.POTENT, Rarity.RARE, 1)), "socketed");
        h.assertValueEqual(ProgressionApi.sigil(p, def.id(), 0), ProgressionApi.sockets(p, def.id()).get(0), "one socket read");
        double want = def.perkValue(1) * ProgressionApi.perkMultiplier(p, def);
        h.assertTrue(Math.abs(ProgressionApi.boosted(p, def).perkValue(1) - want) < 1e-6, "boosted value");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void unsocketReturnsTheSigil(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = find(d -> true);
        own(p, def, 1);
        SigilData s = store(p, SigilType.LUCKY, Rarity.EPIC, 2);
        h.assertTrue(Actions.socket(p, def.id(), 0, s), "socketed");
        h.assertValueEqual(ProgressionApi.sigilCount(p, SigilType.LUCKY, Rarity.EPIC), 1, "one left the store");
        double drop = ProgressionApi.dropMultiplier(p, def);
        h.assertTrue(Math.abs(drop - (1 + ProgressionInit.sigilPercent(SigilType.LUCKY, Rarity.EPIC) / 100)) < 1e-9, "Lucky drop multiplier");
        // swapping puts the old one back in the store
        SigilData s2 = store(p, SigilType.POTENT, Rarity.COMMON, 1);
        h.assertTrue(Actions.socket(p, def.id(), 0, s2), "swapped");
        h.assertValueEqual(Effects.data(p).stored(s), 2, "old sigil back in the store");
        h.assertValueEqual(Effects.data(p).stored(s2), 0, "new one taken");
        h.assertTrue(Actions.unsocket(p, def.id(), 0), "unsocketed");
        h.assertValueEqual(Effects.data(p).stored(s2), 1, "removed sigil back in the store");
        h.assertValueEqual(ProgressionApi.storedSigils(p), List.of(s2, s), "store lists both kinds, by type then rarity");
        h.assertTrue(Effects.data(p).isEmpty(), "socket empty");
        h.assertFalse(Actions.unsocket(p, def.id(), 0), "nothing left to remove");
        h.assertValueEqual(count(p, st -> SigilItem.dataOf(st) != null), 0, "no sigil item ever in the inventory");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void charmEquipRespectsSlotCount(GameTestHelper h) {
        ServerPlayer p = player(h);
        h.assertValueEqual(ProgressionApi.charmSlots(p), 1, "one slot with no skills");
        RandomSource r = RandomSource.create(1);
        bag(p, CharmData.roll(Category.WARRIOR, Rarity.RARE, r));
        h.assertFalse(Actions.equipCharm(p, 1, 0), "slot 2 locked");
        h.assertTrue(Actions.equipCharm(p, 0, 0), "slot 1 open");
        // 25 owned skills open slot 2
        Map<ResourceLocation, SkillProgress> m = new HashMap<>();
        for (SkillDef d : SkillRegistry.all()) { if (m.size() == 25) break; m.put(d.id(), new SkillProgress(1, 0, true)); }
        p.setData(CoreRegistries.COLLECTION, new HabitsCollection(m));
        h.assertValueEqual(ProgressionApi.charmSlots(p), 2, "two slots at 25 skills");
        bag(p, CharmData.roll(Category.MINING, Rarity.COMMON, r));
        h.assertTrue(Actions.equipCharm(p, 1, 0), "slot 2 open");
        h.assertTrue(Effects.data(p).bag().isEmpty(), "both moved out of the bag");
        h.assertTrue(Actions.unequipCharm(p, 0), "taken off");
        h.assertValueEqual(Effects.data(p).bag().size(), 1, "back in the bag");
        h.assertValueEqual(ProgressionApi.equippedCharms(p).size(), 2, "two charms listed");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void rerollSpendsASigilAndChangesOneLine(GameTestHelper h) {
        ServerPlayer p = player(h);
        CharmData c = CharmData.roll(Category.GUARDIAN, Rarity.LEGENDARY, RandomSource.create(7));
        h.assertValueEqual(c.lines().size(), 4, "legendary has four lines");
        bag(p, c);
        h.assertTrue(Actions.equipCharm(p, 0, 0), "equipped");
        SigilData epic = store(p, SigilType.SWIFT, Rarity.EPIC, 1);
        h.assertFalse(Actions.rerollCharmLine(p, true, 0, 2), "epic sigil too weak for a legendary charm");
        SigilData legLucky = store(p, SigilType.LUCKY, Rarity.LEGENDARY, 1), legPotent = store(p, SigilType.POTENT, Rarity.LEGENDARY, 1);
        h.assertTrue(Actions.rerollCharmLine(p, true, 0, 2), "rerolled");
        h.assertValueEqual(Effects.data(p).stored(legPotent), 0, "the first legendary kind (by type) spent");
        h.assertValueEqual(Effects.data(p).stored(legLucky), 1, "the other legendary kept");
        h.assertValueEqual(Effects.data(p).stored(epic), 1, "epic sigil kept");
        CharmData after = Effects.data(p).charm(0);
        // an Uncommon charm takes the cheapest qualifying sigil: Epic over Legendary
        CharmData kept = bag(p, CharmData.roll(Category.EXPLORATION, Rarity.UNCOMMON, RandomSource.create(2)));
        h.assertTrue(Actions.rerollCharmLine(p, false, 0, 0), "stored charm rerolled");
        h.assertTrue(Effects.data(p).bag().get(0) != kept, "stored charm replaced in place");
        h.assertValueEqual(Effects.data(p).stored(epic), 0, "epic spent before legendary");
        h.assertValueEqual(Effects.data(p).stored(legLucky), 1, "legendary still kept");
        for (int i = 0; i < 4; i++) if (i != 2) h.assertValueEqual(after.lines().get(i), c.lines().get(i), "line " + i + " unchanged");
        long stats = after.lines().stream().filter(l -> l.kind() == CharmData.LineKind.STAT).count();
        h.assertTrue(stats <= 1, "at most one stat line");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void sigilDropsOnTriggerMissAtConfiguredRate(GameTestHelper h) {
        ServerPlayer p = player(h);
        Drops.TEST_PLAYERS.add(p.getUUID());
        try {
            Drops.testMissChance = 1.0;
            // a block id no card matches: every roll misses (roll first, match after), so each call is a miss
            TriggerContext none = TriggerContext.of(Habits.id("no_such_block"));
            Drops.testIdleMs = 31_000;
            HabitsApi.onTrigger(p, TriggerType.BREAK_BLOCK, none);
            h.assertValueEqual(stored(p), 0, "an idle player gets no sigil");
            Drops.testIdleMs = -1;
            HabitsApi.onTrigger(p, TriggerType.BREAK_BLOCK, none);
            HabitsApi.onTrigger(p, TriggerType.BREAK_BLOCK, none);
            h.assertValueEqual(stored(p), 1, "one sigil, the second miss is inside the minimum gap");
            h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(4),
                    e -> SigilItem.dataOf(e.getItem()) != null).isEmpty(), "nothing on the ground");
            h.assertValueEqual(count(p, st -> SigilItem.dataOf(st) != null), 0, "nothing in the inventory");
        } finally {
            Drops.testMissChance = -1;
            Drops.testIdleMs = -1;
            Drops.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void dataSurvivesDeath(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = find(d -> true);
        own(p, def, 1);
        bag(p, CharmData.roll(Category.EXPLORATION, Rarity.UNCOMMON, RandomSource.create(3)));
        h.assertTrue(Actions.socket(p, def.id(), 0, store(p, SigilType.POTENT, Rarity.RARE, 1)) && Actions.equipCharm(p, 0, 0), "set up");
        bag(p, CharmData.roll(Category.MINING, Rarity.RARE, RandomSource.create(4)));
        store(p, SigilType.SWIFT, Rarity.EPIC, 5);
        ProgressionData before = Effects.data(p);
        ServerPlayer reborn = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-prog-2"));
        NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(reborn, p, true));
        h.assertValueEqual(Effects.data(reborn), before, "sockets, charms and stored sigils kept on death");
        h.assertValueEqual(ProgressionApi.sigilCount(reborn, SigilType.SWIFT, Rarity.EPIC), 5, "store count kept");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void pickupAbsorbsSigilItems(GameTestHelper h) {
        ServerPlayer p = player(h);
        Drops.TEST_PLAYERS.add(p.getUUID());
        try {
            ItemStack st = SigilItem.create(new SigilData(SigilType.REACH, Rarity.RARE));
            st.setCount(3);
            ItemEntity e = new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), st);
            e.setDefaultPickUpDelay();
            h.getLevel().addFreshEntity(e);
            e.playerTouch(p);
            h.assertValueEqual(stored(p), 0, "pickup delay still respected");
            e.setNoPickUpDelay();
            e.playerTouch(p);
            h.assertValueEqual(ProgressionApi.sigilCount(p, SigilType.REACH, Rarity.RARE), 3, "three absorbed");
            h.assertTrue(e.isRemoved(), "the item entity is gone");
            h.assertValueEqual(count(p, x -> SigilItem.dataOf(x) != null), 0, "none in the inventory");
        } finally {
            Drops.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void inventorySweepAbsorbsSigilItems(GameTestHelper h) {
        ServerPlayer p = player(h);
        ItemStack st = SigilItem.create(new SigilData(SigilType.SWIFT, Rarity.UNCOMMON));
        st.setCount(7);
        p.getInventory().setItem(12, st);
        p.getInventory().setItem(40, SigilItem.create(new SigilData(SigilType.SWIFT, Rarity.UNCOMMON)));
        p.getInventory().setItem(3, CharmItem.create(CharmData.roll(Category.EXPLORATION, Rarity.COMMON, RandomSource.create(1))));
        Drops.absorbInventory(p); // a FakePlayer never ticks: call the once a second sweep directly
        h.assertValueEqual(ProgressionApi.sigilCount(p, SigilType.SWIFT, Rarity.UNCOMMON), 8, "stacks merged into one count");
        h.assertValueEqual(count(p, x -> SigilItem.dataOf(x) != null), 0, "no sigil items left");
        h.assertValueEqual(count(p, x -> CharmItem.dataOf(x) != null), 0, "old charm items convert too");
        h.assertValueEqual(Effects.data(p).bag().size(), 1, "into the book's bag");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void combineSigilsTwoIntoOneOfTheNextRarity(GameTestHelper h) {
        ServerPlayer p = player(h);
        SigilData common = store(p, SigilType.POTENT, Rarity.COMMON, 3);
        h.assertTrue(Actions.combineSigils(p, common), "combined");
        h.assertValueEqual(Effects.data(p).stored(common), 1, "two spent");
        h.assertValueEqual(ProgressionApi.sigilCount(p, SigilType.POTENT, Rarity.UNCOMMON), 1, "one of the next rarity, same type");
        h.assertFalse(Actions.combineSigils(p, common), "one left is not enough");
        h.assertValueEqual(Effects.data(p).stored(common), 1, "nothing spent on a refusal");
        SigilData leg = store(p, SigilType.REACH, Rarity.LEGENDARY, 2);
        h.assertTrue(Actions.combineSigils(p, leg), "two Legendary combine");
        h.assertValueEqual(ProgressionApi.sigilCount(p, SigilType.REACH, Rarity.MYTHICAL), 1, "into one Mythical");
        SigilData top = store(p, SigilType.LUCKY, Rarity.MYTHICAL, 4);
        h.assertFalse(Actions.combineSigils(p, top), "the top rarity never combines");
        h.assertValueEqual(Effects.data(p).stored(top), 4, "top kept");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void salvageCharmGivesOneSigilOfItsRarity(GameTestHelper h) {
        ServerPlayer p = player(h);
        RandomSource r = RandomSource.create(5);
        bag(p, CharmData.roll(Category.MINING, Rarity.EPIC, r));
        h.assertFalse(Actions.salvageCharm(p, 3), "index out of range");
        h.assertTrue(Actions.salvageCharm(p, 0), "salvaged");
        h.assertTrue(Effects.data(p).bag().isEmpty(), "charm gone");
        int epic = 0;
        for (SigilType t : SigilType.values()) epic += Effects.data(p).stored(new SigilData(t, Rarity.EPIC));
        h.assertValueEqual(epic, 1, "one Epic sigil");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void offSpecCharmsConvertToThePicks(GameTestHelper h) {
        ServerPlayer p = player(h);
        RandomSource r = RandomSource.create(6);
        bag(p, CharmData.roll(Category.BUILDING, Rarity.RARE, r));
        bag(p, CharmData.roll(Category.MINING, Rarity.COMMON, r));
        p.setData(com.habits.core.CoreRegistries.SPECS, new com.habits.core.Specs(List.of(Category.WARRIOR, Category.MINING), false));
        ProgressionApi.specsChanged(p);
        List<CharmData> bag = Effects.data(p).bag();
        h.assertTrue(bag.stream().allMatch(c -> c.category() == Category.WARRIOR || c.category() == Category.MINING), "all in the picks");
        h.assertValueEqual(bag.stream().map(CharmData::rarity).sorted().toList(), List.of(Rarity.COMMON, Rarity.RARE), "rarities kept");
        Drops.grantCharm(p, CharmData.roll(Category.NATURE, Rarity.EPIC, r));
        h.assertTrue(Effects.data(p).bag().get(0).category() != Category.NATURE, "a new off-spec charm arrives converted");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void bossCharmGoesStraightIntoTheBook(GameTestHelper h) {
        ServerPlayer p = player(h);
        Drops.TEST_PLAYERS.add(p.getUUID());
        try {
            var boss = h.spawnWithNoFreeWill(net.minecraft.world.entity.EntityType.ZOMBIE, 1, 2, 1);
            boss.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(ProgressionInit.BOSS_HEALTH.get() + 10);
            boss.hurt(p.damageSources().playerAttack(p), Float.MAX_VALUE);
            h.assertTrue(boss.isDeadOrDying(), "boss down");
            h.assertValueEqual(Effects.data(p).bag().size(), 1, "boss charm in the bag");
            h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, boss.getBoundingBox().inflate(4),
                    e -> CharmItem.dataOf(e.getItem()) != null).isEmpty(), "no charm on the ground");
            h.assertValueEqual(count(p, x -> CharmItem.dataOf(x) != null), 0, "none in the inventory");
        } finally {
            Drops.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void oldCharmItemConvertsOnPickup(GameTestHelper h) {
        ServerPlayer p = player(h);
        Drops.TEST_PLAYERS.add(p.getUUID());
        try {
            CharmData c = CharmData.roll(Category.FARMING, Rarity.RARE, RandomSource.create(6));
            ItemEntity e = new ItemEntity(h.getLevel(), p.getX(), p.getY(), p.getZ(), CharmItem.create(c));
            e.setNoPickUpDelay();
            h.getLevel().addFreshEntity(e);
            e.playerTouch(p);
            h.assertValueEqual(Effects.data(p).bag(), List.of(c), "the same charm, now in the bag");
            h.assertTrue(e.isRemoved(), "the item entity is gone");
            h.assertValueEqual(count(p, x -> CharmItem.dataOf(x) != null), 0, "none in the inventory");
        } finally {
            Drops.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }
}
