package com.habits.core;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/**
 * Closed set of passive effects. The PERKS agent implements every one in {@code com.habits.perk}. JSON: lowercase name.
 * <p>Each line gives the value unit ({@link PerkSpec#valueAt}) and the {@code params} it reads. Filter params are
 * {@link IdMatch} strings ({@code "minecraft:stone"}, {@code "#minecraft:logs"}, comma-separated for several); a missing
 * filter means "everything". "%" values are percent points (25 = +25%). Values of several active skills add up.
 * <p><b>Every</b> perk type also accepts an optional {@code condition} param: comma-separated, all must hold. Grammar
 * (PERKS implements it once, for all types): {@code underground} (below y 50, no sky light), {@code in_water},
 * {@code in_lava}, {@code in_rain}, {@code sneaking}, {@code sprinting}, {@code swimming}, {@code gliding},
 * {@code riding}, {@code on_fire}, {@code sleeping}, {@code low_health} (at or below 30%), {@code full_health},
 * {@code night}, {@code day}, {@code dimension=<id>}, {@code biome=<IdMatch>}, {@code holding=<IdMatch>} (main hand).
 */
public enum PerkType implements StringRepresentable {
    // ---- numbers ----
    /** % faster mining. params: {@code block}. */
    MINING_SPEED,
    /** Any attribute modifier. value = amount. params: {@code attribute} (id, required), {@code operation}
     * ({@code add_value} default, {@code add_multiplied_base}, {@code add_multiplied_total}; multiplied ops use value/100). */
    ATTRIBUTE,
    /** % less damage taken (100 = immune). params: {@code damage} (damage type IdMatch, required; e.g. minecraft:fall,
     * #minecraft:is_fire). */
    DAMAGE_TYPE_RESIST,
    /** % more damage dealt. params: {@code damage} (damage type), {@code entity} (victim), {@code item} (weapon). */
    DAMAGE_BONUS,
    /** % chance the drops happen again. params: {@code block} or {@code entity} (one of them; none = both). */
    DROP_BONUS,
    /** % more experience from orbs. */
    XP_BONUS,
    /** % less hunger exhaustion. */
    HUNGER_SAVE,
    /** % more nutrition and saturation. params: {@code item}. */
    FOOD_BONUS,
    /** % chance a tool or armor use costs no durability. params: {@code item}. */
    DURABILITY_SAVE,
    /** % chance per second for each crop within {@code radius} (param, default 4) to grow one stage. */
    CROP_GROWTH_AURA,
    /** Extra Luck of the Sea levels while fishing. */
    FISHING_LUCK,
    /** Extra Lure levels while fishing. */
    FISHING_SPEED,
    /** Extra Looting levels on kills. */
    LOOTING_BONUS,
    /** % chance a furnace result taken out is doubled. params: {@code item}. */
    SMELT_BONUS,
    /** % chance breeding makes twins. params: {@code entity}. */
    BREED_TWINS,
    /** % added to taming chance. params: {@code entity}. */
    TAME_CHANCE,
    /** % off villager trade prices. */
    TRADE_DISCOUNT,
    /** % off enchanting table level costs. */
    ENCHANT_DISCOUNT,
    /** % off anvil level costs. */
    ANVIL_DISCOUNT,
    /** Health regained per minute (2 = one heart). */
    REGEN,
    /** % of melee damage dealt healed back. */
    LIFESTEAL,
    /** % more critical hit damage. */
    CRIT_DAMAGE,
    /** % of melee damage taken reflected to the attacker. */
    THORNS,
    /** % faster climbing on ladders, vines, scaffolding. */
    CLIMB_SPEED,

    // ---- utility / QoL ----
    /** Extra reach in blocks. params: {@code kind} = {@code block}, {@code entity} or {@code both} (default). */
    REACH,
    /** Item and xp orb pull radius in blocks. */
    MAGNET_PICKUP,
    /** % chance a harvested crop replants itself from its drops. params: {@code block}. */
    AUTO_REPLANT,
    /** A mob effect while {@code condition} holds. value = amplifier level (1 = level I, 0 = off). params: {@code effect}
     * (id, required), {@code condition} (see class javadoc; none = always). Refreshed often; no particles. */
    MOB_EFFECT_CONDITIONAL,
    /** Shows coordinates (and facing) on the HUD while value is above 0. Client-side. */
    SHOW_COORDS;

    public static final Codec<PerkType> CODEC = StringRepresentable.fromEnum(PerkType::values);

    /** Lang key of the default perk line, {@code perk.habits.<name>}, with one {@code %s} for the value. */
    public String translationKey() {
        return "perk.habits." + getSerializedName();
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
