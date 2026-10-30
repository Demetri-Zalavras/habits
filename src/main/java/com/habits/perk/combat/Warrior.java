package com.habits.perk.combat;

import com.habits.core.HabitsApi;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.ArrayList;
import java.util.List;

/** The warrior spec batch (2026-10-13): new melee mechanics and their custom trigger events. Called from the existing
 * hooks ({@link OnHit}, {@link OnKill}, {@link Damage}, {@link Defense}, {@link Ticks}, {@link Hooks}); same rules as
 * the rest of the package: no world queries inside damage events, area work goes through {@link Tracked#later}. */
final class Warrior {
    private Warrior() {}

    static final String EXPOSE = "expose", MARK = "death_mark";
    /** Event thresholds (documented in melee.md): giant = max health at least this many times yours; last breath = at or
     * below this much health; a weapon swap counts within this many ticks of the last hit; revenge within this window. */
    static final double GIANT = 3, LAST_BREATH = 2;
    static final int SWAP_TICKS = 40, REVENGE_TICKS = 100, TRADE_TICKS = 20;

    /** 1 sword, 2 axe, 3 mace, 4 trident, 0 anything else. */
    static int kind(ItemStack s) {
        if (s.is(ItemTags.SWORDS)) return 1;
        if (s.is(ItemTags.AXES)) return 2;
        if (s.is(Items.MACE)) return 3;
        if (s.is(Items.TRIDENT)) return 4;
        return 0;
    }

    static boolean airborne(LivingEntity v) {
        return !v.onGround() && !v.isInWater() && !v.isPassenger() && !(v instanceof FlyingMob) && !(v instanceof FlyingAnimal);
    }

    // ---------------------------------------------------------------- damage scaling (LivingIncomingDamageEvent)

    /** Any damage to an exposed mob. */
    static float exposed(LivingEntity v, float amt) {
        if (Tracked.size() > 0 && Tracked.get(v, EXPOSE) instanceof Expose x) return amt * (float) (1 + x.stacks * x.pct / 100);
        return amt;
    }

    /** The player's own melee hit, before armor. */
    static float scale(ServerPlayer p, PS s, LivingEntity v, boolean main, long now, float amt) {
        HabitsApi.PerkHit h;
        ItemStack hand = p.getMainHandItem();
        if (main) {
            int k = kind(hand);
            s.swapHit = k != 0 && s.lastKind != 0 && k != s.lastKind && now - s.lastKindTick <= SWAP_TICKS;
            if (s.swapHit && (h = M.hit(p, Mech.ARSENAL)) != null && now - s.lastKindTick <= M.ticks(M.p(h, "window")))
                amt *= (float) (1 + h.value() / 100);
            if (s.veng > 0 && (h = M.hit(p, Mech.VENGEANCE)) != null) {
                if (now <= s.vengUntil) amt += s.veng;
                s.veng = 0;
            }
            if (s.juggleTarget == v.getId() && s.juggleStacks > 0 && airborne(v) && (h = M.hit(p, Mech.JUGGLER)) != null)
                amt *= (float) (1 + s.juggleStacks * h.value() / 100);
        }
        if ((h = M.hit(p, Mech.COLOSSUS_SLAYER)) != null) {
            double ratio = v.getMaxHealth() / Math.max(1, p.getMaxHealth());
            if (ratio > 1) amt *= (float) (1 + Math.min(M.p(h, "cap"), h.value() * (ratio - 1)) / 100);
        }
        return amt;
    }

    // ---------------------------------------------------------------- defense

    static void incoming(ServerPlayer p, LivingEntity la, LivingIncomingDamageEvent e) {
        HabitsApi.PerkHit h;
        if (e.getSource().getDirectEntity() == la && p.isShiftKeyDown() && p.getMainHandItem().is(ItemTags.SWORDS) && front(p, la)
                && (h = M.hit(p, Mech.BLADE_GUARD)) != null) {
            e.setAmount(e.getAmount() * (float) (1 - Math.min(100, h.value()) / 100));
            M.fx(p, ParticleTypes.CRIT, 4, 0.3);
        }
    }

    /** The attacker is within 60 degrees of where the player looks. */
    static boolean front(ServerPlayer p, LivingEntity la) {
        Vec3 look = p.getLookAngle(), to = la.position().subtract(p.position());
        Vec3 a = new Vec3(look.x, 0, look.z), b = new Vec3(to.x, 0, to.z);
        if (a.lengthSqr() < 1e-6 || b.lengthSqr() < 1e-6) return true;
        return a.normalize().dot(b.normalize()) > 0.5;
    }

    /** The player lost health to a living attacker (LivingDamageEvent.Post). */
    static void hurt(ServerPlayer p, PS s, LivingEntity atk, DamageSource src, float dmg, long now) {
        s.lastAttacker = atk.getId();
        s.lastAttackerTick = now;
        HabitsApi.PerkHit h;
        if ((h = M.hit(p, Mech.VENGEANCE)) != null) {
            s.veng = (float) Math.min(M.p(h, "cap"), (now <= s.vengUntil ? s.veng : 0) + dmg * h.value() / 100);
            s.vengUntil = now + M.ticks(M.p(h, "seconds"));
        }
        if (src.getDirectEntity() == atk && M.foe(p, atk) && now >= s.counterReady && (h = M.hit(p, Mech.COUNTERSTRIKE)) != null
                && M.roll(p, h.value())) {
            s.counterReady = now + M.ticks(M.p(h, "cooldown"));
            float d = (float) (p.getAttributeValue(Attributes.ATTACK_DAMAGE) * M.p(h, "power") / 100);
            Tracked.later(() -> {
                if (!atk.isAlive() || !p.isAlive() || atk.distanceToSqr(p) > 25) return;
                p.swing(InteractionHand.MAIN_HAND, true);
                M.hurt(atk, p.damageSources().playerAttack(p), d, true);
                M.fx(atk, ParticleTypes.SWEEP_ATTACK, 1, 0);
            });
        }
    }

    /** Undying Fury: a blow that would kill leaves you at 1 health and starts the fury; during it you can't drop lower. */
    static void lethal(ServerPlayer p, LivingDamageEvent.Pre e) {
        if (e.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        float hp = p.getHealth();
        if (e.getNewDamage() < hp) return;
        HabitsApi.PerkHit h = M.hit(p, Mech.UNDYING_FURY);
        if (h == null) return;
        PS s = PS.of(p);
        long now = Hooks.now(p);
        if (now > s.furyUntil) {
            if (now < s.furyReady) return;
            s.furyUntil = now + M.ticks(h.value());
            s.furyReady = s.furyUntil + M.ticks(M.p(h, "cooldown"));
            p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, M.ticks(h.value()), (int) M.p(h, "level")));
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 1, 1.4f);
            M.fx(p, ParticleTypes.ANGRY_VILLAGER, 8, 0.5);
        }
        e.setNewDamage(Math.max(0, hp - 1));
    }

    // ---------------------------------------------------------------- melee hit (LivingDamageEvent.Post)

    static void hit(ServerPlayer p, PS s, LivingEntity v, float dmg, boolean main, boolean crit, boolean smash, boolean boss, long now) {
        ItemStack hand = p.getMainHandItem();
        HabitsApi.PerkHit h;
        if (main) {
            if (v.getId() == s.lastAttacker && now - s.lastAttackerTick <= TRADE_TICKS) M.fire(p, "trade_blows", 1, hand, v);
            if (s.swapHit) {
                s.swapHit = false;
                M.fire(p, "weapon_swap_hit", 1, hand, v);
            }
            int k = kind(hand);
            if (k != 0) {
                s.lastKind = k;
                s.lastKindTick = now;
            }
        }
        if (Tracked.size() > 0 && Tracked.get(v, MARK) instanceof Mark mk && mk.p == p) mk.total += dmg;
        if (!main) return;

        if (crit && !boss && (h = M.hit(p, Mech.CRIPPLING_BLOW)) != null)
            v.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, M.ticks(h.value()), (int) M.p(h, "level")), p);
        if (hand.is(ItemTags.SWORDS) && (h = M.hit(p, Mech.EXPOSE)) != null) expose(v, h);
        if ((hand.is(Items.MACE) || hand.is(ItemTags.AXES)) && !boss && v instanceof Mob m && (h = M.hit(p, Mech.ARMOR_CRUSHER)) != null
                && M.roll(p, h.value())) crush(m);
        if (smash && (h = M.hit(p, Mech.GRAVITY_WELL)) != null) well(p, v, h);
        if ((h = M.hit(p, Mech.JUGGLER)) != null) {
            if (!boss && airborne(v)) {
                s.juggleStacks = s.juggleTarget == v.getId() ? Math.min((int) M.p(h, "stacks"), s.juggleStacks + 1) : 1;
                s.juggleTarget = v.getId();
                double pop = M.p(h, "pop");
                Tracked.later(() -> OnHit.push(v, new Vec3(v.getDeltaMovement().x * 0.3, pop, v.getDeltaMovement().z * 0.3)));
            } else s.juggleStacks = 0;
        }
        if (crit && hand.is(ItemTags.AXES) && now >= s.splitReady && (h = M.hit(p, Mech.EARTHSPLITTER)) != null) {
            s.splitReady = now + M.ticks(M.p(h, "cooldown"));
            split(p, h);
        }
        if (hand.is(ItemTags.AXES) && s.strength >= 0.95f && !boss && v.isAlive() && (h = M.hit(p, Mech.GUILLOTINE)) != null
                && v.getHealth() <= v.getMaxHealth() * Math.min(50, h.value()) / 100) {
            Tracked.later(() -> {
                if (!v.isAlive()) return;
                M.fx(v, ParticleTypes.DAMAGE_INDICATOR, 10, 0.3);
                v.level().playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1, 0.6f);
                M.hurt(v, M.src(p, DamageTypes.GENERIC_KILL), v.getHealth() + v.getAbsorptionAmount() + 100, true);
            });
        }
        if (!Tracked.has(v, MARK) && (h = M.hit(p, Mech.DEATH_MARK)) != null) mark(p, v, h, dmg);
    }

    static final class Expose extends Tracked.Timed {
        int stacks;
        double pct;

        Expose() {
            super(0);
        }
    }

    static void expose(LivingEntity v, HabitsApi.PerkHit h) {
        Expose x = Tracked.get(v, EXPOSE) instanceof Expose e ? e : null;
        if (x == null) Tracked.put(v, EXPOSE, x = new Expose());
        x.stacks = Math.min((int) M.p(h, "stacks"), x.stacks + 1);
        x.pct = h.value();
        x.ticks = M.ticks(M.p(h, "seconds"));
        M.fx(v, ParticleTypes.ENCHANTED_HIT, 3, 0.3);
    }

    static void crush(Mob m) {
        List<EquipmentSlot> worn = new ArrayList<>(4);
        for (EquipmentSlot sl : EquipmentSlot.values())
            if (sl.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && !m.getItemBySlot(sl).isEmpty()) worn.add(sl);
        if (worn.isEmpty()) return;
        EquipmentSlot sl = worn.get(m.getRandom().nextInt(worn.size()));
        ItemStack it = m.getItemBySlot(sl).copy();
        m.setItemSlot(sl, ItemStack.EMPTY);
        m.level().playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.SHIELD_BREAK, SoundSource.PLAYERS, 0.8f, 0.8f);
        if (!EnchantmentHelper.has(it, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) Tracked.later(() -> m.spawnAtLocation(it));
    }

    static void well(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h) {
        double r = h.value(), pull = M.p(h, "pull");
        Tracked.later(() -> {
            Vec3 c = v.position();
            M.fx((ServerLevel) p.level(), ParticleTypes.REVERSE_PORTAL, c.add(0, 0.3, 0), 16, r / 3);
            for (LivingEntity f : M.foesNear(p, c, r, v)) {
                Vec3 d = c.subtract(f.position());
                if (d.lengthSqr() > 1e-4) OnHit.push(f, new Vec3(d.x, 0, d.z).normalize().scale(pull).add(0, 0.2, 0));
            }
        });
    }

    /** A fissure along the player's horizontal look: every foe within a block of the line takes the hit and flies up. */
    static void split(ServerPlayer p, HabitsApi.PerkHit h) {
        int len = (int) M.p(h, "length");
        float dmg = (float) h.value();
        Vec3 look = p.getLookAngle();
        Vec3 dir = new Vec3(look.x, 0, look.z);
        if (dir.lengthSqr() < 1e-4) return;
        Vec3 d = dir.normalize(), start = p.position();
        Tracked.later(() -> {
            ServerLevel sl = (ServerLevel) p.level();
            List<LivingEntity> hit = new ArrayList<>();
            for (int i = 1; i <= len; i++) {
                Vec3 c = start.add(d.scale(i));
                var ground = sl.getBlockState(net.minecraft.core.BlockPos.containing(c.x, c.y - 0.5, c.z));
                if (!ground.isAir()) sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), c.x, c.y + 0.1, c.z, 2, 0.2, 0, 0.2, 0.1);
                for (LivingEntity f : sl.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(1, 1, 1), f -> M.foe(p, f))) {
                    if (hit.contains(f) || hit.size() >= M.MAX_AOE) continue;
                    hit.add(f);
                    M.hurt(f, p.damageSources().playerAttack(p), dmg, false);
                    if (!M.boss(f)) OnHit.push(f, f.getDeltaMovement().add(0, 0.6, 0));
                }
            }
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.5f, 1.6f);
        });
    }

    static final class Mark extends Tracked.Timed {
        final ServerPlayer p;
        final HabitsApi.PerkHit h;
        float total;

        Mark(ServerPlayer p, HabitsApi.PerkHit h, int ticks) {
            super(ticks);
            this.p = p;
            this.h = h;
        }

        @Override
        void run(LivingEntity e) {
            if (ticks % 10 == 0) M.fx(e, ParticleTypes.SOUL_FIRE_FLAME, 2, 0.3);
        }

        @Override
        void end(LivingEntity e) {
            if (!p.isAlive() || total <= 0) return;
            float echo = (float) (total * h.value() / 100 * (M.boss(e) ? M.p(h, "boss") / 100 : 1));
            M.fx(e, ParticleTypes.SOUL, 10, 0.4);
            e.level().playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1, 0.8f);
            M.hurt(e, p.damageSources().indirectMagic(p, p), echo, true);
            if (e.isAlive()) return;
            LivingEntity next = null;
            double bd = Double.MAX_VALUE;
            for (LivingEntity f : M.foesNear(p, e.position(), M.p(h, "radius"), e)) {
                double d = f.distanceToSqr(e);
                if (d < bd && !Tracked.has(f, MARK)) {
                    bd = d;
                    next = f;
                }
            }
            if (next != null) {
                M.line((ServerLevel) e.level(), ParticleTypes.SOUL, e.position().add(0, 1, 0), next.position().add(0, 1, 0));
                Tracked.put(next, MARK, new Mark(p, h, M.ticks(M.p(h, "seconds"))));
            }
        }
    }

    static void mark(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h, float first) {
        Mark mk = new Mark(p, h, M.ticks(M.p(h, "seconds")));
        mk.total = first;
        Tracked.put(v, MARK, mk);
    }

    // ---------------------------------------------------------------- melee kill (LivingDeathEvent)

    static void kill(ServerPlayer p, PS s, LivingEntity v, boolean main, boolean boss, boolean streak, long now) {
        ItemStack hand = p.getMainHandItem();
        // ---- triggers
        if (p.level().isNight() && p.level().canSeeSky(p.blockPosition())) M.fire(p, "night_kill", 1, hand, v);
        if (v.getId() == s.lastAttacker && now - s.lastAttackerTick <= REVENGE_TICKS) M.fire(p, "revenge_kill", 1, hand, v);
        for (ItemStack a : v.getArmorSlots()) if (!a.isEmpty()) {
            M.fire(p, "armored_kill", 1, hand, v);
            break;
        }
        if (airborne(v)) M.fire(p, "air_kill", 1, hand, v);
        if (p.isOnFire()) M.fire(p, "burning_kill", 1, hand, v);
        if (v.getMaxHealth() >= p.getMaxHealth() * GIANT) M.fire(p, "giant_kill", 1, hand, v);
        if (p.getHealth() <= LAST_BREATH) M.fire(p, "last_breath_kill", 1, hand, v);
        if (s.attackTick == now) s.swingKills++;

        // ---- perks
        HabitsApi.PerkHit h;
        boolean enemy = v instanceof Enemy;
        if (v.isOnFire() && (h = M.hit(p, Mech.PYRE)) != null) {
            double r = M.p(h, "radius");
            float secs = (float) h.value();
            Vec3 c = v.position();
            Tracked.later(() -> {
                M.fx((ServerLevel) p.level(), ParticleTypes.FLAME, c.add(0, 0.5, 0), 16, r / 3);
                for (LivingEntity f : M.foesNear(p, c, r, v)) f.igniteForSeconds(secs);
            });
        }
        if (enemy && (h = M.hit(p, Mech.RAVENOUS)) != null) p.getFoodData().eat((int) h.value(), (float) M.p(h, "saturation"));
        if ((h = M.hit(p, Mech.BLOODIED_PLATE)) != null) {
            float cap = (float) M.p(h, "cap") * 2;
            roomFor(p, PLATE, cap);
            if (p.getAbsorptionAmount() < cap) p.setAbsorptionAmount(Math.min(cap, p.getAbsorptionAmount() + (float) h.value() * 2));
        }
        if ((h = M.hit(p, Mech.BLOODBATH)) != null) bloodbath(p, v, h);
        if (streak && (h = M.hit(p, Mech.BATTLE_CRY)) != null) cry(p, h);
        if ((h = M.hit(p, Mech.SPIRIT_BLADES)) != null) {
            s.blades = now <= s.bladesUntil ? Math.min((int) h.value(), s.blades + 1) : 1;
            s.bladesUntil = now + M.ticks(M.p(h, "seconds"));
        }
        if (main && now >= s.shadowReady && (h = M.hit(p, Mech.SHADOW_STEP)) != null) shadow(p, s, v, h, now);
        if (now <= s.furyUntil && (h = M.hit(p, Mech.UNDYING_FURY)) != null) p.heal((float) M.p(h, "heal"));
    }

    static final net.minecraft.resources.ResourceLocation PLATE = com.habits.Habits.id("melee/bloodied_plate");

    /** setAbsorptionAmount clamps to the max_absorption attribute (0 without the Absorption effect): raise it to the cap.
     * Left in place after the absorption runs out; it only allows absorption, never grants it. */
    static void roomFor(ServerPlayer p, net.minecraft.resources.ResourceLocation id, double cap) {
        var a = p.getAttribute(Attributes.MAX_ABSORPTION);
        if (a == null) return;
        var old = a.getModifier(id);
        if (old != null && old.amount() == cap) return;
        if (old != null) a.removeModifier(id);
        a.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(id, cap,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
    }

    static void bloodbath(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h) {
        double r = M.p(h, "radius");
        int t = M.ticks(M.p(h, "seconds"));
        double dps = h.value();
        Vec3 c = v.position();
        Tracked.later(() -> {
            M.fx((ServerLevel) p.level(), ParticleTypes.DAMAGE_INDICATOR, c.add(0, 0.8, 0), 12, r / 3);
            for (LivingEntity f : M.foesNear(p, c, r, v)) {
                OnHit.Bleed b = Tracked.get(f, "bleed") instanceof OnHit.Bleed x ? x : null;
                if (b == null) Tracked.put(f, "bleed", b = new OnHit.Bleed(p));
                b.dps = Math.max(b.dps, dps);
                b.stacks = Math.max(1, b.stacks);
                b.ticks = Math.max(b.ticks, t);
            }
        });
    }

    static void cry(ServerPlayer p, HabitsApi.PerkHit h) {
        p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, M.ticks(h.value()), (int) M.p(h, "level")));
        double r = M.p(h, "radius");
        int weak = M.ticks(M.p(h, "weakness"));
        Tracked.later(() -> {
            ServerLevel sl = (ServerLevel) p.level();
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 1, 1.3f);
            M.fx(sl, ParticleTypes.ANGRY_VILLAGER, p.position().add(0, 2, 0), 6, 0.5);
            for (LivingEntity f : M.foesNear(p, p.position(), r, null)) f.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, weak), p);
        });
    }

    /** Blink behind the nearest other foe: 1.5 blocks past it, away from you, if the spot is free. */
    static void shadow(ServerPlayer p, PS s, LivingEntity v, HabitsApi.PerkHit h, long now) {
        double range = h.value();
        double cd = M.p(h, "cooldown");
        Tracked.later(() -> {
            if (!p.isAlive()) return;
            LivingEntity best = null;
            double bd = Double.MAX_VALUE;
            for (LivingEntity f : M.foesNear(p, p.position(), range, v)) {
                double d = f.distanceToSqr(p);
                if (d < bd && p.hasLineOfSight(f)) {
                    bd = d;
                    best = f;
                }
            }
            if (best == null) return;
            Vec3 away = best.position().subtract(p.position());
            Vec3 hz = new Vec3(away.x, 0, away.z);
            if (hz.lengthSqr() < 1e-4) return;
            Vec3 dest = best.position().add(hz.normalize().scale(1.5 + best.getBbWidth() / 2));
            if (!p.level().noCollision(p, p.getBoundingBox().move(dest.subtract(p.position())))) return;
            s.shadowReady = now + M.ticks(cd);
            ServerLevel sl = (ServerLevel) p.level();
            M.fx(sl, ParticleTypes.PORTAL, p.position().add(0, 1, 0), 16, 0.4);
            float yaw = (float) (Math.atan2(-hz.x, hz.z) * 180 / Math.PI) + 180; // face the foe's back
            p.connection.teleport(dest.x, dest.y, dest.z, yaw, p.getXRot());
            sl.playSound(null, dest.x, dest.y, dest.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8f, 1.2f);
        });
    }

    // ---------------------------------------------------------------- drops

    static void drops(ServerPlayer p, LivingEntity v, LivingDropsEvent e) {
        HabitsApi.PerkHit h = M.hit(p, Mech.SCAVENGER);
        if (h == null || !(v instanceof Mob m)) return;
        for (EquipmentSlot sl : EquipmentSlot.values()) {
            ItemStack it = m.getItemBySlot(sl);
            if (it.isEmpty() || EnchantmentHelper.has(it, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP) || !M.roll(p, h.value())) continue;
            ItemStack out = it.copy();
            if (out.isDamageableItem()) // vanilla's wear on dropped mob gear
                out.setDamageValue(out.getMaxDamage() - m.getRandom().nextInt(1 + m.getRandom().nextInt(Math.max(out.getMaxDamage() - 3, 1))));
            m.setItemSlot(sl, ItemStack.EMPTY);
            OnKill.add(e, v, out);
        }
    }

    // ---------------------------------------------------------------- active moves

    /** A sprint swing at the air with a weapon: dash at the nearest foe ahead (30 degree cone, in sight). */
    static boolean lunge(ServerPlayer p) {
        if (!p.isSprinting() || kind(p.getMainHandItem()) == 0) return false;
        HabitsApi.PerkHit h = M.hit(p, Mech.LUNGE);
        if (h == null) return false;
        PS s = PS.of(p);
        long now = Hooks.now(p);
        if (now < s.lungeReady) return false;
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity f : M.foesNear(p, p.position(), h.value(), null)) {
            Vec3 to = f.getBoundingBox().getCenter().subtract(eye);
            double d = to.lengthSqr();
            if (d < 4 || d >= bd || to.normalize().dot(look) < 0.866 || !p.hasLineOfSight(f)) continue;
            bd = d;
            best = f;
        }
        if (best == null) return false;
        s.lungeReady = now + M.ticks(M.p(h, "cooldown"));
        Vec3 to = best.position().subtract(p.position());
        Vec3 hz = new Vec3(to.x, 0, to.z);
        double dist = Math.max(0, hz.length() - 1.5);
        Vec3 vel = hz.normalize().scale(Math.min(2.4, 0.3 + dist * 0.3)).add(0, 0.2, 0);
        p.setDeltaMovement(vel);
        p.hurtMarked = true;
        M.fx((ServerLevel) p.level(), ParticleTypes.CLOUD, p.position(), 6, 0.2);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8f, 1.5f);
        return true;
    }

    /** Sneak + right-click the air with an axe: the throw hits the first foe along your look (no projectile entity; the
     * axe stays in your hand and takes 1 durability). */
    static boolean hatchet(ServerPlayer p, InteractionHand hand, ItemStack axe) {
        if (hand != InteractionHand.MAIN_HAND || !p.isShiftKeyDown() || !axe.is(ItemTags.AXES)) return false;
        HabitsApi.PerkHit h = M.hit(p, Mech.HATCHET_THROW);
        if (h == null) return false;
        PS s = PS.of(p);
        long now = Hooks.now(p);
        if (now < s.hatchetReady) return false;
        s.hatchetReady = now + M.ticks(M.p(h, "cooldown"));
        ServerLevel sl = (ServerLevel) p.level();
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        int range = (int) M.p(h, "range");
        Vec3 end = eye.add(look.scale(range));
        var block = sl.clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        double max = block.getLocation().distanceTo(eye);
        LivingEntity target = null;
        for (double i = 1; i <= max && target == null; i += 0.5) {
            Vec3 c = eye.add(look.scale(i));
            for (LivingEntity f : sl.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(0.5), f -> M.foe(p, f))) {
                target = f;
                break;
            }
        }
        Vec3 hitAt = target == null ? eye.add(look.scale(max)) : target.getBoundingBox().getCenter();
        M.line(sl, new ItemParticleOption(ParticleTypes.ITEM, axe), eye.add(look), hitAt);
        sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1, 0.7f);
        p.swing(hand, true);
        axe.hurtAndBreak(1, p, EquipmentSlot.MAINHAND);
        if (target != null) M.hurt(target, p.damageSources().playerAttack(p), (float) h.value(), false);
        return true;
    }

    // ---------------------------------------------------------------- player tick

    static void tick(ServerPlayer p, PS s, long now) {
        if (s.blades <= 0) return;
        HabitsApi.PerkHit h = M.hit(p, Mech.SPIRIT_BLADES);
        if (h == null || now > s.bladesUntil) {
            s.blades = 0;
            return;
        }
        ServerLevel sl = (ServerLevel) p.level();
        if (p.tickCount % 4 == 0) for (int i = 0; i < s.blades; i++) {
            double a = (p.tickCount / 4.0) * 0.6 + i * Math.PI * 2 / s.blades;
            sl.sendParticles(ParticleTypes.ENCHANTED_HIT, p.getX() + Math.cos(a) * 1.2, p.getY() + 1.1, p.getZ() + Math.sin(a) * 1.2, 1, 0, 0, 0, 0);
        }
        if (p.tickCount % 20 != 0) return;
        List<LivingEntity> foes = M.foesNear(p, p.position(), M.p(h, "radius"), null);
        foes.sort((a, b) -> Double.compare(a.distanceToSqr(p), b.distanceToSqr(p)));
        float dmg = (float) M.p(h, "damage");
        for (int i = 0; i < Math.min(s.blades, foes.size()); i++) {
            LivingEntity f = foes.get(i);
            M.line(sl, ParticleTypes.ENCHANTED_HIT, p.position().add(0, 1.1, 0), f.position().add(0, f.getBbHeight() / 2, 0));
            M.hurt(f, p.damageSources().indirectMagic(p, p), dmg, true);
        }
    }
}
