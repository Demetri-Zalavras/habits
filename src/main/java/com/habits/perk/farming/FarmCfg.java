package com.habits.perk.farming;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Config of the Overgeared-style farming systems (perks.farming): Piaro's Way and rated dishes. */
public final class FarmCfg {
    private FarmCfg() {}

    static ModConfigSpec.IntValue PIARO_PER;
    static ModConfigSpec.DoubleValue PIARO_CAP, DISH_SATURATION, DISH_BUFF_SECONDS;

    public static void defineConfig(ModConfigSpec.Builder b) {
        PIARO_PER = b.comment("Piaro's Way: crops harvested per +1 hoe damage").defineInRange("piaro_per", 500, 1, 1000000);
        PIARO_CAP = b.comment("Piaro's Way: most bonus hoe damage").defineInRange("piaro_cap", 6.0, 0, 100);
        DISH_SATURATION = b.comment("Rated dishes: more saturation per tier above Normal, in percent (nutrition gets +1 per tier)")
                .defineInRange("dish_saturation_per_tier", 20.0, 0, 1000);
        DISH_BUFF_SECONDS = b.comment("Rated dishes: seconds of each Legendary/Myth buff (doubled for Myth)")
                .defineInRange("dish_buff_seconds", 60.0, 1, 3600);
    }
}
