package com.habits.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.habits.Habits;
import com.habits.trigger.Triggers;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Entry point of package core. Owner: CORE. Called once from Habits. */
public final class CoreInit {
    private CoreInit() {}

    public static ModConfigSpec.DoubleValue DROP_RATE;
    public static ModConfigSpec.IntValue CARD_LIFESPAN;
    public static ModConfigSpec.DoubleValue AUTO_ADD_VOLUME, RESPEC_BOSS_CHANCE, RESPEC_ELITE_CHANCE,
            CLASS_BOOK_CHEST_CHANCE, CLASS_BOOK_BOSS_CHANCE, CLASS_BOOK_LEVEL_CHANCE;
    public static ModConfigSpec.ConfigValue<java.util.List<? extends Number>> CLASS_BOOK_WEIGHTS;
    public static ModConfigSpec.BooleanValue COMBAT_SPECS;
    public static ModConfigSpec.ConfigValue<java.util.List<? extends Number>> MYTHICAL_CURVE, XP_RATE, QUEST_REWARD_XP;

    public static void init(IEventBus modBus) {
        CoreRegistries.register(modBus);
        PerkMechanics.register(HabitsApi.WILD_CARD, () -> "% chance an action's skill XP is doubled");
        SkillXp.init(NeoForge.EVENT_BUS);
        Titles.init(NeoForge.EVENT_BUS);
        modBus.addListener(Payloads::register);
        modBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Loading e) -> applyConfig(e.getConfig()));
        modBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Reloading e) -> applyConfig(e.getConfig()));
        NeoForge.EVENT_BUS.addListener(SkillRegistry::addReloadListener);
        NeoForge.EVENT_BUS.addListener(SkillRegistry::sync);
        NeoForge.EVENT_BUS.addListener(PerkMechanics::tick);
        NeoForge.EVENT_BUS.addListener(CoreInit::onLogin);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> LAST_SWING.remove(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> LAST_SWING.clear());
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> HabitsCommands.register(e.getDispatcher()));
    }

    /** Core config values (inside the "core" section). */
    public static void defineConfig(ModConfigSpec.Builder b) {
        DROP_RATE = b.comment("Multiplies all skill XP gained by doing things (2 = skills level twice as fast).")
                .defineInRange("xpMultiplier", 1.0, 0.0, 1000.0);
        // shortcut: assumes skill chances follow the catalog targets (one card per 10/16/30/60/120 min by rarity); retune here if they drift
        XP_RATE = b.comment("XP per action = the skill's chance x 100 x this, per rarity: Common, Uncommon, Rare, Epic, Legendary, Mythical.",
                        "The defaults max a skill (Lv10) in about 1, 1.75, 2.5, 3.5 and 5 hours of doing its action.")
                .defineList(java.util.List.of("xpRateByRarity"), () -> java.util.List.of(9.2, 8.4, 11.0, 15.7, 22.0, 10.0), () -> 1.0,
                        o -> o instanceof Number n && n.doubleValue() >= 0);
        QUEST_REWARD_XP = b.comment("XP a quest reward (/habits rewardcard) gives one of your skills, in cards' worth (100 XP each), per rarity:",
                        "Common, Uncommon, Rare, Epic, Legendary, Mythical.")
                .defineList(java.util.List.of("questRewardXp"), () -> java.util.List.of(2.0, 4.0, 8.0, 15.0, 30.0, 30.0), () -> 1.0,
                        o -> o instanceof Number n && n.doubleValue() >= 0);
        // vanilla items last 300s; the item entity caps lifespan just under Short.MAX_VALUE ticks
        CARD_LIFESPAN = b.comment("Seconds a dropped card stays on the ground before it despawns.")
                .defineInRange("cardDespawnSeconds", 600, 1, 1600);
        AUTO_ADD_VOLUME = b.comment("Volume of the add and level-up sounds when a won card adds itself (book toggle Auto-add drops).")
                .defineInRange("autoAddVolume", 0.35, 0.0, 1.0);
        MYTHICAL_CURVE = b.comment("Cards a Mythical skill needs for each next level: unlock, then Lv1 to Lv2 ... Lv9 to Lv10 (10 numbers, each 1 or more).",
                        "Other rarities need level + 1 cards (55 for Lv10). The default needs 15 in total.")
                .define(java.util.List.of("mythicalCardsPerLevel"), () -> Leveling.MYTHICAL_DEFAULT, Leveling::validCurve);
        defineSpecConfig(b);
    }

    /** Specialization config values (inside the "core" section). */
    static void defineSpecConfig(ModConfigSpec.Builder b) {
        RESPEC_BOSS_CHANCE = b.comment("Chance a boss drops a Scroll of Unlearning (pick specializations again; cards outside the new picks are lost).")
                .defineInRange("respecBossChance", 0.1, 0.0, 1.0);
        RESPEC_ELITE_CHANCE = b.comment("Chance a danger-zone elite drops a Scroll of Unlearning.")
                .defineInRange("respecEliteChance", 0.01, 0.0, 1.0);
        CLASS_BOOK_CHEST_CHANCE = b.comment("Chance a loot chest holds a class book.").defineInRange("classBookChestChance", 0.01, 0.0, 1.0);
        CLASS_BOOK_BOSS_CHANCE = b.comment("Chance a boss drops a class book.").defineInRange("classBookBossChance", 0.08, 0.0, 1.0);
        CLASS_BOOK_LEVEL_CHANCE = b.comment("Chance a skill level-up gives a class book.").defineInRange("classBookLevelUpChance", 0.002, 0.0, 1.0);
        CLASS_BOOK_WEIGHTS = b.comment("Relative weight of each class rarity when a class book is rolled: Common, Uncommon, Rare, Epic, Legendary.")
                .defineList(java.util.List.of("classBookWeights"), () -> java.util.List.of(0.0, 0.0, 75.0, 22.0, 3.0), () -> 1.0,
                        o -> o instanceof Number n && n.doubleValue() >= 0);
        b.push("specs");
        COMBAT_SPECS = b.comment("Offer the combat specializations (warrior, ranger, guardian). false: players pick two different professions, combat",
                        "cards and charms never drop or work, and a combat pick already made is dropped on login (StarSword: false)")
                .define("combat_specs", true);
        b.pop();
    }

    /** A per-rarity config number (the last entry repeats when the list is short; 0 when empty). */
    public static double at(ModConfigSpec.ConfigValue<java.util.List<? extends Number>> v, Rarity r) {
        var l = v.get();
        return l.isEmpty() ? 0 : l.get(Math.min(r.ordinal(), l.size() - 1)).doubleValue();
    }

    private static void applyConfig(net.neoforged.fml.config.ModConfig c) {
        if (c.getSpec() == com.habits.Config.SPEC) Leveling.setMythicalCurve(MYTHICAL_CURVE.get());
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer sp) {
            giveFirstBook(sp);
            SkillXp.resetOnce(sp);
            Specs.onLogin(sp);
        }
    }

    /** Gives the free book once per player. Returns true if it gave one. */
    static boolean giveFirstBook(ServerPlayer player) {
        if (player.getData(CoreRegistries.GOT_BOOK)) return false;
        player.setData(CoreRegistries.GOT_BOOK, true);
        ItemStack book = new ItemStack(CoreRegistries.BOOK.get());
        if (!player.getInventory().add(book)) player.drop(book, false);
        return true;
    }

    /** Game time of each player's last accepted air swing. */
    private static final Map<UUID, Long> LAST_SWING = new ConcurrentHashMap<>();
    public static final ResourceLocation SWING_AIR = Habits.id("swing_air");

    /** {@link Payloads.SwingAir}: alive, not spectating, and no faster than the held item's full attack cooldown
     * (the vanilla attack cadence). Returns true when it fired. */
    static boolean swingAir(ServerPlayer p) {
        if (!HabitsApi.canAct(p)) return false;
        long now = p.level().getGameTime();
        long cadence = Math.max(1, Math.round(p.getCurrentItemAttackStrengthDelay()));
        Long last = LAST_SWING.get(p.getUUID());
        if (last != null && now - last < cadence) return false;
        LAST_SWING.put(p.getUUID(), now);
        Triggers.fire(p, TriggerType.CUSTOM, TriggerContext.of(SWING_AIR));
        return true;
    }
}
