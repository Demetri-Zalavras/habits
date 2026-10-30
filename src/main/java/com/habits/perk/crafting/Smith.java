package com.habits.perk.crafting;

import com.habits.core.HabitsApi;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Crafting class mechanics (skills in {@code habits_skills/class/crafting/}): the Runewright's stronger table enchants and
 * Runic Surge, and the Mythsmith's Masterworks (crafted gear with the maker's name and better stats) with the skills that
 * read them. A Masterwork is marked by the maker's UUID in custom data, so it survives smithing upgrades and trades.
 */
final class Smith {
    private Smith() {}

    static final ResourceLocation RUNEWRIGHT = C.mech("runewright", "% chance a table enchant goes one level higher");
    static final ResourceLocation RUNIC_SURGE = C.mech("runic_surge", "% more melee damage per enchantment level");
    static final ResourceLocation MYTHSMITH = C.mech("mythsmith", "% chance crafted gear becomes a Masterwork");
    static final ResourceLocation MAKERS_BOND = C.mech("makers_bond", "% more damage with your own Masterwork");
    static final ResourceLocation LIVING_METAL = C.mech("living_metal", "seconds per durability point your Masterworks mend");
    static final ResourceLocation SIGNATURE = C.mech("signature", "% stronger Masterwork bonuses");

    static final String SMITH = "habits_smith";
    static final ResourceLocation MW_DAMAGE = com.habits.Habits.id("crafting/masterwork_damage");
    static final ResourceLocation MW_ARMOR = com.habits.Habits.id("crafting/masterwork_armor");
    static final ResourceLocation MW_TOUGH = com.habits.Habits.id("crafting/masterwork_toughness");

    static void init() {}

    // ------------------------------------------------------------------ runewright

    @SubscribeEvent(priority = EventPriority.HIGH) // before arcane echo adds its level I bonus enchant
    static void onEnchant(PlayerEnchantItemEvent e) {
        HabitsApi.PerkHit h = C.hit(e.getEntity(), RUNEWRIGHT);
        if (h != null) runes(e.getEntity(), e.getEnchantedItem(), h);
    }

    /** Each enchant rolls the value% to go up one level, never past its max plus {@code over}. Returns how many rose. */
    static int runes(Player p, ItemStack s, HabitsApi.PerkHit h) {
        int over = (int) C.param(h, "over", 0, 1);
        List<Holder<Enchantment>> up = new ArrayList<>();
        for (var en : EnchantmentHelper.getEnchantmentsForCrafting(s).entrySet())
            if (en.getIntValue() < en.getKey().value().getMaxLevel() + over && C.roll(p, h.value())) up.add(en.getKey());
        if (!up.isEmpty()) EnchantmentHelper.updateEnchantments(s, m -> up.forEach(en -> m.set(en, m.getLevel(en) + 1)));
        return up.size();
    }

    // ------------------------------------------------------------------ mythsmith

    /** Rating roll, then the Mythsmith's Masterwork roll, on a freshly crafted gear stack (from {@code Item#onCraftedBy},
     * see CraftHooks; vanilla's emptied-stack second call on a shift-click is skipped as empty). Server only. */
    static void crafted(Player p, ItemStack s) {
        if (!C.ok(p) || s.isEmpty() || !s.isDamageableItem() || C.special(s) || !inMenu(p, s)) return;
        Rating.onCrafted(p, s); // first, so a Masterwork names the rated item
        HabitsApi.PerkHit h = C.hit(p, MYTHSMITH);
        if (h != null && C.roll(p, h.value())) masterwork(p, s, h);
    }

    /** The real result: on the cursor or still in a slot of the open menu. A shift-click also reports a copy vanilla
     * made before moving the stack; that copy is in no slot and is skipped. */
    static boolean inMenu(Player p, ItemStack s) {
        if (p.containerMenu.getCarried() == s) return true;
        for (var slot : p.containerMenu.slots) if (slot.getItem() == s) return true;
        return false;
    }

    /** Turns {@code s} into the player's Masterwork. False when it already is one or can't take damage. */
    static boolean masterwork(Player p, ItemStack s, HabitsApi.PerkHit h) {
        if (!s.isDamageableItem() || maker(s) != null) return false;
        HabitsApi.PerkHit sig = C.hit(p, SIGNATURE);
        double boost = 1 + (sig == null ? 0 : sig.value() / 100);
        double dmg = C.param(h, "damage", 10, 25) * boost / 100, arm = C.param(h, "armor", 10, 25) * boost / 100;
        ItemAttributeModifiers base = base(s), mods = base;
        for (var m : base.modifiers()) {
            if (m.modifier().operation() != AttributeModifier.Operation.ADD_VALUE || m.modifier().amount() <= 0) continue;
            if (m.attribute().is(Attributes.ATTACK_DAMAGE)) mods = add(mods, m, Attributes.ATTACK_DAMAGE, MW_DAMAGE, dmg);
            else if (m.attribute().is(Attributes.ARMOR)) mods = add(mods, m, Attributes.ARMOR, MW_ARMOR, arm);
            else if (m.attribute().is(Attributes.ARMOR_TOUGHNESS)) mods = add(mods, m, Attributes.ARMOR_TOUGHNESS, MW_TOUGH, arm);
        }
        if (mods != base) s.set(DataComponents.ATTRIBUTE_MODIFIERS, mods);
        s.set(DataComponents.MAX_DAMAGE, (int) Math.round(s.getMaxDamage() * (1 + C.param(h, "durability", 25, 100) * boost / 100)));
        s.set(DataComponents.CUSTOM_NAME, Component.translatable("habits.crafting.masterwork.name", p.getName(), s.getHoverName())
                .withStyle(st -> st.withColor(ChatFormatting.GOLD).withItalic(false)));
        Rating.lore(s, List.of(Component.translatable("habits.crafting.masterwork.lore", p.getName())
                .withStyle(st -> st.withColor(ChatFormatting.GRAY).withItalic(false))));
        CustomData.update(DataComponents.CUSTOM_DATA, s, tag -> tag.putUUID(SMITH, p.getUUID()));
        return true;
    }

    static ItemAttributeModifiers add(ItemAttributeModifiers mods, ItemAttributeModifiers.Entry m, Holder<Attribute> attr,
                                              ResourceLocation id, double pct) {
        ResourceLocation slotId = id.withSuffix("_" + m.slot().getSerializedName());
        if (pct <= 0 || mods.modifiers().stream().anyMatch(x -> x.modifier().id().equals(slotId))) return mods;
        return mods.withModifierAdded(attr, new AttributeModifier(slotId, m.modifier().amount() * pct, AttributeModifier.Operation.ADD_VALUE), m.slot());
    }

    /** The stack's own modifiers: the component, or the item's defaults (armor keeps them off the component). */
    static ItemAttributeModifiers base(ItemStack s) {
        ItemAttributeModifiers b = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return b.modifiers().isEmpty() ? s.getItem().getDefaultAttributeModifiers(s) : b;
    }

    @org.jetbrains.annotations.Nullable
    static UUID maker(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.contains(SMITH) ? d.copyTag().getUUID(SMITH) : null;
    }

    static boolean own(Player p, ItemStack s) {
        return !s.isEmpty() && p.getUUID().equals(maker(s));
    }

    // ------------------------------------------------------------------ fights: runic surge, maker's bond

    @SubscribeEvent(priority = EventPriority.LOW)
    static void onHurt(LivingIncomingDamageEvent e) {
        if (e.getEntity().level().isClientSide) return;
        HabitsApi.PerkHit h;
        if (e.getSource().getEntity() instanceof Player a && e.getSource().getDirectEntity() == a) {
            ItemStack w = a.getMainHandItem();
            double pct = 0;
            if ((h = C.hit(a, RUNIC_SURGE)) != null) {
                int levels = 0;
                for (var en : w.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet()) levels += en.getIntValue();
                pct += Math.min(C.param(h, "cap", 15, 40), h.value() * levels);
            }
            if ((h = C.hit(a, MAKERS_BOND)) != null && own(a, w)) pct += h.value();
            if (pct > 0) e.setAmount(e.getAmount() * (float) (1 + pct / 100));
        }
        if (e.getEntity() instanceof Player v && (h = C.hit(v, MAKERS_BOND)) != null) {
            int pieces = 0;
            for (EquipmentSlot slot : EquipmentSlot.values()) if (slot.isArmor() && own(v, v.getItemBySlot(slot))) pieces++;
            double cut = Math.min(100, pieces * C.param(h, "guard", 2, 5));
            if (cut > 0) e.setAmount(e.getAmount() * (float) (1 - cut / 100));
        }
    }

    // ------------------------------------------------------------------ living metal

    /** Once a second each damaged Masterwork you made, held or worn, mends one point with chance 1/value. */
    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.level().isClientSide || (p.tickCount + p.getId()) % 20 != 0) return;
        HabitsApi.PerkHit h = C.hit(p, LIVING_METAL);
        if (h != null) mend(p, h);
    }

    static int mend(Player p, HabitsApi.PerkHit h) {
        int mended = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = p.getItemBySlot(slot);
            if (s.isDamaged() && own(p, s) && p.getRandom().nextDouble() * Math.max(1, h.value()) < 1) {
                s.setDamageValue(s.getDamageValue() - 1);
                mended++;
            }
        }
        return mended;
    }
}
