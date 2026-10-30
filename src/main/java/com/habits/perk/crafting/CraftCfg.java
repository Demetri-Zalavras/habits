package com.habits.perk.crafting;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/** Config of the Overgeared-style crafting systems (perks.crafting): gear ratings, focus, sets, potions, enhancement. */
public final class CraftCfg {
    private CraftCfg() {}

    static ModConfigSpec.DoubleValue FULL_LEVELS, FOCUS_STEP, SET2, SET4, SET4_DEALT, POTION_DURATION, POTION_BONUS_SECONDS,
            ENHANCE_PCT, ENHANCE_CRAFTER_BONUS, ENHANCE_BREAK_CHANCE;
    static ModConfigSpec.IntValue MAX_FOCUS, ENHANCE_SAFE, ENHANCE_BREAK_FROM, ENHANCE_XP_BASE, ENHANCE_XP_PER;
    static ModConfigSpec.ConfigValue<List<? extends Double>> ENHANCE_CHANCES;

    public static void defineConfig(ModConfigSpec.Builder b) {
        FULL_LEVELS = b.comment("Total skill levels in the category where rating odds stop improving")
                .defineInRange("rating_full_levels", 700.0, 1, 100000);
        FOCUS_STEP = b.comment("Blacksmith's Focus: rating odds bonus per same-item craft in a row, in percent")
                .defineInRange("focus_step", 5.0, 0, 100);
        MAX_FOCUS = b.comment("Blacksmith's Focus: most crafts in a row that count").defineInRange("focus_max", 10, 0, 100);
        SET2 = b.comment("Forged set, 2 pieces: less damage taken, in percent").defineInRange("set2_taken", 8.0, 0, 90);
        SET4 = b.comment("Forged set, 4 pieces: less damage taken, in percent").defineInRange("set4_taken", 15.0, 0, 90);
        SET4_DEALT = b.comment("Forged set, 4 pieces: more melee damage, in percent").defineInRange("set4_dealt", 10.0, 0, 500);
        POTION_DURATION = b.comment("Rated potions: longer effects per tier above Normal, in percent")
                .defineInRange("potion_duration_per_tier", 25.0, 0, 1000);
        POTION_BONUS_SECONDS = b.comment("Rated potions: seconds of each Legendary/Myth bonus effect (doubled for Myth)")
                .defineInRange("potion_bonus_seconds", 60.0, 1, 3600);
        ENHANCE_CHANCES = b.comment("Enhancement success chance in percent for +1 .. +10")
                .defineList("enhance_chances", List.of(100.0, 100.0, 100.0, 90.0, 80.0, 65.0, 50.0, 35.0, 25.0, 15.0),
                        () -> 50.0, o -> o instanceof Double d && d >= 0 && d <= 100);
        ENHANCE_PCT = b.comment("Enhancement: damage/armor added per level, in percent of the item's base")
                .defineInRange("enhance_per_level", 3.0, 0, 100);
        ENHANCE_CRAFTER_BONUS = b.comment("Enhancement: success chance added for players who picked Crafting, in percent points")
                .defineInRange("enhance_crafter_bonus", 10.0, 0, 100);
        ENHANCE_SAFE = b.comment("Enhancement: failing up to this level loses only the stone and XP")
                .defineInRange("enhance_safe_up_to", 5, 0, 10);
        ENHANCE_BREAK_FROM = b.comment("Enhancement: failing at this level or higher can destroy the item")
                .defineInRange("enhance_break_from", 8, 1, 11);
        ENHANCE_BREAK_CHANCE = b.comment("Enhancement: chance a failure at the break levels destroys the item, in percent (else it drops a level)")
                .defineInRange("enhance_break_chance", 33.0, 0, 100);
        ENHANCE_XP_BASE = b.comment("Enhancement: XP level cost of +1").defineInRange("enhance_xp_base", 2, 0, 39);
        ENHANCE_XP_PER = b.comment("Enhancement: extra XP levels per current level").defineInRange("enhance_xp_per_level", 3, 0, 39);
    }
}
