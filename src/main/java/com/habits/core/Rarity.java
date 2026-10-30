package com.habits.core;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/** Card rarity. JSON: lowercase name ({@code "common"}). Lang key {@code rarity.habits.<name>} (core lang part). */
public enum Rarity implements StringRepresentable {
    COMMON(0xC9C9C9),
    UNCOMMON(0x5FE063),
    RARE(0x4AA8FF),
    EPIC(0xC06BFF),
    LEGENDARY(0xFFB52E),
    /** Above Legendary (2026-10-12): sigils and charms only for now, no cards yet. Saved by name, so old data keeps its meaning. */
    MYTHICAL(0xD81B3C);

    public static final Codec<Rarity> CODEC = StringRepresentable.fromEnum(Rarity::values);

    /** RGB, no alpha (OR with 0xFF000000 for GuiGraphics). */
    public final int color;

    Rarity(int color) {
        this.color = color;
    }

    public Component displayName() {
        return Component.translatable("rarity.habits." + getSerializedName());
    }

    /** Epic and Legendary get the animated foil shine. */
    public boolean foil() {
        return this.ordinal() >= EPIC.ordinal();
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
