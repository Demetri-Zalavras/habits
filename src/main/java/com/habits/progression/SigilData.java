package com.habits.progression;

import com.habits.core.Rarity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** The {@code habits:sigil} component, and what a socket holds. A sigil has no other state, so taking it out of a
 * socket gives back an identical item. */
public record SigilData(SigilType type, Rarity rarity) {
    public static final Codec<SigilData> CODEC = RecordCodecBuilder.create(i -> i.group(
            SigilType.CODEC.fieldOf("type").forGetter(SigilData::type),
            Rarity.CODEC.fieldOf("rarity").forGetter(SigilData::rarity)
    ).apply(i, SigilData::new));

    static final StreamCodec<ByteBuf, Rarity> RARITY_STREAM =
            ByteBufCodecs.VAR_INT.map(i -> Rarity.values()[Math.floorMod(i, Rarity.values().length)], Enum::ordinal);

    public static final StreamCodec<ByteBuf, SigilData> STREAM_CODEC = StreamCodec.composite(
            SigilType.STREAM_CODEC, SigilData::type, RARITY_STREAM, SigilData::rarity, SigilData::new);

    /** Rarities in the first stored layout (Common..Legendary): saves from before Mythical hold 4 x 5 counts, type-major. */
    static final int LEGACY_RARITIES = 5;

    /** Index of this kind in the stored counts: the old 4 x 5 type-major block first (so old saves read the same), then
     * rarities added later, type-major again. */
    public int bucket() {
        int t = type.ordinal(), r = rarity.ordinal(), types = SigilType.values().length;
        return r < LEGACY_RARITIES ? t * LEGACY_RARITIES + r
                : types * LEGACY_RARITIES + t * (Rarity.values().length - LEGACY_RARITIES) + r - LEGACY_RARITIES;
    }

    /** Every sigil kind, indexed by {@link #bucket}. */
    public static final java.util.List<SigilData> ALL;

    static {
        SigilData[] all = new SigilData[SigilType.values().length * Rarity.values().length];
        for (SigilType t : SigilType.values()) for (Rarity r : Rarity.values()) { SigilData s = new SigilData(t, r); all[s.bucket()] = s; }
        ALL = java.util.List.of(all);
    }

    /** The shared instance of a kind. */
    public static SigilData of(SigilType t, Rarity r) {
        return ALL.get(new SigilData(t, r).bucket());
    }

    /** "Rare Potent Sigil" in its rarity color. */
    public net.minecraft.network.chat.MutableComponent name() {
        return net.minecraft.network.chat.Component.translatable("item.habits.sigil.named", rarity.displayName(),
                        net.minecraft.network.chat.Component.translatable("sigil.habits." + type.getSerializedName()))
                .withStyle(s -> s.withColor(rarity.color).withItalic(false));
    }

    /** The percent this sigil adds (config, per type and rarity). */
    public double percent() {
        return ProgressionInit.sigilPercent(type, rarity);
    }
}
