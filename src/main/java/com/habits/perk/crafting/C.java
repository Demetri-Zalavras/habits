package com.habits.perk.crafting;

import com.habits.Habits;
import com.habits.core.CardItems;
import com.habits.core.HabitsApi;
import com.habits.core.IdMatch;
import com.habits.core.PerkMechanics;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared helpers for the crafting spec mechanics: the player gate, perk reads, level-scaled params, item moves. */
final class C {
    private C() {}

    /** Fake players allowed through the gate (GameTests only). */
    static final Set<UUID> TEST_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final List<Map<UUID, ?>> PLAYER_MAPS = new ArrayList<>();

    static <V> Map<UUID, V> playerMap() {
        Map<UUID, V> m = new ConcurrentHashMap<>();
        PLAYER_MAPS.add(m);
        return m;
    }

    static void forget(UUID id) {
        for (Map<UUID, ?> m : PLAYER_MAPS) m.remove(id);
    }

    static void forgetAll() {
        for (Map<UUID, ?> m : PLAYER_MAPS) m.clear();
    }

    /** Register mechanic {@code habits:crafting/<name>}; the id equals the skill id. */
    static ResourceLocation mech(String name, String meaning) {
        ResourceLocation id = Habits.id("crafting/" + name);
        PerkMechanics.register(id, () -> meaning);
        return id;
    }

    /** Server player, alive, not spectating, not a fake player (except registered test players). */
    static boolean ok(@Nullable Player p) {
        return p instanceof ServerPlayer sp && sp.isAlive() && !sp.isSpectator()
                && (!(sp instanceof FakePlayer) || TEST_PLAYERS.contains(sp.getUUID()));
    }

    @Nullable
    static HabitsApi.PerkHit hit(@Nullable Player p, ResourceLocation mech) {
        return ok(p) ? HabitsApi.mechanicHit(p, mech) : null;
    }

    /** Both sides (client prediction, e.g. use duration): alive and not spectating. */
    @Nullable
    static HabitsApi.PerkHit hitAnySide(Player p, ResourceLocation mech) {
        if (!p.isAlive() || p.isSpectator() || p instanceof FakePlayer && !TEST_PLAYERS.contains(p.getUUID())) return null;
        return HabitsApi.mechanicHit(p, mech);
    }

    /** A level-scaled param: {@code "lo..hi"} runs linearly Lv1..Lv10, a single number is flat. */
    static double param(HabitsApi.PerkHit h, String key, double lo, double hi) {
        String s = h.skill().perk().params().get(key);
        if (s != null) {
            try {
                int i = s.indexOf("..");
                if (i < 0) return Double.parseDouble(s.trim());
                lo = Double.parseDouble(s.substring(0, i).trim());
                hi = Double.parseDouble(s.substring(i + 2).trim());
            } catch (NumberFormatException ignored) {}
        }
        return lo + (hi - lo) * (Math.min(10, Math.max(1, h.level())) - 1) / 9.0;
    }

    static String str(HabitsApi.PerkHit h, String key, String fallback) {
        return h.skill().perk().param(key, fallback);
    }

    /** Does the item pass the skill's IdMatch param (missing param = {@code fallback})? */
    static boolean matches(Player p, HabitsApi.PerkHit h, String key, String fallback, ItemStack s) {
        return IdMatch.parse(str(h, key, fallback)).test(BuiltInRegistries.ITEM.getKey(s.getItem()), Registries.ITEM, p.registryAccess());
    }

    /** {@code a=b,c=d} item table from a param; unknown ids are skipped. */
    static Map<Item, Item> table(HabitsApi.PerkHit h, String key, String fallback) {
        Map<Item, Item> m = new HashMap<>();
        for (String pair : str(h, key, fallback).split(",")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            ResourceLocation a = ResourceLocation.tryParse(pair.substring(0, i).trim());
            ResourceLocation b = ResourceLocation.tryParse(pair.substring(i + 1).trim());
            if (a == null || b == null || !BuiltInRegistries.ITEM.containsKey(a) || !BuiltInRegistries.ITEM.containsKey(b)) continue;
            m.put(BuiltInRegistries.ITEM.get(a), BuiltInRegistries.ITEM.get(b));
        }
        return m;
    }

    /** Roll a percent chance (0..100). */
    static boolean roll(Player p, double pct) {
        return pct > 0 && p.getRandom().nextDouble() * 100 < pct;
    }

    /** Integer part of {@code amount} plus a random carry of the fraction. */
    static int carry(Player p, double amount) {
        int n = (int) amount;
        return n + (p.getRandom().nextDouble() < amount - n ? 1 : 0);
    }

    static void give(Player p, ItemStack s) {
        if (s.isEmpty()) return;
        if (!p.getInventory().add(s) && !s.isEmpty()) p.drop(s, false);
    }

    /** Effect refresher: no particles. */
    static void effect(LivingEntity e, Holder<MobEffect> eff, int amp, int ticks) {
        MobEffectInstance cur = e.getEffect(eff);
        if (cur != null && (cur.getAmplifier() > amp || cur.getDuration() > ticks && cur.getAmplifier() == amp)) return;
        e.addEffect(new MobEffectInstance(eff, ticks, amp, true, false, true));
    }

    /** True if a stack carries a non-empty container or bundle, or is a card (never multiply those). */
    static boolean special(ItemStack s) {
        var c = s.get(DataComponents.CONTAINER);
        if (c != null && c.nonEmptyItems().iterator().hasNext()) return true;
        var b = s.get(DataComponents.BUNDLE_CONTENTS);
        return b != null && !b.isEmpty() || CardItems.dataOf(s) != null;
    }

    /**
     * The stack a craft or take actually put in the player's hands. A normal click hands over the event stack itself
     * (now carried); a shift-click reports a copy after the real stack moved, so take an identical inventory stack of
     * the same count (a merged, bigger stack is refused so a bonus never spreads over items made earlier). Null if none.
     */
    @Nullable
    static ItemStack taken(Player p, ItemStack event) {
        if (p.containerMenu.getCarried() == event) return event;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != event && s.getCount() == event.getCount() && ItemStack.isSameItemSameComponents(s, event)) return s;
        }
        return null;
    }

    /** A per-player cooldown in ticks: true (and restarted) when ready. */
    static boolean ready(Map<UUID, Long> cd, Player p, double seconds) {
        long now = p.level().getGameTime();
        Long until = cd.get(p.getUUID());
        if (until != null && now < until) return false;
        cd.put(p.getUUID(), now + (long) (seconds * 20));
        return true;
    }
}
