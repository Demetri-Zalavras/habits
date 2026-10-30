package com.habits.danger;

import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/** Entry point of package danger (mob scaling by distance and dimension, elites). Owner: DANGER. Called once from Habits. */
public final class DangerInit {
    private DangerInit() {}

    /** Max health ceiling; vanilla's 1024 would clip scaled bosses (End Dragon 200 x 8). Runs on both sides at mod init. */
    static final double HEALTH_CAP = 1_000_000;

    public static void init(IEventBus modBus) {
        RangedAttribute maxHealth = (RangedAttribute) Attributes.MAX_HEALTH.value();
        maxHealth.maxValue = Math.max(HEALTH_CAP, maxHealth.getMaxValue());
        Scaling.init(NeoForge.EVENT_BUS);
        Elites.init(NeoForge.EVENT_BUS);
        ZoneWatch.init(NeoForge.EVENT_BUS);
    }

    // ------------------------------------------------------------------ config ("danger" section)

    public static ModConfigSpec.DoubleValue SAFE_RADIUS, RAMP_END, MAX_HEALTH, MAX_DAMAGE, ELITE_CHANCE, ELITE_HEALTH,
            SHOCK_DAMAGE, SHOCK_RADIUS, SHIELD_SECONDS, THORNS_SHARE, PARTICLE_RANGE, DRUM_VOLUME, DRUM_PITCH, SHIELD_VOLUME;
    public static ModConfigSpec.IntValue FIRE_SECONDS, FROZEN_TICKS, FROZEN_LEVEL, PARTICLE_INTERVAL, PARTICLE_COUNT, CHECK_TICKS;
    public static ModConfigSpec.ConfigValue<List<? extends Number>> TIER_STARTS, MAX_AFFIXES, CHARM_CHANCE;
    public static ModConfigSpec.ConfigValue<List<? extends String>> DIMENSIONS, EXCLUDE, LOOT_WEIGHTS;

    private static ModConfigSpec.ConfigValue<List<? extends Number>> nums(ModConfigSpec.Builder b, String key, String comment, List<? extends Number> def) {
        return b.comment(comment).defineList(key, def, () -> 0, o -> o instanceof Number n && n.doubleValue() >= 0);
    }

    private static ModConfigSpec.ConfigValue<List<? extends String>> strs(ModConfigSpec.Builder b, String key, String comment, List<String> def) {
        return b.comment(comment).defineListAllowEmpty(key, def, () -> "", o -> o instanceof String);
    }

    public static void defineConfig(ModConfigSpec.Builder b) {
        String perTier = " One entry per zone: Safe, Frontier I, II, III, then each dimension in dimensionMultipliers in order (the last entry repeats).";
        SAFE_RADIUS = b.comment("Blocks from world spawn (flat distance) where mobs are still normal.").defineInRange("safeRadius", 500.0, 0.0, 1e7);
        RAMP_END = b.comment("Blocks from world spawn where mobs reach full strength.").defineInRange("fullStrengthDistance", 5000.0, 0.0, 1e7);
        MAX_HEALTH = b.comment("Mob health multiplier at full strength, overworld and other dimensions not listed below.").defineInRange("maxHealth", 3.0, 1.0, 1000.0);
        MAX_DAMAGE = b.comment("Mob damage multiplier at full strength.").defineInRange("maxDamage", 2.0, 1.0, 1000.0);
        TIER_STARTS = nums(b, "frontierStarts", "Distance where each Frontier tier starts (I, II, III), for the action bar line and elite tiers.", List.of(500, 2000, 3500));
        DIMENSIONS = strs(b, "dimensionMultipliers", "Flat multipliers per dimension, ignoring distance: dimension_id=health,damage. Order them from least to most dangerous.",
                List.of("minecraft:the_nether=5,3", "minecraft:the_end=8,4"));
        EXCLUDE = strs(b, "exclude", "Entity ids or #tags that are never scaled or made elite, e.g. minecraft:ender_dragon.", List.of());

        ELITE_CHANCE = b.comment("Chance (0..1) a hostile, non-boss mob spawns as an elite.").defineInRange("eliteChance", 0.05, 0.0, 1.0);
        ELITE_HEALTH = b.comment("Extra health multiplier for elites, on top of zone scaling.").defineInRange("eliteHealth", 1.5, 1.0, 100.0);
        MAX_AFFIXES = nums(b, "eliteMaxAffixes", "Most affixes an elite can roll (1 to 5)." + perTier, List.of(1, 2, 2, 2, 2, 3));
        FIRE_SECONDS = b.comment("Fiery: seconds a hit sets the target on fire.").defineInRange("fierySeconds", 4, 0, 60);
        FROZEN_TICKS = b.comment("Frozen: ticks of slowness per hit.").defineInRange("frozenTicks", 60, 0, 1200);
        FROZEN_LEVEL = b.comment("Frozen: slowness level (1 = Slowness I).").defineInRange("frozenLevel", 2, 1, 10);
        SHOCK_DAMAGE = b.comment("Shocking: zap damage to the target and one nearby creature.").defineInRange("shockDamage", 3.0, 0.0, 1000.0);
        SHOCK_RADIUS = b.comment("Shocking: blocks the zap can jump from the target.").defineInRange("shockRadius", 5.0, 0.0, 32.0);
        SHIELD_SECONDS = b.comment("Shielded: seconds between blocked hits.").defineInRange("shieldSeconds", 5.0, 0.0, 600.0);
        SHIELD_VOLUME = b.comment("Shielded: volume of the block sound.").defineInRange("shieldVolume", 1.0, 0.0, 2.0);
        THORNS_SHARE = b.comment("Thorny: share (0..1) of melee damage sent back to the attacker.").defineInRange("thornsShare", 0.3, 0.0, 10.0);
        PARTICLE_INTERVAL = b.comment("Ticks between elite affix particle puffs.").defineInRange("particleInterval", 10, 1, 200);
        PARTICLE_COUNT = b.comment("Particles per affix per puff (and per zap step).").defineInRange("particleCount", 3, 0, 50);
        PARTICLE_RANGE = b.comment("Elite particles only show when a player is this close.").defineInRange("particleRange", 32.0, 1.0, 256.0);

        CHARM_CHANCE = nums(b, "eliteCharmChance", "Chance (0..1) an elite also drops a charm." + perTier, List.of(0.1, 0.15, 0.2, 0.25, 0.3, 0.4));
        // every entry needs a weight per rarity: entries from before Mythical (5 weights) reset the list to the defaults
        LOOT_WEIGHTS = b.comment("Rarity weights (Common,Uncommon,Rare,Epic,Legendary,Mythical) of elite sigils and charms." + perTier).define(
                List.of("eliteLootWeights"), () -> List.of("40,30,18,9,3,0", "32,32,22,11,3,0", "26,32,25,13,4,0", "20,30,28,16,6,0.5", "14,28,30,20,8,0.5", "6,20,34,28,12,0.5"),
                o -> o instanceof List<?> l && l.stream().allMatch(e -> e instanceof String str && str.split(",").length >= com.habits.core.Rarity.values().length));

        CHECK_TICKS = b.comment("Ticks between checks of which zone each player is in.").defineInRange("zoneCheckTicks", 20, 1, 1200);
        DRUM_VOLUME = b.comment("Volume of the drum when danger rises.").defineInRange("drumVolume", 1.0, 0.0, 2.0);
        DRUM_PITCH = b.comment("Pitch of the drum when danger rises.").defineInRange("drumPitch", 0.5, 0.5, 2.0);
    }
}
