package com.habits.core;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import org.jetbrains.annotations.Nullable;

/** What a trigger's target id names. Core uses {@link #registry} to resolve {@code "tag"} matches in a {@link TriggerSpec}. */
public enum TargetKind {
    /** No target; the trigger only carries an amount. */
    NONE(null),
    BLOCK(Registries.BLOCK),
    ITEM(Registries.ITEM),
    ENTITY(Registries.ENTITY_TYPE),
    DAMAGE_TYPE(Registries.DAMAGE_TYPE),
    /** Potion contents id, e.g. {@code minecraft:swiftness}. */
    POTION(Registries.POTION),
    ENCHANTMENT(Registries.ENCHANTMENT),
    BIOME(Registries.BIOME),
    STRUCTURE(Registries.STRUCTURE),
    /** Dimension (level) id, e.g. {@code minecraft:the_nether}. No tags. */
    DIMENSION(null),
    /** Advancement id. No tags. */
    ADVANCEMENT(null),
    /** Custom stat id ({@code minecraft:walk_one_cm}). */
    STAT(Registries.CUSTOM_STAT),
    /** Mod-defined moment id ({@code habits:crit_hit}), see {@link TriggerType#CUSTOM}. No tags. */
    EVENT(null);

    /** Registry the target id lives in, for tag lookups; null = tags never match. */
    @Nullable
    public final ResourceKey<? extends Registry<?>> registry;

    TargetKind(@Nullable ResourceKey<? extends Registry<?>> registry) {
        this.registry = registry;
    }
}
