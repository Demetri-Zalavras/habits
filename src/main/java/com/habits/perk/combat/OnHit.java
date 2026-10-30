package com.habits.perk.combat;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.TriggerType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Everything that happens when a player's melee hit lands (LivingDamageEvent.Post). Secondary damage is queued with
 * {@link Tracked#later} so it never re-enters the hurt call in progress. */
final class OnHit {
    private OnHit() {}

    static final String PINNED = "habits_pinned", HUNTED = "habits_hunted", PICKED = "habits_picked", LOST = "lost", DAZE = "daze";
    static final ResourceLocation SUNDER_ID = Habits.id("melee/sunder");

    static void hit(ServerPlayer p, LivingEntity v, float dmg) {
        PS s = PS.of(p);
        long now = Hooks.now(p);
        boolean swing = s.attackTick == now;
        boolean main = swing && v.getId() == s.mainTarget;
        boolean crit = main && s.critTick == now && s.critTarget == v.getId();
        ItemStack hand = p.getMainHandItem();
        if (swing) s.swingHits++;

        // ---- triggers (fire for everyone; the card roll is core's)
        M.fire(p, "melee_hit", dmg, hand, v);
        boolean smash = hand.is(Items.MACE) && MaceItem.canSmashAttack(p);
        if (main) {
            s.lastMainDamage = dmg;
            s.lastHitId = v.getId();
            if (crit) M.fire(p, "crit", 1, hand, v);
            if (smash) {
                M.fire(p, "mace_smash", p.fallDistance, hand, v);
                if (ench(p, hand, Enchantments.WIND_BURST) > 0) M.fire(p, "gale", 1, hand, v);
            }
            if (v.getArmorValue() > 0) M.fire(p, "armored_hit", 1, hand, v);
            if (Damage.behind(v, p)) M.fire(p, "backstab", 1, hand, v);
            if (s.sprintHit) M.fire(p, "sprint_hit", 1, hand, v);
            if (p.isPassenger()) M.fire(p, "mounted_hit", 1, hand, v);
            if (p.isEyeInFluid(FluidTags.WATER)) M.fire(p, "underwater_hit", 1, hand, v);
            if (p.getOffhandItem().is(Items.SHIELD)) M.fire(p, "shield_hit", 1, hand, v);
            if (Damage.weapon(hand) && Damage.weapon(p.getOffhandItem())) M.fire(p, "dual_wield_hit", 1, hand, v);
            if (ench(p, hand, Enchantments.FIRE_ASPECT) > 0) M.fire(p, TriggerType.CUSTOM_UNITS, "wildfire", 1, hand, v);
            multiHit(p, s, v, now);
        }

        // ---- per-victim effects
        HabitsApi.PerkHit h;
        boolean boss = M.boss(v);
        if (main && (s.sprintHit || ench(p, hand, Enchantments.KNOCKBACK) > 0)) {
            h = M.hit(p, Mech.WALL_SLAM);
            Slam.track(p, v, h == null ? 0 : (float) h.value(), h == null ? 0 : (int) M.p(h, "chain"), 20);
        }
        if (main && hand.is(ItemTags.SHOVELS) && (h = M.hit(p, Mech.HOME_RUN)) != null) Slam.track(p, v, (float) M.p(h, "wall"), 0, 30);
        if (hand.getItem() instanceof TieredItem ti && ti.getTier() == Tiers.WOOD && (h = M.hit(p, Mech.SPLINTERS)) != null) bleed(p, v, h);
        if (!boss && (h = M.hit(p, Mech.CONCUSSION)) != null && M.roll(p, h.value())) daze(v, M.ticks(M.p(h, "seconds")));
        if ((h = M.hit(p, Mech.SUNDER)) != null && v.getArmorValue() > 0) sunder(v, h);
        if (hand.is(Items.TRIDENT) && v.isInWater() && (h = M.hit(p, Mech.UNDERTOW)) != null) undertow(v, h);
        if (v.getType().is(EntityTypeTags.UNDEAD) && (h = M.hit(p, Mech.HOLY_STRIKE)) != null) v.igniteForSeconds((float) h.value());
        if (v.getType().is(EntityTypeTags.ARTHROPOD) && !boss && (h = M.hit(p, Mech.EXTERMINATOR)) != null) daze(v, M.ticks(h.value()));
        if ((h = M.hit(p, Mech.WILDFIRE)) != null && (v.isOnFire() || ench(p, hand, Enchantments.FIRE_ASPECT) > 0)) Slam.wildfire(p, v, h);
        if (!v.getTags().contains(PICKED) && (h = M.hit(p, Mech.PICKPOCKET)) != null) {
            v.addTag(PICKED);
            if (M.roll(p, h.value())) Tracked.later(() -> OnKill.rollLoot(p, v, 1, v::spawnAtLocation));
        }
        if ((h = M.hit(p, Mech.FROSTBITE)) != null && v.canFreeze()) frost(p, v, h, boss);
        if ((h = M.hit(p, Mech.WITHER_HAND)) != null && M.roll(p, M.p(h, "chance")))
            v.addEffect(new MobEffectInstance(MobEffects.WITHER, M.ticks(h.value()), (int) M.p(h, "level")), p);
        if ((h = M.hit(p, Mech.STICKY_BLADE)) != null) glue(v, M.ticks(h.value()), (int) M.p(h, "level"));
        if ((h = M.hit(p, Mech.PINNED)) != null) {
            v.addTag(PINNED);
            v.getPersistentData().putLong(PINNED, now + M.ticks(h.value()));
        }
        if (!boss && (h = M.hit(p, Mech.LEVITATE_LOCK)) != null && M.roll(p, h.value()))
            v.addEffect(new MobEffectInstance(MobEffects.LEVITATION, M.ticks(M.p(h, "seconds"))), p);
        if ((h = M.hit(p, Mech.HEX_BREAKER)) != null && M.roll(p, h.value())) hex(p, v, h);
        if (!boss && (h = M.hit(p, Mech.INK_BLADE)) != null && M.roll(p, h.value())) {
            int t = M.ticks(M.p(h, "seconds"));
            lose(v, t);
            v.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, t), p);
            M.fx(v, ParticleTypes.SQUID_INK, 8, 0.3);
        }
        if (v instanceof Mob mob && !v.getMainHandItem().isEmpty() && !boss && (h = M.hit(p, Mech.DISARM)) != null && M.roll(p, h.value())) {
            ItemStack it = mob.getMainHandItem().copy();
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            Tracked.later(() -> mob.spawnAtLocation(it));
        }
        if (v instanceof Creeper c && c.getSwellDir() > 0 && M.hit(p, Mech.VOLATILE) != null) c.setSwellDir(-1);
        if (s.emberHits > 0 && M.hit(p, Mech.EMBER_HEART) != null) {
            s.emberHits--;
            v.igniteForSeconds(4);
        }
        if (!boss && !v.onGround() && !v.isInWater() && (h = M.hit(p, Mech.SKY_SWATTER)) != null) {
            double slam = M.p(h, "slam");
            Tracked.later(() -> push(v, new Vec3(v.getDeltaMovement().x, -slam, v.getDeltaMovement().z)));
        }
        Warrior.hit(p, s, v, dmg, main, crit, smash, boss, now);
        if (!main) return;

        // ---- once per swing, on the main target
        if ((h = M.hit(p, Mech.PACK_TACTICS)) != null) pack(p, v);
        if (crit && hand.isEmpty() && !boss && (h = M.hit(p, Mech.HAYMAKER)) != null) haymaker(p, v, h);
        if (smash && (h = M.hit(p, Mech.METEOR)) != null) meteor(p, v, h, dmg);
        if (smash && (h = M.hit(p, Mech.SKYFALL)) != null) skyfall(p, s, h, now);
        if (crit && (h = M.hit(p, Mech.CHAIN_CRIT)) != null) chain(p, v, h, dmg);
        if (crit && (h = M.hit(p, Mech.FANG_STRIKE)) != null && now >= s.fangReady && M.roll(p, h.value())) {
            s.fangReady = now + M.ticks(M.p(h, "cooldown"));
            fangs(p, v, (int) M.p(h, "fangs"));
        }
        if (crit && (h = M.hit(p, Mech.SONIC_BLADE)) != null && ++s.crits >= h.value() && now >= s.sonicReady) {
            s.crits = 0;
            s.sonicReady = now + M.ticks(M.p(h, "cooldown"));
            Tracked.later(() -> sonic(p, h_(p, Mech.SONIC_BLADE)));
        }
        if (crit && (h = M.hit(p, Mech.STORMBLADE)) != null && now >= s.stormReady && p.level().isRainingAt(v.blockPosition().above())
                && M.roll(p, h.value())) {
            s.stormReady = now + M.ticks(M.p(h, "cooldown"));
            storm(p, v, (float) M.p(h, "damage"));
        }
        if ((h = M.hit(p, Mech.MOLTEN_CORE)) != null && ++s.heat >= h.value()) {
            s.heat = 0;
            ring(p, v, M.p(h, "radius"), (float) M.p(h, "damage"));
        }
        if (hand.is(ItemTags.SWORDS) && (h = M.hit(p, Mech.SWORDPLAY)) != null) swordplay(s, v, h, dmg, p);
        if (hand.is(ItemTags.AXES) && (h = M.hit(p, Mech.CLEAVE)) != null) cleave(p, v, h, dmg);
        if ((h = M.hit(p, Mech.GALE_STRIKE)) != null && ++s.galeHits >= h.value()) {
            s.galeHits = 0;
            double r = M.p(h, "radius");
            Tracked.later(() -> gust(p, r));
        }
        if (Damage.weapon(hand) && Damage.weapon(p.getOffhandItem()) && (h = M.hit(p, Mech.OFF_HAND)) != null && M.roll(p, h.value()))
            Tracked.later(() -> offHand(p, v));
        if (hand.isEmpty() && (h = M.hit(p, Mech.BRAWLER)) != null) {
            s.punchChain = now - s.punchTime <= 20 ? Math.min((int) M.p(h, "max"), s.punchChain + 1) : 1;
            s.punchTime = now;
            Ticks.mod(p, Attributes.ATTACK_SPEED, Ticks.BRAWLER, s.punchChain * M.p(h, "speed") / 100);
        }
        if (s.sprintHit && !boss && M.hit(p, Mech.CHARGE) != null)
            Tracked.later(() -> push(v, v.getDeltaMovement().add(0, 0.35, 0)));
        if (p.isEyeInFluid(FluidTags.WATER) && (h = M.hit(p, Mech.MAELSTROM)) != null) maelstrom(p, v, h);
    }

    static HabitsApi.PerkHit h_(ServerPlayer p, Mech m) {
        return M.hit(p, m);
    }

    static int ench(Player p, ItemStack s, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        if (s.isEnchanted()) {
            var reg = p.level().registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            var holder = reg.getHolder(key);
            if (holder.isPresent()) return s.getEnchantments().getLevel(holder.get());
        }
        return 0;
    }

    static void push(LivingEntity e, Vec3 v) {
        if (!e.isAlive()) return;
        e.setDeltaMovement(v);
        e.hurtMarked = true;
    }

    /** Three distinct mobs hit within 2 seconds. */
    static void multiHit(ServerPlayer p, PS s, LivingEntity v, long now) {
        for (int i = 0; i < 3; i++) if (s.hitIds[i] == v.getId() && now - s.hitTimes[i] <= 40) {
            s.hitTimes[i] = now;
            return;
        }
        s.hitIds[s.hitIdx] = v.getId();
        s.hitTimes[s.hitIdx] = now;
        s.hitIdx = (s.hitIdx + 1) % 3;
        for (int i = 0; i < 3; i++) if (now - s.hitTimes[i] > 40 || s.hitTimes[i] == 0) return;
        M.fire(p, "multi_hit", 3, p.getMainHandItem(), v);
        java.util.Arrays.fill(s.hitTimes, 0);
        java.util.Arrays.fill(s.hitIds, -1);
    }

    // ---------------------------------------------------------------- status effects on mobs

    /** The mob forgets its target and can't pick a new one for {@code ticks}. */
    static void lose(LivingEntity v, int ticks) {
        if (v instanceof Mob m) {
            m.setTarget(null);
            m.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET); // brain mobs (piglins, hoglins) aim from memory
        }
        Tracked.put(v, LOST, new Tracked.Timed(ticks) {});
    }

    /** Frozen in place: no walking, no jumping, no target. */
    static void daze(LivingEntity v, int ticks) {
        lose(v, ticks);
        Tracked.put(v, DAZE, new Tracked.Timed(ticks) {
            @Override
            void run(LivingEntity e) {
                Vec3 d = e.getDeltaMovement();
                e.setDeltaMovement(0, Math.min(0, d.y), 0);
                if (e instanceof Mob m) m.getNavigation().stop();
                if (ticks % 10 == 0) M.fx((ServerLevel) e.level(), ParticleTypes.CRIT, e.getEyePosition().add(0, 0.5, 0), 3, 0.25);
            }
        });
    }

    static void glue(LivingEntity v, int ticks, int level) {
        v.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, level));
        Tracked.put(v, "glue", new Tracked.Timed(ticks) {
            @Override
            void run(LivingEntity e) {
                Vec3 d = e.getDeltaMovement();
                if (d.y > 0) e.setDeltaMovement(d.x, 0, d.z);
            }
        });
    }

    static final class Bleed extends Tracked.Timed {
        final ServerPlayer p;
        double dps;
        int stacks;

        Bleed(ServerPlayer p) {
            super(0);
            this.p = p;
        }

        @Override
        void run(LivingEntity e) {
            if (ticks % 20 == 0) {
                M.hurtStill(e, M.src(p, DamageTypes.GENERIC), (float) (dps * stacks));
                M.fx(e, ParticleTypes.DAMAGE_INDICATOR, stacks, 0.2);
            }
        }
    }

    static void bleed(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h) {
        Bleed b = Tracked.get(v, "bleed") instanceof Bleed x ? x : null;
        if (b == null) Tracked.put(v, "bleed", b = new Bleed(p));
        b.dps = h.value();
        b.stacks = Math.min((int) M.p(h, "stacks"), b.stacks + 1);
        b.ticks = M.ticks(M.p(h, "seconds"));
    }

    static final class Sunder extends Tracked.Timed {
        int stacks;

        Sunder() {
            super(0);
        }

        @Override
        void end(LivingEntity e) {
            AttributeInstance a = e.getAttribute(Attributes.ARMOR);
            if (a != null) a.removeModifier(SUNDER_ID);
        }
    }

    static void sunder(LivingEntity v, HabitsApi.PerkHit h) {
        AttributeInstance a = v.getAttribute(Attributes.ARMOR);
        if (a == null) return;
        Sunder s = Tracked.get(v, "sunder") instanceof Sunder x ? x : null;
        if (s == null) Tracked.put(v, "sunder", s = new Sunder());
        s.stacks = Math.min((int) M.p(h, "stacks"), s.stacks + 1);
        s.ticks = M.ticks(M.p(h, "seconds"));
        a.removeModifier(SUNDER_ID);
        a.addTransientModifier(new AttributeModifier(SUNDER_ID, -h.value() * s.stacks, AttributeModifier.Operation.ADD_VALUE));
        M.fx(v, ParticleTypes.CRIT, 4, 0.3);
    }

    static void undertow(LivingEntity v, HabitsApi.PerkHit h) {
        if (!v.canBreatheUnderwater()) v.setAirSupply(Math.max(-19, v.getAirSupply() - (int) h.value()));
        double sink = M.p(h, "sink") / 20;
        Tracked.put(v, "sink", new Tracked.Timed(40) {
            @Override
            void run(LivingEntity e) {
                if (!e.isInWater()) return;
                Vec3 d = e.getDeltaMovement();
                e.setDeltaMovement(d.x, Math.min(d.y, -sink * 2), d.z);
                e.hurtMarked = true;
            }
        });
        M.fx(v, ParticleTypes.BUBBLE_COLUMN_UP, 8, 0.4);
    }

    static void frost(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h, boolean boss) {
        v.setTicksFrozen(Math.min(v.getTicksRequiredToFreeze() + 160, v.getTicksFrozen() + (int) h.value()));
        M.fx(v, ParticleTypes.SNOWFLAKE, 4, 0.3);
        if (boss || !v.isFullyFrozen() || v.getHealth() >= v.getMaxHealth() * M.p(h, "shatter") / 100) return;
        double r = M.p(h, "radius");
        int add = (int) h.value();
        Tracked.later(() -> {
            if (!v.isAlive()) return;
            M.fx(v, ParticleTypes.ITEM_SNOWBALL, 20, 0.5);
            v.level().playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1, 1.2f);
            M.hurt(v, M.src(p, DamageTypes.FREEZE), v.getHealth() + 20, true);
            if (r >= 0.5) for (LivingEntity f : M.foesNear(p, v.position(), r, v))
                if (f.canFreeze()) f.setTicksFrozen(Math.min(f.getTicksRequiredToFreeze() + 160, f.getTicksFrozen() + add));
        });
    }

    static void hex(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h) {
        boolean stole = false;
        for (MobEffectInstance i : new ArrayList<>(v.getActiveEffects())) {
            if (!i.getEffect().value().isBeneficial()) continue;
            v.removeEffect(i.getEffect());
            if (!stole && M.roll(p, M.p(h, "steal"))) {
                p.addEffect(new MobEffectInstance(i));
                stole = true;
            }
        }
        M.fx(v, ParticleTypes.WITCH, 6, 0.3);
    }

    static void pack(ServerPlayer p, LivingEntity v) {
        if (PS.of(p).packTarget == v.getId()) return; // pets are already on it
        PS.of(p).packTarget = v.getId();
        v.addTag(HUNTED);
        for (TamableAnimal pet : p.level().getEntitiesOfClass(TamableAnimal.class, p.getBoundingBox().inflate(16),
                t -> t.isOwnedBy(p) && !t.isOrderedToSit())) {
            if (pet.getTarget() != v) pet.setTarget(v);
        }
    }

    // ---------------------------------------------------------------- active effects

    static void haymaker(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h) {
        double speed = M.p(h, "distance") / 8;
        Vec3 look = p.getLookAngle();
        Vec3 dir = new Vec3(look.x, 0, look.z).normalize();
        float impact = (float) h.value();
        Tracked.later(() -> {
            push(v, new Vec3(dir.x * speed, 0.35, dir.z * speed));
            Slam.track(p, v, impact, 0, 30);
        });
    }

    static void meteor(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h, float dmg) {
        double r = M.p(h, "radius");
        float wave = (float) (dmg * h.value() / 100);
        Tracked.later(() -> {
            ServerLevel sl = (ServerLevel) p.level();
            Vec3 c = v.position();
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                M.fx(sl, ParticleTypes.CLOUD, c.add(Math.cos(a) * r, 0.1, Math.sin(a) * r), 1, 0.05);
            }
            for (LivingEntity f : M.foesNear(p, c, r, v)) {
                if (!f.onGround()) continue;
                M.hurt(f, p.damageSources().playerAttack(p), wave, false);
                push(f, f.getDeltaMovement().add(0, 0.6, 0));
            }
        });
    }

    static void skyfall(ServerPlayer p, PS s, HabitsApi.PerkHit h, long now) {
        double v0 = Math.min(1.6, Math.sqrt(0.18 * h.value()));
        s.noFallUntil = now + 200;
        Tracked.later(() -> {
            p.resetFallDistance();
            p.setDeltaMovement(p.getDeltaMovement().x, v0, p.getDeltaMovement().z);
            p.hurtMarked = true;
        });
    }

    static void chain(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h, float dmg) {
        int jumps = (int) M.p(h, "jumps");
        double r = M.p(h, "radius");
        float arc = (float) (dmg * h.value() / 100);
        Tracked.later(() -> {
            List<LivingEntity> hit = new ArrayList<>(jumps + 1);
            hit.add(v);
            LivingEntity cur = v;
            ServerLevel sl = (ServerLevel) p.level();
            for (int j = 0; j < jumps; j++) {
                LivingEntity best = null;
                double bd = Double.MAX_VALUE;
                for (LivingEntity f : M.foesNear(p, cur.position(), r, null)) {
                    if (hit.contains(f)) continue;
                    double d = f.distanceToSqr(cur);
                    if (d < bd) {
                        bd = d;
                        best = f;
                    }
                }
                if (best == null) break;
                M.line(sl, ParticleTypes.ELECTRIC_SPARK, cur.position().add(0, cur.getBbHeight() / 2, 0), best.position().add(0, best.getBbHeight() / 2, 0));
                M.hurt(best, p.damageSources().indirectMagic(p, p), arc, false);
                hit.add(best);
                cur = best;
            }
        });
    }

    static void fangs(ServerPlayer p, LivingEntity v, int count) {
        ServerLevel sl = (ServerLevel) p.level();
        double dx = v.getX() - p.getX(), dz = v.getZ() - p.getZ();
        double base = Math.atan2(dz, dx);
        int lines = count > 6 ? 3 : 1, per = Math.max(1, count / lines);
        for (int l = 0; l < lines; l++) {
            double a = base + (l - (lines - 1) / 2.0) * 0.45;
            for (int i = 0; i < per; i++) {
                double d = 1.25 * (i + 1);
                double x = p.getX() + Math.cos(a) * d, y = v.getY(), z = p.getZ() + Math.sin(a) * d;
                // one fang per line per tick: the line still ripples outward, and spawning is spread over ticks
                Tracked.laterIn(i, () -> {
                    if (p.isAlive()) sl.addFreshEntity(new EvokerFangs(sl, x, y, z, (float) a, 0, p));
                });
            }
        }
    }

    static void sonic(ServerPlayer p, HabitsApi.PerkHit h) {
        if (h == null || !p.isAlive()) return;
        ServerLevel sl = (ServerLevel) p.level();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        int range = (int) M.p(h, "range");
        float dmg = (float) M.p(h, "damage");
        List<LivingEntity> hit = new ArrayList<>();
        for (int i = 1; i <= range; i++) {
            Vec3 c = eye.add(look.scale(i));
            sl.sendParticles(ParticleTypes.SONIC_BOOM, c.x, c.y, c.z, 1, 0, 0, 0, 0);
            for (LivingEntity f : sl.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(0.75), f -> M.foe(p, f))) {
                if (hit.contains(f)) continue;
                hit.add(f);
                M.hurt(f, p.damageSources().sonicBoom(p), dmg, true);
            }
        }
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1, 1);
    }

    static void storm(ServerPlayer p, LivingEntity v, float dmg) {
        Tracked.later(() -> {
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(p.level());
            if (bolt == null || !v.isAlive()) return;
            bolt.moveTo(v.getX(), v.getY(), v.getZ());
            bolt.setVisualOnly(true); // no fire, no charged creepers, no witches
            p.level().addFreshEntity(bolt);
            M.hurt(v, M.src(p, DamageTypes.LIGHTNING_BOLT), dmg, true);
        });
    }

    static void ring(ServerPlayer p, LivingEntity v, double r, float dmg) {
        Tracked.later(() -> {
            ServerLevel sl = (ServerLevel) p.level();
            Vec3 c = v.position();
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                M.fx(sl, ParticleTypes.FLAME, c.add(Math.cos(a) * r, 0.2, Math.sin(a) * r), 2, 0.1);
            }
            sl.playSound(null, c.x, c.y, c.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.8f, 0.8f);
            if (v.isAlive()) M.hurt(v, M.src(p, DamageTypes.IN_FIRE), dmg, true);
            for (LivingEntity f : M.foesNear(p, c, r, v)) M.hurt(f, M.src(p, DamageTypes.IN_FIRE), dmg, false);
        });
    }

    static void swordplay(PS s, LivingEntity v, HabitsApi.PerkHit h, float dmg, ServerPlayer p) {
        // a new target breaks the combo here; a miss (core's swing_air) breaks it in Hooks.onSwingAir
        if (v.getId() == s.comboTarget) s.comboCount++;
        else {
            s.comboTarget = v.getId();
            s.comboCount = 1;
        }
        if (s.comboCount < h.value()) return;
        s.comboCount = 0;
        float slash = (float) (dmg * M.p(h, "slash") / 100);
        Tracked.later(() -> {
            if (!v.isAlive()) return;
            M.hurt(v, p.damageSources().playerAttack(p), slash, true);
            M.fx(v, ParticleTypes.SWEEP_ATTACK, 1, 0);
        });
    }

    static void cleave(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h, float dmg) {
        int n = (int) M.p(h, "targets");
        double r = M.p(h, "radius");
        float d = (float) (dmg * h.value() / 100);
        Tracked.later(() -> {
            List<LivingEntity> near = M.foesNear(p, v.position(), r, v);
            near.sort((a, b) -> Double.compare(a.distanceToSqr(v), b.distanceToSqr(v)));
            for (int i = 0; i < Math.min(n, near.size()); i++) {
                M.hurt(near.get(i), p.damageSources().playerAttack(p), d, false);
                M.fx(near.get(i), ParticleTypes.SWEEP_ATTACK, 1, 0);
            }
        });
    }

    static void gust(ServerPlayer p, double r) {
        ServerLevel sl = (ServerLevel) p.level();
        sl.sendParticles(ParticleTypes.GUST_EMITTER_SMALL, p.getX(), p.getY() + 0.5, p.getZ(), 1, 0, 0, 0, 0);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WIND_CHARGE_BURST, SoundSource.PLAYERS, 1, 1);
        for (LivingEntity f : M.foesNear(p, p.position(), r, null)) {
            Vec3 d = f.position().subtract(p.position());
            Vec3 h = new Vec3(d.x, 0, d.z);
            h = h.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : h.normalize();
            push(f, f.getDeltaMovement().add(h.x * 1.4, 0.5, h.z * 1.4));
        }
    }

    static void offHand(ServerPlayer p, LivingEntity v) {
        ItemStack off = p.getOffhandItem();
        if (!v.isAlive() || off.isEmpty()) return;
        double[] dmg = {1};
        off.getAttributeModifiers().forEach(EquipmentSlot.MAINHAND, (attr, mod) -> {
            if (attr.is(Attributes.ATTACK_DAMAGE) && mod.operation() == AttributeModifier.Operation.ADD_VALUE) dmg[0] += mod.amount();
        });
        p.swing(InteractionHand.OFF_HAND, true);
        M.hurt(v, p.damageSources().playerAttack(p), (float) dmg[0], true);
        off.hurtAndBreak(1, p, EquipmentSlot.OFFHAND);
    }

    static void maelstrom(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h) {
        double r = h.value();
        float dps = (float) M.p(h, "damage");
        Tracked.later(() -> {
            Vec3 c = v.position();
            M.fx((ServerLevel) p.level(), ParticleTypes.BUBBLE, c, 16, r / 3);
            for (LivingEntity f : M.foesNear(p, c, r, v)) {
                Vec3 d = c.subtract(f.position());
                if (d.lengthSqr() > 1e-4) push(f, f.getDeltaMovement().add(d.normalize().scale(0.6)));
                if (dps > 0) Tracked.put(f, "whirl", new Tracked.Timed(60) {
                    @Override
                    void run(LivingEntity e) {
                        if (ticks % 20 == 0) M.hurtStill(e, M.src(p, DamageTypes.DROWN), dps);
                    }
                });
            }
        });
    }
}
