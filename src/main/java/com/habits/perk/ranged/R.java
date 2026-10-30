package com.habits.perk.ranged;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;

/** Small shared helpers for the ranged mechanics: perk reads, param scaling, target filters, custom events. */
final class R {
    private R() {}

    static ResourceLocation id(String mechanic) {
        return Habits.id(mechanic);
    }

    /** Best active hit for a mechanic, or null. Null for players a perk must never touch (dead, spectator, fakes). */
    @Nullable
    static HabitsApi.PerkHit hit(@Nullable Player p, String mechanic) {
        if (p == null || !real(p)) return null;
        return HabitsApi.mechanicHit(p, id(mechanic));
    }

    /** Alive, not spectating, and not a fake player (GameTest fakes are allowed through like the trigger gate). */
    static boolean real(Player p) {
        if (!p.isAlive() || p.isSpectator()) return false;
        return !(p instanceof ServerPlayer sp) || !sp.isFakePlayer() || Triggers.TEST_PLAYERS.contains(sp.getUUID());
    }

    /**
     * A param at the hit's level. Forms: {@code "a..b"} (linear Lv1 a to Lv10 b), {@code "v1,...,v10"} (one per level),
     * or a plain number. Missing or broken params read as 0.
     */
    static double p(HabitsApi.PerkHit h, String key) {
        return param(h.skill().perk().param(key, "0"), h.level());
    }

    static double param(String s, int level) {
        int lv = Math.max(1, Math.min(10, level));
        try {
            int dots = s.indexOf("..");
            if (dots >= 0) {
                double a = Double.parseDouble(s.substring(0, dots).trim()), b = Double.parseDouble(s.substring(dots + 2).trim());
                return a + (b - a) * (lv - 1) / 9.0;
            }
            if (s.indexOf(',') >= 0) {
                String[] parts = s.split(",");
                return Double.parseDouble(parts[Math.min(lv, parts.length) - 1].trim());
            }
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Roll a percent chance. */
    static boolean roll(Entity e, double percent) {
        return percent > 0 && e.getRandom().nextDouble() * 100 < percent;
    }

    /** Hostile mob a perk may aim at or hurt: never players, pets, villagers or anything owned. */
    static boolean hostile(Entity e) {
        return e instanceof LivingEntity l && e instanceof Enemy && l.isAlive() && !(e instanceof Player)
                && !(e instanceof AbstractVillager) && !(e instanceof OwnableEntity o && o.getOwnerUUID() != null)
                && !e.isSpectator();
    }

    static boolean boss(Entity e) {
        return e.getType().is(Tags.EntityTypes.BOSSES);
    }

    /** Fire a custom card event for this player (the trigger gate drops fakes, spectators and the dead). */
    static void fire(Player p, String event, @Nullable Vec3 pos) {
        Triggers.fire(p, TriggerType.CUSTOM, TriggerContext.of(Habits.id(event)).withPos(pos));
    }

    /** Capped particle burst; ServerLevel only sends it to players within 32 blocks. */
    static void puff(ServerLevel level, ParticleOptions type, Vec3 at, int count, double spread) {
        level.sendParticles(type, at.x, at.y, at.z, Math.min(count, 24), spread, spread, spread, 0.02);
    }

    /** Rotate unit vector {@code from} toward unit vector {@code to} by at most {@code maxRad}. */
    static Vec3 turn(Vec3 from, Vec3 to, double maxRad) {
        double dot = Math.max(-1, Math.min(1, from.dot(to)));
        double ang = Math.acos(dot);
        if (ang <= maxRad || ang < 1e-6) return to;
        double t = maxRad / ang, s = Math.sin(ang);
        // slerp keeps the turn rate even through the whole bend
        return from.scale(Math.sin((1 - t) * ang) / s).add(to.scale(Math.sin(t * ang) / s)).normalize();
    }
}
