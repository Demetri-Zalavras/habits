package com.habits.perk.crafting;

import com.habits.core.HabitsApi;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.GrindstoneEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;

import java.util.ArrayList;
import java.util.List;

/** Anvil, grindstone and enchanting table perks. */
final class Anvil {
    private Anvil() {}

    static final ResourceLocation BRAND_NAME = C.mech("brand_name", "levels refunded on a rename");
    static final ResourceLocation BOOKKEEPER = C.mech("bookkeeper", "% chance to get a book back");
    static final ResourceLocation SCRAP_SAVER = C.mech("scrap_saver", "% chance to keep a repair material");
    static final ResourceLocation MASTER_MENDER = C.mech("master_mender", "% extra durability on anvil repairs");
    static final ResourceLocation CLEAN_SLATE = C.mech("clean_slate", "% chance to wipe the repair cost");
    static final ResourceLocation NAMED_BLADE = C.mech("named_blade", "% damage with a renamed weapon");
    static final ResourceLocation DISENCHANTER = C.mech("disenchanter", "% chance to save an enchant as a book");
    static final ResourceLocation ARCANE_ECHO = C.mech("arcane_echo", "% chance of a bonus enchant");

    static void init() {}

    // ------------------------------------------------------------------ anvil take

    @SubscribeEvent
    static void onAnvil(AnvilRepairEvent e) {
        Player p = e.getEntity();
        if (!C.ok(p)) return;
        ItemStack left = e.getLeft(), right = e.getRight(), out = real(p, e.getOutput());
        if (out == null) return;
        int cost = p.containerMenu instanceof AnvilMenu m ? m.getCost() : 0;
        int matCost = p.containerMenu instanceof AnvilMenu m ? m.repairItemCountCost : 0;
        HabitsApi.PerkHit h;
        // rename only: no right item, the name changed
        if ((h = C.hit(p, BRAND_NAME)) != null && right.isEmpty() && out.has(DataComponents.CUSTOM_NAME) && cost > 1)
            p.giveExperienceLevels(Math.min(cost - 1, (int) h.value()));
        if ((h = C.hit(p, BOOKKEEPER)) != null && right.is(Items.ENCHANTED_BOOK) && C.roll(p, h.value())) C.give(p, new ItemStack(Items.BOOK));
        boolean material = matCost > 0 && !right.isEmpty() && left.getItem().isValidRepairItem(left, right);
        if ((h = C.hit(p, SCRAP_SAVER)) != null && material && C.roll(p, h.value())) C.give(p, right.copyWithCount(1));
        boolean repair = out.isDamageableItem() && (material || right.is(left.getItem()));
        if ((h = C.hit(p, MASTER_MENDER)) != null && repair)
            out.setDamageValue(Math.max(0, out.getDamageValue() - (int) Math.round(out.getMaxDamage() * h.value() / 100)));
        if ((h = C.hit(p, CLEAN_SLATE)) != null && C.roll(p, h.value())) out.set(DataComponents.REPAIR_COST, 0);
    }

    /**
     * The result as the player got it. A shift-click moves a split copy into the bag and reports the emptied stack
     * (count 0, components intact), so read its components and find the identical bag stack.
     */
    @org.jetbrains.annotations.Nullable
    static ItemStack real(Player p, ItemStack out) {
        if (!out.isEmpty()) return out;
        if (out == ItemStack.EMPTY) return null;
        out.setCount(1);
        ItemStack copy = out.copy();
        out.setCount(0);
        for (ItemStack s : p.getInventory().items) if (ItemStack.isSameItemSameComponents(s, copy)) return s;
        return null;
    }

    // ------------------------------------------------------------------ named blade: renamed weapons hit harder

    @SubscribeEvent
    static void onHurt(LivingIncomingDamageEvent e) {
        if (!(e.getSource().getEntity() instanceof Player p) || e.getSource().getDirectEntity() != p) return;
        HabitsApi.PerkHit h = C.hit(p, NAMED_BLADE);
        ItemStack w = p.getMainHandItem();
        if (h != null && w.isDamageableItem() && w.has(DataComponents.CUSTOM_NAME)) e.setAmount(e.getAmount() * (float) (1 + h.value() / 100));
    }

    // ------------------------------------------------------------------ grindstone: disenchanter

    @SubscribeEvent
    static void onGrind(GrindstoneEvent.OnTakeItem e) {
        Player p = e.getPlayer();
        HabitsApi.PerkHit h = C.hit(p, DISENCHANTER);
        if (h == null || !C.roll(p, h.value())) return;
        ItemStack book = savedBook(p, e.getTopItem(), e.getBottomItem());
        if (!book.isEmpty()) C.give(p, book);
    }

    /** One random non-curse enchant of the ground items, as a book. Books are skipped: a one-enchant book would loop XP. */
    static ItemStack savedBook(Player p, ItemStack... inputs) {
        List<Object[]> pool = new ArrayList<>();
        for (ItemStack s : inputs) {
            if (s.isEmpty() || s.is(Items.ENCHANTED_BOOK)) continue;
            for (var en : s.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet())
                if (!en.getKey().is(EnchantmentTags.CURSE)) pool.add(new Object[]{en.getKey(), en.getIntValue()});
        }
        if (pool.isEmpty()) return ItemStack.EMPTY;
        Object[] pick = pool.get(p.getRandom().nextInt(pool.size()));
        @SuppressWarnings("unchecked") Holder<Enchantment> ench = (Holder<Enchantment>) pick[0];
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        EnchantmentHelper.updateEnchantments(book, m -> m.set(ench, (int) pick[1]));
        return book;
    }

    // ------------------------------------------------------------------ enchanting table: arcane echo

    @SubscribeEvent
    static void onEnchant(PlayerEnchantItemEvent e) {
        Player p = e.getEntity();
        HabitsApi.PerkHit h = C.hit(p, ARCANE_ECHO);
        if (h != null && C.roll(p, h.value())) echo(p, e.getEnchantedItem());
    }

    /** Add one table enchant the item can take and that fits the ones it has, at level 1. */
    static boolean echo(Player p, ItemStack s) {
        ItemEnchantments cur = EnchantmentHelper.getEnchantmentsForCrafting(s);
        boolean book = s.is(Items.ENCHANTED_BOOK) || s.is(Items.BOOK);
        List<Holder<Enchantment>> pool = new ArrayList<>();
        p.registryAccess().registryOrThrow(Registries.ENCHANTMENT).holders().forEach(en -> {
            if (en.is(EnchantmentTags.CURSE) || !en.is(EnchantmentTags.IN_ENCHANTING_TABLE) || cur.getLevel(en) > 0) return;
            if (!book && !en.value().canEnchant(s)) return;
            for (Holder<Enchantment> have : cur.keySet()) if (!Enchantment.areCompatible(have, en)) return;
            pool.add(en);
        });
        if (pool.isEmpty()) return false;
        Holder<Enchantment> pick = pool.get(p.getRandom().nextInt(pool.size()));
        EnchantmentHelper.updateEnchantments(s, m -> m.set(pick, 1));
        return true;
    }
}
