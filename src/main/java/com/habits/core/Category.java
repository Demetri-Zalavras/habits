package com.habits.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;

/** Binder tab and specialization. Declaration order = tab order. JSON: lowercase name. Lang key {@code category.habits.<name>}.
 * A player picks one {@link #combat} style and one profession ({@link Specs}). */
public enum Category implements StringRepresentable {
    WARRIOR(0xD9453B, true),
    RANGER(0x26A69A, true),
    GUARDIAN(0xF2994A, true),
    MINING(0x9AA3AD, false),
    FARMING(0x7CC242, false),
    CRAFTING(0xB07A47, false),
    NATURE(0x3FA36B, false),
    EXPLORATION(0x5C6BC0, false),
    BUILDING(0xC9A66B, false),
    MOVEMENT(0x4FC3F7, false);

    /** Accepts the pre-specialization names (combat, survival, odd) so old charms and data packs still load. */
    public static final Codec<Category> CODEC = Codec.STRING.comapFlatMap(Category::parse, Category::getSerializedName);

    /** RGB of the binder tab, no alpha. */
    public final int tabColor;
    /** A combat style (the first pick) rather than a profession. */
    public final boolean combat;

    Category(int tabColor, boolean combat) {
        this.tabColor = tabColor;
        this.combat = combat;
    }

    static DataResult<Category> parse(String s) {
        String n = switch (s) {
            case "combat" -> "warrior";
            case "survival" -> "guardian";
            case "odd" -> "exploration";
            default -> s;
        };
        for (Category c : values()) if (c.getSerializedName().equals(n)) return DataResult.success(c);
        return DataResult.error(() -> "Unknown category: " + s);
    }

    public Component displayName() {
        return Component.translatable("category.habits." + getSerializedName());
    }

    /** Binder tab and picker icon. */
    public Item icon() {
        return switch (this) {
            case WARRIOR -> Items.IRON_SWORD;
            case RANGER -> Items.BOW;
            case GUARDIAN -> Items.SHIELD;
            case MINING -> Items.IRON_PICKAXE;
            case FARMING -> Items.WHEAT;
            case CRAFTING -> Items.CRAFTING_TABLE;
            case NATURE -> Items.OAK_SAPLING;
            case EXPLORATION -> Items.COMPASS;
            case BUILDING -> Items.BRICKS;
            case MOVEMENT -> Items.FEATHER;
        };
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
