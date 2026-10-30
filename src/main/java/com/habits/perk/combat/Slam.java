package com.habits.perk.combat;

import com.habits.core.HabitsApi;
import com.habits.core.TriggerType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

/** A knocked-back mob for a few ticks: hitting a wall or another mob is a slam (wall slam trigger, impact damage). Also
 * carries Wildfire's spread task. Only tagged mobs are checked, with a box just around them. */
final class Slam extends Tracked.Timed {
    final ServerPlayer p;
    final float dmg;
    final int chain;
    double lastSpeed;

    private Slam(ServerPlayer p, float dmg, int chain, int ticks) {
        super(ticks);
        this.p = p;
        this.dmg = dmg;
        this.chain = chain;
    }

    static void track(ServerPlayer p, LivingEntity v, float dmg, int chain, int ticks) {
        if (M.boss(v)) return;
        Slam old = Tracked.get(v, "slam") instanceof Slam s ? s : null;
        if (old != null && old.dmg > dmg) return;
        Tracked.put(v, "slam", new Slam(p, dmg, chain, ticks));
    }

    @Override
    void run(LivingEntity e) {
        double speed = e.getDeltaMovement().horizontalDistance();
        if (e.horizontalCollision && lastSpeed > 0.12) {
            impact(e, null);
            ticks = 0;
            return;
        }
        if (speed > 0.12) {
            List<LivingEntity> hit = e.level().getEntitiesOfClass(LivingEntity.class, e.getBoundingBox().inflate(0.15),
                    o -> o != e && M.foe(p, o));
            if (!hit.isEmpty()) {
                impact(e, hit.get(0));
                ticks = 0;
                return;
            }
        }
        lastSpeed = speed;
    }

    private void impact(LivingEntity e, LivingEntity other) {
        M.fire(p, TriggerType.CUSTOM_UNITS, "wall_slam", 1, p.getMainHandItem(), e);
        M.fx(e, ParticleTypes.POOF, 6, 0.3);
        if (dmg <= 0) return;
        M.hurt(e, M.src(p, DamageTypes.FLY_INTO_WALL), dmg, true);
        if (other == null) return;
        M.hurt(other, M.src(p, DamageTypes.FLY_INTO_WALL), dmg, true);
        if (chain > 0) {
            OnHit.push(other, e.getDeltaMovement().scale(0.8).add(0, 0.2, 0));
            track(p, other, dmg, chain - 1, 15);
        }
    }

    // ---------------------------------------------------------------- wildfire

    static void wildfire(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h) {
        if (Tracked.has(v, "fire")) return;
        double chance = h.value(), r = M.p(h, "radius") + 0.5;
        Tracked.put(v, "fire", new Tracked.Task() {
            int age;

            @Override
            public boolean tick(LivingEntity e) {
                age++;
                if (!e.isOnFire()) return age < 5; // fire aspect lights the mob right after the hit
                if (age % 20 == 0 && M.roll(p, chance)) {
                    for (LivingEntity f : M.foesNear(p, e.position(), r, e)) {
                        if (f.isOnFire() || f.fireImmune()) continue;
                        f.setRemainingFireTicks(Math.max(60, e.getRemainingFireTicks()));
                        M.fx(f, ParticleTypes.FLAME, 4, 0.3);
                        wildfire(p, f, h);
                    }
                }
                return true;
            }
        });
    }
}
