package com.habits.perk.utility;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.InstrumentItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.NoteBlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Jukebox, bell, note block and goat horn perks. */
final class Music {
    private Music() {}

    static final ResourceLocation DJ = U.mech("dj", "buff radius in blocks");
    static final ResourceLocation BELL = U.mech("bellringer", "glow radius in blocks");
    static final ResourceLocation CRIER = U.mech("town_crier", "rally radius in blocks");
    static final ResourceLocation COMPOSER = U.mech("composer", "slow radius in blocks");
    static final ResourceLocation PITCH = U.mech("perfect_pitch", "health healed per note");
    static final ResourceLocation EAR = U.mech("collectors_ear", "% chance creepers drop a disc");
    static final ResourceLocation GOAT = U.mech("goat_simulator", "horn effect radius in blocks");

    static void init() {}

    // ------------------------------------------------------------------ effect tables ("key=effect,key=effect" params)

    private static final Map<String, Map<String, Holder<MobEffect>>> TABLES = new ConcurrentHashMap<>();

    static Map<String, Holder<MobEffect>> table(String spec) {
        return TABLES.computeIfAbsent(spec, s -> {
            Map<String, Holder<MobEffect>> m = new HashMap<>();
            for (String part : s.split(",")) {
                String[] kv = part.trim().split("=");
                if (kv.length != 2) continue;
                ResourceLocation id = ResourceLocation.tryParse(kv[1].trim());
                if (id != null) BuiltInRegistries.MOB_EFFECT.getHolder(id).ifPresent(h -> m.put(kv[0].trim(), h));
            }
            return m;
        });
    }

    static <T extends LivingEntity> List<T> around(Level level, Class<T> type, net.minecraft.world.phys.Vec3 at, double r) {
        return level.getEntitiesOfClass(type, new AABB(at, at).inflate(r), e -> e.isAlive() && e.distanceToSqr(at) <= r * r);
    }

    /** Players within {@code r} of {@code at}, always counting the perk owner when in range. */
    static List<Player> players(Player owner, net.minecraft.world.phys.Vec3 at, double r) {
        List<Player> out = new java.util.ArrayList<>(around(owner.level(), Player.class, at, r));
        if (!out.contains(owner) && owner.distanceToSqr(at) <= r * r) out.add(owner);
        out.removeIf(Player::isSpectator);
        return out;
    }

    // ------------------------------------------------------------------ DJ: jukeboxes you started buff everyone near

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.tickCount % 20 != 0 || !U.ok(p)) return;
        HabitsApi.PerkHit h = U.hit(p, DJ);
        if (h != null) dj(p, h);
    }

    static void dj(Player p, HabitsApi.PerkHit h) {
        Map<String, Holder<MobEffect>> songs = table(U.str(h, "songs", ""));
        int amp = (int) U.param(h, "amp", 0, 1);
        for (JukeboxBlockEntity box : Stations.used(p, JukeboxBlockEntity.class)) {
            if (!box.getSongPlayer().isPlaying()) continue;
            var song = JukeboxSong.fromStack(p.registryAccess(), box.getTheItem()).flatMap(Holder::unwrapKey);
            if (song.isEmpty()) continue;
            Holder<MobEffect> eff = songs.getOrDefault(song.get().location().getPath(), songs.get("default"));
            if (eff == null) continue;
            for (Player near : players(p, box.getBlockPos().getCenter(), h.value())) U.effect(near, eff, amp, 100);
        }
    }

    // ------------------------------------------------------------------ clicks: bells, jukeboxes, note blocks

    private static final Map<UUID, Long> BELL_CD = U.playerMap();
    /** Who last hit each note block (note block events carry no player). Pruned by age; cleared on server stop. */
    static final Map<GlobalPos, long[]> NOTE_CLICK = new ConcurrentHashMap<>();
    private static final Map<GlobalPos, UUID> NOTE_WHO = new ConcurrentHashMap<>();

    @SubscribeEvent
    static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        clicked(e.getEntity(), e.getPos(), e.getItemStack());
    }

    @SubscribeEvent
    static void onLeftClick(PlayerInteractEvent.LeftClickBlock e) {
        Player p = e.getEntity();
        if (U.ok(p) && p.level().getBlockState(e.getPos()).is(Blocks.NOTE_BLOCK)) noteClick(p, e.getPos());
    }

    static void clicked(Player p, BlockPos pos, ItemStack held) {
        if (!U.ok(p)) return;
        Level level = p.level();
        var state = level.getBlockState(pos);
        if (state.is(Blocks.BELL)) ringBell(p, pos);
        else if (state.is(Blocks.NOTE_BLOCK)) noteClick(p, pos);
        else if (state.is(Blocks.JUKEBOX) && held.has(DataComponents.JUKEBOX_PLAYABLE)
                && level.getBlockEntity(pos) instanceof JukeboxBlockEntity box && box.getTheItem().isEmpty()) {
            Stations.used(p, pos);
            HabitsApi.PerkHit h = U.hit(p, EAR);
            if (h != null) pingChest(p, (int) U.param(h, "ping", 16, 96));
        }
    }

    static void ringBell(Player p, BlockPos pos) {
        long now = p.level().getGameTime();
        if (p.level() instanceof ServerLevel sl && sl.getRaidAt(pos) != null) U.fire(p, "bell_in_raid", pos.getCenter());
        HabitsApi.PerkHit bell = U.hit(p, BELL), crier = U.hit(p, CRIER);
        if (bell == null && crier == null) return;
        if (now < BELL_CD.getOrDefault(p.getUUID(), 0L)) return;
        BELL_CD.put(p.getUUID(), now + 100);
        var at = pos.getCenter();
        if (bell != null) {
            int glow = (int) (U.param(bell, "glow", 3, 10) * 20);
            boolean slow = bell.level() >= U.param(bell, "slow_from", 10, 10);
            for (LivingEntity m : around(p.level(), LivingEntity.class, at, bell.value())) {
                if (!(m instanceof Enemy)) continue;
                m.addEffect(new MobEffectInstance(MobEffects.GLOWING, glow, 0));
                if (slow) m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 3));
            }
        }
        if (crier != null) {
            int dur = (int) (U.param(crier, "seconds", 30, 60) * 20);
            int str = (int) U.param(crier, "strength", 0, 2);
            boolean regen = crier.level() >= U.param(crier, "regen_from", 10, 10);
            for (IronGolem g : around(p.level(), IronGolem.class, at, crier.value())) {
                g.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, dur, str));
                g.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, dur, 0));
                if (regen) g.addEffect(new MobEffectInstance(MobEffects.REGENERATION, dur, 0));
            }
            for (AbstractVillager v : around(p.level(), AbstractVillager.class, at, crier.value())) {
                v.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, dur, 0));
            }
        }
    }

    static void noteClick(Player p, BlockPos pos) {
        GlobalPos g = GlobalPos.of(p.level().dimension(), pos.immutable());
        NOTE_CLICK.put(g, new long[]{p.level().getGameTime()});
        NOTE_WHO.put(g, p.getUUID());
        if (NOTE_CLICK.size() > 256) {
            long now = p.level().getGameTime();
            NOTE_CLICK.entrySet().removeIf(en -> now - en.getValue()[0] > 40);
            NOTE_WHO.keySet().retainAll(NOTE_CLICK.keySet());
        }
    }

    private static final Map<UUID, Long> HEAL_CD = U.playerMap();

    @SubscribeEvent
    static void onNote(NoteBlockEvent.Play e) {
        if (!(e.getLevel() instanceof Level level) || level.isClientSide) return;
        GlobalPos g = GlobalPos.of(level.dimension(), e.getPos());
        long[] t = NOTE_CLICK.get(g);
        UUID who = NOTE_WHO.get(g);
        if (t == null || who == null || level.getGameTime() - t[0] > 20) return;
        Player p = level.getPlayerByUUID(who);
        if (p == null) p = TEST_PLAYER != null && TEST_PLAYER.getUUID().equals(who) ? TEST_PLAYER : null;
        if (p != null) played(p, e.getPos());
    }

    @Nullable
    static Player TEST_PLAYER;

    static void played(Player p, BlockPos pos) {
        var at = pos.getCenter();
        HabitsApi.PerkHit h = U.hit(p, COMPOSER);
        if (h != null) {
            int amp = (int) U.param(h, "slow_amp", 0, 2);
            int dur = (int) (U.param(h, "seconds", 2, 4) * 20);
            boolean weak = h.level() >= U.param(h, "weak_from", 10, 10);
            for (LivingEntity m : around(p.level(), LivingEntity.class, at, h.value())) {
                if (!(m instanceof Enemy)) continue;
                m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, dur, amp));
                if (weak) m.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, dur, 0));
            }
        }
        h = U.hit(p, PITCH);
        if (h != null) {
            long now = p.level().getGameTime();
            for (Player near : players(p, at, U.param(h, "radius", 8, 8))) {
                // one heal per second per target, tracked on the target
                if (now < HEAL_CD.getOrDefault(near.getUUID(), 0L)) continue;
                HEAL_CD.put(near.getUUID(), now + 20);
                near.heal((float) h.value());
            }
        }
    }

    // ------------------------------------------------------------------ collector's ear

    @SubscribeEvent
    static void onDrops(LivingDropsEvent e) {
        if (!(e.getEntity() instanceof Creeper c) || !(e.getSource().getEntity() instanceof Player p)) return;
        HabitsApi.PerkHit h = U.hit(p, EAR);
        if (h == null || !U.roll(p, h.value())) return;
        for (ItemEntity d : e.getDrops()) if (d.getItem().is(ItemTags.CREEPER_DROP_MUSIC_DISCS)) return; // already got one
        BuiltInRegistries.ITEM.getRandomElementOf(ItemTags.CREEPER_DROP_MUSIC_DISCS, p.getRandom()).ifPresent(disc ->
                e.getDrops().add(new ItemEntity(c.level(), c.getX(), c.getY(), c.getZ(), new ItemStack(disc))));
    }

    /** Nearest unopened loot chest within {@code radius}, loaded chunks only; on demand (disc inserted). */
    @Nullable
    static BlockPos pingChest(Player p, int radius) {
        BlockPos c = p.blockPosition();
        BlockPos best = null;
        double bestD = (double) radius * radius;
        for (int cx = (c.getX() - radius) >> 4; cx <= (c.getX() + radius) >> 4; cx++) {
            for (int cz = (c.getZ() - radius) >> 4; cz <= (c.getZ() + radius) >> 4; cz++) {
                LevelChunk ch = p.level().getChunkSource().getChunkNow(cx, cz);
                if (ch == null) continue;
                for (BlockEntity be : ch.getBlockEntities().values()) {
                    if (be instanceof RandomizableContainerBlockEntity r && r.getLootTable() != null) {
                        double d = be.getBlockPos().distSqr(c);
                        if (d < bestD) {
                            bestD = d;
                            best = be.getBlockPos();
                        }
                    }
                }
            }
        }
        if (best != null) {
            int dx = best.getX() - c.getX(), dz = best.getZ() - c.getZ();
            String dir = (dz < -Math.abs(dx) / 2 ? "n" : dz > Math.abs(dx) / 2 ? "s" : "") + (dx > Math.abs(dz) / 2 ? "e" : dx < -Math.abs(dz) / 2 ? "w" : "");
            if (dir.isEmpty()) dir = "here";
            int dist = (int) Math.sqrt(bestD);
            p.displayClientMessage(Component.translatable(dist == 1 ? "habits.utility.chest_ping.one" : "habits.utility.chest_ping", dist,
                    Component.translatable("habits.utility.dir." + dir)), true);
        } else {
            p.displayClientMessage(Component.translatable("habits.utility.chest_ping_none"), true);
        }
        return best;
    }

    // ------------------------------------------------------------------ goat horns

    @SubscribeEvent
    static void onHorn(PlayerInteractEvent.RightClickItem e) {
        Player p = e.getEntity();
        ItemStack s = e.getItemStack();
        if (!(s.getItem() instanceof InstrumentItem) || p.getCooldowns().isOnCooldown(s.getItem())) return;
        HabitsApi.PerkHit h = U.hit(p, GOAT);
        if (h != null) horn(p, s, h);
    }

    static boolean horn(Player p, ItemStack s, HabitsApi.PerkHit h) {
        var key = s.get(DataComponents.INSTRUMENT);
        if (key == null || key.unwrapKey().isEmpty()) return false;
        Holder<MobEffect> eff = table(U.str(h, "horns", "")).get(key.unwrapKey().get().location().getPath());
        if (eff == null) return false;
        int dur = (int) (U.param(h, "seconds", 30, 120) * 20);
        int amp = (int) U.param(h, "amp", 0, 1);
        if (eff.equals(MobEffects.GLOWING)) { // the seek horn lights up hostiles instead of friends
            for (LivingEntity m : around(p.level(), LivingEntity.class, p.position(), h.value())) {
                if (m instanceof Enemy) m.addEffect(new MobEffectInstance(eff, dur, 0));
            }
            return true;
        }
        for (Player near : players(p, p.position(), h.value())) near.addEffect(new MobEffectInstance(eff, dur, amp));
        return true;
    }
}
