package com.habits.perk.ranged;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SimpleExplosionDamageCalculator;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/** What our players' projectiles do when they hit something (server side). Cancelling means we handled the hit. */
public final class Impacts {
    private Impacts() {}

    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent e) {
        Projectile pr = e.getProjectile();
        if (pr.level().isClientSide()) return;
        Shots.Shot s = Shots.get(pr);
        if (s == null || !R.real(s.owner)) return;
        HitResult hr = e.getRayTraceResult();
        boolean handled;
        if (hr instanceof EntityHitResult eh) handled = entity(s, pr, eh.getEntity());
        else if (hr instanceof BlockHitResult bh) handled = block(s, pr, bh);
        else handled = false;
        if (handled) e.setCanceled(true);
    }

    // ------------------------------------------------------------------ entity hits

    static boolean entity(Shots.Shot s, Projectile pr, Entity target) {
        ServerPlayer p = s.owner;
        if (target == p) return false;
        if (target instanceof EndCrystal) R.fire(p, "crystal_shot", target.position());
        if (s.shard && !(pr instanceof WitherSkull)) return false;
        HabitsApi.PerkHit h;
        if (pr instanceof ThrownTrident t) {
            if (s.passHits.contains(target)) return true; // already pierced: fly on through
            if ((h = R.hit(p, "javelin")) != null && target instanceof LivingEntity && s.passHits.size() < h.value()) {
                s.passHits.add(target);
                DamageSource src = t.damageSources().trident(t, p);
                ServerLevel level = (ServerLevel) t.level();
                float dmg = EnchantmentHelper.modifyDamage(level, t.getWeaponItem(), target, src, 8.0f);
                if (target.hurt(src, dmg)) EnchantmentHelper.doPostAttackEffectsWithItemSource(level, target, src, t.getWeaponItem());
                t.hasImpulse = true;
                return true;
            }
            return false;
        }
        if (pr instanceof AbstractArrow a) {
            if (target instanceof Creeper c && (h = R.hit(p, "crystal_popper")) != null && R.roll(p, h.value())) {
                float power = (c.isPowered() ? 6 : 3) * (float) (1 + R.p(h, "blast") / 100);
                Hits.blast(p, c, c.position(), power);
                c.discard();
                a.discard();
                return true;
            }
            return false;
        }
        if (pr instanceof Snowball sb) {
            if (target instanceof LivingEntity l && l.isOnFire() && friendly(p, l)) { l.clearFire(); return false; }
            if ((h = R.hit(p, "frost_pelter")) != null && target instanceof LivingEntity l && !friendly(p, l)) {
                boolean fiery = l instanceof Blaze || l instanceof MagmaCube || l instanceof Strider;
                l.hurt(sb.damageSources().thrown(sb, p), (float) (h.value() * (fiery ? 3 : 1)));
                l.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, (int) (R.p(h, "slow") * 20), 1), p);
                if (R.p(h, "freeze") > 0) l.setTicksFrozen(Math.min(l.getTicksRequiredToFreeze() + 60, l.getTicksFrozen() + (int) R.p(h, "freeze")));
            }
            return false;
        }
        if (pr instanceof ThrownEgg egg && target instanceof Mob m) {
            R.fire(p, "egg_hit", m.position());
            if ((h = R.hit(p, "yolk_splatter")) != null) {
                m.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, (int) (h.value() * 20)), p);
                if (m.getTarget() == p && R.roll(p, R.p(h, "calm"))) {
                    m.setTarget(null);
                    Status.CALM.put(m, new Status.Calm(p.getUUID(), m.level().getGameTime() + (long) (h.value() * 20)));
                }
            }
            return thrown(s, pr, m.position(), m); // the egg still lands: Egg Toss may hatch it
        }
        if (pr instanceof ThrownEnderpearl pearl && target instanceof LivingEntity l && !(l instanceof Player)) {
            R.fire(p, "pearl_hit_mob", l.position());
            if ((h = R.hit(p, "pearl_swap")) != null && !R.boss(l) && p.level() == l.level()) {
                Vec3 me = p.position(), it = l.position();
                p.teleportTo(it.x, it.y, it.z);
                l.teleportTo(me.x, me.y, me.z);
                p.resetFallDistance();
                int ticks = (int) (h.value() * 20);
                l.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, ticks, 9), p);
                l.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks, 2), p);
                if (l instanceof Mob m) Status.pin(m, l.level().getGameTime() + ticks);
                if (R.p(h, "dmg") > 0) l.hurt(pearl.damageSources().thrown(pearl, p), (float) R.p(h, "dmg"));
                pearl.level().playSound(null, me.x, me.y, me.z, SoundEvents.PLAYER_TELEPORT, SoundSource.PLAYERS, 1, 1);
                pearl.discard();
                return true;
            }
            return false;
        }
        return thrown(s, pr, target.position().add(0, target.getBbHeight() * 0.5, 0), target);
    }

    /**
     * Breeze Breaker, called from the ranged mixins (enderman dodge, breeze deflection, shield block): true when this
     * projectile of ours goes through them. Rolled once per projectile; one map lookup when the projectile isn't ours.
     */
    public static boolean breaks(@Nullable Entity direct) {
        Shots.Shot s = direct == null || direct.level().isClientSide() ? null : Shots.get(direct);
        if (s == null) return false;
        if (s.breaks == 0) {
            HabitsApi.PerkHit h = R.hit(s.owner, "breeze_breaker");
            s.breaks = (byte) (h != null && R.roll(s.owner, h.value()) ? 1 : 2);
        }
        return s.breaks == 1;
    }

    static boolean friendly(Player owner, LivingEntity l) {
        return l instanceof Player || l instanceof OwnableEntity o && owner.getUUID().equals(o.getOwnerUUID());
    }

    // ------------------------------------------------------------------ block hits

    static boolean block(Shots.Shot s, Projectile pr, BlockHitResult bh) {
        ServerPlayer p = s.owner;
        ServerLevel level = (ServerLevel) pr.level();
        BlockPos pos = bh.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (pr instanceof AbstractArrow a && !(pr instanceof ThrownTrident)) {
            if (s.shard) { s.landed = true; return false; }
            boolean hard = state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(Tags.Blocks.ORES)
                    || state.is(Tags.Blocks.STONES) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.DEEPSLATE);
            if (hard) R.fire(p, "arrow_hit_stone", bh.getLocation());
            if (state.is(Blocks.TNT) && a.isOnFire()) R.fire(p, "tnt_flame_arrow", bh.getLocation());
            if (state.is(Blocks.TARGET) && targetPower(bh) >= 15) R.fire(p, "target_bullseye", bh.getLocation());
            if (trickTarget(state)) R.fire(p, "trickshot", bh.getLocation());
            if (wallBang(s, a, state, bh)) return true;
            if (trick(s, a, state, pos, bh)) return true;
            if (hard && ricochet(s, a, bh)) return true;
            if (!s.hit) { Status.of(p).streak = 0; Ranger.arrowMissed(p, bh.getLocation()); } // a miss ends Hot Streak
            demolition(s, a, bh);
            FallingStar.landed(s, bh.getLocation());
            s.landed = true;
            s.landedAt = level.getGameTime();
            return false;
        }
        if (pr instanceof ThrownTrident t) Ranger.tridentLanded(s, t);
        return thrown(s, pr, bh.getLocation(), null);
    }

    static int targetPower(BlockHitResult bh) {
        Vec3 l = bh.getLocation();
        double x = Math.abs(Mth.frac(l.x) - 0.5), y = Math.abs(Mth.frac(l.y) - 0.5), z = Math.abs(Mth.frac(l.z) - 0.5);
        Direction.Axis ax = bh.getDirection().getAxis();
        double d = ax == Direction.Axis.Y ? Math.max(x, z) : ax == Direction.Axis.Z ? Math.max(x, y) : Math.max(y, z);
        return Math.max(1, Mth.ceil(15.0 * Mth.clamp((0.5 - d) / 0.5, 0.0, 1.0)));
    }

    static boolean trickTarget(BlockState st) {
        return st.is(BlockTags.BUTTONS) || st.is(Blocks.BELL) || st.is(BlockTags.CANDLES) || st.is(BlockTags.CAMPFIRES)
                || st.is(Blocks.CHORUS_FLOWER) || st.is(Blocks.POINTED_DRIPSTONE) || st.is(Blocks.DECORATED_POT);
    }

    /** Ricochet: reflect off the face, keep damage, lose some speed; late levels bend the bounce at a nearby mob. */
    static boolean ricochet(Shots.Shot s, AbstractArrow a, BlockHitResult bh) {
        HabitsApi.PerkHit h = R.hit(s.owner, "ricochet");
        if (h == null || s.bounces >= h.value()) return false;
        s.bounces++;
        Vec3 n = Vec3.atLowerCornerOf(bh.getDirection().getNormal());
        Vec3 v = a.getDeltaMovement();
        Vec3 out = v.subtract(n.scale(2 * v.dot(n))).scale(R.p(h, "speed") / 100);
        double aim = R.p(h, "aim");
        if (aim > 0) {
            Vec3 at = bh.getLocation();
            Mob best = null;
            double bd = aim * aim;
            for (Mob m : a.level().getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(aim), R::hostile)) {
                double d = m.distanceToSqr(at);
                if (d < bd) { bd = d; best = m; }
            }
            if (best != null) out = Shots.aimPoint(best).subtract(at).normalize().scale(out.length());
        }
        Vec3 at = bh.getLocation().add(n.scale(0.05));
        a.setPos(at.x, at.y, at.z);
        a.setDeltaMovement(out);
        a.hasImpulse = true;
        a.level().playSound(null, at.x, at.y, at.z, SoundEvents.ARROW_HIT, SoundSource.PLAYERS, 0.6f, 1.6f);
        return true;
    }

    /** Wall Bang: fly on through thin cover (leaves first, then glass, panes, wool and planks). */
    static boolean wallBang(Shots.Shot s, AbstractArrow a, BlockState st, BlockHitResult bh) {
        HabitsApi.PerkHit h = R.hit(s.owner, "wall_bang");
        if (h == null || s.covers >= h.value() || !thin(h, st)) return false;
        s.covers++;
        a.hasImpulse = true;
        return true;
    }

    /** Wall Bang's cover rule, shared with the client copy in RangedClient so both sides let the arrow through. */
    public static boolean thin(HabitsApi.PerkHit h, BlockState st) {
        return st.is(BlockTags.LEAVES) || R.p(h, "all") > 0 && (st.is(Tags.Blocks.GLASS_BLOCKS) || st.is(Tags.Blocks.GLASS_PANES)
                || st.is(BlockTags.WOOL) || st.is(BlockTags.PLANKS));
    }

    /** Trickshot: tier 1 flips levers, doors and trapdoors; tier 2 also breaks glass, panes, ice and leaves. */
    static boolean trick(Shots.Shot s, AbstractArrow a, BlockState st, BlockPos pos, BlockHitResult bh) {
        HabitsApi.PerkHit h = R.hit(s.owner, "trickshot");
        if (h == null) return false;
        Level level = a.level();
        ServerPlayer p = s.owner;
        if (st.getBlock() instanceof LeverBlock lever && allowed(p, pos, st)) {
            lever.pull(st, level, pos, p);
            return false;
        }
        if (st.getBlock() instanceof DoorBlock door && door.type().canOpenByHand() && allowed(p, pos, st)) {
            door.setOpen(p, level, st, pos, !door.isOpen(st));
            return false;
        }
        if (st.getBlock() instanceof TrapDoorBlock && st.hasProperty(TrapDoorBlock.OPEN) && allowed(p, pos, st)) {
            boolean open = !st.getValue(TrapDoorBlock.OPEN);
            level.setBlock(pos, st.setValue(TrapDoorBlock.OPEN, open), Block.UPDATE_ALL);
            level.playSound(null, pos, open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1, 1);
            return false;
        }
        boolean brittle = st.is(Tags.Blocks.GLASS_BLOCKS) || st.is(Tags.Blocks.GLASS_PANES) || st.is(BlockTags.ICE) || st.is(BlockTags.LEAVES);
        if (h.value() >= 2 && brittle && allowed(p, pos, st)) {
            level.destroyBlock(pos, true, p);
            a.setDeltaMovement(a.getDeltaMovement().scale(0.8));
            return true; // the arrow keeps flying through the gap
        }
        return false;
    }

    /** Protection plugins and claims see this as the archer breaking the block. */
    static boolean allowed(ServerPlayer p, BlockPos pos, BlockState st) {
        if (!p.mayBuild() || !p.level().mayInteract(p, pos)) return false;
        boolean was = com.habits.perk.mining.Work.busy;
        com.habits.perk.mining.Work.busy = true; // a permission check, not a break: no cards, no mining perks
        try {
            return !NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(p.level(), pos, st, p)).isCanceled();
        } finally {
            com.habits.perk.mining.Work.busy = was;
        }
    }

    static void demolition(Shots.Shot s, AbstractArrow a, BlockHitResult bh) {
        if (!a.isOnFire()) return;
        HabitsApi.PerkHit h = R.hit(s.owner, "demolition_shot");
        if (h == null || !R.roll(s.owner, h.value())) return;
        Status.P st = Status.of(s.owner);
        long now = a.level().getGameTime();
        if (now - st.lastBlastTick < R.p(h, "gap")) return;
        st.lastBlastTick = now;
        Hits.blast(s.owner, a, bh.getLocation(), (float) R.p(h, "power"));
    }

    // ------------------------------------------------------------------ throwables (entity or block)

    static boolean thrown(Shots.Shot s, Projectile pr, Vec3 at, Entity direct) {
        ServerPlayer p = s.owner;
        ServerLevel level = (ServerLevel) pr.level();
        HabitsApi.PerkHit h;
        if (pr instanceof ThrownEgg egg && (h = R.hit(p, "egg_toss")) != null) {
            if (direct != null) direct.hurt(egg.damageSources().thrown(egg, p), 0);
            if (R.roll(p, h.value())) hatch(level, egg, (int) R.p(h, "max"), (int) R.p(h, "cap"));
            level.broadcastEntityEvent(egg, (byte) 3);
            egg.discard();
            return true;
        }
        if (pr instanceof WindCharge wc) return wind(s, wc, at);
        if (pr instanceof ThrownPotion tp) return potion(s, tp, at, direct);
        if (pr instanceof ThrownExperienceBottle && (h = R.hit(p, "bottle_pop")) != null) {
            int bonus = (int) Math.round(7 * h.value() / 100);
            if (bonus > 0) net.minecraft.world.entity.ExperienceOrb.award(level, at, bonus);
            if (R.roll(p, R.p(h, "bounce")) && !p.getInventory().add(new ItemStack(Items.EXPERIENCE_BOTTLE))) p.drop(new ItemStack(Items.EXPERIENCE_BOTTLE), false);
            return false; // vanilla still pays its own XP and breaks the bottle
        }
        if (pr instanceof SmallFireball fb && s.charge == Throws.PITCHED) {
            Throws.pitchHit(s, fb, at, direct);
            return true;
        }
        if (pr instanceof WitherSkull skull && s.shard) {
            if (direct instanceof LivingEntity l && l != p) {
                l.hurt(skull.damageSources().witherSkull(skull, p), (float) s.dmgBonus);
                l.addEffect(new MobEffectInstance(MobEffects.WITHER, 200, 1), p);
            }
            Hits.blast(p, skull, at, 1);
            skull.discard();
            return true;
        }
        return false;
    }

    static void hatch(ServerLevel level, ThrownEgg egg, int max, int cap) {
        int near = level.getEntitiesOfClass(Chicken.class, egg.getBoundingBox().inflate(16)).size();
        int n = 1 + (max > 1 ? egg.getRandom().nextInt(max) : 0);
        for (int i = 0; i < n && near + i < cap; i++) {
            Chicken c = EntityType.CHICKEN.create(level);
            if (c == null) return;
            c.setAge(-24000);
            c.moveTo(egg.getX(), egg.getY(), egg.getZ(), egg.getYRot(), 0);
            level.addFreshEntity(c);
        }
    }

    /** Gale Thrower (bigger, stronger blast; sneak-throws pull mobs in first) and Wind Jumper (higher self-launch). */
    static boolean wind(Shots.Shot s, WindCharge wc, Vec3 at) {
        ServerPlayer p = s.owner;
        HabitsApi.PerkHit gale = R.hit(p, "gale_thrower"), jump = R.hit(p, "wind_jumper");
        double near = jump == null ? 2 : R.p(jump, "near");
        boolean self = p.position().distanceToSqr(at) <= near * near + 4;
        if (self) R.fire(p, "wind_self_launch", at);
        if (gale == null && (jump == null || !self)) return false;
        float radius = (float) (1.2 * (1 + (gale == null ? 0 : gale.value() / 100)));
        float knock = (float) (1.22 * (1 + (gale == null ? 0 : R.p(gale, "knock") / 100)));
        ServerLevel level = (ServerLevel) wc.level();
        double vortex = gale == null ? 0 : R.p(gale, "vortex");
        Runnable boom = () -> {
            SimpleExplosionDamageCalculator calc = new SimpleExplosionDamageCalculator(true, false, Optional.of(knock),
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getTag(BlockTags.BLOCKS_WIND_CHARGE_EXPLOSIONS).map(x -> x));
            Vec3 before = p.getDeltaMovement();
            level.explode(wc, null, calc, at.x, at.y, at.z, radius, false, Level.ExplosionInteraction.TRIGGER,
                    ParticleTypes.GUST_EMITTER_SMALL, ParticleTypes.GUST_EMITTER_LARGE, SoundEvents.WIND_CHARGE_BURST);
            if (jump != null && self) {
                Vec3 gain = p.getDeltaMovement().subtract(before);
                p.setDeltaMovement(before.add(gain.scale(1 + jump.value() / 100)));
                p.hurtMarked = true;
                Status.P st = Status.of(p);
                st.noFallUntil = level.getGameTime() + (long) (R.p(jump, "fall_s") * 20) + 40;
                st.noFallCut = R.p(jump, "fall") / 100;
            }
        };
        if (vortex > 0 && s.sneak && Status.VORTICES.size() < Status.MAX_VORTICES) {
            Status.VORTICES.add(new Status.Vortex(level, at, level.getGameTime() + (long) (vortex * 20), boom));
        } else {
            boom.run();
        }
        wc.discard();
        return true;
    }

    /** Splash Zone, Field Medic and Water Balloon; anything else falls through to vanilla. */
    static boolean potion(Shots.Shot s, ThrownPotion tp, Vec3 at, Entity direct) {
        ServerPlayer p = s.owner;
        ServerLevel level = (ServerLevel) tp.level();
        PotionContents pc = Splash.contents(tp);
        boolean lingering = tp.getItem().is(Items.LINGERING_POTION);
        HabitsApi.PerkHit h;
        if (pc.is(Potions.WATER) && !lingering) {
            h = R.hit(p, "water_balloon");
            double r = h == null ? 4 : R.p(h, "radius");
            boolean hurtAny = false;
            for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(r, r * 0.5 + 1, r))) {
                if (l.distanceToSqr(at) > r * r) continue;
                if (l.isSensitiveToWater() || l.isOnFire()) hurtAny = true;
                if (h == null) continue;
                if (l.isSensitiveToWater() && l != p) l.hurt(tp.damageSources().indirectMagic(tp, p), (float) h.value());
                l.extinguishFire();
                Status.WET.put(l, level.getGameTime() + (long) (R.p(h, "wet") * 20));
            }
            if (hurtAny) R.fire(p, "water_splash_hurt", at);
            if (h != null && R.p(h, "fire_blocks") > 0) dowse(level, BlockPos.containing(at), (int) Math.ceil(r));
            return false; // vanilla still does its own water splash
        }
        if (!pc.hasEffects()) return false;
        if (lingering) return false;
        HabitsApi.PerkHit zone = R.hit(p, "splash_zone"), med = R.hit(p, "field_medic");
        boolean good = Splash.beneficial(pc);
        boolean healAlly = good && !level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(4), l -> Shots.ally(p, l)).isEmpty();
        if (healAlly) R.fire(p, "heal_splash_ally", at);
        double radius = 4 * (1 + (zone == null ? 0 : zone.value() / 100));
        double falloff = zone == null ? 1 : R.p(zone, "falloff") / 100;
        double strength = good && med != null ? 1 + med.value() / 100 : 1;
        if (zone == null && strength == 1) {
            if (!good && direct instanceof Mob) R.fire(p, "splash_hit", at);
            return false;
        }
        int touched = Splash.apply(level, tp, p, at, pc.getAllEffects(), radius, Math.min(1, falloff), strength, direct, null);
        if (!good && touched > 0) R.fire(p, "splash_hit", at);
        int ev = pc.potion().isPresent() && pc.potion().get().value().hasInstantEffects() ? 2007 : 2002;
        level.levelEvent(ev, tp.blockPosition(), pc.getColor());
        tp.discard();
        return true;
    }

    static void dowse(ServerLevel level, BlockPos c, int r) {
        int budget = 64; // block checks stay tiny even at the largest radius
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-r, -1, -r), c.offset(r, 1, r))) {
            if (budget-- <= 0) return;
            if (level.getBlockState(q).getBlock() instanceof BaseFireBlock) level.removeBlock(q, false);
        }
    }

    // ------------------------------------------------------------------ fireworks

    /** Firework Artillery: a wider blast ring around the vanilla one (which already covers 5 blocks). */
    static void fireworkBlast(Shots.Shot s, FireworkRocketEntity f) {
        HabitsApi.PerkHit h = R.hit(s.owner, "firework_artillery");
        Fireworks fw = f.getItem().get(DataComponents.FIREWORKS);
        if (h == null || fw == null || fw.explosions().isEmpty() || !f.isShotAtAngle()) return;
        double outer = 5 + R.p(h, "blast");
        float base = 5 + fw.explosions().size() * 2;
        for (LivingEntity l : f.level().getEntitiesOfClass(LivingEntity.class, f.getBoundingBox().inflate(outer), R::hostile)) {
            double d = l.distanceTo(f);
            if (d <= 5 || d > outer) continue;
            l.hurt(f.damageSources().fireworks(f, s.owner), (float) (base * Math.sqrt((outer - d) / outer)));
        }
    }
}
