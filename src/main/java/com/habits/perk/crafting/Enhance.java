package com.habits.perk.crafting;

import com.habits.Habits;
import com.habits.core.Category;
import com.habits.core.CoreRegistries;
import com.habits.core.Specs;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.List;

/**
 * Overgeared-style enhancement: any damageable gear plus an Enhancement Stone in an anvil tries for the next level
 * (+1 .. +10). The anvil shows the success result; the roll happens when the result is taken. Each level adds a share
 * of the item's base damage/armor/toughness. A failure costs the stone and XP; above the safe levels it also drops a
 * level, and from the break level on it can destroy the item. Players who picked Crafting succeed more often.
 */
public final class Enhance {
    private Enhance() {}

    public static final DeferredItem<Item> STONE = CoreRegistries.ITEMS.registerSimpleItem("enhancement_stone",
            new Item.Properties().rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    static final String KEY = "habits_enhance";
    static final int MAX = 10;

    static void init() {}

    public static int level(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? 0 : d.copyTag().getInt(KEY);
    }

    /** Sets the level and rebuilds its modifiers from the item's own defaults (ratings and other bonuses are kept). */
    static void setLevel(ItemStack s, int lv) {
        ItemAttributeModifiers cur = Smith.base(s);
        ItemAttributeModifiers.Builder b = ItemAttributeModifiers.builder();
        for (var m : cur.modifiers()) if (!m.modifier().id().getPath().startsWith("enhance/")) b.add(m.attribute(), m.modifier(), m.slot());
        ItemAttributeModifiers mods = b.build().withTooltip(cur.showInTooltip());
        double pct = lv * CraftCfg.ENHANCE_PCT.get() / 100;
        // the item's own stats: prototype component (weapons), else the item's defaults (armor)
        ItemAttributeModifiers own = s.getItem().components().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        if (own.modifiers().isEmpty()) own = s.getItem().getDefaultAttributeModifiers(s);
        if (lv > 0) for (var m : own.modifiers()) {
            if (m.modifier().operation() != AttributeModifier.Operation.ADD_VALUE || m.modifier().amount() <= 0) continue;
            String name = m.attribute().is(Attributes.ATTACK_DAMAGE) ? "damage" : m.attribute().is(Attributes.ARMOR) ? "armor"
                    : m.attribute().is(Attributes.ARMOR_TOUGHNESS) ? "toughness" : null;
            if (name != null) mods = mods.withModifierAdded(m.attribute(), new AttributeModifier(
                    Habits.id("enhance/" + name + "_" + m.slot().getSerializedName()), m.modifier().amount() * pct, AttributeModifier.Operation.ADD_VALUE), m.slot());
        }
        s.set(DataComponents.ATTRIBUTE_MODIFIERS, mods);
        CustomData.update(DataComponents.CUSTOM_DATA, s, t -> {
            if (lv > 0) t.putInt(KEY, lv);
            else t.remove(KEY);
        });
    }

    /** Success chance in percent of going from {@code lv} to lv+1. */
    static double chance(Player p, int lv) {
        List<? extends Double> c = CraftCfg.ENHANCE_CHANCES.get();
        double base = c.isEmpty() ? 50 : c.get(Math.min(lv, c.size() - 1));
        if (Specs.of(p).picks().contains(Category.CRAFTING)) base += CraftCfg.ENHANCE_CRAFTER_BONUS.get();
        return Math.min(100, base);
    }

    // ------------------------------------------------------------------ anvil

    @SubscribeEvent
    static void onUpdate(AnvilUpdateEvent e) {
        ItemStack left = e.getLeft();
        if (!e.getRight().is(STONE.get()) || !left.isDamageableItem() || left.getCount() != 1) return;
        int lv = level(left);
        if (lv >= MAX) return;
        ItemStack out = left.copy();
        setLevel(out, lv + 1);
        e.setOutput(out);
        e.setCost(Math.min(39, CraftCfg.ENHANCE_XP_BASE.get() + (long) CraftCfg.ENHANCE_XP_PER.get() * lv));
        e.setMaterialCost(1);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST) // before the anvil perks touch the result
    static void onTake(AnvilRepairEvent e) {
        Player p = e.getEntity();
        if (!e.getRight().is(STONE.get()) || p.level().isClientSide) return;
        ItemStack out = Anvil.real(p, e.getOutput());
        if (out == null) return;
        int from = level(e.getLeft());
        outcome(p, out, from, p.getRandom().nextDouble() * 100);
    }

    /** Applies a roll ({@code r} in 0..100) to the taken result, which already holds level from+1. */
    static void outcome(Player p, ItemStack out, int from, double r) {
        int to = from + 1;
        if (r < chance(p, from)) {
            p.displayClientMessage(Component.translatable("habits.enhance.success", to).withStyle(st -> st.withColor(0x7CE0FF)), true);
            p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.2f);
            if (to == MAX && p.getServer() != null) p.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                    "habits.enhance.announce", p.getDisplayName(), out.getHoverName()).withStyle(st -> st.withColor(0xE8E0C8)), false);
            return;
        }
        if (to >= CraftCfg.ENHANCE_BREAK_FROM.get() && p.getRandom().nextDouble() * 100 < CraftCfg.ENHANCE_BREAK_CHANCE.get()) {
            p.displayClientMessage(Component.translatable("habits.enhance.broken", out.getHoverName()).withStyle(st -> st.withColor(0xFF5555)), true);
            p.level().playSound(null, p.blockPosition(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 1, 0.8f);
            out.setCount(0);
            return;
        }
        int back = from > CraftCfg.ENHANCE_SAFE.get() ? from - 1 : from;
        setLevel(out, back);
        p.displayClientMessage(Component.translatable(back < from ? "habits.enhance.dropped" : "habits.enhance.failed", back)
                .withStyle(st -> st.withColor(0xFFAA55)), true);
        p.level().playSound(null, p.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.6f, 0.7f);
    }
}
