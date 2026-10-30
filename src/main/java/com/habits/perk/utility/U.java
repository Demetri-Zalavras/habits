package com.habits.perk.utility;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.PerkMechanics;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared helpers for the utility mechanics: the player gate, perk reads, level-scaled params, inventory moves. */
final class U {
    private U() {}

    /** Fake players allowed through the gate (GameTests only). */
    static final Set<UUID> TEST_PLAYERS = ConcurrentHashMap.newKeySet();
    /** Every per-player map in this package; emptied of a player on logout and fully on server stop. */
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

    /** Register a mechanic whose id is the skill id; returns the id. */
    static ResourceLocation mech(String name, String meaning) {
        ResourceLocation id = Habits.id(name);
        PerkMechanics.register(id, () -> meaning);
        return id;
    }

    /** Server player, alive, not spectating, not a fake player (except registered test players). */
    static boolean ok(@Nullable Player p) {
        return p instanceof ServerPlayer sp && sp.isAlive() && !sp.isSpectator()
                && (!(sp instanceof FakePlayer) || TEST_PLAYERS.contains(sp.getUUID()));
    }

    @Nullable
    static HabitsApi.PerkHit hit(Player p, ResourceLocation mech) {
        return ok(p) ? HabitsApi.mechanicHit(p, mech) : null;
    }

    /** A tier skill's tier from its unboosted value, so Potent can't unlock the next tier early. */
    static int tier(HabitsApi.PerkHit h) {
        var raw = com.habits.core.SkillRegistry.get(h.skill().id());
        return (int) Math.min(h.value(), raw == null ? h.value() : raw.perkValue(h.level()));
    }

    static double v(Player p, ResourceLocation mech) {
        HabitsApi.PerkHit h = hit(p, mech);
        return h == null ? 0 : h.value();
    }

    /** A level-scaled param from the skill JSON: {@code "lo..hi"} runs linearly Lv1..Lv10, a single number is flat. */
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

    /** Roll a percent chance (0..100). */
    static boolean roll(Player p, double pct) {
        return pct > 0 && p.getRandom().nextDouble() * 100 < pct;
    }

    static void fire(Player p, String event) {
        Triggers.fire(p, TriggerType.CUSTOM, TriggerContext.of(Habits.id(event)));
    }

    static void fire(Player p, String event, Vec3 pos) {
        Triggers.fire(p, TriggerType.CUSTOM, TriggerContext.of(Habits.id(event)).withPos(pos));
    }

    static void give(Player p, ItemStack s) {
        if (s.isEmpty()) return;
        if (!p.getInventory().add(s) && !s.isEmpty()) p.drop(s, false);
    }

    /** Effect refresher: no particles, short duration so it ends soon after the perk stops. */
    static void effect(LivingEntity e, Holder<MobEffect> eff, int amp, int ticks) {
        MobEffectInstance cur = e.getEffect(eff);
        if (cur != null && (cur.getAmplifier() > amp || cur.getDuration() > ticks / 2 && cur.getAmplifier() == amp)) return;
        e.addEffect(new MobEffectInstance(eff, ticks, amp, true, false, true));
    }

    /** Add (amount != 0) or remove (amount == 0) a transient attribute modifier. */
    static void attr(LivingEntity e, Holder<Attribute> a, ResourceLocation id, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst == null) return;
        if (amount == 0) {
            if (inst.hasModifier(id)) inst.removeModifier(id);
            return;
        }
        AttributeModifier cur = inst.getModifier(id);
        if (cur != null && cur.amount() == amount && cur.operation() == op) return;
        inst.addOrUpdateTransientModifier(new AttributeModifier(id, amount, op));
    }

    /** Move as much of {@code s} as fits into the container (merging first, then empty slots). Shrinks {@code s}. */
    static void insert(Container c, ItemStack s, boolean onlyMerge) {
        for (int pass = 0; pass < (onlyMerge ? 1 : 2) && !s.isEmpty(); pass++) {
            for (int i = 0; i < c.getContainerSize() && !s.isEmpty(); i++) {
                ItemStack t = c.getItem(i);
                if (!c.canPlaceItem(i, s)) continue;
                int max = Math.min(c.getMaxStackSize(s), s.getMaxStackSize());
                if (pass == 0 && !t.isEmpty() && ItemStack.isSameItemSameComponents(t, s) && t.getCount() < max) {
                    int n = Math.min(max - t.getCount(), s.getCount());
                    t.grow(n);
                    s.shrink(n);
                    c.setChanged();
                } else if (pass == 1 && t.isEmpty()) {
                    c.setItem(i, s.split(Math.min(max, s.getCount())));
                }
            }
        }
    }

    /** True if a stack carries a non-empty container or bundle (nesting guard). */
    static boolean holdsItems(ItemStack s) {
        ItemContainerContents c = s.get(DataComponents.CONTAINER);
        if (c != null && c.nonEmptyItems().iterator().hasNext()) return true;
        var b = s.get(DataComponents.BUNDLE_CONTENTS);
        return b != null && !b.isEmpty();
    }

    /** Read-only block access that never loads a chunk. */
    static boolean loaded(Level level, BlockPos pos) {
        return level.isLoaded(pos);
    }

    /** Integer part of {@code amount} plus a random carry of the fraction. */
    static int carry(Player p, double amount) {
        int n = (int) amount;
        return n + (p.getRandom().nextDouble() < amount - n ? 1 : 0);
    }
}
