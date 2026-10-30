package com.habits.perk.farming;

import net.neoforged.neoforge.common.NeoForge;

import static com.habits.perk.farming.FarmingMechanics.reg;

/**
 * The farming spec batch (2026-10-13): mechanic ids {@code habits:farming/<skill>}, one per skill in
 * {@code habits_skills/farming/}. docs/mechanics/farming-nature.md ("Spec batch") lists values, params and events.
 */
final class FarmSpec {
    private FarmSpec() {}

    static void init() {
        String f = "farming/";
        // fields: harvest
        reg(f + "pick_and_plant", "bonus crop chance %");
        reg(f + "gilded_harvest", "extra crops per golden hoe harvest");
        reg(f + "harvest_feast", "chance % a ripe crop feeds you 1 hunger");
        reg(f + "green_wisdom", "experience chance % per ripe crop");
        reg(f + "giant_crop", "giant yield chance %");
        reg(f + "in_the_zone", "speed and haste seconds after a streak");
        reg(f + "harvest_festival", "festival seconds after a streak");
        reg(f + "harvest_king", "ripen-all chance %");
        reg(f + "scythe", "row length in blocks");
        reg(f + "thousand_hands", "max crops harvested from the patch");
        // soil, water, planting
        reg(f + "berry_picker", "berry picking radius in blocks");
        reg(f + "quick_stalk", "full height chance %");
        reg(f + "head_start", "head start chance %");
        reg(f + "florist", "extra flowers per bone meal");
        reg(f + "bone_dry", "bone meal kept chance %");
        reg(f + "hot_compost", "extra layer chance %");
        reg(f + "black_gold", "extra bone meal per full composter");
        reg(f + "dirt_finds", "find chance % per till");
        reg(f + "wonder_soil", "rich farmland chance %");
        reg(f + "watering_can", "watering radius in blocks");
        reg(f + "sprinkler", "sprinkler radius in blocks");
        reg(f + "fertile_ground", "failed growth rolls that grow %");
        reg(f + "bumper_vine", "extra fruit chance %");
        reg(f + "planters_stride", "planting strip width in blocks", Soil::strideTick);
        reg(f + "seed_storm", "seeds sown per throw");
        reg(f + "rain_maker", "cooldown in minutes");
        reg(f + "pumpkin_carver", "jack o'lantern chance %");
        reg(f + "hay_diver", "safe fall height in blocks");
        reg(f + "crop_guard", "no-trample radius in blocks");
        reg(f + "pest_patrol", "damage per second", Soil::pestTick);
        reg(f + "pumpkin_king", "radius in blocks", Soil::pumpkinTick);
        reg(f + "combine", "harvest width in blocks", Soil::combineTick);
        // livestock
        reg(f + "woolly", "extra wool per shear");
        reg(f + "wool_dyer", "new color chance %");
        reg(f + "golden_fleece", "gold chance % per shear");
        reg(f + "dairy_farmer", "second bucket chance %");
        reg(f + "plucker", "seconds between plucks per chicken");
        reg(f + "brush_up", "extra scutes per brush");
        reg(f + "animal_doctor", "hearts healed per feeding");
        reg(f + "feed_bag", "feeding radius in blocks");
        reg(f + "hatchery", "extra hatch chance %");
        reg(f + "egg_bomb", "egg damage");
        reg(f + "double_yolk", "double egg chance %");
        reg(f + "golden_goose", "gold chance % per laid egg");
        reg(f + "quick_breed", "breeding cooldown cut %");
        reg(f + "prize_livestock", "prize baby chance %");
        reg(f + "lucky_litter", "rabbit's foot chance %");
        reg(f + "noahs_ark", "extra baby chance %");
        reg(f + "herd_immunity", "damage cut % near your herd");
        reg(f + "flock_guard", "damage cut % for farm animals");
        reg(f + "truffle_pig", "seconds between digs", Ranch::truffleTick);
        reg(f + "pig_rider", "pig speed bonus %", Ranch::pigTick);
        // bees
        reg(f + "comb_cutter", "extra honeycomb per shear");
        reg(f + "honey_bottler", "extra bottles per harvest");
        reg(f + "smoke_and_honey", "honey levels kept");
        reg(f + "honey_flow", "seconds per honey level", Apiary::honeyFlowTick);
        reg(f + "pollinator", "grow chance % per bee every 2 seconds", Apiary::pollinateTick);
        reg(f + "bee_mover", "keep bees chance %");
        // kitchen
        reg(f + "leftovers", "food kept chance %");
        reg(f + "melon_medic", "health per melon slice");
        reg(f + "hearty_meal", "absorption hearts");
        reg(f + "glow_snack", "night vision seconds");
        reg(f + "fresh_milk", "regeneration seconds");
        reg(f + "cocoa_rush", "haste seconds");
        reg(f + "royal_jelly", "buff seconds");
        reg(f + "cornucopia", "cooldown in minutes");
        reg(f + "chefs_special", "double craft chance %");
        // farming classes (habits_skills/class/farming)
        reg(f + "hearth_meal", "regeneration and strength seconds per meal");
        reg(f + "verdant_crown", "double crop chance %, crops replant grown");

        for (Class<?> c : new Class<?>[]{Fields.class, Soil.class, Ranch.class, Apiary.class, Kitchen.class}) NeoForge.EVENT_BUS.register(c);
    }
}
