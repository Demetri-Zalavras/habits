package com.habits.core;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/**
 * Closed set of actions that can drop a card. JSON: lowercase name ({@code "break_block"}).
 * <p>Each line says what {@link TriggerContext#target()} is (its {@link TargetKind}) and what {@link TriggerContext#amount()}
 * means. Unless noted, amount is 1. Per-unit triggers ({@link #perUnit}) roll the skill's chance once per unit of amount
 * (core computes {@code 1 - (1 - chance)^amount}); the TRIGGERS agent batches them and fires at most once per second per
 * player with the accumulated amount. All others roll once per event.
 */
public enum TriggerType implements StringRepresentable {
    // ---- blocks ----
    /** Player broke a block. target = block. */
    BREAK_BLOCK(TargetKind.BLOCK, false),
    /** Player placed a block. target = block. */
    PLACE_BLOCK(TargetKind.BLOCK, false),
    /** Right-clicked a block with a result (workstations, bells, note blocks, jukebox, levers, doors, composter, cake,
     * signs...). target = block, item = held item (null when empty-handed). */
    INTERACT_BLOCK(TargetKind.BLOCK, false),
    /** Opened a container. target = the container block, or null for entity containers (chest boat, minecart). */
    OPEN_CONTAINER(TargetKind.BLOCK, false),
    /** Broke a fully grown crop (or picked berries). target = crop block. */
    HARVEST_CROP(TargetKind.BLOCK, false),
    /** Planted a seed, sapling, or other plant. target = the placed plant block. */
    PLANT(TargetKind.BLOCK, false),
    /** Hoed dirt or grass into farmland (also pathing and stripping logs). target = the block before the change. */
    TILL(TargetKind.BLOCK, false),
    /** Used bone meal successfully. target = block it was used on. */
    BONEMEAL(TargetKind.BLOCK, false),

    // ---- combat ----
    /** Killed an entity. target = entity type, item = weapon in main hand. */
    KILL_ENTITY(TargetKind.ENTITY, false),
    /** Hurt an entity. target = damage type (minecraft:player_attack, arrow, trident, mace_smash, thorns, magic...),
     * item = weapon (main hand, or the bow/crossbow/trident that fired), entity = victim type, amount = damage dealt. */
    DAMAGE_DEALT(TargetKind.DAMAGE_TYPE, false),
    /** Took damage. target = damage type, item = attacker's main-hand item, entity = attacker type (direct or source
     * entity, null for environment), amount = damage taken. */
    DAMAGE_TAKEN(TargetKind.DAMAGE_TYPE, false),
    /** Blocked a hit with a shield. target = damage type, entity = attacker type, amount = damage blocked. */
    SHIELD_BLOCK(TargetKind.DAMAGE_TYPE, false),
    /** Fired or threw a projectile (bow, crossbow, trident, snowball, egg, pearl). target = item used. */
    SHOOT_PROJECTILE(TargetKind.ITEM, false),
    /** Died. target = damage type of the killing blow, entity = killer type (null for environment). */
    DIE(TargetKind.DAMAGE_TYPE, false),
    /** Respawned after death (not on dimension change). */
    RESPAWN(TargetKind.NONE, false),

    // ---- items and food ----
    /** Finished eating. target = food item. */
    EAT(TargetKind.ITEM, false),
    /** Finished drinking a potion. target = potion contents id (water bottle = minecraft:water). */
    DRINK_POTION(TargetKind.POTION, false),
    /** Right-clicked with an item (flint and steel, spyglass, horn, pearl...). target = item. */
    USE_ITEM(TargetKind.ITEM, false),
    /** Filled a bucket (water, lava, powder snow, milk, fish, axolotl...). target = the filled bucket item. */
    BUCKET_FILL(TargetKind.ITEM, false),
    /** Emptied a bucket. target = the bucket item before emptying. */
    BUCKET_EMPTY(TargetKind.ITEM, false),
    /** A tool or armor piece broke. target = item. */
    ITEM_BREAK(TargetKind.ITEM, false),
    /** Picked up an item entity (not cards). target = item, amount = stack count. */
    PICKUP_ITEM(TargetKind.ITEM, false),
    /** Picked up experience orbs. amount = xp points. */
    XP_PICKUP(TargetKind.NONE, true),
    /** Reached a new XP level. amount = the new level. */
    LEVEL_UP_XP(TargetKind.NONE, false),

    // ---- crafting ----
    /** Took a crafting result. target = result item, amount = result count. */
    CRAFT(TargetKind.ITEM, false),
    /** Took a furnace/smoker/blast furnace result. target = result item, amount = count. */
    SMELT(TargetKind.ITEM, false),
    /** Took a brewed potion from a brewing stand. target = potion contents id, amount = count. */
    BREW(TargetKind.POTION, false),
    /** Enchanted at a table. target = first enchantment applied, amount = levels spent. */
    ENCHANT(TargetKind.ENCHANTMENT, false),
    /** Took an anvil result. target = result item, amount = xp levels spent. */
    ANVIL(TargetKind.ITEM, false),
    /** Traded with a villager or wandering trader. target = item received, amount = count. */
    TRADE(TargetKind.ITEM, false),

    // ---- nature ----
    /** Reeled in a catch. target = caught item. */
    FISH(TargetKind.ITEM, false),
    /** Bred two animals. target = baby entity type. */
    BREED(TargetKind.ENTITY, false),
    /** Tamed an animal. target = entity type. */
    TAME(TargetKind.ENTITY, false),
    /** Sheared an entity (sheep, mooshroom, snow golem, bogged). target = entity type. */
    SHEAR(TargetKind.ENTITY, false),
    /** Right-clicked an entity with a result (milk, shear, name tag, saddle, leash, feed, dye...). target = entity type,
     * item = held item (null when empty-handed). */
    INTERACT_ENTITY(TargetKind.ENTITY, false),

    // ---- movement (per unit: amount = blocks, or jumps) ----
    JUMP(TargetKind.NONE, true),
    WALK_DISTANCE(TargetKind.NONE, true),
    SPRINT_DISTANCE(TargetKind.NONE, true),
    SNEAK_DISTANCE(TargetKind.NONE, true),
    SWIM_DISTANCE(TargetKind.NONE, true),
    /** Distance climbed on ladders, vines, scaffolding. */
    CLIMB_DISTANCE(TargetKind.NONE, true),
    ELYTRA_DISTANCE(TargetKind.NONE, true),
    /** Distance ridden. target = mount entity type (horse, pig, strider, camel...). */
    RIDE_DISTANCE(TargetKind.ENTITY, true),
    /** Distance in a boat or raft. */
    BOAT_DISTANCE(TargetKind.NONE, true),
    /** Landed after a fall (fired even if damage was 0). amount = blocks fallen. Use {@code "min"} for big falls. */
    FALL(TargetKind.NONE, false),

    // ---- world ----
    /** Slept through to morning (or set spawn). */
    SLEEP(TargetKind.NONE, false),
    /** Entered a biome different from the last one. target = biome. */
    ENTER_BIOME(TargetKind.BIOME, false),
    /** Changed dimension. target = destination dimension id. */
    ENTER_DIMENSION(TargetKind.DIMENSION, false),
    /** Walked into a structure's bounds (checked about once a second). target = structure. */
    ENTER_STRUCTURE(TargetKind.STRUCTURE, false),
    /** Completed an advancement (recipes excluded). target = advancement id. */
    ADVANCEMENT(TargetKind.ADVANCEMENT, false),
    /** Any vanilla custom stat went up (minecraft:custom stats: walk_one_cm, climb_one_cm, fly_one_cm, play_noteblock,
     * bell_ring, open_barrel, clean_armor, eat_cake_slice...). target = stat id, amount = increment (cm stats count
     * centimetres, so pick a tiny chance). Covers whatever the specific triggers above miss. */
    STAT_INCREMENT(TargetKind.STAT, true),
    /** A mod-defined moment no vanilla event covers (a crit, a 30+ block arrow hit, a wall jump...). target = event id
     * like {@code habits:crit_hit}. Any package may fire it via {@code Triggers.fire}; the firing package documents its
     * event ids in its docs/mechanics/<domain>.md. Rolled once per event. */
    CUSTOM(TargetKind.EVENT, false),
    /** Same as {@link #CUSTOM} but rolled per unit of amount (e.g. {@code habits:wall_slide_blocks}). */
    CUSTOM_UNITS(TargetKind.EVENT, true);

    public static final Codec<TriggerType> CODEC = StringRepresentable.fromEnum(TriggerType::values);

    public final TargetKind targetKind;
    /** True: chance is rolled per unit of amount (distance, jumps, xp). False: once per event. */
    public final boolean perUnit;

    TriggerType(TargetKind targetKind, boolean perUnit) {
        this.targetKind = targetKind;
        this.perUnit = perUnit;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
