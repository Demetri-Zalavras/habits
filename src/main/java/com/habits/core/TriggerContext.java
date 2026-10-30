package com.habits.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * What happened, passed to {@link HabitsApi#onTrigger}. Field meaning per type is in the {@link TriggerType} javadoc.
 *
 * @param target main id, of the trigger's {@link TargetKind} (null when none)
 * @param amount 1 unless the type says otherwise
 * @param item   secondary item id (weapon, tool, held item) or null
 * @param entity secondary entity type id (victim, attacker, killer, mount) or null
 * @param pos    where a won card drops (broken block, killed mob); null drops it at the player. Set with {@link #withPos}.
 */
public record TriggerContext(@Nullable ResourceLocation target, double amount, @Nullable ResourceLocation item,
                             @Nullable ResourceLocation entity, @Nullable Vec3 pos) {
    public TriggerContext(@Nullable ResourceLocation target, double amount, @Nullable ResourceLocation item, @Nullable ResourceLocation entity) {
        this(target, amount, item, entity, null);
    }

    public static TriggerContext none() {
        return new TriggerContext(null, 1, null, null);
    }

    public static TriggerContext amount(double amount) {
        return new TriggerContext(null, amount, null, null);
    }

    public static TriggerContext of(@Nullable ResourceLocation target) {
        return new TriggerContext(target, 1, null, null);
    }

    public static TriggerContext of(Block block) {
        return of(BuiltInRegistries.BLOCK.getKey(block));
    }

    public static TriggerContext of(Item item) {
        return of(BuiltInRegistries.ITEM.getKey(item));
    }

    public static TriggerContext of(EntityType<?> type) {
        return of(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    public TriggerContext withAmount(double amount) {
        return new TriggerContext(target, amount, item, entity, pos);
    }

    public TriggerContext withItem(@Nullable Item item) {
        return new TriggerContext(target, amount, item == null ? null : BuiltInRegistries.ITEM.getKey(item), entity, pos);
    }

    public TriggerContext withEntity(@Nullable EntityType<?> type) {
        return new TriggerContext(target, amount, item, type == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(type), pos);
    }

    /** Drop a won card here (an entity's position, e.g. the killed mob). */
    public TriggerContext withPos(@Nullable Vec3 pos) {
        return new TriggerContext(target, amount, item, entity, pos);
    }

    /** Drop a won card at the centre of this block (the broken / harvested block). */
    public TriggerContext withPos(BlockPos pos) {
        return withPos(Vec3.atCenterOf(pos));
    }
}
