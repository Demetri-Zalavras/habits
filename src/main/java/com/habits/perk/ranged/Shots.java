package com.habits.perk.ranged;

import com.habits.core.HabitsApi;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Every projectile a real player fires gets a {@link Shot} (in memory; ProjectileMixin saves it with the projectile).
 * Only shots that need per-tick work (homing, gravity, rain, return...) go into {@link #TICKING}; the server tick walks
 * that list and nothing else, so vanilla arrows and players without these perks cost nothing.
 */
public final class Shots {
    private Shots() {}

    /** Shot state for one projectile. */
    static final class Shot {
        final ServerPlayer owner;
        final Projectile pr;
        final Vec3 origin;
        boolean sneak, glide, ride, moving, shard, hit, landed;
        double pitch;
        long landedAt;
        int bounces, covers, hits, charge;
        long volley = -1; // multishot volley tick, -1 when not part of one
        double dmgBonus; // overdraw, percent
        boolean headshot, fullHpVictim;
        boolean star; // Falling Star: a star falls where this one lands
        boolean dark, invis; // Ranger: fired from the shadows / while invisible
        int kills; // Ranger: mobs this one projectile killed
        double flatBlocks; // Deadeye: no gravity until this far from the origin
        byte breaks; // Breeze Breaker roll: 0 not rolled yet, 1 breaks through, 2 doesn't
        // ticking work
        double homeR, homeTurn, homeLock;
        boolean homeAllies;
        @Nullable Entity target;
        double gravityBack, waterKeep, returnBoost, returnDmg;
        boolean rain, rainDone, fetch;
        int returnTicks;
        final List<Entity> passHits = new ArrayList<>(2);

        Shot(ServerPlayer owner, Projectile pr) {
            this(owner, pr, pr.position());
        }

        Shot(ServerPlayer owner, Projectile pr, Vec3 origin) {
            this.owner = owner;
            this.pr = pr;
            this.origin = origin;
        }
    }

    static final Map<Entity, Shot> SHOTS = new HashMap<>();
    static final List<Shot> TICKING = new ArrayList<>();
    static final List<AreaEffectCloud> CLOUDS = new ArrayList<>();
    static final int MAX_CLOUDS = 16;
    static final int MAX_RAIN = 15;
    static final double GRAVITY_ARROW = 0.05;

    @Nullable
    static Shot get(@Nullable Entity e) {
        return e == null ? null : SHOTS.get(e);
    }

    /** Register a projectile we spawned ourselves (shards, rain): owner set after adding, so no trigger fires for it. */
    static Shot shard(ServerPlayer owner, Projectile pr) {
        Shot s = new Shot(owner, pr);
        s.shard = true;
        SHOTS.put(pr, s);
        return s;
    }

    static void tick(Shot s) {
        if (!TICKING.contains(s)) TICKING.add(s);
    }

    // ------------------------------------------------------------------ fire

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.loadedFromDisk()) return;
        Entity en = e.getEntity();
        if (en instanceof AreaEffectCloud c) { cloud(c); return; }
        if (!(en instanceof Projectile pr) || !(pr.getOwner() instanceof ServerPlayer p) || !R.real(p)) return;
        Shot s = new Shot(p, pr);
        s.sneak = p.isShiftKeyDown();
        s.glide = p.isFallFlying();
        s.ride = p.isPassenger();
        s.moving = p.isSprinting() || !p.onGround();
        Vec3 v = pr.getDeltaMovement();
        s.pitch = v.lengthSqr() < 1e-6 ? 0 : Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, v.y / v.length()))));
        SHOTS.put(pr, s);
        long now = p.level().getGameTime();

        if (pr instanceof AbstractArrow a && !(pr instanceof ThrownTrident)) {
            s.charge = Draw.takeCharge(p);
            if (witherBane(p, a)) { e.setCanceled(true); SHOTS.remove(pr); return; }
            arrowFired(s, a, p, now);
            FallingStar.fired(s, a, p);
        } else if (pr instanceof ThrownTrident t) {
            HabitsApi.PerkHit jav = R.hit(p, "javelin");
            if (jav != null) { s.gravityBack = R.p(jav, "gravity") / 100; tick(s); }
            HabitsApi.PerkHit loyal = R.hit(p, "loyal_return");
            if (loyal != null) { s.returnBoost = loyal.value() / 100; s.returnDmg = R.p(loyal, "dmg") / 100; tick(s); }
        } else if (pr instanceof ThrownEnderpearl) {
            HabitsApi.PerkHit pitch = R.hit(p, "pearl_pitcher");
            if (pitch != null) p.getCooldowns().addCooldown(Items.ENDER_PEARL, (int) Math.round(20 * R.p(pitch, "cooldown")));
            HabitsApi.PerkHit far = R.hit(p, "far_pearl");
            if (far != null) {
                pr.setDeltaMovement(v.scale(1 + far.value() / 100));
                s.gravityBack = R.p(far, "gravity") / 100;
                tick(s);
            }
        } else if (pr instanceof FireworkRocketEntity f && f.isShotAtAngle()) {
            HabitsApi.PerkHit art = R.hit(p, "firework_artillery");
            if (art != null) homing(s, art.value(), R.p(art, "turn"), R.p(art, "lock"), false);
        } else if (pr instanceof ThrownPotion tp) {
            HabitsApi.PerkHit med = R.hit(p, "field_medic");
            PotionContents pc = tp.getItem().get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
            if (med != null && pc != null && Splash.beneficial(pc) && R.p(med, "homing") > 0)
                homing(s, R.p(med, "homing"), 12, 16, true);
        }
        Ranger.fired(s, pr, p);
    }

    private static void arrowFired(Shot s, AbstractArrow a, ServerPlayer p, long now) {
        ItemStack weapon = a.getWeaponItem();
        boolean bow = weapon != null && weapon.getItem() instanceof BowItem;
        boolean crossbow = weapon != null && weapon.getItem() instanceof CrossbowItem;
        Vec3 v = a.getDeltaMovement();
        double speed = v.length();

        HabitsApi.PerkHit qm = R.hit(p, "quartermaster");
        if (qm != null && (bow || crossbow) && a.pickup == AbstractArrow.Pickup.ALLOWED && !p.hasInfiniteMaterials()
                && R.roll(p, qm.value())) {
            // refund now and make this arrow unpickable, so the same arrow can never be collected twice
            ItemStack back = a.getPickupItemStackOrigin().copyWithCount(1);
            if (!p.getInventory().add(back)) p.drop(back, false);
            a.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
        }
        if (qm != null && R.p(qm, "return") > 0 && a.pickup == AbstractArrow.Pickup.ALLOWED) {
            s.returnTicks = (int) R.p(qm, "return");
            tick(s);
        }

        HabitsApi.PerkHit steady = R.hit(p, "steady_draw");
        if (steady != null && bow && speed > 0) {
            Vec3 look = p.getLookAngle();
            double k = Math.min(1, R.p(steady, "spread") / 100);
            Vec3 dir = v.scale(1 / speed).scale(1 - k).add(look.scale(k)).normalize();
            a.setDeltaMovement(dir.scale(speed));
            v = a.getDeltaMovement();
        }

        HabitsApi.PerkHit skeet = R.hit(p, "skeet_shooter");
        if (skeet != null && speed > 0.5) lead(a, p, skeet.value() / 100);

        HabitsApi.PerkHit sky = R.hit(p, "sky_gunner");
        if (sky != null && s.glide) a.setDeltaMovement(a.getDeltaMovement().scale(1 + sky.value() / 100));

        HabitsApi.PerkHit od = R.hit(p, "overdraw");
        if (od != null && bow && s.charge > 20) {
            double extraS = (s.charge - 20) / 20.0;
            s.dmgBonus = Math.min(R.p(od, "cap"), od.value() * extraS);
            a.setDeltaMovement(a.getDeltaMovement().scale(1 + s.dmgBonus / 400));
            int pierce = (int) R.p(od, "pierce");
            if (pierce > 0 && extraS >= 1) setPierce(a, Math.max(a.getPierceLevel(), pierce));
        }

        // homing: Homing Shot, plus Sky Gunner's extra radius while gliding
        HabitsApi.PerkHit home = R.hit(p, "homing_arrows");
        double r = home == null ? 0 : home.value(), turn = home == null ? 0 : R.p(home, "turn"), lock = home == null ? 0 : R.p(home, "lock");
        if (sky != null && s.glide && R.p(sky, "homing") > 0) {
            r += R.p(sky, "homing");
            turn = Math.max(turn, R.p(sky, "turn"));
            lock = Math.max(lock, R.p(sky, "lock"));
        }
        if (r > 0 && !s.sneak) homing(s, r, turn, lock, false);

        HabitsApi.PerkHit rain = R.hit(p, "rain_of_arrows");
        if (rain != null && s.pitch >= R.p(rain, "steep")) { s.rain = true; tick(s); }

        HabitsApi.PerkHit tide = R.hit(p, "tide_archer");
        if (tide != null) { s.waterKeep = tide.value() / 100; tick(s); }

        HabitsApi.PerkHit trick = R.hit(p, "trickshot");
        if (trick != null && trick.value() >= 3) { s.fetch = true; tick(s); }

        Status.P st = Status.of(p);
        if (now - st.lastHitTick <= 10) R.fire(p, "quick_followup", null);

        if (crossbow) Draw.fan(p, a, weapon);
    }

    static void homing(Shot s, double r, double turnDeg, double lock, boolean allies) {
        s.homeR = r;
        s.homeTurn = Math.toRadians(turnDeg);
        s.homeLock = lock;
        s.homeAllies = allies;
        tick(s);
    }

    private static boolean witherBane(ServerPlayer p, AbstractArrow a) {
        HabitsApi.PerkHit h = R.hit(p, "wither_bane");
        if (h == null || a.getWeaponItem() == null || !R.roll(p, h.value())) return false;
        WitherSkull skull = new WitherSkull(p.level(), p, a.getDeltaMovement().normalize());
        skull.setPos(a.getX(), a.getY(), a.getZ());
        skull.setDangerous(true);
        skull.setDeltaMovement(a.getDeltaMovement());
        Shot s = new Shot(p, skull);
        s.shard = true;
        s.dmgBonus = R.p(h, "dmg");
        p.level().addFreshEntity(skull);
        SHOTS.put(skull, s); // after adding: the join handler would replace it with a plain shot
        return true;
    }

    /** Skeet Shooter: aim at where a moving mob near the crosshair will be when the arrow gets there. */
    private static void lead(AbstractArrow a, ServerPlayer p, double frac) {
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Mob best = null;
        double bestCos = Math.cos(Math.toRadians(6));
        AABB box = new AABB(eye, eye.add(look.scale(48))).inflate(4);
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, box, R::hostile)) {
            Vec3 to = m.getBoundingBox().getCenter().subtract(eye);
            double cos = to.normalize().dot(look);
            if (cos > bestCos) { bestCos = cos; best = m; }
        }
        if (best == null) return;
        Vec3 v = a.getDeltaMovement();
        double speed = v.length();
        Vec3 c = best.getBoundingBox().getCenter();
        double t = c.distanceTo(a.position()) / speed;
        Vec3 aim = c.add(best.getDeltaMovement().scale(t * frac));
        a.setDeltaMovement(aim.subtract(a.position()).normalize().scale(speed).add(0, GRAVITY_ARROW * t * 0.5, 0));
    }

    /** AbstractArrow.setPierceLevel is made public by the access transformer. */
    static void setPierce(AbstractArrow a, int level) {
        a.setPierceLevel((byte) Math.min(127, level));
    }

    private static void cloud(AreaEffectCloud c) {
        if (!(c.getOwner() instanceof ServerPlayer p) || !R.real(p)) return;
        HabitsApi.PerkHit h = R.hit(p, "lingering_mist");
        if (h == null) return;
        c.setDuration((int) (c.getDuration() * (1 + h.value() / 100)));
        c.setRadiusPerTick((float) (c.getRadiusPerTick() * R.p(h, "shrink") / 100));
        double drift = R.p(h, "drift");
        if (drift > 0 && CLOUDS.size() < MAX_CLOUDS) {
            c.getPersistentData().putDouble("habits_drift", drift / 20);
            CLOUDS.add(c);
        }
    }

    // ------------------------------------------------------------------ flight

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre e) {
        tickAll();
    }

    static void tickAll() {
        if (!TICKING.isEmpty()) TICKING.removeIf(s -> s.pr.isRemoved() || !flight(s));
        if (!CLOUDS.isEmpty()) CLOUDS.removeIf(Shots::drift);
    }

    /** One tick of per-shot work. Returns false when the shot needs no more ticks. */
    static boolean flight(Shot s) {
        Projectile pr = s.pr;
        if (s.landed) return quartermasterReturn(s);
        Vec3 v = pr.getDeltaMovement();
        if (s.flatBlocks > 0 && pr.position().distanceToSqr(s.origin) > s.flatBlocks * s.flatBlocks) s.gravityBack = s.flatBlocks = 0;
        if (s.gravityBack > 0 && !pr.onGround()) pr.setDeltaMovement(v = v.add(0, pr.getGravity() * s.gravityBack, 0));
        if (s.waterKeep > 0 && pr.isInWater()) {
            // arrows keep 0.6 of their speed per tick in water instead of 0.99; give part of it back before the move
            pr.setDeltaMovement(v = v.scale(1 + s.waterKeep * (0.99 / 0.6 - 1)));
        }
        if (s.homeR > 0) home(s, pr);
        if (s.rain && !s.rainDone && v.y < 0) rain(s);
        if (s.fetch && (pr.tickCount & 1) == 0) fetch(s);
        if (s.returnBoost > 0 && pr instanceof ThrownTrident t) loyal(s, t);
        return true;
    }

    /** Homing: steer toward a locked target, at most turn radians per tick, speed unchanged. Scans only while unlocked,
     * every other tick, inside a box along the next {@code lock} blocks of flight padded by R. */
    static void home(Shot s, Projectile pr) {
        Vec3 v = pr.getDeltaMovement();
        double speed = v.length();
        if (speed < 0.1) return;
        Vec3 dir = v.scale(1 / speed), pos = pr.position();
        Entity t = s.target;
        if (t != null) {
            Vec3 to = aimPoint(t).subtract(pos);
            if (!t.isAlive() || t.isRemoved() || t.level() != pr.level() || to.lengthSqr() > s.homeLock * s.homeLock * 2.25
                    || to.dot(dir) <= 0) t = s.target = null;
        }
        if (t == null) {
            if ((pr.tickCount & 1) != 0) return;
            t = s.target = acquire(s, pr, pos, dir);
            if (t == null) return;
        }
        Vec3 to = aimPoint(t).subtract(pos);
        Vec3 nd = R.turn(dir, to.normalize(), s.homeTurn);
        pr.setDeltaMovement(nd.scale(speed));
        pr.hasImpulse = true;
    }

    static Vec3 aimPoint(Entity t) {
        return t.position().add(0, t.getBbHeight() * 0.55, 0);
    }

    @Nullable
    static Entity acquire(Shot s, Projectile pr, Vec3 pos, Vec3 dir) {
        double cosCone = Math.cos(Math.toRadians(35)); // the 70 degree cone ahead of the arrow
        Predicate<Entity> ok = s.homeAllies ? e -> ally(s.owner, e) : R::hostile;
        AABB box = new AABB(pos, pos.add(dir.scale(s.homeLock))).inflate(s.homeR);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity e : pr.level().getEntitiesOfClass(LivingEntity.class, box, ok::test)) {
            Vec3 rel = aimPoint(e).subtract(pos);
            double len = rel.length(), along = rel.dot(dir);
            if (along <= 0 || along > s.homeLock || along < len * cosCone) continue;
            double perp = Math.sqrt(Math.max(0, len * len - along * along));
            if (perp > s.homeR + e.getBbWidth() * 0.5) continue;
            double score = perp + (Status.markedBy(s.owner, e) ? -100 : 0);
            if (score < bestScore) { bestScore = score; best = e; }
        }
        return best;
    }

    /** Field Medic allies: other players and the owner's pets, hurt. Never the thrower, never mobs. */
    static boolean ally(ServerPlayer owner, Entity e) {
        if (!(e instanceof LivingEntity l) || e == owner || !l.isAlive() || l.getHealth() >= l.getMaxHealth()) return false;
        if (e instanceof Player pl) return !pl.isSpectator();
        return e instanceof OwnableEntity o && owner.getUUID().equals(o.getOwnerUUID());
    }

    private static void rain(Shot s) {
        s.rainDone = true;
        HabitsApi.PerkHit h = R.hit(s.owner, "rain_of_arrows");
        if (h == null || !(s.pr instanceof AbstractArrow a)) return;
        Status.P st = Status.of(s.owner);
        long now = a.level().getGameTime();
        if (now < st.rainReadyAt) return;
        st.rainReadyAt = now + (long) (R.p(h, "cooldown") * 20);
        int n = (int) Math.min(MAX_RAIN, h.value());
        double radius = R.p(h, "radius");
        var rnd = a.getRandom();
        for (int i = 0; i < n; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * radius;
            Arrow extra = new Arrow(a.level(), a.getX() + Math.cos(ang) * d, a.getY() + rnd.nextDouble(), a.getZ() + Math.sin(ang) * d,
                    new ItemStack(Items.ARROW), null);
            extra.pickup = AbstractArrow.Pickup.DISALLOWED;
            extra.setBaseDamage(a.getBaseDamage());
            extra.setDeltaMovement(a.getDeltaMovement().add(rnd.nextGaussian() * 0.03, 0, rnd.nextGaussian() * 0.03));
            a.level().addFreshEntity(extra);
            extra.setOwner(s.owner);
            shard(s.owner, extra);
        }
    }

    private static void fetch(Shot s) {
        for (ItemEntity it : s.pr.level().getEntitiesOfClass(ItemEntity.class, s.pr.getBoundingBox().inflate(0.6))) {
            // normal pickup rules, like the other magnets: never-pickup items and items meant for someone else stay put
            if (it.getItem().isEmpty() || it.hasPickUpDelay() || it.getTarget() != null && !it.getTarget().equals(s.owner.getUUID())) continue;
            it.teleportTo(s.owner.getX(), s.owner.getY() + 0.5, s.owner.getZ());
        }
    }

    /** Loyal Return: faster return, and the trident hurts hostiles it passes on the way back (each once). */
    private static void loyal(Shot s, ThrownTrident t) {
        if (!t.isNoPhysics()) return;
        Vec3 to = s.owner.getEyePosition().subtract(t.position());
        if (to.lengthSqr() < 1e-4) return;
        t.setDeltaMovement(t.getDeltaMovement().add(to.normalize().scale(0.05 * 3 * s.returnBoost)));
        t.hasImpulse = true;
        if (s.returnDmg <= 0 || (t.tickCount & 1) != 0) return;
        for (LivingEntity e : t.level().getEntitiesOfClass(LivingEntity.class, t.getBoundingBox().inflate(0.8), R::hostile)) {
            if (s.passHits.contains(e) || s.passHits.size() >= 8) continue;
            s.passHits.add(e);
            e.hurt(t.damageSources().trident(t, s.owner), (float) (8 * s.returnDmg));
        }
    }

    private static boolean quartermasterReturn(Shot s) {
        if (s.returnTicks <= 0 || !(s.pr instanceof AbstractArrow a)) return false;
        if (a.level().getGameTime() - s.landedAt < s.returnTicks) return true;
        if (a.pickup == AbstractArrow.Pickup.ALLOWED && s.owner.isAlive() && !s.owner.hasDisconnected()
                && s.owner.level() == a.level()) {
            ItemStack back = a.getPickupItemStackOrigin().copyWithCount(1);
            if (!s.owner.getInventory().add(back)) s.owner.drop(back, false);
            R.puff((ServerLevel) a.level(), net.minecraft.core.particles.ParticleTypes.PORTAL, a.position(), 6, 0.2);
            a.discard();
        }
        return false;
    }

    /** Lingering Mist: drift toward the nearest hostile, retargeting twice a second. Returns true to drop the cloud. */
    private static boolean drift(AreaEffectCloud c) {
        if (c.isRemoved()) return true;
        double speed = c.getPersistentData().getDouble("habits_drift");
        if (c.tickCount % 10 != 0 && !c.getPersistentData().contains("habits_tx")) return false;
        if (c.tickCount % 10 == 0) {
            Mob near = null;
            double best = Double.MAX_VALUE;
            for (Mob m : c.level().getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(12), R::hostile)) {
                double d = m.distanceToSqr(c);
                if (d < best) { best = d; near = m; }
            }
            if (near == null) { c.getPersistentData().remove("habits_tx"); return false; }
            c.getPersistentData().putDouble("habits_tx", near.getX());
            c.getPersistentData().putDouble("habits_tz", near.getZ());
        }
        double dx = c.getPersistentData().getDouble("habits_tx") - c.getX(), dz = c.getPersistentData().getDouble("habits_tz") - c.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0.3) c.setPos(c.getX() + dx / len * speed, c.getY(), c.getZ() + dz / len * speed);
        return false;
    }

    // ------------------------------------------------------------------ save (ProjectileMixin)

    static final String SAVE_KEY = "habits_shot";

    /** Write this projectile's shot (if any) into its save tag, so perks survive chunk unloads and restarts. */
    public static void save(Projectile pr, CompoundTag tag) {
        Shot s = SHOTS.get(pr);
        if (s == null) return;
        CompoundTag t = new CompoundTag();
        t.putDouble("ox", s.origin.x);
        t.putDouble("oy", s.origin.y);
        t.putDouble("oz", s.origin.z);
        t.putByte("f", (byte) ((s.sneak ? 1 : 0) | (s.glide ? 2 : 0) | (s.ride ? 4 : 0) | (s.moving ? 8 : 0) | (s.shard ? 16 : 0)
                | (s.hit ? 32 : 0) | (s.landed ? 64 : 0)));
        t.putByte("g", (byte) ((s.headshot ? 1 : 0) | (s.fullHpVictim ? 2 : 0) | (s.homeAllies ? 4 : 0) | (s.rain ? 8 : 0)
                | (s.rainDone ? 16 : 0) | (s.fetch ? 32 : 0)));
        t.putByte("breaks", s.breaks);
        t.putIntArray("i", new int[]{s.bounces, s.covers, s.hits, s.charge, s.returnTicks});
        t.putLong("landedAt", s.landedAt);
        t.putLong("volley", s.volley);
        t.putDouble("pitch", s.pitch);
        t.putDouble("dmg", s.dmgBonus);
        t.putDouble("homeR", s.homeR);
        t.putDouble("homeTurn", s.homeTurn);
        t.putDouble("homeLock", s.homeLock);
        t.putDouble("gravity", s.gravityBack);
        t.putDouble("water", s.waterKeep);
        t.putDouble("retBoost", s.returnBoost);
        t.putDouble("retDmg", s.returnDmg);
        t.putDouble("flat", s.flatBlocks);
        tag.put(SAVE_KEY, t);
    }

    /** Rebuild a saved shot. Its owner must be online in the same level, or the projectile loads as a plain one. */
    public static void load(Projectile pr, CompoundTag tag) {
        if (tag.contains(SAVE_KEY) && !pr.level().isClientSide() && pr.getOwner() instanceof ServerPlayer p) load(pr, tag, p);
    }

    static void load(Projectile pr, CompoundTag tag, ServerPlayer p) {
        CompoundTag t = tag.getCompound(SAVE_KEY);
        Shot s = new Shot(p, pr, new Vec3(t.getDouble("ox"), t.getDouble("oy"), t.getDouble("oz")));
        int f = t.getByte("f"), g = t.getByte("g");
        s.sneak = (f & 1) != 0;
        s.glide = (f & 2) != 0;
        s.ride = (f & 4) != 0;
        s.moving = (f & 8) != 0;
        s.shard = (f & 16) != 0;
        s.hit = (f & 32) != 0;
        s.landed = (f & 64) != 0;
        s.headshot = (g & 1) != 0;
        s.fullHpVictim = (g & 2) != 0;
        s.homeAllies = (g & 4) != 0;
        s.rain = (g & 8) != 0;
        s.rainDone = (g & 16) != 0;
        s.fetch = (g & 32) != 0;
        s.breaks = t.getByte("breaks");
        int[] i = t.getIntArray("i");
        if (i.length == 5) { s.bounces = i[0]; s.covers = i[1]; s.hits = i[2]; s.charge = i[3]; s.returnTicks = i[4]; }
        s.landedAt = t.getLong("landedAt");
        s.volley = t.getLong("volley");
        s.pitch = t.getDouble("pitch");
        s.dmgBonus = t.getDouble("dmg");
        s.homeR = t.getDouble("homeR");
        s.homeTurn = t.getDouble("homeTurn");
        s.homeLock = t.getDouble("homeLock");
        s.gravityBack = t.getDouble("gravity");
        s.waterKeep = t.getDouble("water");
        s.returnBoost = t.getDouble("retBoost");
        s.returnDmg = t.getDouble("retDmg");
        s.flatBlocks = t.getDouble("flat");
        SHOTS.put(pr, s);
        if (s.homeR > 0 || s.gravityBack > 0 || s.waterKeep > 0 || s.rain && !s.rainDone || s.fetch || s.returnBoost > 0
                || s.returnTicks > 0) tick(s);
    }

    // ------------------------------------------------------------------ gone

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent e) {
        if (e.getLevel().isClientSide()) return;
        Shot s = SHOTS.remove(e.getEntity());
        if (s == null) return;
        Entity en = e.getEntity();
        boolean discarded = en.getRemovalReason() == Entity.RemovalReason.DISCARDED;
        ServerPlayer p = s.owner;
        if (!discarded || s.shard) return;
        double d2 = en.distanceToSqr(p);
        if (en instanceof ThrownTrident t && t.isNoPhysics() && d2 < 9) R.fire(p, "trident_caught", null);
        else if (en instanceof AbstractArrow && s.landed && d2 < 9) R.fire(p, "arrow_pickup", null);
        else if (en instanceof FireworkRocketEntity f) Impacts.fireworkBlast(s, f);
    }
}
