package com.habits.progression;

import com.habits.core.Category;
import com.habits.core.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/** Entry point of package progression (sigils and charms). Owner: PROGRESSION. Called once from Habits. */
public final class ProgressionInit {
    private ProgressionInit() {}

    public static void init(IEventBus modBus) {
        ProgRegistries.register(modBus);
        modBus.addListener(ProgPayloads::register);
        modBus.addListener(ProgressionInit::creativeTab);
        Drops.init(NeoForge.EVENT_BUS);
        Effects.init(NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, ProgPayloads.Limits.local());
        });
    }

    private static void creativeTab(net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent e) {
        if (e.getTab() != com.habits.core.CoreRegistries.TAB.get()) return;
        for (SigilType t : SigilType.values())
            for (Rarity r : Rarity.values()) e.accept(SigilItem.create(new SigilData(t, r)));
        // charms live in the book now; the item stays registered only so old stacks load (and convert on pickup)
    }

    // ------------------------------------------------------------------ config ("progression" section)

    /** Per-rarity lists, Common..Legendary. */
    public static ModConfigSpec.ConfigValue<List<? extends Number>> POTENT, SWIFT, REACH, LUCKY, SOCKET_LEVELS,
            SIGIL_WEIGHTS, LOOT_WEIGHTS, CHARM_LINES, CHARM_PERK_MIN, CHARM_PERK_MAX, CHARM_DROP_MIN, CHARM_DROP_MAX,
            CHARM_STAT_MIN, CHARM_STAT_MAX, STAT_SCALE;
    public static ModConfigSpec.DoubleValue SWIFT_CAP, MISS_CHANCE, MOB_SIGIL_CHANCE, MOB_CHARM_CHANCE, CHEST_SIGIL_CHANCE,
            CHEST_CHARM_CHANCE, BOSS_HEALTH, BOSS_LEGENDARY_CHANCE, BOSS_MYTHICAL_CHANCE, CHIME_PITCH, CHIME_PITCH_STEP, CHIME_VOLUME;
    public static ModConfigSpec.IntValue MISS_GAP_SECONDS, MISS_IDLE_SECONDS, CHARM_BASE_SLOTS, CHARM_SKILLS_PER_SLOT, CHARM_MAX_SLOTS, SIGIL_COMBINE_COUNT;

    private static ModConfigSpec.ConfigValue<List<? extends Number>> list(ModConfigSpec.Builder b, String key, String comment, List<? extends Number> def) {
        return b.comment(comment).defineList(key, def, () -> 0, o -> o instanceof Number n && n.doubleValue() >= 0);
    }

    /** A per-rarity list: one number per rarity at least. A list from before Mythical (5 numbers) fails the size check and
     * is reset to the defaults, so Mythical never silently copies Legendary. */
    private static ModConfigSpec.ConfigValue<List<? extends Number>> rarityList(ModConfigSpec.Builder b, String key, String comment, List<? extends Number> def) {
        // the list check (not defineList's size range, which only the config screen reads) is what makes the spec reset it
        return b.comment(comment).define(List.of(key), () -> def, o -> o instanceof List<?> l && l.size() >= Rarity.values().length
                && l.stream().allMatch(e -> e instanceof Number n && n.doubleValue() >= 0));
    }

    public static void defineConfig(ModConfigSpec.Builder b) {
        String perRarity = " One number per rarity: Common, Uncommon, Rare, Epic, Legendary, Mythical.";
        POTENT = rarityList(b, "potentPercent", "Potent sigil: perk strength +% on its card." + perRarity, List.of(10, 20, 35, 50, 75, 150));
        SWIFT = rarityList(b, "swiftPercent", "Swift sigil: cooldowns -% on its card." + perRarity, List.of(5, 10, 15, 22, 30, 60));
        SWIFT_CAP = b.comment("Most a card's cooldowns can shrink from Swift sigils, in percent.")
                .defineInRange("swiftCapPercent", 75.0, 0.0, 95.0);
        REACH = rarityList(b, "reachPercent", "Reach sigil: radius and range +% on its card." + perRarity, List.of(10, 20, 35, 50, 75, 150));
        LUCKY = rarityList(b, "luckyPercent", "Lucky sigil: that card drops +% more often." + perRarity, List.of(10, 20, 35, 50, 75, 150));
        SOCKET_LEVELS = list(b, "socketLevels", "Card level that opens each socket (at most 4).", List.of(1, 4, 7, 10));

        MISS_CHANCE = b.comment("Chance (0..1) of a sigil each time an action rolls for cards and wins none.")
                .defineInRange("sigilOnMissChance", 0.003, 0.0, 1.0);
        MISS_GAP_SECONDS = b.comment("Seconds after a sigil from actions before the next one can drop (with the chance, about one every 3 minutes of active play).")
                .defineInRange("sigilOnMissMinSeconds", 90, 0, 3600);
        MISS_IDLE_SECONDS = b.comment("No sigils from actions after this many seconds without moving, looking or clicking (stops AFK sigil farming).")
                .defineInRange("sigilOnMissIdleSeconds", 30, 1, 3600);
        SIGIL_WEIGHTS = rarityList(b, "sigilRarityWeights", "Rarity weights of sigils from actions." + perRarity, List.of(60, 25, 10, 4, 1, 0));
        LOOT_WEIGHTS = rarityList(b, "lootRarityWeights", "Rarity weights of sigils and charms from mobs and chests." + perRarity, List.of(40, 30, 18, 9, 3, 0));
        MOB_SIGIL_CHANCE = b.comment("Chance (0..1) a hostile mob killed by a player drops a sigil.")
                .defineInRange("mobSigilChance", 0.03, 0.0, 1.0);
        MOB_CHARM_CHANCE = b.comment("Chance (0..1) a hostile mob killed by a player drops a charm.")
                .defineInRange("mobCharmChance", 0.008, 0.0, 1.0);
        CHEST_SIGIL_CHANCE = b.comment("Chance (0..1) a loot chest holds a sigil.")
                .defineInRange("chestSigilChance", 0.35, 0.0, 1.0);
        CHEST_CHARM_CHANCE = b.comment("Chance (0..1) a loot chest holds a charm.")
                .defineInRange("chestCharmChance", 0.12, 0.0, 1.0);
        BOSS_HEALTH = b.comment("Mobs with at least this much max health count as bosses (as do mobs in the c:bosses tag).")
                .defineInRange("bossMinHealth", 150.0, 1.0, 100000.0);
        BOSS_LEGENDARY_CHANCE = b.comment("Chance (0..1) a boss charm is Legendary instead of Epic.")
                .defineInRange("bossLegendaryChance", 0.3, 0.0, 1.0);
        BOSS_MYTHICAL_CHANCE = b.comment("Chance (0..1) a boss charm is Mythical (checked before Legendary).")
                .defineInRange("bossMythicalChance", 0.01, 0.0, 1.0);
        CHIME_PITCH = b.comment("Pitch of the sigil chime for a Common sigil.").defineInRange("chimePitch", 0.8, 0.5, 2.0);
        CHIME_PITCH_STEP = b.comment("Pitch added per rarity step.").defineInRange("chimePitchStep", 0.2, 0.0, 0.5);
        CHIME_VOLUME = b.comment("Volume of the sigil chime.").defineInRange("chimeVolume", 0.8, 0.0, 2.0);

        CHARM_BASE_SLOTS = b.comment("Charm slots everyone starts with.").defineInRange("charmBaseSlots", 1, 0, 5);
        CHARM_SKILLS_PER_SLOT = b.comment("One more charm slot per this many owned skills.").defineInRange("charmSkillsPerSlot", 25, 1, 1000);
        CHARM_MAX_SLOTS = b.comment("Most charm slots a player can have.").defineInRange("charmMaxSlots", 5, 0, 5);
        SIGIL_COMBINE_COUNT = b.comment("Sigils of one kind that combine into one of the next rarity.").defineInRange("sigilCombineCount", 2, 2, 64);
        CHARM_LINES = rarityList(b, "charmLines", "Lines a charm rolls (1 to 5)." + perRarity, List.of(1, 2, 2, 3, 4, 5));
        CHARM_PERK_MIN = rarityList(b, "charmPerkMin", "Lowest 'category perks +%' roll." + perRarity, List.of(3, 5, 8, 12, 20, 32));
        CHARM_PERK_MAX = rarityList(b, "charmPerkMax", "Highest 'category perks +%' roll." + perRarity, List.of(6, 10, 14, 20, 30, 45));
        CHARM_DROP_MIN = rarityList(b, "charmDropMin", "Lowest 'category card drops +%' roll." + perRarity, List.of(3, 5, 8, 12, 18, 28));
        CHARM_DROP_MAX = rarityList(b, "charmDropMax", "Highest 'category card drops +%' roll." + perRarity, List.of(6, 10, 14, 18, 25, 38));
        CHARM_STAT_MIN = rarityList(b, "charmStatMin", "Lowest stat line roll in points (times the category's stat scale)." + perRarity, List.of(2, 3, 5, 8, 12, 18));
        CHARM_STAT_MAX = rarityList(b, "charmStatMax", "Highest stat line roll in points." + perRarity, List.of(4, 6, 9, 12, 18, 26));
        // new key: the category order changed with specializations, so old per-position values would land on the wrong stat
        STAT_SCALE = list(b, "charmStatScaleBySpec", "Stat per point, one per category: Warrior (attack damage, x base), Ranger (speed, x base), "
                + "Guardian (max health), Mining (break speed, x base), Farming (luck), Crafting (block reach), Nature (luck), "
                + "Exploration (movement efficiency), Building (block reach), Movement (speed, x base).",
                List.of(0.01, 0.005, 0.25, 0.01, 0.1, 0.05, 0.1, 0.02, 0.05, 0.005));
    }

    /** A per-rarity config number (the last entry repeats when the list is short; 0 when empty). */
    public static double at(ModConfigSpec.ConfigValue<List<? extends Number>> v, Rarity r) {
        return at(v, r.ordinal());
    }

    static double at(ModConfigSpec.ConfigValue<List<? extends Number>> v, int i) {
        List<? extends Number> l = v.get();
        return l.isEmpty() ? 0 : l.get(Math.min(i, l.size() - 1)).doubleValue();
    }

    public static double sigilPercent(SigilType t, Rarity r) {
        return at(switch (t) { case POTENT -> POTENT; case SWIFT -> SWIFT; case REACH -> REACH; case LUCKY -> LUCKY; }, r);
    }

    static double statScale(Category c) {
        return at(STAT_SCALE, c.ordinal());
    }
}
