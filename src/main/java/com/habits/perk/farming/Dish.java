package com.habits.perk.farming;

import com.habits.core.Category;
import com.habits.perk.crafting.Rating;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.StatAwardEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Rated dishes (Overgeared's cooks): a player who picked Farming rolls a rating for each food item they cook in a
 * furnace/smoker or craft (bread, stews, pies, cookies). Each tier above Normal adds 1 nutrition and more saturation;
 * Legendary and Myth add one or two buffs on eating. Rated items are split off the plain stack by tier.
 */
public final class Dish {
    private Dish() {}

    @SuppressWarnings("unchecked")
    static final Holder<MobEffect>[] BUFFS = new Holder[]{MobEffects.DAMAGE_BOOST, MobEffects.DIG_SPEED, MobEffects.MOVEMENT_SPEED,
            MobEffects.REGENERATION, MobEffects.DAMAGE_RESISTANCE, MobEffects.JUMP};

    @SubscribeEvent
    static void onStat(StatAwardEvent e) {
        if (e.getStat().getType() != Stats.ITEM_CRAFTED || !(e.getEntity() instanceof ServerPlayer p) || !p.isAlive() || p.isSpectator()) return;
        if (!(e.getStat().getValue() instanceof Item item) || !new ItemStack(item).has(DataComponents.FOOD)) return;
        if (!(p.containerMenu instanceof AbstractFurnaceMenu || p.containerMenu instanceof CraftingMenu || p.containerMenu instanceof InventoryMenu)) return;
        int amount = com.habits.trigger.Triggers.statDelta(e);
        if (amount > 0) cooked(p, item, amount);
    }

    /** Rolls each of {@code amount} fresh items and swaps the rated ones out of the player's plain stacks. */
    static void cooked(Player p, Item item, int amount) {
        int[] byTier = new int[Rating.Tier.values().length];
        for (int i = 0; i < amount; i++) byTier[Rating.rollFor(p, Category.FARMING).ordinal()]++;
        for (Rating.Tier t : Rating.Tier.values()) {
            int k = byTier[t.ordinal()];
            if (t == Rating.Tier.NORMAL || k == 0) continue;
            k = takePlain(p, item, k);
            if (k == 0) continue;
            ItemStack rated = new ItemStack(item, k);
            apply(p, rated, t);
            if (!p.getInventory().add(rated)) p.drop(rated, false);
            if (t.opts > 0) Rating.announce(p, rated, t);
        }
    }

    /** Removes up to {@code k} unrated {@code item}s, carried stack first. Returns how many it found. */
    static int takePlain(Player p, Item item, int k) {
        int left = k;
        ItemStack carried = p.containerMenu.getCarried();
        if (carried.is(item) && Rating.data(carried) == null) {
            int n = Math.min(left, carried.getCount());
            carried.shrink(n);
            left -= n;
        }
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(item) || Rating.data(s) != null) continue;
            int n = Math.min(left, s.getCount());
            s.shrink(n);
            left -= n;
        }
        return k - left;
    }

    public static void apply(Player p, ItemStack s, Rating.Tier t) {
        FoodProperties f = s.get(DataComponents.FOOD);
        if (f == null) return;
        int idx = t.ordinal();
        List<FoodProperties.PossibleEffect> effects = new ArrayList<>(f.effects());
        List<Holder<MobEffect>> pool = new ArrayList<>(List.of(BUFFS));
        int ticks = (int) (FarmCfg.DISH_BUFF_SECONDS.get() * 20 * t.opts);
        for (int i = 0; i < t.opts && !pool.isEmpty(); i++) {
            MobEffectInstance buff = new MobEffectInstance(pool.remove(p.getRandom().nextInt(pool.size())), ticks, 0);
            effects.add(new FoodProperties.PossibleEffect(() -> new MobEffectInstance(buff), 1f));
        }
        s.set(DataComponents.FOOD, new FoodProperties(f.nutrition() + idx,
                (float) (f.saturation() * (1 + FarmCfg.DISH_SATURATION.get() / 100 * idx)), f.canAlwaysEat(), f.eatSeconds(), f.usingConvertsTo(), effects));
        s.set(DataComponents.CUSTOM_NAME, s.getHoverName().copy().withStyle(st -> st.withColor(TextColor.fromRgb(t.color)).withItalic(false)));
        Rating.stamp(p, s, t, "food");
    }
}
