package com.habits.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Data component on a card stack ({@link CoreRegistries#CARD_DATA}). Rarity is in here so client renderers (inventory
 * card) never need a registry lookup. Stacks saved while print variants existed still carry a {@code variant} key; the
 * codec ignores unknown keys, so they load as the one normal print. Read it with {@link CardItems}. */
public record CardData(ResourceLocation skill, Rarity rarity) {
    public static final Codec<CardData> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("skill").forGetter(CardData::skill),
            Rarity.CODEC.fieldOf("rarity").forGetter(CardData::rarity)
    ).apply(i, CardData::new));

    public static final StreamCodec<ByteBuf, CardData> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, CardData::skill,
            ByteBufCodecs.VAR_INT.map(o -> Rarity.values()[o], Rarity::ordinal), CardData::rarity, CardData::new);
}
