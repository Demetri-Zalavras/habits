package com.habits.perk;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Entry point of package perk. Owner: CORE (stat PerkTypes in {@link StatEvents}, {@link StatTicks}, {@link StatHooks} and
 * the mixins in {@code perk.mixin}; the condition grammar in {@link Conditions}). Called once from Habits. */
public final class PerkInit {
    private PerkInit() {}

    /** Ceilings for stacked stat percents (Potent and charms can push a sum to 100, which would mean immunity). */
    public static ModConfigSpec.DoubleValue RESIST_CAP, HUNGER_SAVE_CAP, BOSS_HIT_CAP;

    public static void init(IEventBus modBus) {
        StatEvents.init();
        StatTicks.init();
        // domain mechanics, one subpackage each (owners in CONTRACT.md); stat PerkTypes are implemented in this package
        com.habits.perk.combat.CombatMechanics.init(modBus);
        com.habits.perk.ranged.RangedMechanics.init(modBus);
        com.habits.perk.mining.MiningMechanics.init(modBus);
        com.habits.perk.farming.FarmingMechanics.init(modBus);
        com.habits.perk.movement.MovementMechanics.init(modBus);
        com.habits.perk.survival.SurvivalMechanics.init(modBus);
        com.habits.perk.utility.UtilityMechanics.init(modBus);
        com.habits.perk.crafting.CraftingMechanics.init(modBus);
        com.habits.perk.nature.NatureMechanics.init(modBus);
        com.habits.perk.exploration.ExplorationMechanics.init(modBus);
        com.habits.perk.building.BuildingMechanics.init(modBus);
    }

    /** Define this package's config values (already inside its own section). Store them in your own static fields. */
    public static void defineConfig(ModConfigSpec.Builder b) {
        RESIST_CAP = b.comment("Most damage that damage_type_resist perks can cut together, in percent")
                .defineInRange("resist_cap", 95.0, 0, 100);
        HUNGER_SAVE_CAP = b.comment("Most hunger that hunger_save perks can save together, in percent")
                .defineInRange("hunger_save_cap", 90.0, 0, 100);
        BOSS_HIT_CAP = b.comment("Most damage one hit from a player can deal to a boss (#c:bosses), in percent of its max health, after every",
                        "perk and other mod bonus, before armor. 0 = no cap. Packs that stack Habits with other power mods set it (StarSword: 34)")
                .defineInRange("boss_hit_cap", 0.0, 0, 100);
        b.push("building"); com.habits.perk.building.BuildingMechanics.defineConfig(b); b.pop();
        b.push("crafting"); com.habits.perk.crafting.CraftCfg.defineConfig(b); b.pop();
        b.push("farming"); com.habits.perk.farming.FarmCfg.defineConfig(b); b.pop();
    }
}
