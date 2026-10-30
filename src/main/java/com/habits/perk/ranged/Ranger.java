package com.habits.perk.ranged;

import com.habits.core.HabitsApi;
import com.habits.perk.Later;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranger spec batch (mechanic ids {@code habits:ranged/<name>}, events {@code habits:ranged/<event>}). Hooks are called
 * from {@link Shots} (fired), {@link Hits} (bonus, hit, damage, death, drops) and {@link Impacts} (landed); stealth,
 * dodging and item-use perks are listeners here. Table: docs/mechanics/ranged.md.
 */
public final class Ranger {
    private Ranger() {}

    static final int BLEED_CAP = 64;
    static final Map<LivingEntity, Bleed> BLEEDS = new HashMap<>();

    record Bleed(ServerPlayer by, float dmg, long until) {}

    // ------------------------------------------------------------------ fired (Shots.onJoin)

    static void fired(Shots.Shot s, Projectile pr, ServerPlayer p) {
        ServerLevel level = (ServerLevel) p.level();
        HabitsApi.PerkHit h;
        if (pr instanceof AbstractArrow a && !(pr instanceof ThrownTrident)) {
            ItemStack w = a.getWeaponItem();
            boolean bow = w != null && w.getItem() instanceof BowItem;
            s.dark = level.getMaxLocalRawBrightness(p.blockPosition()) <= light(p);
            s.invis = p.isInvisible();
            float power = BowItem.getPowerForTime(s.charge);
            if (bow && power < 1 && power > 0 && (h = R.hit(p, "ranged/snap_shot")) != null) {
                double target = power + (1 - power) * h.value() / 100;
                a.setDeltaMovement(a.getDeltaMovement().scale(target / power));
            }
            if ((h = R.hit(p, "ranged/swift_fletch")) != null) a.setDeltaMovement(a.getDeltaMovement().scale(1 + h.value() / 100));
            if ((h = R.hit(p, "ranged/two_birds")) != null && R.roll(p, h.value())) Shots.setPierce(a, a.getPierceLevel() + 1);
            if ((h = R.hit(p, "ranged/heavenpiercer")) != null) Shots.setPierce(a, a.getPierceLevel() + (int) R.p(h, "pierce"));
            if ((h = R.hit(p, "ranged/deadeye")) != null && p.isShiftKeyDown() && a.isCritArrow()) {
                s.flatBlocks = h.value();
                s.gravityBack = 1;
                Shots.tick(s);
            }
            if ((h = R.hit(p, "ranged/sonic_shot")) != null && a.isCritArrow()) {
                Status.P st = Status.of(p);
                if (++st.sonicShots >= Math.max(1, (int) R.p(h, "every"))) {
                    st.sonicShots = 0;
                    sonic(p, a, h);
                }
            }
        } else if (pr instanceof ThrownTrident t && !s.shard) {
            if ((h = R.hit(p, "ranged/twin_tide")) != null && R.roll(p, h.value())) twin(p, t);
        } else if (pr instanceof ThrownPotion && (h = R.hit(p, "ranged/potion_sling")) != null) {
            pr.setDeltaMovement(pr.getDeltaMovement().scale(1 + h.value() / 100));
        } else if (pr instanceof WindCharge && (h = R.hit(p, "ranged/gust_pack")) != null) {
            int cd = (int) Math.round(10 * Math.max(0, 1 - h.value() / 100));
            // vanilla sets its 10 tick cooldown after the charge spawns; replace it at the end of this tick
            Later.run(level, 0, () -> p.getCooldowns().addCooldown(Items.WIND_CHARGE, Math.max(1, cd)));
        }
    }

    /** Shadow Shot's light threshold (param {@code light}, default 7). */
    static int light(ServerPlayer p) {
        HabitsApi.PerkHit h = R.hit(p, "ranged/shadow_shot");
        return h == null ? 7 : (int) R.p(h, "light");
    }

    /** Sonic Shot: a sonic beam along the arrow's line, hostiles only, armor ignored (sonic boom damage). */
    static int sonic(ServerPlayer p, AbstractArrow a, HabitsApi.PerkHit h) {
        ServerLevel level = (ServerLevel) p.level();
        Vec3 from = p.getEyePosition(), dir = a.getDeltaMovement().normalize();
        double range = R.p(h, "range");
        List<LivingEntity> done = new ArrayList<>();
        for (double d = 1; d <= range; d += 1) {
            Vec3 at = from.add(dir.scale(d));
            if (((int) d & 1) == 0) R.puff(level, ParticleTypes.SONIC_BOOM, at, 1, 0);
            for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(0.8), R::hostile))
                if (!done.contains(l)) done.add(l);
        }
        for (LivingEntity l : done) {
            l.invulnerableTime = 0;
            l.hurt(level.damageSources().sonicBoom(p), (float) h.value());
        }
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 0.8f, 1.4f);
        return done.size();
    }

    /** Twin Tide: a ghost copy of the trident flies beside it, can't be picked up, gone after 3 s. */
    static void twin(ServerPlayer p, ThrownTrident t) {
        Vec3 v = t.getDeltaMovement();
        Vec3 side = v.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1e-6 ? new Vec3(0.5, 0, 0) : side.normalize().scale(0.5);
        Vec3 at = t.position().add(side);
        ThrownTrident g = new ThrownTrident(p.level(), at.x, at.y, at.z, t.getPickupItemStackOrigin());
        g.setDeltaMovement(v.yRot(-0.05f));
        g.pickup = AbstractArrow.Pickup.DISALLOWED;
        p.level().addFreshEntity(g);
        g.setOwner(p); // after adding: no card roll and no second twin for the copy
        Shots.shard(p, g);
        Later.run((ServerLevel) p.level(), 60, g::discard);
    }

    // ------------------------------------------------------------------ hits (Hits)

    /** % arrow damage from this batch (Hits.arrowBonus). */
    static double bonus(Shots.Shot s, AbstractArrow a, ServerPlayer p, LivingEntity victim) {
        double pct = 0;
        HabitsApi.PerkHit h;
        if ((h = R.hit(p, "ranged/porcupine")) != null) pct += Math.min(R.p(h, "cap"), h.value() * victim.getArrowCount());
        double above = s.origin.y - victim.getY();
        if ((h = R.hit(p, "ranged/high_ground")) != null && above > 0) pct += Math.min(R.p(h, "cap"), h.value() * above);
        if ((h = R.hit(p, "ranged/shadow_shot")) != null && s.dark) pct += h.value();
        if ((h = R.hit(p, "ranged/unseen")) != null && s.invis) pct += h.value();
        if ((h = R.hit(p, "ranged/spotter")) != null && Status.markedBy(p, victim)) pct += R.p(h, "dmg");
        if ((h = R.hit(p, "ranged/ambush")) != null && victim instanceof Mob m && m.getTarget() != p) pct += h.value();
        if ((h = R.hit(p, "ranged/galeborn")) != null && (s.moving || s.ride || s.glide)) pct += h.value();
        if ((h = R.hit(p, "ranged/heavenpiercer")) != null) pct += h.value();
        return pct;
    }

    /** After an arrow hit lands (Hits.arrowHit, inside its re-entrancy guard). */
    static void arrowHit(Shots.Shot s, AbstractArrow a, LivingEntity victim, boolean firstHit) {
        ServerPlayer p = s.owner;
        ServerLevel level = (ServerLevel) victim.level();
        Vec3 at = victim.position();
        boolean leg = victim.getBbHeight() > 0.6 && a.getY() <= victim.getY() + victim.getBbHeight() * 0.33;
        if (leg) R.fire(p, "ranged/leg_shot", at);
        if (a.isCritArrow()) R.fire(p, "ranged/crit_arrow_hit", at);
        if (victim.getArrowCount() >= 3) R.fire(p, "ranged/pincushion_hit", at);
        if (s.origin.y - victim.getY() >= 8) R.fire(p, "ranged/high_ground_hit", at);
        if (a.isOnFire()) R.fire(p, "ranged/flame_arrow_hit", at);
        if (s.dark) R.fire(p, "ranged/dark_hit", at);
        if (s.invis) R.fire(p, "ranged/invisible_hit", at);
        if (s.charge > 0 && BowItem.getPowerForTime(s.charge) < 0.65) R.fire(p, "ranged/snap_shot_hit", at);

        HabitsApi.PerkHit h;
        if ((h = R.hit(p, "ranged/galeborn")) != null && (s.moving || s.ride || s.glide))
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, (int) (R.p(h, "speed") * 20), 0, false, false, true));
        if ((h = R.hit(p, "ranged/hobble")) != null && leg && victim.isAlive())
            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, (int) (h.value() * 20), (int) R.p(h, "amp")), p);
        if ((h = R.hit(p, "ranged/barbed")) != null && a.isCritArrow() && victim.isAlive() && R.hostile(victim)
                && (BLEEDS.size() < BLEED_CAP || BLEEDS.containsKey(victim)))
            BLEEDS.put(victim, new Bleed(p, (float) h.value(), level.getGameTime() + (long) (R.p(h, "secs") * 20)));
        if ((h = R.hit(p, "ranged/kindling")) != null && a.isOnFire()) {
            double r = h.value();
            for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, victim.getBoundingBox().inflate(r), R::hostile))
                if (l != victim && l.distanceToSqr(victim) <= r * r) l.igniteForSeconds((float) R.p(h, "secs"));
            R.puff(level, ParticleTypes.FLAME, victim.getBoundingBox().getCenter(), 12, r * 0.4);
        }
        if ((h = R.hit(p, "ranged/fang_arrows")) != null && firstHit && R.hostile(victim) && R.roll(p, h.value()))
            fangs(p, victim, (int) R.p(h, "fangs"));
        if ((h = R.hit(p, "ranged/dragonfire")) != null && firstHit && R.roll(p, R.p(h, "chance"))) dragonfire(p, at, h);
    }

    /** Evoker fangs in a small ring under the mob, yours (they skip you and your allies). */
    static void fangs(ServerPlayer p, LivingEntity victim, int n) {
        for (int i = 0; i < n; i++) {
            double ang = Math.PI * 2 * i / Math.max(1, n - 1), d = i == 0 ? 0 : 1.2;
            p.level().addFreshEntity(new EvokerFangs(p.level(), victim.getX() + Math.cos(ang) * d, victim.getY(),
                    victim.getZ() + Math.sin(ang) * d, 0, i == 0 ? 0 : 2 + i, p));
        }
    }

    /** Dragonfire Quiver: a burst of dragon breath, magic damage to hostiles within {@code radius}. */
    static int dragonfire(ServerPlayer p, Vec3 at, HabitsApi.PerkHit h) {
        ServerLevel level = (ServerLevel) p.level();
        double r = R.p(h, "radius");
        R.puff(level, ParticleTypes.DRAGON_BREATH, at.add(0, 0.5, 0), 24, r * 0.4);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.PLAYERS, 0.6f, 1.3f);
        int n = 0;
        for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(r), e -> R.hostile(e) && e.distanceToSqr(at) <= r * r)) {
            l.invulnerableTime = 0;
            if (l.hurt(p.damageSources().indirectMagic(p, p), (float) h.value())) n++;
        }
        return n;
    }

    /** Arrow damage that landed (Hits.onPost): Leech Fletching heals the archer. */
    static void damaged(Shots.Shot s, float dmg) {
        HabitsApi.PerkHit h = R.hit(s.owner, "ranged/leech");
        if (h != null && dmg > 0) s.owner.heal((float) (dmg * h.value() / 100));
    }

    static void tridentHit(Shots.Shot s, ThrownTrident t, LivingEntity victim) {
        HabitsApi.PerkHit h = R.hit(s.owner, "ranged/whirlpool");
        if (h == null) return;
        double r = h.value();
        for (Mob m : victim.level().getEntitiesOfClass(Mob.class, victim.getBoundingBox().inflate(r), R::hostile)) {
            if (m == victim || R.boss(m)) continue;
            Vec3 d = victim.position().subtract(m.position());
            if (d.lengthSqr() > r * r || d.lengthSqr() < 1) continue;
            m.setDeltaMovement(m.getDeltaMovement().add(d.normalize().scale(R.p(h, "pull"))).add(0, 0.15, 0));
            m.hurtMarked = true;
        }
        R.puff((ServerLevel) victim.level(), ParticleTypes.BUBBLE_POP, victim.position().add(0, 0.5, 0), 24, r * 0.4);
    }

    /** An arrow of ours killed a mob (Hits.onDeath). */
    static void killed(Shots.Shot s, AbstractArrow a, LivingEntity dead) {
        ServerPlayer p = s.owner;
        ServerLevel level = (ServerLevel) dead.level();
        Vec3 at = dead.position();
        R.fire(p, "ranged/arrow_kill", at);
        if (p.isShiftKeyDown()) R.fire(p, "ranged/sneak_arrow_kill", at);
        if (p.getHealth() <= p.getMaxHealth() * 0.3f) R.fire(p, "ranged/arrow_kill_low_hp", at);
        if (at.distanceTo(s.origin) >= 50) R.fire(p, "ranged/long_kill", at);
        if (++s.kills == 2) R.fire(p, "ranged/double_kill", at);
        HabitsApi.PerkHit h;
        if ((h = R.hit(p, "ranged/hunters_high")) != null)
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, (int) (h.value() * 20), (int) R.p(h, "amp")));
        if (!(dead instanceof Mob)) return;
        if (a.shotFromCrossbow()) {
            if ((h = R.hit(p, "ranged/hair_trigger")) != null && R.roll(p, h.value())) reload(p);
            if ((h = R.hit(p, "ranged/bolt_burst")) != null) Hits.blast(p, a, at.add(0, dead.getBbHeight() * 0.5, 0), (float) h.value());
        }
        if ((h = R.hit(p, "ranged/chain_shot")) != null) chain(p, a, dead, h);
    }

    /** Hair Trigger: load an empty crossbow in either hand from the archer's ammo. */
    static boolean reload(ServerPlayer p) {
        for (ItemStack cb : new ItemStack[]{p.getMainHandItem(), p.getOffhandItem()}) {
            if (!(cb.getItem() instanceof CrossbowItem) || CrossbowItem.isCharged(cb)) continue;
            ItemStack ammo = p.getProjectile(cb);
            if (ammo.isEmpty()) return false;
            ItemStack one = ammo.copyWithCount(1);
            if (!p.hasInfiniteMaterials()) ammo.shrink(1);
            cb.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(one));
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.CROSSBOW_LOADING_END, SoundSource.PLAYERS, 1, 1.2f);
            return true;
        }
        return false;
    }

    /** Chain Shot: a new arrow flies from the body to the nearest hostile in range (chains never chain again). */
    static void chain(ServerPlayer p, AbstractArrow a, LivingEntity dead, HabitsApi.PerkHit h) {
        double range = h.value();
        Vec3 from = dead.getBoundingBox().getCenter().add(0, dead.getBbHeight() * 0.5 + 0.3, 0);
        Mob best = null;
        double bd = range * range;
        for (Mob m : dead.level().getEntitiesOfClass(Mob.class, dead.getBoundingBox().inflate(range), R::hostile)) {
            double d = m.distanceToSqr(dead);
            if (m != dead && d < bd) { bd = d; best = m; }
        }
        if (best == null) return;
        Arrow next = new Arrow(dead.level(), from.x, from.y, from.z, new ItemStack(Items.ARROW), null);
        next.pickup = AbstractArrow.Pickup.DISALLOWED;
        next.setBaseDamage(a.getBaseDamage() * R.p(h, "dmg") / 100);
        Vec3 to = best.getBoundingBox().getCenter().subtract(from);
        next.shoot(to.x, to.y + to.horizontalDistance() * 0.05, to.z, 2f, 0);
        dead.level().addFreshEntity(next);
        next.setOwner(p);
        Shots.shard(p, next);
        R.puff((ServerLevel) dead.level(), ParticleTypes.CRIT, from, 6, 0.2);
    }

    /** Scavenger: arrow kills drop a few arrows. */
    static void drops(LivingDropsEvent e, ServerPlayer p, LivingEntity dead) {
        HabitsApi.PerkHit h = R.hit(p, "ranged/scavenger");
        if (h == null || !(dead instanceof Mob) || !R.roll(p, h.value())) return;
        int n = 1 + p.getRandom().nextInt(Math.max(1, (int) R.p(h, "count")));
        Hits.add(e, dead, new ItemStack(Items.ARROW, n));
    }

    // ------------------------------------------------------------------ landed (Impacts.block)

    static void arrowMissed(ServerPlayer p, Vec3 at) {
        R.fire(p, "ranged/arrow_miss", at);
    }

    /** A thrown trident stuck in a block: card event, and Lanyard brings it back after the perk's seconds. */
    static void tridentLanded(Shots.Shot s, ThrownTrident t) {
        if (s.shard || s.landed) return;
        s.landed = true;
        ServerPlayer p = s.owner;
        R.fire(p, "ranged/trident_stuck", t.position());
        HabitsApi.PerkHit h = R.hit(p, "ranged/lanyard");
        if (h == null) return;
        Later.run((ServerLevel) t.level(), (int) (h.value() * 20), () -> lanyard(p, t));
    }

    static boolean lanyard(ServerPlayer p, ThrownTrident t) {
        // a loyal trident already on its way home (no physics) or one picked up meanwhile is left alone
        if (t.isRemoved() || t.isNoPhysics() || t.pickup != AbstractArrow.Pickup.ALLOWED || !R.real(p) || p.level() != t.level()) return false;
        ItemStack back = t.getPickupItemStackOrigin();
        if (!p.getInventory().add(back)) p.drop(back, false);
        R.puff((ServerLevel) t.level(), ParticleTypes.SPLASH, t.position(), 12, 0.3);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1, 1);
        t.discard();
        return true;
    }

    // ------------------------------------------------------------------ listeners

    /** Fletcher: crafted arrows come with extras. */
    @SubscribeEvent
    public static void onCraft(PlayerEvent.ItemCraftedEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !e.getCrafting().is(ItemTags.ARROWS)) return;
        HabitsApi.PerkHit h = R.hit(p, "ranged/fletcher");
        if (h == null || !R.roll(p, h.value())) return;
        ItemStack extra = e.getCrafting().copyWithCount((int) R.p(h, "extra"));
        if (!extra.isEmpty() && !p.getInventory().add(extra)) p.drop(extra, false);
    }

    /** Sidestep (projectiles) and Nimble (melee while holding a ranged weapon): a dodge cancels the hit. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncoming(LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !R.real(p)) return;
        DamageSource src = e.getSource();
        Entity by = src.getEntity();
        if (by == null || by == p) return;
        HabitsApi.PerkHit h;
        if (src.is(DamageTypeTags.IS_PROJECTILE)) {
            if ((h = R.hit(p, "ranged/sidestep")) != null && R.roll(p, h.value() + (p.isSprinting() ? R.p(h, "sprint") : 0))) dodge(p, e);
            return;
        }
        if (src.getDirectEntity() == by && by instanceof Mob && p.getMainHandItem().is(Tags.Items.RANGED_WEAPON_TOOLS)) {
            R.fire(p, "ranged/hit_holding_bow", p.position());
            if ((h = R.hit(p, "ranged/nimble")) != null && R.roll(p, h.value())) {
                dodge(p, e);
                Vec3 away = p.position().subtract(by.position()).multiply(1, 0, 1);
                if (away.lengthSqr() > 1e-4) {
                    p.setDeltaMovement(away.normalize().scale(0.3 * R.p(h, "hop")).add(0, 0.3, 0));
                    p.hurtMarked = true;
                }
            }
        }
    }

    static void dodge(ServerPlayer p, LivingIncomingDamageEvent e) {
        e.setCanceled(true);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_NODAMAGE, SoundSource.PLAYERS, 0.8f, 1.6f);
        R.puff((ServerLevel) p.level(), ParticleTypes.CLOUD, p.position().add(0, 1, 0), 6, 0.3);
    }

    /** Smoke Bomb: dropping to 30% health or less blinds nearby hostiles and hides you (with a cooldown). */
    @SubscribeEvent
    public static void onHurt(LivingDamageEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || e.getNewDamage() <= 0 || !p.isAlive() || p.getHealth() > p.getMaxHealth() * 0.3f) return;
        HabitsApi.PerkHit h = R.hit(p, "ranged/smoke_bomb");
        if (h == null) return;
        Status.P st = Status.of(p);
        long now = p.level().getGameTime();
        if (now < st.smokeReadyAt) return;
        st.smokeReadyAt = now + (long) (R.p(h, "cooldown") * 20);
        smoke(p, h);
    }

    static int smoke(ServerPlayer p, HabitsApi.PerkHit h) {
        ServerLevel level = (ServerLevel) p.level();
        double r = h.value();
        int blind = (int) (R.p(h, "blind") * 20), n = 0;
        for (Mob m : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(r), R::hostile)) {
            if (m.distanceToSqr(p) > r * r) continue;
            m.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, blind), p);
            if (m.getTarget() == p) m.setTarget(null);
            Status.CALM.put(m, new Status.Calm(p.getUUID(), level.getGameTime() + blind));
            n++;
        }
        p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, (int) (R.p(h, "hide") * 20), 0, false, false));
        R.puff(level, ParticleTypes.LARGE_SMOKE, p.position().add(0, 1, 0), 24, r * 0.3);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 1, 0.6f);
        return n;
    }

    /** Low Profile: while you sneak, a mob farther than its shrunk sight range can't pick you as a new target. */
    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent e) {
        if (!(e.getNewAboutToBeSetTarget() instanceof ServerPlayer p) || !(e.getEntity() instanceof Mob m) || m.getTarget() == p
                || e.getTargetType() != LivingChangeTargetEvent.LivingTargetType.MOB_TARGET || !p.isShiftKeyDown()) return;
        HabitsApi.PerkHit h = R.hit(p, "ranged/low_profile");
        if (h == null || m.getLastHurtByMob() == p) return;
        double sight = m.getAttributeValue(Attributes.FOLLOW_RANGE) * Math.max(0, 1 - h.value() / 100);
        if (m.distanceToSqr(p) > sight * sight) e.setCanceled(true);
    }

    /** Twice a second per player: Ghillie, and the sneaking card events. Cheap checks first. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || (p.tickCount % 10) != 0 || !p.isShiftKeyDown() || !R.real(p)) return;
        stealthTick(p);
    }

    static void stealthTick(ServerPlayer p) {
        Status.P st = Status.of(p);
        long now = p.level().getGameTime();
        boolean leafy = foliage(p.level().getBlockState(p.blockPosition())) || foliage(p.level().getBlockState(p.blockPosition().above()));
        if (leafy) {
            if ((st.foliageTicks += 10) >= 600) { st.foliageTicks = 0; R.fire(p, "ranged/foliage_sneak", null); }
            HabitsApi.PerkHit h = R.hit(p, "ranged/ghillie");
            if (h != null) p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 15 + (int) (h.value() * 20), 0, true, false));
        }
        if (now - st.nearHostileAt >= 400) {
            for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(8), R::hostile)) {
                if (m.getTarget() == p) continue;
                st.nearHostileAt = now;
                R.fire(p, "ranged/sneak_near_hostile", null);
                break;
            }
        }
    }

    static boolean foliage(BlockState s) {
        return s.is(BlockTags.REPLACEABLE_BY_TREES) && !s.liquid() || s.is(BlockTags.LEAVES);
    }

    /** Spotter (spyglass marks), Quick Throw (trident wind-up, both sides so the animation matches). */
    @SubscribeEvent
    public static void onUseTick(LivingEntityUseItemEvent.Tick e) {
        if (!(e.getEntity() instanceof Player p) || !p.isAlive() || p.isSpectator()) return;
        ItemStack st = e.getItem();
        HabitsApi.PerkHit h;
        if (st.is(Items.TRIDENT) && (h = HabitsApi.mechanicHit(p, R.id("ranged/quick_throw"))) != null) {
            double speed = h.value() / 100;
            int n = p.getTicksUsingItem();
            int extra = (int) ((n + 1) * speed) - (int) (n * speed);
            if (extra > 0) e.setDuration(Math.max(1, e.getDuration() - extra));
        } else if (st.is(Items.SPYGLASS) && p instanceof ServerPlayer sp && (p.tickCount % 5) == 0 && (h = R.hit(sp, "ranged/spotter")) != null) {
            spot(sp, h);
        }
    }

    @org.jetbrains.annotations.Nullable
    static LivingEntity spot(ServerPlayer p, HabitsApi.PerkHit h) {
        double range = R.p(h, "range");
        Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(range));
        EntityHitResult r = ProjectileUtil.getEntityHitResult(p, eye, end, p.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1),
                R::hostile, range * range);
        if (r == null || !(r.getEntity() instanceof LivingEntity l)) return null;
        Status.glow(p, l, p.level().getGameTime() + (long) (h.value() * 20), true, (int) R.p(h, "max"));
        return l;
    }

    /** Tidal Ram: a riptide launch goes further. Runs after vanilla's push; the new motion is sent to the client. */
    @SubscribeEvent
    public static void onUseStop(LivingEntityUseItemEvent.Stop e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !e.getItem().is(Items.TRIDENT) || !R.real(p)) return;
        ItemStack st = e.getItem();
        if (st.getUseDuration(p) - e.getDuration() < 10 || !p.isInWaterOrRain() || EnchantmentHelper.getTridentSpinAttackStrength(st, p) <= 0) return;
        R.fire(p, "ranged/riptide_launch", null);
        HabitsApi.PerkHit h = R.hit(p, "ranged/riptide");
        if (h == null) return;
        double mult = 1 + h.value() / 100;
        Later.run((ServerLevel) p.level(), 0, () -> {
            if (!p.isAutoSpinAttack()) return;
            p.setDeltaMovement(p.getDeltaMovement().scale(mult));
            p.hurtMarked = true;
        });
    }

    /** Barbed Arrows bleed, once a second. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (BLEEDS.isEmpty() || e.getServer().getTickCount() % 20 != 0) return;
        bleedTick();
    }

    static void bleedTick() {
        BLEEDS.entrySet().removeIf(en -> {
            LivingEntity l = en.getKey();
            Bleed b = en.getValue();
            if (l.isRemoved() || !l.isAlive() || b.until <= l.level().getGameTime() || !R.real(b.by)) return true;
            l.invulnerableTime = 0;
            l.hurt(b.by.damageSources().indirectMagic(b.by, b.by), b.dmg);
            R.puff((ServerLevel) l.level(), ParticleTypes.DAMAGE_INDICATOR, l.getBoundingBox().getCenter(), 3, 0.2);
            return false;
        });
    }

    static void clear() {
        BLEEDS.clear();
    }
}
