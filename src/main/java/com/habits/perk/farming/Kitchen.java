package com.habits.perk.farming;

import com.habits.core.HabitsApi.PerkHit;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Spec batch: food from the farm. */
public final class Kitchen {
    private Kitchen() {}

    static PerkHit hit(Player p, String skill) {
        return Fields.hit(p, skill);
    }

    static boolean stew(ItemStack s) {
        return s.is(Items.MUSHROOM_STEW) || s.is(Items.BEETROOT_SOUP) || s.is(Items.RABBIT_STEW) || s.is(Items.SUSPICIOUS_STEW);
    }

    @SubscribeEvent
    public static void onEaten(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel l) || !Farm.ok(p)) return;
        ItemStack ate = e.getItem();
        PerkHit h;
        if (ate.is(Items.MILK_BUCKET)) {
            Farm.fire(p, "farming/drink_milk", null);
            if ((h = hit(p, "fresh_milk")) != null) p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, (int) (h.value() * 20), 0));
            return;
        }
        if (!ate.has(DataComponents.FOOD)) return;
        if ((h = hit(p, "hearth_meal")) != null) { // Hearthcook: every meal is a buff
            int t = (int) (h.value() * 20);
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, t, (int) Farm.lerp(h, "regen", 0, 1)));
            p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, t, (int) Farm.lerp(h, "strength", 0, 1)));
        }
        if (ate.is(Items.HONEY_BOTTLE) && (h = hit(p, "royal_jelly")) != null) {
            int t = (int) (h.value() * 20);
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, t, 1));
            p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, t, 1));
        }
        if ((ate.is(Items.MELON_SLICE) || ate.is(Items.GLISTERING_MELON_SLICE)) && (h = hit(p, "melon_medic")) != null) p.heal((float) h.value());
        if (stew(ate) && (h = hit(p, "hearty_meal")) != null)
            p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, (int) (Farm.lerp(h, "seconds", 30, 120) * 20), Math.max(0, (int) h.value() / 2 - 1)));
        if (ate.is(Items.GLOW_BERRIES) && (h = hit(p, "glow_snack")) != null) p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, (int) (h.value() * 20), 0));
        if (ate.is(Items.COOKIE) && (h = hit(p, "cocoa_rush")) != null) p.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, (int) (h.value() * 20), 0));
        if ((h = hit(p, "cornucopia")) != null && Farm.cooldown(p, "habits_cornucopia", h.value() * 60)) {
            var f = p.getFoodData();
            f.setFoodLevel(20);
            f.setSaturation(20);
        }
        if ((h = hit(p, "leftovers")) != null && Farm.roll(l, h.value())) {
            ItemStack left = e.getResultStack();
            if (left.isEmpty()) e.setResultStack(ate.copyWithCount(1));
            else if (ItemStack.isSameItemSameComponents(left, ate)) left.grow(1);
            // a bowl or bottle left behind: the meal is gone, nothing to give back
        }
    }

    /** Chef's Special: a food craft sometimes comes out double. */
    @SubscribeEvent
    public static void onCraft(PlayerEvent.ItemCraftedEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Farm.ok(p)) return;
        ItemStack out = e.getCrafting();
        PerkHit h;
        if (!out.isEmpty() && out.has(DataComponents.FOOD) && (h = hit(p, "chefs_special")) != null && Farm.roll(p.level(), h.value()))
            Farm.give(p, out.copy());
    }
}
