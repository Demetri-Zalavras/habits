package com.habits.perk.combat;

import com.habits.core.HabitsApi;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;

/** Mechanics that react to the player being attacked. */
final class Defense {
    private Defense() {}

    static void incoming(ServerPlayer p, LivingIncomingDamageEvent e) {
        Entity atk = e.getSource().getEntity();
        if (!(atk instanceof LivingEntity la) || atk == p) return;
        PS s = PS.of(p);
        HabitsApi.PerkHit h;
        if (s.flowStacks > 0 && (h = M.hit(p, Mech.FLOW)) != null
                && M.roll(p, Math.min(M.p(h, "cap"), s.flowStacks * h.value()))) {
            e.setCanceled(true);
            M.fx(p, ParticleTypes.CLOUD, 6, 0.3);
            return;
        }
        float amt = e.getAmount();
        if (s.hostiles == 1 && (h = M.hit(p, Mech.DUELIST)) != null) amt *= (float) (1 - M.p(h, "taken") / 100);
        if (e.getSource().getDirectEntity() == la && charging(la, p) && (h = M.hit(p, Mech.CHARGE_BREAKER)) != null) {
            amt *= (float) (1 - h.value() / 100);
            if (!M.boss(la)) OnHit.daze(la, M.ticks(M.p(h, "stun")));
        }
        e.setAmount(amt);
        Warrior.incoming(p, la, e);
    }

    /** Lunging or charging at the player: moving fast toward them, or airborne (spider pounce, goat ram). */
    static boolean charging(LivingEntity la, ServerPlayer p) {
        if (!(la instanceof Mob)) return false;
        Vec3 v = la.getDeltaMovement();
        double dx = p.getX() - la.getX(), dz = p.getZ() - la.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double toward = len < 1e-4 ? 0 : (v.x * dx + v.z * dz) / len;
        return toward > 0.15 || (la instanceof Spider && !la.onGround());
    }

    static void hurt(ServerPlayer p, DamageSource src, float dmg) {
        PS s = PS.of(p);
        long now = Hooks.now(p);
        s.flawless = 0;
        Entity atk = src.getEntity();
        if (atk instanceof LivingEntity && atk != p) {
            s.flowStacks = 0;
            Warrior.hurt(p, s, (LivingEntity) atk, src, dmg, now);
            if (now - s.blockTime <= 20) M.fire(p, "riposte", 1, p.getMainHandItem(), atk);
        }
        if (!p.getPassengers().isEmpty()) Special.dropCarried(p);
        HabitsApi.PerkHit h = M.hit(p, Mech.BRAMBLE);
        if (h != null && now >= s.brambleReady && !src.is(DamageTypes.THORNS)) {
            s.brambleReady = now + 10;
            int slow = (int) M.p(h, "slowness");
            for (LivingEntity f : M.foesNear(p, p.position(), M.p(h, "radius") + 0.5, null)) {
                M.hurt(f, p.damageSources().thorns(p), (float) h.value(), false);
                if (slow > 0) f.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, slow - 1));
                M.fx(f, ParticleTypes.DAMAGE_INDICATOR, 3, 0.2);
            }
        }
    }

    static void blocked(ServerPlayer p, LivingShieldBlockEvent e) {
        PS s = PS.of(p);
        s.blockTime = Hooks.now(p);
        int since = Math.max(0, p.getTicksUsingItem() - 5); // a shield only blocks after 5 ticks raised
        Entity atk = e.getDamageSource().getEntity();
        if (since <= 5) M.fire(p, "perfect_block", 1, p.getUseItem(), atk);
        HabitsApi.PerkHit h = M.hit(p, Mech.PERFECT_PARRY);
        if (h == null || !(atk instanceof LivingEntity la) || la == p || since > M.p(h, "window")) return;
        float reflect = (float) (e.getBlockedDamage() * h.value() / 100);
        Tracked.later(() -> {
            if (!la.isAlive()) return;
            M.hurt(la, p.damageSources().thorns(p), reflect, true);
            if (!M.boss(la)) OnHit.daze(la, 20);
            M.fx(la, ParticleTypes.CRIT, 10, 0.4);
        });
    }
}
