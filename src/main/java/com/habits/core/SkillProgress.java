package com.habits.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** One owned skill: level 1..10, cards toward the next level ({@code progress / Leveling.cardsToNext(level, rarity)}), perk on/off. */
public record SkillProgress(int level, int progress, boolean enabled) {
    /** Not owned (never stored in a collection; returned for missing skills). */
    public static final SkillProgress NONE = new SkillProgress(0, 0, true);

    public static final Codec<SkillProgress> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("level").forGetter(SkillProgress::level),
            Codec.INT.optionalFieldOf("progress", 0).forGetter(SkillProgress::progress),
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(SkillProgress::enabled)
    ).apply(i, SkillProgress::new));

    public static final StreamCodec<ByteBuf, SkillProgress> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SkillProgress::level, ByteBufCodecs.VAR_INT, SkillProgress::progress,
            ByteBufCodecs.BOOL, SkillProgress::enabled, SkillProgress::new);

    /** Cards needed for the next level of a skill of this rarity (Mythical levels faster). */
    public int needed(Rarity rarity) {
        return Leveling.xpToNext(level, rarity);
    }

    public boolean maxed() {
        return level >= Leveling.MAX_LEVEL;
    }
}
