package com.habits.core;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Card stack helpers. Safe on both sides. */
public final class CardItems {
    private CardItems() {}

    /** A single card of {@code def}. */
    public static ItemStack create(SkillDef def) {
        ItemStack s = new ItemStack(CoreRegistries.CARD.get());
        s.set(CoreRegistries.CARD_DATA.get(), new CardData(def.id(), def.rarity()));
        return s;
    }

    /** The card's data, or null if {@code stack} is not a card. Use for rarity without a registry lookup. */
    @Nullable
    public static CardData dataOf(ItemStack stack) {
        return stack.is(CoreRegistries.CARD.get()) ? stack.get(CoreRegistries.CARD_DATA.get()) : null;
    }

    /** The skill on a card, or null if not a card or the skill no longer exists. */
    @Nullable
    public static SkillDef skillOf(ItemStack stack) {
        CardData d = dataOf(stack);
        return d == null ? null : SkillRegistry.get(d.skill());
    }
}
