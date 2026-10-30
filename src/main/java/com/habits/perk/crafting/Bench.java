package com.habits.perk.crafting;

import com.habits.core.HabitsApi;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Crafting table perks: bonus yields for one kind of output, tuned gear, firework flight, craft XP, free crafts. */
final class Bench {
    private Bench() {}

    /** Bonus-yield skills: mechanic id to its default output filter (the JSON {@code items} param overrides it). */
    static final Map<ResourceLocation, String> YIELD = new LinkedHashMap<>();
    static final ResourceLocation SAWMILL = bonus("sawmill", "#minecraft:planks");
    static final ResourceLocation PAPER_MILL = bonus("paper_mill", "minecraft:paper,minecraft:book");
    static final ResourceLocation TORCH_SMITH = bonus("torch_smith", "minecraft:torch,minecraft:soul_torch,minecraft:lantern,minecraft:soul_lantern");
    static final ResourceLocation COLOR_THEORY = bonus("color_theory", "#c:dyes");
    static final ResourceLocation FLETCHER = bonus("fletcher", "minecraft:arrow");
    static final ResourceLocation PATTERN_MAKER = bonus("pattern_maker", "#minecraft:trim_templates,minecraft:netherite_upgrade_smithing_template");

    static final ResourceLocation WHETSTONE = C.mech("whetstone", "attack damage on weapons you craft");
    static final ResourceLocation REINFORCED = C.mech("reinforced", "armor points on armor you craft");
    static final ResourceLocation PYRO = C.mech("pyrotechnician", "extra flight on rockets you craft");
    static final ResourceLocation LEARNING = C.mech("learning_by_doing", "% chance a craft gives XP");
    static final ResourceLocation ECHO = C.mech("echo_craft", "% chance a craft is free");

    private static ResourceLocation bonus(String name, String filter) {
        ResourceLocation id = C.mech(name, "% chance of bonus items");
        YIELD.put(id, filter);
        return id;
    }

    static void init() {}

    @SubscribeEvent
    static void onCraft(PlayerEvent.ItemCraftedEvent e) {
        Player p = e.getEntity();
        ItemStack out = e.getCrafting();
        if (!C.ok(p) || out.isEmpty() || C.special(out) || !(e.getInventory() instanceof CraftingContainer grid)) return;
        HabitsApi.PerkHit h;
        for (var y : YIELD.entrySet()) {
            if ((h = C.hit(p, y.getKey())) != null && C.matches(p, h, "items", y.getValue(), out) && C.roll(p, h.value()))
                C.give(p, out.copyWithCount(Math.max(1, (int) C.param(h, "extra", 1, 4))));
        }
        if ((h = C.hit(p, LEARNING)) != null && C.roll(p, h.value())) p.giveExperiencePoints(C.carry(p, C.param(h, "xp", 1, 3)));
        if ((h = C.hit(p, ECHO)) != null && freeSafe(p, grid, out) && C.roll(p, h.value())) refundAll(p, grid);

        boolean tune = C.hit(p, WHETSTONE) != null || C.hit(p, REINFORCED) != null || C.hit(p, PYRO) != null;
        ItemStack real = tune ? C.taken(p, out) : null;
        if (real != null) tune(p, real);
    }

    /** Whetstone, reinforced and pyrotechnician edit the stack that reached the player. */
    static void tune(Player p, ItemStack real) {
        HabitsApi.PerkHit h;
        if ((h = C.hit(p, WHETSTONE)) != null && base(real).modifiers().stream().anyMatch(m -> m.attribute().is(Attributes.ATTACK_DAMAGE))) {
            addModifier(real, new AttributeModifier(WHETSTONE, h.value(), AttributeModifier.Operation.ADD_VALUE),
                    Attributes.ATTACK_DAMAGE, EquipmentSlotGroup.MAINHAND);
        }
        if ((h = C.hit(p, REINFORCED)) != null && real.getItem() instanceof ArmorItem armor) {
            var slot = armor.getEquipmentSlot();
            addModifier(real, new AttributeModifier(REINFORCED.withSuffix("_" + slot.getName()), h.value(), AttributeModifier.Operation.ADD_VALUE),
                    Attributes.ARMOR, EquipmentSlotGroup.bySlot(slot));
        }
        if ((h = C.hit(p, PYRO)) != null && real.is(Items.FIREWORK_ROCKET)) {
            Fireworks f = real.getOrDefault(DataComponents.FIREWORKS, new Fireworks(1, java.util.List.of()));
            int cap = (int) C.param(h, "max_flight", 6, 6);
            real.set(DataComponents.FIREWORKS, new Fireworks(Math.min(cap, f.flightDuration() + (int) h.value()), f.explosions()));
        }
    }

    private static void addModifier(ItemStack s, AttributeModifier mod, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr,
                                    EquipmentSlotGroup slot) {
        ItemAttributeModifiers base = base(s);
        if (base.modifiers().stream().anyMatch(m -> m.modifier().id().equals(mod.id()))) return;
        s.set(DataComponents.ATTRIBUTE_MODIFIERS, base.withModifierAdded(attr, mod, slot));
    }

    /** The stack's own modifiers: the component, or the item's defaults (armor keeps them off the component). */
    private static ItemAttributeModifiers base(ItemStack s) {
        ItemAttributeModifiers base = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return base.modifiers().isEmpty() ? s.getItem().getDefaultAttributeModifiers(s) : base;
    }

    /**
     * A free craft is only safe on plain recipes with 2+ different ingredients: one-ingredient recipes loop (ingots to
     * block and back) and special recipes (book or map cloning, shulker dyeing) carry contents.
     */
    static boolean freeSafe(Player p, CraftingContainer grid, ItemStack out) {
        java.util.Set<net.minecraft.world.item.Item> kinds = new java.util.HashSet<>();
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack s = grid.getItem(i);
            if (s.isEmpty()) continue;
            if (C.special(s)) return false;
            kinds.add(s.getItem());
        }
        if (kinds.size() < 2 || out.has(DataComponents.WRITTEN_BOOK_CONTENT)) return false;
        var recipe = p.level().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid.asCraftInput(), p.level());
        return recipe.isPresent() && !(recipe.get().value() instanceof CustomRecipe);
    }

    /** One of every grid ingredient back; items with a leftover (milk bucket) are skipped, vanilla returns their container. */
    private static void refundAll(Player p, CraftingContainer grid) {
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack s = grid.getItem(i);
            if (!s.isEmpty() && !s.hasCraftingRemainingItem()) C.give(p, s.copyWithCount(1));
        }
    }
}
