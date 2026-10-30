package com.habits.perk.exploration;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.IdMatch;
import com.habits.core.PerkMechanic;
import com.habits.core.PerkMechanics;
import com.habits.core.TargetKind;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Shared helpers for the exploration mechanics: the player gate, perk reads, level-scaled params, saved "seen" lists. */
final class X {
    private X() {}

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
        TEST_PLAYERS.clear();
    }

    /** Register {@code habits:exploration/<name>} (mechanic id = skill id). */
    static ResourceLocation mech(String name, String meaning) {
        return mech(name, meaning, null);
    }

    /** Same, with a server-side tick run once a second while the skill is active. */
    static ResourceLocation mech(String name, String meaning, @Nullable BiConsumer<ServerPlayer, HabitsApi.PerkHit> everySecond) {
        ResourceLocation id = Habits.id("exploration/" + name);
        PerkMechanics.register(id, new PerkMechanic() {
            @Override
            public String valueMeaning() {
                return meaning;
            }

            @Override
            public void tick(Player p, HabitsApi.PerkHit hit) {
                if (everySecond != null && p.tickCount % 20 == 0 && ok(p)) everySecond.accept((ServerPlayer) p, hit);
            }
        });
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

    static boolean has(HabitsApi.PerkHit h, String key) {
        return h.skill().perk().params().containsKey(key);
    }

    /** A level-scaled param: {@code "lo..hi"} runs linearly Lv1..Lv10, a single number is flat. */
    static double param(HabitsApi.PerkHit h, String key, double fallback) {
        String s = h.skill().perk().params().get(key);
        if (s == null) return fallback;
        try {
            int i = s.indexOf("..");
            if (i < 0) return Double.parseDouble(s.trim());
            double lo = Double.parseDouble(s.substring(0, i).trim()), hi = Double.parseDouble(s.substring(i + 2).trim());
            return lo + (hi - lo) * (Math.min(10, Math.max(1, h.level())) - 1) / 9.0;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String str(HabitsApi.PerkHit h, String key, String fallback) {
        return h.skill().perk().param(key, fallback);
    }

    /** Param IdMatch test; a missing param matches everything. */
    static boolean matches(HabitsApi.PerkHit h, String key, @Nullable ResourceLocation id, TargetKind kind, Player p) {
        String s = h.skill().perk().params().get(key);
        return s == null || id != null && IdMatch.parse(s).test(id, kind, p.level().registryAccess());
    }

    /** Roll a percent chance (0..100). */
    static boolean roll(Player p, double pct) {
        return pct > 0 && p.getRandom().nextDouble() * 100 < pct;
    }

    static void fire(Player p, String event, @Nullable Vec3 pos) {
        TriggerContext c = TriggerContext.of(Habits.id(event));
        Triggers.fire(p, TriggerType.CUSTOM, pos == null ? c : c.withPos(pos));
    }

    static void give(Player p, ItemStack s) {
        if (!s.isEmpty() && !p.getInventory().add(s)) p.drop(s, false);
    }

    /** Adds {@code entry} to the player's saved list {@code key} (kept across death, oldest dropped past {@code cap}).
     * True when it was not there yet. */
    static boolean remember(Player p, String key, String entry, int cap) {
        CompoundTag pd = p.getPersistentData();
        if (!pd.contains(Player.PERSISTED_NBT_TAG)) pd.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        CompoundTag root = pd.getCompound(Player.PERSISTED_NBT_TAG);
        ListTag list = root.getList(key, Tag.TAG_STRING);
        StringTag tag = StringTag.valueOf(entry);
        if (list.contains(tag)) return false;
        list.add(tag);
        while (list.size() > cap) list.remove(0);
        root.put(key, list);
        return true;
    }
}
