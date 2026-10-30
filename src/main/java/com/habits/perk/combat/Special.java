package com.habits.perk.combat;

import com.habits.core.HabitsApi;
import com.habits.core.TriggerType;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** The bigger one-off mechanics: carrying mobs, sneak attacks, sweeps, swatting projectiles, homing, stomps. */
final class Special {
    private Special() {}

    static final String CARRIED = "habits_carried", NOAI = "habits_noai", BATTED = "habits_batted", SHOT = "habits_shot", FLAME = "habits_flame";

    // ---------------------------------------------------------------- heave

    static boolean pickUp(ServerPlayer p, Entity t) {
        if (!p.isShiftKeyDown() || !p.getMainHandItem().isEmpty() || !(t instanceof Mob m) || !p.getPassengers().isEmpty() || p.isPassenger())
            return false;
        HabitsApi.PerkHit h = M.hit(p, Mech.HEAVE);
        if (h == null || M.boss(m) || m.getMaxHealth() > h.value() || m.isVehicle() || m.isPassenger() || !m.isAlive()) return false;
        if (!m.startRiding(p, true)) return false;
        syncPassengers(p);
        m.addTag(CARRIED);
        if (!m.isNoAi()) {
            m.setNoAi(true);
            m.addTag(NOAI);
        }
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 1, 0.8f);
        return true;
    }

    static boolean throwCarried(ServerPlayer p) {
        if (p.getPassengers().isEmpty()) return false;
        HabitsApi.PerkHit h = M.hit(p, Mech.HEAVE);
        double speed = (h == null ? 3 : M.p(h, "throw")) / 8;
        HabitsApi.PerkHit slam = M.hit(p, Mech.WALL_SLAM);
        Vec3 look = p.getLookAngle();
        boolean any = false;
        for (Entity e : List.copyOf(p.getPassengers())) {
            if (!e.getTags().contains(CARRIED)) continue;
            release(e);
            any = true;
            e.setPos(p.getX() + look.x, p.getEyeY(), p.getZ() + look.z);
            e.setDeltaMovement(look.x * speed, 0.3 + Math.max(0, look.y) * speed * 0.5, look.z * speed);
            e.hurtMarked = true;
            if (e instanceof LivingEntity le) Slam.track(p, le, slam == null ? 0 : (float) slam.value(), 0, 40);
        }
        if (any) p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1, 0.7f);
        return any;
    }

    static void release(Entity e) {
        Entity carrier = e.getVehicle();
        e.stopRiding();
        syncPassengers(carrier);
        e.removeTag(CARRIED);
        if (e.getTags().contains(NOAI) && e instanceof Mob m) {
            m.setNoAi(false);
            e.removeTag(NOAI);
        }
    }

    /** Vanilla tells only the OTHER players that a player's passengers changed, so the carrier would see the mob left behind. */
    static void syncPassengers(@org.jetbrains.annotations.Nullable Entity carrier) {
        if (carrier instanceof ServerPlayer sp) sp.connection.send(new net.minecraft.network.protocol.game.ClientboundSetPassengersPacket(sp));
    }

    static void dropCarried(ServerPlayer p) {
        for (Entity e : List.copyOf(p.getPassengers())) if (e.getTags().contains(CARRIED)) release(e);
    }

    // ---------------------------------------------------------------- attack phase helpers

    static void endMomentum(ServerPlayer p, PS s) {
        if (s.momentumSet < s.attackTick) {
            Ticks.mod(p, Attributes.ATTACK_SPEED, Ticks.MOMENTUM, 0);
            s.momentumUntil = 0;
        }
    }

    /** Sneak + attack, on a mob or (t == null) at the air. */
    static void sneakAttack(ServerPlayer p, @Nullable LivingEntity t) {
        if ((t != null && !t.isAlive()) || !M.active(p)) return;
        HabitsApi.PerkHit h;
        if (p.getOffhandItem().is(Items.SHIELD) && (h = M.hit(p, Mech.SHIELD_BASH)) != null) {
            if (t == null && (t = inFront(p)) == null) return;
            double dx = t.getX() - p.getX(), dz = t.getZ() - p.getZ(), len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
            double sp = h.value() / 8;
            M.hurt(t, p.damageSources().playerAttack(p), (float) M.p(h, "damage"), true);
            OnHit.push(t, new Vec3(dx / len * sp, 0.3, dz / len * sp));
            OnHit.lose(t, 60);
            HabitsApi.PerkHit slam = M.hit(p, Mech.WALL_SLAM);
            Slam.track(p, t, slam == null ? 0 : (float) slam.value(), 0, 30);
            p.getOffhandItem().hurtAndBreak(1, p, EquipmentSlot.OFFHAND);
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1, 0.8f);
            return;
        }
        if ((h = M.hit(p, Mech.WHIRLWIND)) == null) return;
        PS s = PS.of(p);
        long now = Hooks.now(p);
        if (now < s.whirlReady) return;
        s.whirlReady = now + M.ticks(M.p(h, "cooldown"));
        double r = M.p(h, "radius");
        float dmg = (float) (p.getAttributeValue(Attributes.ATTACK_DAMAGE) * h.value() / 100);
        ServerLevel sl = (ServerLevel) p.level();
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            sl.sendParticles(ParticleTypes.SWEEP_ATTACK, p.getX() + Math.cos(a) * r * 0.7, p.getY() + 1, p.getZ() + Math.sin(a) * r * 0.7, 1, 0, 0, 0, 0);
        }
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1, 0.8f);
        for (LivingEntity f : M.foesNear(p, p.position(), r, null)) M.hurt(f, p.damageSources().playerAttack(p), dmg, f != t);
    }

    /** The nearest foe within reach and within 60 degrees of where the player looks (a shield bash at the air). */
    @Nullable
    static LivingEntity inFront(ServerPlayer p) {
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity f : M.foesNear(p, eye, p.entityInteractionRange(), null)) {
            Vec3 to = f.getBoundingBox().getCenter().subtract(eye);
            double d = to.lengthSqr();
            if (d < bd && to.normalize().dot(look) > 0.5) {
                bd = d;
                best = f;
            }
        }
        return best;
    }

    /** Wide Arc replaces the vanilla sweep: a bigger box and a share of the main hit. Unguarded like a vanilla sweep, so
     * on-hit perks apply to every mob it hits. */
    static void wideArc(ServerPlayer p, Entity t, HabitsApi.PerkHit h) {
        if (!M.active(p)) return;
        PS s = PS.of(p);
        double reach = M.p(h, "reach");
        float dmg = (float) Math.max(1, s.lastMainDamage * h.value() / 100);
        double max = p.entityInteractionRange() + reach;
        AABB box = t.getBoundingBox().inflate(1 + reach, 0.25, 1 + reach);
        DamageSource src = p.damageSources().playerAttack(p);
        float sin = Mth.sin(p.getYRot() * Mth.DEG_TO_RAD), cos = -Mth.cos(p.getYRot() * Mth.DEG_TO_RAD);
        int hits = 0;
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (e == p || e == t || p.isAlliedTo(e) || (e instanceof ArmorStand as && as.isMarker()) || e.distanceToSqr(p) >= max * max) continue;
            e.knockback(0.4, sin, cos);
            if (e.hurt(src, dmg)) hits++;
        }
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, p.getSoundSource(), 1, 1);
        p.sweepAttack();
        if (hits >= 1) M.fire(p, TriggerType.CUSTOM, "sweep", hits + 1, p.getMainHandItem(), null);
    }

    // ---------------------------------------------------------------- projectiles

    record Homing(Projectile pr, Entity target, double strength, int[] ticks) {}

    static final List<Homing> HOMING = new ArrayList<>();

    static void home(Projectile pr, Entity target, double strength, int ticks) {
        if (HOMING.size() < 64) HOMING.add(new Homing(pr, target, strength, new int[]{ticks}));
    }

    static void tickHoming() {
        if (HOMING.isEmpty()) return;
        Iterator<Homing> it = HOMING.iterator();
        while (it.hasNext()) {
            Homing h = it.next();
            if (h.pr.isRemoved() || !h.target.isAlive() || h.ticks[0]-- <= 0 || (h.pr instanceof AbstractArrow a && a.isNoPhysics())) {
                it.remove();
                continue;
            }
            Vec3 v = h.pr.getDeltaMovement();
            double speed = Math.max(0.5, v.length());
            Vec3 want = weakPoint(h.target).subtract(h.pr.position());
            if (want.lengthSqr() < 1e-4) continue;
            h.pr.setDeltaMovement(v.lerp(want.normalize().scale(speed), h.strength));
            h.pr.hurtMarked = true;
        }
    }

    /** Where homing aims: dragon head, the Wither's middle head, the Warden's chest, else the middle of the body. */
    static Vec3 weakPoint(Entity e) {
        if (e instanceof EnderDragon d) return d.head.position().add(0, d.head.getBbHeight() / 2, 0);
        if (e instanceof WitherBoss w) return w.getEyePosition();
        if (e instanceof Warden w) return w.position().add(0, w.getBbHeight() * 0.6, 0);
        return e.position().add(0, e.getBbHeight() / 2, 0);
    }

    /** Batter Up: melee-hitting a projectile sends it home to its shooter, harder. */
    static void swat(ServerPlayer p, Projectile pr, AttackEntityEvent e) {
        HabitsApi.PerkHit h = M.hit(p, Mech.BATTER_UP);
        if (h == null) return;
        Entity shooter = pr.getOwner();
        if (shooter == null || shooter == p) return;
        if (pr instanceof ShulkerBullet) {
            e.setCanceled(true);
            replaceBullet(p, pr, shooter, h);
            return;
        }
        if (!pr.getType().is(EntityTypeTags.REDIRECTABLE_PROJECTILE)) return;
        // vanilla deflects it right after this event; aim it once that happened
        Tracked.later(() -> {
            if (pr.isRemoved() || pr.getOwner() != p) return;
            bat(pr, shooter, h);
        });
    }

    static void bat(Projectile pr, Entity shooter, HabitsApi.PerkHit h) {
        pr.addTag(BATTED);
        pr.getPersistentData().putFloat(BATTED, (float) h.value());
        home(pr, shooter, M.p(h, "homing"), 100);
    }

    static void replaceBullet(ServerPlayer p, Projectile old, Entity shooter, HabitsApi.PerkHit h) {
        ShulkerBullet b = new ShulkerBullet(p.level(), p, shooter, Direction.Axis.Y);
        b.moveTo(old.getX(), old.getY(), old.getZ());
        old.discard();
        b.addTag(BATTED);
        b.getPersistentData().putFloat(BATTED, (float) h.value());
        p.level().addFreshEntity(b);
    }

    /** Deflector: swat an incoming projectile back at its shooter. True = cancel the impact. */
    static boolean deflect(ServerPlayer p, Projectile pr) {
        if (!M.active(p)) return false;
        ItemStack hand = p.getMainHandItem();
        if (!hand.is(ItemTags.SWORDS) && !hand.is(ItemTags.AXES)) return false;
        HabitsApi.PerkHit h = M.hit(p, Mech.DEFLECTOR);
        if (h == null || !M.roll(p, h.value())) return false;
        Entity shooter = pr.getOwner();
        HabitsApi.PerkHit bat = M.hit(p, Mech.BATTER_UP);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1, 1.3f);
        if (pr instanceof ShulkerBullet) {
            if (shooter != null) replaceBullet(p, pr, shooter, bat != null ? bat : h);
            else pr.discard();
            return true;
        }
        Vec3 dir = shooter != null ? shooter.getEyePosition().subtract(pr.position()) : pr.getDeltaMovement().reverse();
        dir = dir.lengthSqr() < 1e-4 ? p.getLookAngle() : dir.normalize();
        double speed = Math.max(0.8, pr.getDeltaMovement().length());
        pr.setOwner(p);
        pr.setPos(pr.position().add(dir.scale(1.2)));
        pr.setDeltaMovement(dir.scale(speed));
        pr.hurtMarked = true;
        if (bat != null && shooter != null) bat(pr, shooter, bat);
        return true;
    }

    /** A player's projectile hit a mob: marks for "a mob you shot died from a fall/lava/wall" and flame arrows. */
    static void shot(ServerPlayer op, LivingEntity v, Projectile pr) {
        long now = Hooks.now(op);
        if (pr instanceof AbstractArrow) {
            v.addTag(SHOT);
            v.getPersistentData().putUUID(SHOT, op.getUUID());
            v.getPersistentData().putLong(SHOT + "_at", now);
            if (pr.isOnFire()) {
                v.addTag(FLAME);
                HabitsApi.PerkHit h = M.hit(op, Mech.WILDFIRE);
                if (h != null) Slam.wildfire(op, v, h);
            }
        }
        if (v instanceof EnderDragon) M.fire(op, TriggerType.CUSTOM_UNITS, "dragonslayer", 1, null, v);
    }

    static void indirectDeath(LivingEntity v, DamageSource src) {
        if (!v.getTags().contains(SHOT) || !(v.level() instanceof ServerLevel sl)) return;
        var data = v.getPersistentData();
        if (!data.hasUUID(SHOT) || sl.getGameTime() - data.getLong(SHOT + "_at") > 100) return;
        if (!(sl.getPlayerByUUID(data.getUUID(SHOT)) instanceof ServerPlayer p) || !M.active(p)) return;
        if (Hooks.environmental(src)) M.fire(p, TriggerType.CUSTOM_UNITS, "wall_slam", 12, null, v);
        if (v.getTags().contains(FLAME) && (v.isOnFire() || src.is(DamageTypes.ON_FIRE) || src.is(DamageTypes.IN_FIRE)))
            M.fire(p, TriggerType.CUSTOM_UNITS, "wildfire", 12, null, v);
    }

    /** Dragonslayer Lv10: the player's arrows and tridents bend toward a nearby boss's weak point. */
    static void onProjectileJoin(EntityJoinLevelEvent e) {
        if (e.loadedFromDisk() || !(e.getEntity() instanceof AbstractArrow pr) || !(pr.getOwner() instanceof ServerPlayer p)) return;
        HabitsApi.PerkHit h = M.hit(p, Mech.DRAGONSLAYER);
        if (h == null || M.p(h, "homing") < 1) return;
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity b : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(64), M::boss)) {
            double d = b.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        if (best != null) home(pr, best, pr instanceof ThrownTrident ? 0.15 : 0.2, 100);
    }

    // ---------------------------------------------------------------- falls

    static void fall(ServerPlayer p, LivingFallEvent e) {
        PS s = PS.of(p);
        long now = Hooks.now(p);
        if (now <= s.noFallUntil) {
            s.noFallUntil = 0;
            e.setDamageMultiplier(0);
        }
        float d = e.getDistance();
        if (d < 3) return;
        List<LivingEntity> under = p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(0.4, 0, 0.4).expandTowards(0, -1, 0),
                x -> x != p && x.isAlive() && !(x instanceof Player) && !(x instanceof ArmorStand));
        if (under.isEmpty()) return;
        M.fire(p, "stomp", d, null, under.get(0));
        HabitsApi.PerkHit h = M.hit(p, Mech.GOOMBA_STOMP);
        if (h == null) return;
        float dmg = (float) Math.min(M.p(h, "cap"), h.value() * d);
        for (LivingEntity x : under) if (M.foe(p, x)) M.hurt(x, M.src(p, DamageTypes.FALLING_ANVIL), dmg, true);
        e.setDamageMultiplier(0);
        double v0 = Math.sqrt(0.18 * M.p(h, "bounce"));
        Tracked.later(() -> {
            p.setDeltaMovement(p.getDeltaMovement().x, v0, p.getDeltaMovement().z);
            p.hurtMarked = true;
            M.fx(p, ParticleTypes.CRIT, 10, 0.4);
        });
    }
}
