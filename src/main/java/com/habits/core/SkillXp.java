package com.habits.core;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skills level by doing them. The first matching action unlocks a skill at Lv1; every later one earns XP
 * ({@link HabitsApi#onTrigger}). XP is held here per player and written into the collection only on a level-up or every
 * {@link #FLUSH_TICKS} (a collection write re-syncs it and rebuilds every perk cache, far too often per block broken).
 * Pending XP is flushed on logout and before the server saves on stop.
 */
public final class SkillXp {
    private SkillXp() {}

    static final int FLUSH_TICKS = 200;
    private static final Map<UUID, Map<ResourceLocation, Integer>> PENDING = new ConcurrentHashMap<>();
    /** XP gained but not yet shown in the player's feed; sent every {@link #FEED_TICKS}. */
    private static final Map<UUID, Map<ResourceLocation, Integer>> UNSHOWN = new ConcurrentHashMap<>();
    static final int FEED_TICKS = 10;
    /** Fake players that learn from actions (GameTests only). Machines (deployers, quarries) never do. */
    public static final java.util.Set<UUID> TEST_PLAYERS = ConcurrentHashMap.newKeySet();

    static void init(IEventBus bus) {
        bus.addListener((ServerTickEvent.Post e) -> {
            if (e.getServer().getTickCount() % FEED_TICKS == 0) e.getServer().getPlayerList().getPlayers().forEach(SkillXp::showGains);
            if (e.getServer().getTickCount() % FLUSH_TICKS == 0) e.getServer().getPlayerList().getPlayers().forEach(SkillXp::flush);
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) flush(sp);
            PENDING.remove(e.getEntity().getUUID());
            UNSHOWN.remove(e.getEntity().getUUID());
        });
        bus.addListener((ServerStoppingEvent e) -> { // before the stop save, which runs ahead of the logout events
            e.getServer().getPlayerList().getPlayers().forEach(SkillXp::flush);
            PENDING.clear();
            UNSHOWN.clear();
            TEST_PLAYERS.clear();
        });
    }

    /** One matching action for {@code def}: unlocks it, or adds {@code xp} (fraction kept as a chance of one more).
     * Returns true when the skill unlocked or levelled up. */
    static boolean onAction(ServerPlayer p, SkillDef def, double xp) {
        SkillProgress cur = HabitsApi.collection(p).get(def.id());
        if (cur.maxed()) return false;
        if (cur.level() <= 0) return HabitsApi.addCard(p, def.id()) && HabitsApi.notifyGain(p, def, cur);
        int whole = (int) xp + (p.getRandom().nextDouble() < xp - (int) xp ? 1 : 0);
        if (whole <= 0) return false;
        Map<ResourceLocation, Integer> mine = PENDING.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
        int pending = mine.merge(def.id(), whole, Integer::sum);
        UNSHOWN.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).merge(def.id(), whole, Integer::sum);
        if (cur.progress() + pending < cur.needed(def.rarity())) return false;
        return flush(p) > 0;
    }

    /** Sends the batched XP gains as feed rows: gained amount plus the progress reached (pending included). */
    static void showGains(ServerPlayer p) {
        Map<ResourceLocation, Integer> shown = UNSHOWN.remove(p.getUUID());
        if (shown == null) return;
        for (var e : shown.entrySet()) {
            SkillDef def = SkillRegistry.get(e.getKey());
            if (def == null) continue;
            SkillProgress cur = HabitsApi.collection(p).get(def.id());
            if (cur.level() <= 0 || cur.maxed()) continue;
            int needed = cur.needed(def.rarity());
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new Payloads.XpGained(def.id(), e.getValue(), cur.level(),
                    Math.min(needed, cur.progress() + pending(p, def.id())), needed));
        }
    }

    /** XP gained but not yet written into the collection. */
    public static int pending(ServerPlayer p, ResourceLocation skill) {
        Map<ResourceLocation, Integer> mine = PENDING.get(p.getUUID());
        return mine == null ? 0 : mine.getOrDefault(skill, 0);
    }

    /** Writes pending XP into the collection in one update; level-ups get their feed row and sound. Returns how many
     * skills levelled. */
    public static int flush(ServerPlayer p) {
        Map<ResourceLocation, Integer> mine = PENDING.remove(p.getUUID());
        if (mine == null || mine.isEmpty()) return 0;
        HabitsCollection before = HabitsApi.collection(p), c = before;
        for (var e : mine.entrySet()) {
            SkillDef def = SkillRegistry.get(e.getKey());
            SkillProgress next = def == null ? null : Leveling.addXp(c.get(e.getKey()), def.rarity(), e.getValue());
            if (next != null) c = c.with(e.getKey(), next);
        }
        if (c == before) return 0;
        p.setData(CoreRegistries.COLLECTION, c);
        int levelled = 0;
        for (ResourceLocation id : mine.keySet()) {
            SkillDef def = SkillRegistry.get(id);
            if (def != null && c.get(id).level() > before.get(id).level() && HabitsApi.notifyGain(p, def, before.get(id))) levelled++;
        }
        return levelled;
    }

    /** {@code /habits rewardcard}: {@code core.questRewardXp} cards' worth of XP for {@code rarity} into a random unlocked,
     * not maxed skill of the player's picks; with none, a random skill of the picks unlocks instead. Returns the skill. */
    @Nullable
    public static SkillDef reward(ServerPlayer p, Rarity rarity) {
        flush(p);
        var pool = SkillRegistry.all().stream().filter(d -> d.title().isEmpty() && Specs.allows(p, d)).toList();
        var open = pool.stream().filter(d -> { SkillProgress s = HabitsApi.collection(p).get(d.id()); return s.level() > 0 && !s.maxed(); }).toList();
        var from = open.isEmpty() ? pool.stream().filter(d -> HabitsApi.collection(p).get(d.id()).level() == 0).toList() : open;
        if (from.isEmpty()) return null;
        SkillDef def = from.get(p.getRandom().nextInt(from.size()));
        SkillProgress before = HabitsApi.collection(p).get(def.id());
        if (before.level() == 0) {
            HabitsApi.addCard(p, def.id());
        } else {
            int xp = (int) Math.round(CoreInit.at(CoreInit.QUEST_REWARD_XP, rarity) * Leveling.XP_PER_CARD);
            SkillProgress next = Leveling.addXp(before, def.rarity(), xp);
            if (next == null) return null;
            p.setData(CoreRegistries.COLLECTION, HabitsApi.collection(p).with(def.id(), next));
        }
        HabitsApi.notifyGain(p, def, before);
        return def;
    }

    /** Once per player: skills now level by doing them, so card-era levels reset; socketed sigils go back to the pouch. */
    static void resetOnce(ServerPlayer p) {
        if (p.getData(CoreRegistries.XP_ERA)) return;
        p.setData(CoreRegistries.XP_ERA, true);
        if (HabitsApi.collection(p).skills().isEmpty()) return;
        com.habits.progression.ProgressionApi.refundSockets(p);
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY);
        p.setData(CoreRegistries.UNSEEN, java.util.List.of());
        p.displayClientMessage(Component.translatable("habits.msg.xp_reset"), false);
    }
}
