package com.habits.perk.ranged;

import com.habits.core.HabitsApi;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.List;
import java.util.Map;

/** Arrow and trident hits: damage bonuses (incoming), effects after the hit lands (post), kills and drops. */
public final class Hits {
    private Hits() {}

    /** Re-entrancy guard: damage we deal from inside a hit handler must not run the handlers again. */
    static boolean busy;
    /** Set while one of our blasts is exploding, so it never hurts its owner. */
    static ServerPlayer blastOwner;

    static final Map<EntityType<?>, Item> HEADS = Map.of(
            EntityType.ZOMBIE, Items.ZOMBIE_HEAD, EntityType.SKELETON, Items.SKELETON_SKULL,
            EntityType.WITHER_SKELETON, Items.WITHER_SKELETON_SKULL, EntityType.CREEPER, Items.CREEPER_HEAD,
            EntityType.PIGLIN, Items.PIGLIN_HEAD, EntityType.ENDER_DRAGON, Items.DRAGON_HEAD);

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncoming(LivingIncomingDamageEvent e) {
        LivingEntity victim = e.getEntity();
        if (victim.level().isClientSide()) return;
        DamageSource src = e.getSource();
        if (blastOwner != null && victim == blastOwner) { e.setCanceled(true); return; }
        // Firework Artillery: your own rockets never hurt you
        if (victim instanceof ServerPlayer vp && src.is(DamageTypes.FIREWORKS) && src.getEntity() == vp
                && R.hit(vp, "firework_artillery") != null) { e.setCanceled(true); return; }
        if (busy || !(src.getEntity() instanceof ServerPlayer p) || p == victim || !R.real(p)) return;

        double pct = 0;
        // Tracker's Mark: everything you do hurts a marked mob more
        HabitsApi.PerkHit mark = R.hit(p, "trackers_mark");
        if (mark != null && Status.markedBy(p, victim)) pct += R.p(mark, "dmg");

        Shots.Shot s = Shots.get(src.getDirectEntity());
        if (s != null && !s.shard && s.pr instanceof AbstractArrow a && !(a instanceof ThrownTrident)) pct += arrowBonus(s, a, p, victim);
        if (pct != 0) e.setAmount((float) (e.getAmount() * (1 + pct / 100)));
        if (s != null && !s.shard) s.fullHpVictim = s.hits == 0 && victim.getHealth() >= victim.getMaxHealth();
    }

    static double arrowBonus(Shots.Shot s, AbstractArrow a, ServerPlayer p, LivingEntity victim) {
        double pct = s.dmgBonus;
        double dist = victim.position().distanceTo(s.origin);
        HabitsApi.PerkHit h;
        if ((h = R.hit(p, "long_shot")) != null && dist > R.p(h, "from")) pct += Math.min(R.p(h, "cap"), h.value() * (dist - R.p(h, "from")));
        s.headshot = victim.getBbHeight() > 0.6 && a.getY() >= victim.getEyeY() - 0.25;
        if (s.headshot && (h = R.hit(p, "headshot")) != null) {
            pct += h.value();
            p.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, 0.7f, 1.6f);
        }
        if ((h = R.hit(p, "pinning_shot")) != null && Status.pinned(victim)) pct += R.p(h, "dmg");
        if ((h = R.hit(p, "skeet_shooter")) != null && !victim.onGround() && !victim.isInWater()) pct += R.p(h, "dmg");
        if ((h = R.hit(p, "silent_archer")) != null && s.sneak && victim instanceof Mob m && m.getTarget() != p) pct += h.value();
        if ((h = R.hit(p, "horse_archer")) != null && s.ride) pct += h.value();
        if ((h = R.hit(p, "hot_streak")) != null) pct += h.value() * Math.min(R.p(h, "cap"), Status.of(p).streak);
        if ((h = R.hit(p, "tide_archer")) != null && victim.getType().is(EntityTypeTags.AQUATIC)) pct += R.p(h, "aquatic");
        if ((h = R.hit(p, "skewer")) != null && a.shotFromCrossbow()) pct += h.value() * s.hits;
        pct += Ranger.bonus(s, a, p, victim);
        if ((h = R.hit(p, "target_practice")) != null && centerHit(a, victim, R.p(h, "center"))) {
            pct += h.value();
            R.puff((ServerLevel) victim.level(), ParticleTypes.CRIT, victim.getBoundingBox().getCenter(), 8, 0.3);
        }
        return pct;
    }

    /** The arrow's flight line passes within {@code r} blocks of the mob's centre. */
    static boolean centerHit(Entity a, Entity victim, double r) {
        Vec3 v = a.getDeltaMovement();
        if (v.lengthSqr() < 1e-6) return false;
        Vec3 dir = v.normalize(), rel = victim.getBoundingBox().getCenter().subtract(a.position());
        double along = rel.dot(dir);
        return rel.lengthSqr() - along * along <= r * r;
    }

    @SubscribeEvent
    public static void onPost(LivingDamageEvent.Post e) {
        LivingEntity victim = e.getEntity();
        if (victim.level().isClientSide() || e.getNewDamage() <= 0) return;
        Shots.Shot s = Shots.get(e.getSource().getDirectEntity());
        if (s == null) return;
        if (!busy && !s.shard && s.owner != victim && R.real(s.owner)) {
            busy = true;
            try {
                if (s.pr instanceof ThrownTrident t) tridentHit(s, t, victim);
                else if (s.pr instanceof AbstractArrow a) { arrowHit(s, a, victim); Ranger.damaged(s, e.getNewDamage()); }
                else if (s.pr instanceof FireworkRocketEntity && R.hostile(victim)) { /* trigger handled by damage_dealt */ }
            } finally {
                busy = false;
            }
        }
        // Breeze Breaker: vanilla returns early after hurting an enderman (it never expects that hit to land), which
        // leaves the projectile flying on; stop it the way any other mob hit does
        if (victim instanceof EnderMan && s.breaks == 1) {
            if (s.pr instanceof ThrownTrident t) t.setDeltaMovement(t.getDeltaMovement().multiply(-0.01, -0.1, -0.01));
            else if (s.pr instanceof AbstractArrow a && a.getPierceLevel() <= 0) a.discard();
        }
    }

    static void arrowHit(Shots.Shot s, AbstractArrow a, LivingEntity victim) {
        ServerPlayer p = s.owner;
        ServerLevel level = (ServerLevel) victim.level();
        long now = level.getGameTime();
        Vec3 at = victim.position();
        double dist = at.distanceTo(s.origin);
        boolean firstHit = !s.hit;
        s.hit = true;
        s.hits++;
        Status.P st = Status.of(p);

        // ---- card events
        if (R.hostile(victim)) R.fire(p, "arrow_hit_hostile", at);
        if (dist >= 30) R.fire(p, "arrow_hit_30", at);
        if (dist >= 100) R.fire(p, "arrow_hit_100", at);
        if (dist <= 4) R.fire(p, "arrow_hit_close", at);
        if (s.headshot) R.fire(p, "headshot", at);
        if (s.pitch >= 60) R.fire(p, "steep_arrow_hit", at);
        if (s.charge >= 80) R.fire(p, "overdrawn_hit", at);
        if (a instanceof SpectralArrow) R.fire(p, "spectral_hit", at);
        PotionContents pc = a.getPickupItemStackOrigin().get(DataComponents.POTION_CONTENTS);
        if (a instanceof Arrow && pc != null && pc.hasEffects()) R.fire(p, "tipped_arrow_hit", at);
        if (!victim.onGround() && !victim.isInWater()) R.fire(p, "airborne_hit", at);
        if (s.moving) R.fire(p, "moving_hit", at);
        if (s.ride) R.fire(p, "mounted_hit", at);
        if (s.glide) R.fire(p, "gliding_hit", at);
        if (a.isInWater()) R.fire(p, "underwater_arrow_hit", at);
        if (victim instanceof Mob m && m.getTarget() != p && s.sneak) R.fire(p, "unaware_hit", at);
        if (s.covers > 0) R.fire(p, "arrow_through_cover", at);
        if (a.shotFromCrossbow() && s.hits == 2) R.fire(p, "bolt_pierce_two", at);
        if (s.volley >= 0) volley(p, st, s.volley, at);
        boolean wall = wallBehind(a, victim);
        if (wall) R.fire(p, "arrow_hit_near_wall", at);
        List<Mob> crowd = level.getEntitiesOfClass(Mob.class, victim.getBoundingBox().inflate(5), m -> m != victim && R.hostile(m));
        if (crowd.size() >= 3) R.fire(p, "arrow_hit_crowd", at);

        // ---- streaks and follow-ups
        if (firstHit) {
            FallingStar.landed(s, at);
            st.streak++;
            st.lastHitTick = now;
            if (st.streak == 5) R.fire(p, "arrow_streak_5", at);
            HabitsApi.PerkHit hs = R.hit(p, "hot_streak");
            if (hs != null) p.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.5f,
                    (float) Math.min(2, 0.6 + 0.1 * Math.min(R.p(hs, "cap"), st.streak)));
            HabitsApi.PerkHit qd = R.hit(p, "quickdraw");
            if (qd != null && R.roll(p, qd.value())) RangedNet.quick(p, st, (int) R.p(qd, "chain"));
        }

        boolean boss = R.boss(victim), mob = victim instanceof Mob;
        HabitsApi.PerkHit h;
        if ((h = R.hit(p, "point_blank")) != null && dist <= R.p(h, "range")) pointBlank(h, p, victim, boss);
        if ((h = R.hit(p, "pinning_shot")) != null && wall && mob && !boss) Status.pin((Mob) victim, now + (long) (h.value() * 20));
        if ((h = R.hit(p, "trackers_mark")) != null && victim.isAlive())
            Status.glow(p, victim, now + (long) (h.value() * 20), true, (int) R.p(h, "max"));
        if ((h = R.hit(p, "venom_fletcher")) != null && a instanceof Arrow && pc != null && pc.hasEffects()) venom(h, p, a, victim, pc);
        if ((h = R.hit(p, "skeet_shooter")) != null && mob && !boss && !victim.onGround() && R.p(h, "drop") > 0)
            Status.GROUNDED.put((Mob) victim, now + (long) (R.p(h, "drop") * 20));
        if ((h = R.hit(p, "split_shot")) != null && firstHit) split(h, p, a, victim);
        if ((h = R.hit(p, "skewer")) != null && a.shotFromCrossbow() && R.p(h, "drag") > 0 && !boss && a.getPierceLevel() > 0) {
            Vec3 d = a.getDeltaMovement().normalize().scale(0.25 * R.p(h, "drag"));
            victim.setDeltaMovement(victim.getDeltaMovement().add(d));
            victim.hurtMarked = true;
        }
        if ((h = R.hit(p, "shulker_sniper")) != null && !boss && victim.isAlive() && R.roll(p, h.value()))
            victim.addEffect(new MobEffectInstance(MobEffects.LEVITATION, (int) (R.p(h, "secs") * 20)), p);
        if ((h = R.hit(p, "echo_shot")) != null) {
            int max = (int) R.p(h, "max");
            for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, victim.getBoundingBox().inflate(h.value()), l -> l != p)) {
                if (max-- <= 0) break;
                Status.glow(p, l, now + (long) (R.p(h, "secs") * 20), false, 0);
            }
            level.playSound(null, at.x, at.y, at.z, SoundEvents.SCULK_CLICKING, SoundSource.PLAYERS, 0.8f, 1.2f);
        }
        if ((h = R.hit(p, "horse_archer")) != null && s.ride && p.getVehicle() instanceof LivingEntity mount)
            mount.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, (int) (R.p(h, "speed_s") * 20), (int) R.p(h, "speed_amp")));
        if ((h = R.hit(p, "silent_archer")) != null && s.sneak && R.p(h, "calm") > 0) {
            long until = now + (long) (R.p(h, "calm_s") * 20);
            for (Mob m : level.getEntitiesOfClass(Mob.class, victim.getBoundingBox().inflate(R.p(h, "calm")), m -> m != victim && m.getTarget() != p))
                Status.CALM.put(m, new Status.Calm(p.getUUID(), until));
        }
        Ranger.arrowHit(s, a, victim, firstHit);
        // Finisher last, so it sees the health every other bonus left
        if ((h = R.hit(p, "finisher")) != null && victim.isAlive() && mob && !boss
                && victim.getHealth() <= victim.getMaxHealth() * h.value() / 100) {
            victim.invulnerableTime = 0;
            victim.hurt(a.damageSources().arrow(a, p), victim.getHealth() + 1000);
            R.puff(level, ParticleTypes.DAMAGE_INDICATOR, victim.getBoundingBox().getCenter(), 6, 0.3);
        }
    }

    /** Fan of Bolts: count the mobs one multishot volley hits. */
    static void volley(ServerPlayer p, Status.P st, long volleyTick, Vec3 at) {
        if (st.volleyTick != volleyTick) { st.volleyTick = volleyTick; st.volleyHits = 0; }
        if (++st.volleyHits == 2) R.fire(p, "multishot_double", at);
    }

    /** Solid block within 1 block behind the mob, along the arrow's flight. */
    /** Point Blank: knock the mob away and hop the archer back. */
    static void pointBlank(HabitsApi.PerkHit h, ServerPlayer p, LivingEntity victim, boolean boss) {
        Vec3 push = victim.position().subtract(p.position()).multiply(1, 0, 1).normalize();
        if (!boss) victim.knockback(h.value(), -push.x, -push.z);
        double hop = R.p(h, "hop");
        p.setDeltaMovement(p.getDeltaMovement().add(push.scale(-0.3 * hop)).add(0, 0.25 + 0.05 * hop, 0));
        p.hurtMarked = true;
        int speed = (int) (R.p(h, "speed") * 20);
        if (speed > 0) p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, speed, 1));
    }

    static boolean wallBehind(AbstractArrow a, LivingEntity victim) {
        Vec3 dir = a.getDeltaMovement().multiply(1, 0, 1);
        if (dir.lengthSqr() < 1e-6) return false;
        dir = dir.normalize();
        Vec3 from = victim.getBoundingBox().getCenter();
        Vec3 to = from.add(dir.scale(victim.getBbWidth() * 0.5 + 1));
        BlockHitResult r = victim.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, victim));
        return r.getType() == HitResult.Type.BLOCK;
    }

    static void venom(HabitsApi.PerkHit h, ServerPlayer p, AbstractArrow a, LivingEntity victim, PotionContents pc) {
        double mult = 1 + h.value() / 100;
        for (MobEffectInstance in : pc.getAllEffects()) {
            Holder<MobEffect> eff = in.getEffect();
            if (eff.value().isInstantenous()) continue;
            MobEffectInstance cur = victim.getEffect(eff);
            if (cur != null && !cur.isInfiniteDuration())
                victim.forceAddEffect(new MobEffectInstance(eff, (int) (cur.getDuration() * mult), cur.getAmplifier(), cur.isAmbient(), cur.isVisible()), p);
        }
        double radius = R.p(h, "cloud");
        if (radius <= 0) return;
        AreaEffectCloud c = new AreaEffectCloud(victim.level(), victim.getX(), victim.getY(), victim.getZ());
        c.setOwner(p);
        c.setRadius((float) radius);
        c.setDuration((int) (R.p(h, "cloud_s") * 20));
        c.setRadiusPerTick(0);
        c.setWaitTime(0);
        c.setPotionContents(pc);
        victim.level().addFreshEntity(c);
    }

    static void split(HabitsApi.PerkHit h, ServerPlayer p, AbstractArrow a, LivingEntity victim) {
        int n = (int) h.value();
        double range = R.p(h, "range"), dmg = R.p(h, "dmg") / 100;
        Vec3 from = victim.getBoundingBox().getCenter().add(0, victim.getBbHeight() * 0.5 + 0.3, 0);
        List<Mob> near = victim.level().getEntitiesOfClass(Mob.class, victim.getBoundingBox().inflate(range), m -> m != victim && R.hostile(m));
        near.sort((x, y) -> Double.compare(x.distanceToSqr(victim), y.distanceToSqr(victim)));
        for (int i = 0; i < Math.min(n, near.size()); i++) {
            Mob t = near.get(i);
            Arrow shard = new Arrow(victim.level(), from.x, from.y, from.z, new ItemStack(Items.ARROW), null);
            shard.pickup = AbstractArrow.Pickup.DISALLOWED;
            shard.setBaseDamage(a.getBaseDamage() * dmg);
            Vec3 to = t.getBoundingBox().getCenter().subtract(from);
            shard.shoot(to.x, to.y + to.horizontalDistance() * 0.05, to.z, 1.6f, 0);
            victim.level().addFreshEntity(shard);
            shard.setOwner(p);
            Shots.shard(p, shard);
        }
        if (!near.isEmpty()) R.puff((ServerLevel) victim.level(), ParticleTypes.CRIT, from, 6, 0.2);
    }

    // ------------------------------------------------------------------ tridents

    static void tridentHit(Shots.Shot s, ThrownTrident t, LivingEntity victim) {
        ServerPlayer p = s.owner;
        ServerLevel level = (ServerLevel) victim.level();
        Vec3 at = victim.position();
        boolean boss = R.boss(victim);
        if (at.distanceTo(s.origin) >= 20) R.fire(p, "trident_long_hit", at);
        boolean wet = Status.wet(victim);
        if (wet) R.fire(p, "trident_wet_hit", at);
        if (victim.getType().is(EntityTypeTags.AQUATIC) || victim.getType() == EntityType.DROWNED || victim.getType() == EntityType.FROG)
            R.fire(p, "trident_aquatic_hit", at);
        HabitsApi.PerkHit h;
        if ((h = R.hit(p, "tidecaller")) != null && wet) {
            double r = h.value();
            for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, victim.getBoundingBox().inflate(r), R::hostile)) {
                if (l.distanceToSqr(victim) > r * r) continue;
                if (l != victim) l.hurt(t.damageSources().trident(t, p), (float) R.p(h, "dmg"));
                Vec3 d = l.position().subtract(victim.position()).multiply(1, 0, 1);
                if (!R.boss(l)) l.knockback(0.6, -d.x, -d.z);
            }
            R.puff(level, ParticleTypes.SPLASH, victim.position().add(0, 0.5, 0), 24, r * 0.5);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1, 1);
        }
        if ((h = R.hit(p, "harpoon")) != null && !boss && victim.getBbWidth() <= 1.95 && victim.getBbHeight() <= 2.2) {
            Vec3 to = p.position().subtract(victim.position());
            double pull = Math.min(h.value(), Math.max(0, to.length() - 1.5));
            if (pull > 0) {
                // ground friction eats about 0.55 of the speed per tick, so speed ~ 0.45 * blocks lands near the target
                victim.setDeltaMovement(to.normalize().scale(0.45 * Math.sqrt(pull) * 0.9).add(0, 0.25, 0));
                victim.hurtMarked = true;
            }
        }
        if ((h = R.hit(p, "stormcaller")) != null && R.roll(p, h.value())) storm(h, p, victim);
        Ranger.tridentHit(s, t, victim);
    }

    static void storm(HabitsApi.PerkHit h, ServerPlayer p, LivingEntity first) {
        ServerLevel level = (ServerLevel) first.level();
        int chain = (int) R.p(h, "chain");
        float dmg = (float) R.p(h, "dmg");
        DamageSource src = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(DamageTypes.LIGHTNING_BOLT), p);
        LivingEntity cur = first;
        java.util.List<LivingEntity> done = new java.util.ArrayList<>();
        for (int i = 0; i <= chain && cur != null; i++) {
            done.add(cur);
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
            if (bolt != null) {
                bolt.moveTo(cur.getX(), cur.getY(), cur.getZ());
                bolt.setVisualOnly(true); // visual only: no fire, no villager-to-witch or pig-to-piglin conversions
                level.addFreshEntity(bolt);
            }
            cur.invulnerableTime = 0;
            cur.hurt(src, dmg);
            LivingEntity from = cur;
            cur = null;
            double best = R.p(h, "range") * R.p(h, "range");
            for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, from.getBoundingBox().inflate(R.p(h, "range")), R::hostile)) {
                double d = l.distanceToSqr(from);
                if (!done.contains(l) && d < best) { best = d; cur = l; }
            }
        }
    }

    // ------------------------------------------------------------------ deaths and drops

    /** Card event: a lingering cloud you threw applies an effect to a mob. */
    @SubscribeEvent
    public static void onEffect(MobEffectEvent.Added e) {
        if (e.getEffectSource() instanceof AreaEffectCloud c && c.getOwner() instanceof ServerPlayer p && e.getEntity() instanceof Mob)
            R.fire(p, "lingering_hit", e.getEntity().position());
    }

    @SubscribeEvent
    public static void onBolt(EntityStruckByLightningEvent e) {
        // vanilla channeling bolts hurt with a damage source that has no attacker; remember who called this one
        if (e.getLightning().getCause() != null && e.getEntity() instanceof LivingEntity l && !l.level().isClientSide()) {
            l.getPersistentData().putLong("habits_bolt", l.level().getGameTime());
            l.getPersistentData().putUUID("habits_bolt_by", e.getLightning().getCause().getUUID());
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Level level = dead.level();
        if (level.isClientSide()) return;
        DamageSource src = e.getSource();
        if (src.is(DamageTypeTags.IS_LIGHTNING)) {
            ServerPlayer p = src.getEntity() instanceof ServerPlayer sp ? sp : null;
            var tag = dead.getPersistentData();
            if (p == null && tag.hasUUID("habits_bolt_by") && level.getGameTime() - tag.getLong("habits_bolt") < 3)
                p = level.getPlayerByUUID(tag.getUUID("habits_bolt_by")) instanceof ServerPlayer sp ? sp : null;
            if (p != null) R.fire(p, "channel_kill", dead.position());
        }
        Shots.Shot s = Shots.get(src.getDirectEntity());
        if (s != null && !s.shard && s.pr instanceof AbstractArrow && !(s.pr instanceof ThrownTrident) && s.fullHpVictim)
            R.fire(s.owner, "one_shot_kill", dead.position());
        if (s != null && !s.shard && s.pr instanceof AbstractArrow a && !(s.pr instanceof ThrownTrident) && R.real(s.owner)) Ranger.killed(s, a, dead);
        Splash.onDeath(e);
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent e) {
        LivingEntity dead = e.getEntity();
        if (dead.level().isClientSide()) return;
        Shots.Shot s = Shots.get(e.getSource().getDirectEntity());
        if (s == null || s.shard || !R.real(s.owner)) return;
        ServerPlayer p = s.owner;
        HabitsApi.PerkHit h;
        if (s.pr instanceof AbstractArrow && !(s.pr instanceof ThrownTrident)) {
            Item head = HEADS.get(dead.getType());
            if (s.headshot && head != null && (h = R.hit(p, "headshot")) != null && R.roll(p, R.p(h, "head"))) add(e, dead, new ItemStack(head));
            if ((h = R.hit(p, "hunters_instinct")) != null) cook(e, h, p, dead);
            Ranger.drops(e, p, dead);
        }
        if (s.pr instanceof ThrownTrident && dead instanceof Drowned d && (h = R.hit(p, "trident_thief")) != null) {
            if (d.getMainHandItem().is(Items.TRIDENT) && R.roll(p, h.value())) {
                ItemStack tr = new ItemStack(Items.TRIDENT);
                tr.setDamageValue(p.getRandom().nextInt(Math.max(1, tr.getMaxDamage() / 2)));
                add(e, dead, tr);
            }
            if (R.roll(p, R.p(h, "shell"))) add(e, dead, new ItemStack(Items.NAUTILUS_SHELL));
        }
    }

    static void cook(LivingDropsEvent e, HabitsApi.PerkHit h, ServerPlayer p, LivingEntity dead) {
        ServerLevel level = (ServerLevel) dead.level();
        Item meat = null;
        for (ItemEntity ie : e.getDrops()) {
            ItemStack st = ie.getItem();
            if (!st.has(DataComponents.FOOD) || !R.roll(p, h.value())) continue;
            var r = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(st), level);
            if (r.isEmpty()) continue;
            ItemStack out = r.get().value().getResultItem(level.registryAccess()).copyWithCount(st.getCount());
            if (out.isEmpty()) continue;
            ie.setItem(out);
            meat = out.getItem();
        }
        int extra = (int) R.p(h, "extra");
        if (meat != null && extra > 0) add(e, dead, new ItemStack(meat, 1 + p.getRandom().nextInt(extra)));
    }

    static void add(LivingDropsEvent e, LivingEntity dead, ItemStack st) {
        e.getDrops().add(new ItemEntity(dead.level(), dead.getX(), dead.getY() + 0.3, dead.getZ(), st));
    }

    /** Explosion that hurts mobs but never its owner and never breaks blocks. */
    static void blast(ServerPlayer owner, Entity source, Vec3 at, float power) {
        blastOwner = owner;
        try {
            source.level().explode(source, source.damageSources().explosion(source, owner), null, at.x, at.y, at.z, power, false,
                    Level.ExplosionInteraction.NONE);
        } finally {
            blastOwner = null;
        }
    }

}
