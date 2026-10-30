package com.habits.progression;

import com.habits.core.SkillDef;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/** What a sigil boosts on the card it sits in. Lang: {@code sigil.habits.<name>} (type name) and
 * {@code sigil.habits.<name>.effect} (one %s = percent). */
public enum SigilType implements StringRepresentable {
    /** Perk strength (the skill's value). */
    POTENT,
    /** Cooldown params and wait values get shorter. */
    SWIFT,
    /** Radius and range params get bigger. */
    REACH,
    /** That card drops more often. */
    LUCKY;

    public static final Codec<SigilType> CODEC = StringRepresentable.fromEnum(SigilType::values);
    public static final StreamCodec<ByteBuf, SigilType> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(i -> values()[Math.floorMod(i, values().length)], Enum::ordinal);

    /** True when this sigil does something on that card. Server-side socket validation uses it; the book greys out
     * sockets with it. Swift needs a cooldown param or a wait value, Reach a radius or range param; Potent and Lucky fit every card. */
    public boolean fits(SkillDef def) {
        return switch (this) {
            case POTENT, LUCKY -> true;
            case SWIFT -> Effects.timeValue(def) || def.perk().params().keySet().stream().anyMatch(PMath::isCooldownKey);
            case REACH -> def.perk().params().keySet().stream().anyMatch(PMath::isRangeKey);
        };
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
