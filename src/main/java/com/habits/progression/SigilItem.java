package com.habits.progression;

import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A sigil as an item (chest loot, /give, older saves): it never stays in an inventory, {@link Drops} moves it into the
 * book's store. Socket it into a card in the Habits book. Type and rarity live in {@link ProgRegistries#SIGIL_DATA}. */
public class SigilItem extends Item {
    public SigilItem(Properties props) {
        super(props);
    }

    public static ItemStack create(SigilData data) {
        ItemStack s = new ItemStack(ProgRegistries.SIGIL.get());
        s.set(ProgRegistries.SIGIL_DATA.get(), data);
        return s;
    }

    @Nullable
    public static SigilData dataOf(ItemStack s) {
        return s.is(ProgRegistries.SIGIL.get()) ? s.get(ProgRegistries.SIGIL_DATA.get()) : null;
    }

    @Override
    public Component getName(ItemStack stack) {
        SigilData d = dataOf(stack);
        if (d == null) return super.getName(stack);
        return d.name();
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        SigilData d = dataOf(stack);
        return d != null && d.rarity().foil();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> lines, TooltipFlag flag) {
        SigilData d = dataOf(stack);
        if (d == null) return;
        lines.add(Component.translatable("sigil.habits." + d.type().getSerializedName() + ".effect", SkillDef.format(d.percent()))
                .withStyle(s -> s.withColor(Rarity.COMMON.color).withItalic(false)));
        lines.add(Component.translatable("sigil.habits.hint").withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(false)));
    }
}
