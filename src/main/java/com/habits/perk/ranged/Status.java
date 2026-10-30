package com.habits.perk.ranged;

import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Short-lived state: per-player streaks and windows, mob statuses (pinned, grounded, calm, wet), owner-only glow, wind
 * vortices. Every map is small, pruned as entries expire, cleared on logout (players) and server stop (everything).
 */
public final class Status {
    private Status() {}

    /** Per-player ranged state. */
    static final class P {
        int streak;
        int quickReady;
        long lastHitTick = -1000;
        long volleyTick = -1;
        int volleyHits;
        int starShots; // Falling Star: full-draw shots since the last star
        int sonicShots; // Sonic Shot: full-draw shots since the last beam
        long smokeReadyAt;
        int foliageTicks;
        long nearHostileAt = -1000;
        long rainReadyAt;
        long lastBlastTick = -1000;
        long noFallUntil;
        double noFallCut;
        final List<Glow> glows = new ArrayList<>();
    }

    record Glow(Entity e, long until, boolean mark) {}

    record Calm(UUID player, long until) {}

    static final class Vortex {
        final ServerLevel level;
        final Vec3 at;
        final long until;
        final Runnable boom;

        Vortex(ServerLevel level, Vec3 at, long until, Runnable boom) {
            this.level = level;
            this.at = at;
            this.until = until;
            this.boom = boom;
        }
    }

    static final Map<UUID, P> PLAYERS = new HashMap<>();
    static final Map<Mob, Long> PINNED = new HashMap<>();
    static final Map<Mob, Long> GROUNDED = new HashMap<>();
    static final Map<Mob, Calm> CALM = new HashMap<>();
    static final Map<Entity, Long> WET = new HashMap<>();
    static final List<Vortex> VORTICES = new ArrayList<>();
    /** Global cap: a vortex scans one small box every other tick, so a handful costs nothing. */
    static final int MAX_VORTICES = 8;
    static final int GLOW_CAP = 24;

    static P of(ServerPlayer p) {
        return PLAYERS.computeIfAbsent(p.getUUID(), u -> new P());
    }

    static long now(Level level) {
        return level.getGameTime();
    }

    static void pin(Mob m, long until) {
        PINNED.merge(m, until, Math::max);
        m.setDeltaMovement(0, Math.min(0, m.getDeltaMovement().y), 0);
        m.getNavigation().stop();
    }

    static boolean pinned(Entity e) {
        Long t = e instanceof Mob m ? PINNED.get(m) : null;
        return t != null && t > now(e.level());
    }

    static boolean wet(Entity e) {
        Long t = WET.get(e);
        return e.isInWaterOrRain() || t != null && t > now(e.level());
    }

    /** Glow {@code e} for {@code owner} only; {@code mark} also gives the owner's damage bonus. Oldest mark drops past the cap. */
    static void glow(ServerPlayer owner, Entity e, long until, boolean mark, int markCap) {
        P st = of(owner);
        st.glows.removeIf(g -> g.e == e && g.mark == mark);
        if (mark) {
            int marks = 0;
            for (Glow g : st.glows) if (g.mark) marks++;
            for (Iterator<Glow> it = st.glows.iterator(); it.hasNext() && marks >= markCap; ) {
                Glow g = it.next();
                if (g.mark) { it.remove(); marks--; sendGlow(owner, g.e, false); }
            }
        }
        if (st.glows.size() >= GLOW_CAP) sendGlow(owner, st.glows.remove(0).e, false);
        st.glows.add(new Glow(e, until, mark));
        sendGlow(owner, e, true);
    }

    static boolean markedBy(ServerPlayer owner, Entity e) {
        P st = PLAYERS.get(owner.getUUID());
        if (st == null) return false;
        long t = now(e.level());
        for (Glow g : st.glows) if (g.mark && g.e == e && g.until > t) return true;
        return false;
    }

    /** The shared-flags byte as the viewer would see it, with the glow bit forced on or off. Sent to one player only. */
    static void sendGlow(ServerPlayer to, Entity e, boolean on) {
        if (e.isRemoved() || to.level() != e.level()) return;
        byte f = 0;
        if (e.isOnFire()) f |= 1;
        if (e.isShiftKeyDown()) f |= 2;
        if (e.isSprinting()) f |= 8;
        if (e.isSwimming()) f |= 16;
        if (e.isInvisible()) f |= 32;
        if (on || e.hasGlowingTag()) f |= 64;
        if (e instanceof LivingEntity l && l.isFallFlying()) f |= (byte) 128;
        to.connection.send(new ClientboundSetEntityDataPacket(e.getId(),
                List.of(new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, f))));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        tick(e.getServer().overworld().getGameTime(), e.getServer());
    }

    static void tick(long t, net.minecraft.server.MinecraftServer server) {
        if (!PINNED.isEmpty()) PINNED.entrySet().removeIf(en -> {
            Mob m = en.getKey();
            if (m.isRemoved() || !m.isAlive() || en.getValue() <= now(m.level())) return true;
            m.setDeltaMovement(0, Math.min(0, m.getDeltaMovement().y), 0);
            m.setJumping(false);
            return false;
        });
        if (!GROUNDED.isEmpty()) GROUNDED.entrySet().removeIf(en -> {
            Mob m = en.getKey();
            if (m.isRemoved() || !m.isAlive() || en.getValue() <= now(m.level())) return true;
            Vec3 v = m.getDeltaMovement();
            m.setDeltaMovement(v.x * 0.6, Math.min(v.y, -0.35), v.z * 0.6);
            m.hurtMarked = true;
            return false;
        });
        for (Iterator<Vortex> it = VORTICES.iterator(); it.hasNext(); ) {
            Vortex v = it.next();
            long lt = now(v.level);
            if (lt >= v.until) {
                it.remove();
                v.boom.run();
            } else if ((lt & 1) == 0) {
                for (Mob m : v.level.getEntitiesOfClass(Mob.class, new AABB(v.at, v.at).inflate(6), R::hostile)) {
                    Vec3 d = v.at.subtract(m.position());
                    double len = d.length();
                    if (len > 0.5) m.setDeltaMovement(m.getDeltaMovement().scale(0.5).add(d.scale(0.25 / len)));
                    m.hurtMarked = true;
                }
                R.puff(v.level, net.minecraft.core.particles.ParticleTypes.GUST, v.at, 2, 1.5);
            }
        }
        if (t % 10 != 0) return;
        if (!CALM.isEmpty()) CALM.entrySet().removeIf(en -> en.getKey().isRemoved() || en.getValue().until <= now(en.getKey().level()));
        if (!WET.isEmpty()) WET.entrySet().removeIf(en -> en.getKey().isRemoved() || en.getValue() <= now(en.getKey().level()));
        for (var en : PLAYERS.entrySet()) {
            if (en.getValue().glows.isEmpty()) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null) continue;
            long pt = now(p.level());
            en.getValue().glows.removeIf(g -> {
                boolean dead = g.e.isRemoved() || g.until <= pt;
                // vanilla resends the flags byte whenever any flag changes, so re-assert the glow every half second
                sendGlow(p, g.e, !dead);
                return dead;
            });
        }
    }

    /** Silent Archer: calm mobs won't pick the archer as a target. */
    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent e) {
        if (CALM.isEmpty() || !(e.getEntity() instanceof Mob m)) return;
        Calm c = CALM.get(m);
        LivingEntity t = e.getNewAboutToBeSetTarget();
        if (c != null && t != null && t.getUUID().equals(c.player) && c.until > now(m.level())) e.setCanceled(true);
    }

    /** Grapple Hook and Wind Jumper landing windows. */
    @SubscribeEvent
    public static void onFall(LivingFallEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        P st = PLAYERS.get(p.getUUID());
        if (st == null || st.noFallUntil < now(p.level())) return;
        e.setDamageMultiplier((float) (e.getDamageMultiplier() * Math.max(0, 1 - st.noFallCut)));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        PLAYERS.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent e) {
        clear();
    }

    static void clear() {
        PLAYERS.clear();
        PINNED.clear();
        GROUNDED.clear();
        CALM.clear();
        WET.clear();
        VORTICES.clear();
        Shots.SHOTS.clear();
        Shots.TICKING.clear();
        Shots.CLOUDS.clear();
        Draw.clear();
        Ranger.clear();
    }
}
