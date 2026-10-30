package com.habits.perk.combat;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Per player tick (server). Everything here is O(1) except a few throttled, small-box queries for owners only:
 * hostile count (16 blocks, once a second, Duelist/Crowd Fighter), trample (player box, every 5 ticks while sprinting,
 * Juggernaut), roar (5 blocks, at most every 10 s). */
final class Ticks {
    private Ticks() {}

    static final ResourceLocation BRAWLER = Habits.id("melee/brawler"), BERSERKER = Habits.id("melee/berserker"),
            RAMPAGE = Habits.id("melee/rampage"), MOMENTUM = Habits.id("melee/momentum");

    /** Set (or with 0, remove) a transient multiply-total modifier, touching the attribute only when it changes. */
    static void mod(Player p, Holder<Attribute> attr, ResourceLocation id, double amount) {
        AttributeInstance a = p.getAttribute(attr);
        if (a == null) return;
        AttributeModifier old = a.getModifier(id);
        if (amount == 0) {
            if (old != null) a.removeModifier(id);
            return;
        }
        if (old != null && old.amount() == amount) return;
        if (old != null) a.removeModifier(id);
        a.addTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    static void tick(ServerPlayer p) {
        if (!M.active(p)) return;
        PS s = PS.of(p);
        long now = Hooks.now(p);
        HabitsApi.PerkHit h;

        // sprint distance (Charge) and mount speed (Lancer): position deltas, no allocation
        Vec3 pos = p.position();
        if (s.lastPos != null && p.isSprinting()) {
            double dx = pos.x - s.lastPos.x, dz = pos.z - s.lastPos.z;
            s.sprintDist += Math.sqrt(dx * dx + dz * dz);
        } else if (!p.isSprinting()) s.sprintDist = 0;
        s.lastPos = pos;
        Entity veh = p.getVehicle();
        if (veh != null) {
            Vec3 vp = veh.position();
            if (s.lastVehiclePos != null) {
                double dx = vp.x - s.lastVehiclePos.x, dz = vp.z - s.lastVehiclePos.z;
                s.vehicleSpeed = Math.sqrt(dx * dx + dz * dz);
            }
            s.lastVehiclePos = vp;
        } else s.lastVehiclePos = null;

        if (p.tickCount % 10 == 0) refresh(p, s, now);
        if (p.tickCount % 20 == 0) {
            if (M.hit(p, Mech.DUELIST) != null || M.hit(p, Mech.CROWD_FIGHTER) != null) {
                int n16 = 0, n8 = 0;
                for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(16), e -> e instanceof Enemy && e.isAlive())) {
                    n16++;
                    if (e.distanceToSqr(p) <= 64) n8++;
                }
                s.hostiles = n16;
                s.crowd = n8;
            } else s.hostiles = s.crowd = -1;
        }
        if (p.isSprinting() && (h = M.hit(p, Mech.JUGGERNAUT)) != null) juggernaut(p, s, h, now);
        if (now >= s.abyssReady && s.lastHitId >= 0 && (h = M.hit(p, Mech.ABYSSAL_EYE)) != null) abyss(p, s, h, now);
        Warrior.tick(p, s, now);
        Classes.banner(p);
    }

    /** Twice a second: attribute buffs that depend on state, and decays. Also removes them once the perk is gone. */
    static void refresh(ServerPlayer p, PS s, long now) {
        HabitsApi.PerkHit h = M.hit(p, Mech.BRAWLER);
        if (h == null || !p.getMainHandItem().isEmpty() || now - s.punchTime > 20) {
            s.punchChain = 0;
            mod(p, Attributes.ATTACK_SPEED, BRAWLER, 0);
        }
        h = M.hit(p, Mech.BERSERKER);
        mod(p, Attributes.ATTACK_SPEED, BERSERKER,
                h != null && p.getHealth() < p.getMaxHealth() * M.p(h, "below") / 100 ? M.p(h, "speed") / 100 : 0);
        h = M.hit(p, Mech.RAMPAGE);
        if (h == null) s.rampStacks = 0;
        else if (s.rampStacks > 0 && now - s.rampLast > 100) {
            s.rampStacks--;
            s.rampLast = now - 80; // one stack a second from here
        }
        mod(p, Attributes.MOVEMENT_SPEED, RAMPAGE, h == null ? 0 : s.rampStacks * M.p(h, "speed") / 100);
        if (s.momentumUntil > 0 && (now > s.momentumUntil || M.hit(p, Mech.MOMENTUM) == null)) {
            s.momentumUntil = 0;
            mod(p, Attributes.ATTACK_SPEED, MOMENTUM, 0);
        }
    }

    static void juggernaut(ServerPlayer p, PS s, HabitsApi.PerkHit h, long now) {
        if (p.tickCount % 5 == 0) {
            for (LivingEntity f : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(0.6, 0, 0.6), f -> M.foe(p, f)))
                M.hurt(f, p.damageSources().playerAttack(p), (float) h.value(), false);
        }
        double roar = M.p(h, "roar_cooldown");
        if (roar > 0 && now >= s.roarReady) {
            s.roarReady = now + M.ticks(roar);
            double r = M.p(h, "roar_radius");
            ServerLevel sl = (ServerLevel) p.level();
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 1, 1);
            M.fx(sl, ParticleTypes.POOF, p.position().add(0, 1, 0), 20, r / 3);
            for (LivingEntity f : M.foesNear(p, p.position(), r, null)) {
                Vec3 d = f.position().subtract(p.position());
                Vec3 hz = new Vec3(d.x, 0, d.z);
                hz = hz.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : hz.normalize();
                OnHit.push(f, f.getDeltaMovement().add(hz.x * 1.2, 0.4, hz.z * 1.2));
            }
        }
    }

    static void abyss(ServerPlayer p, PS s, HabitsApi.PerkHit h, long now) {
        s.abyssReady = now + 10; // re-check twice a second while nothing is in sight
        if (!(p.level().getEntity(s.lastHitId) instanceof LivingEntity t) || !t.isAlive() || t instanceof Player) return;
        double range = M.p(h, "range");
        if (t.distanceToSqr(p) > range * range || !p.hasLineOfSight(t)) return;
        s.abyssReady = now + M.ticks(M.p(h, "interval"));
        ServerLevel sl = (ServerLevel) p.level();
        M.line(sl, ParticleTypes.BUBBLE_POP, p.getEyePosition(), t.position().add(0, t.getBbHeight() / 2, 0));
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GUARDIAN_ATTACK, SoundSource.PLAYERS, 0.6f, 1.4f);
        M.hurt(t, p.damageSources().indirectMagic(p, p), (float) h.value(), true);
    }
}
