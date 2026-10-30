package com.habits.core;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * The player attachment ({@link CoreRegistries#COLLECTION}): skill id -> progress. Immutable; change it only through
 * core ({@link HabitsApi}). Saved, copied on death (nothing is lost), synced to its own player, so it is readable on
 * the client with {@code player.getData(CoreRegistries.COLLECTION)}.
 */
public record HabitsCollection(Map<ResourceLocation, SkillProgress> skills) {
    public static final HabitsCollection EMPTY = new HabitsCollection(Map.of());

    public static final Codec<HabitsCollection> CODEC = Codec.unboundedMap(ResourceLocation.CODEC, SkillProgress.CODEC)
            .xmap(HabitsCollection::new, HabitsCollection::skills);

    public static final StreamCodec<ByteBuf, HabitsCollection> STREAM_CODEC =
            ByteBufCodecs.<ByteBuf, ResourceLocation, SkillProgress, Map<ResourceLocation, SkillProgress>>map(
                    HashMap::new, ResourceLocation.STREAM_CODEC, SkillProgress.STREAM_CODEC)
                    .map(HabitsCollection::new, HabitsCollection::skills);

    public HabitsCollection {
        skills = Map.copyOf(skills);
    }

    /** Progress of a skill, {@link SkillProgress#NONE} when not owned. */
    public SkillProgress get(ResourceLocation skill) {
        return skills.getOrDefault(skill, SkillProgress.NONE);
    }

    public HabitsCollection with(ResourceLocation skill, SkillProgress progress) {
        Map<ResourceLocation, SkillProgress> m = new HashMap<>(skills);
        m.put(skill, progress);
        return new HabitsCollection(m);
    }
}
