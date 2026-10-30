package com.habits.perk.ranged;

import com.habits.core.HabitsApi;
import com.habits.perk.Later;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Falling Star (Mythical): every Nth full-draw arrow (param {@code every}) marks where it lands; {@code delay} ticks later
 * a star hits hostile mobs within {@code radius} blocks of the mark for the perk value. Never breaks blocks.
 */
final class FallingStar {
    private FallingStar() {}

    static final String ID = "falling_star";

    /** A player arrow joined the world. Full draw = a crit arrow (bows at full pull, crossbows always). */
    static void fired(Shots.Shot s, AbstractArrow a, ServerPlayer p) {
        HabitsApi.PerkHit h = R.hit(p, ID);
        if (h == null || !a.isCritArrow()) return;
        Status.P st = Status.of(p);
        if (++st.starShots < Math.max(1, (int) R.p(h, "every"))) return;
        st.starShots = 0;
        s.star = true;
    }

    /** The marked arrow landed (mob or block): mark the spot and call the star down. */
    static void landed(Shots.Shot s, Vec3 at) {
        if (!s.star) return;
        s.star = false;
        HabitsApi.PerkHit h = R.hit(s.owner, ID);
        if (h == null || !(s.pr.level() instanceof ServerLevel level)) return;
        ServerPlayer p = s.owner;
        float dmg = (float) h.value();
        double r = R.p(h, "radius");
        R.puff(level, ParticleTypes.END_ROD, at, 12, r * 0.3);
        Later.run(level, (int) R.p(h, "delay"), () -> strike(level, p, at, dmg, r));
    }

    /** The star hits: hostile mobs only, kill credit to the archer. Returns how many it hurt. */
    static int strike(ServerLevel level, ServerPlayer p, Vec3 at, float dmg, double r) {
        if (!R.real(p)) return 0;
        for (int i = 0; i < 6; i++) R.puff(level, ParticleTypes.END_ROD, at.add(0, 8 - i * 1.5, 0), 3, 0.15);
        R.puff(level, ParticleTypes.FLASH, at, 1, 0);
        R.puff(level, ParticleTypes.FIREWORK, at, 24, r * 0.4);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.PLAYERS, 1.2f, 0.7f);
        int n = 0;
        for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(r),
                e -> R.hostile(e) && e.distanceToSqr(at) <= r * r))
            if (l.hurt(p.damageSources().indirectMagic(p, p), dmg)) n++;
        return n;
    }
}
