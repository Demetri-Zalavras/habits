package com.habits.perk.movement;

import com.habits.perk.Later;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Event halves of the spec-batch mechanics ({@link SpecMoves}) and their card events. Registered on the game bus. */
public final class SpecEvents {
    private SpecEvents() {}

    static long now(Entity e) {
        return e.level().getGameTime();
    }

    // ------------------------------------------------------------------------------------------- landing

    @SubscribeEvent
    public static void onFall(LivingFallEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        land(p, MoveState.of(p), e);
    }

    static void land(ServerPlayer p, MoveState s, LivingFallEvent e) {
        float dist = e.getDistance();
        if (dist >= 5 && SpecMoves.underground(p)) Mv.fire(p, "cave_fall");
        if (now(p) <= s.softUntil) {
            s.softUntil = 0;
            e.setDamageMultiplier(0);
        }
        if (s.has(Mech.ADRENALINE) && dist >= s.p(Mech.ADRENALINE, "min_fall")) {
            int ticks = (int) Math.round(s.v(Mech.ADRENALINE) * 20);
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, ticks, (int) s.p(Mech.ADRENALINE, "amp"), true, false, true));
            p.addEffect(new MobEffectInstance(MobEffects.JUMP, ticks, (int) s.p(Mech.ADRENALINE, "jump_amp"), true, false, true));
        }
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        jump(p, MoveState.of(p));
    }

    static void jump(ServerPlayer p, MoveState s) {
        if (p.isSprinting()) s.sprintJumpY = p.getY();
        Vec3 f = Mv.flatLook(p), k = p.getKnownMovement();
        BlockState ahead = p.level().getBlockState(BlockPos.containing(p.getX() + f.x * 0.6, p.getY() + 0.5, p.getZ() + f.z * 0.6));
        boolean fence = ahead.is(BlockTags.FENCES) || ahead.is(BlockTags.WALLS) || ahead.is(BlockTags.FENCE_GATES);
        if (fence) Mv.fire(p, "fence_jump");
        if (s.has(Mech.COILED_SPRING) && s.coil >= s.p(Mech.COILED_SPRING, "charge") * 20) {
            s.coil = 0;
            s.softUntil = now(p) + 80;
            Mv.push(p, new Vec3(k.x, jumpSpeed(s.v(Mech.COILED_SPRING)), k.z));
            p.level().playSound(null, p.blockPosition(), SoundEvents.PISTON_EXTEND, SoundSource.PLAYERS, 0.6f, 1.4f);
        } else if (fence && s.has(Mech.FENCE_HOPPER)) {
            double push = s.p(Mech.FENCE_HOPPER, "push");
            Mv.push(p, new Vec3(k.x + f.x * push, jumpSpeed(s.v(Mech.FENCE_HOPPER)), k.z + f.z * push));
        }
    }

    /** Take-off speed for a jump {@code extra} blocks higher than vanilla's 1.25 (height grows with speed squared). */
    static double jumpSpeed(double extra) {
        return 0.42 * Math.sqrt((1.25 + extra) / 1.25);
    }

    // ------------------------------------------------------------------------------------------- damage

    @SubscribeEvent
    public static void onIncoming(LivingIncomingDamageEvent e) {
        LivingEntity victim = e.getEntity();
        if (victim.level().isClientSide()) return;
        DamageSource src = e.getSource();
        // a mount carrying a player
        if (src.is(DamageTypes.FALL) && victim.getControllingPassenger() instanceof ServerPlayer rider && Mv.ok(rider)) {
            Mv.fire(rider, "mount_fall", victim.fallDistance);
            e.setAmount(mountFall(MoveState.of(rider), e.getAmount()));
        }
        if (victim instanceof ServerPlayer p && Mv.ok(p) && dodged(p, MoveState.of(p), src)) e.setCanceled(true);
    }

    /** Sure Hooves: the fall damage a ridden mount takes. */
    static float mountFall(MoveState s, float amount) {
        return s.has(Mech.SURE_HOOVES) ? amount * (float) Math.max(0, 1 - s.v(Mech.SURE_HOOVES) / 100) : amount;
    }

    /** Sidestep: a projectile hit while sprinting (or within 5 ticks of it) misses at the skill's chance. */
    static boolean dodged(ServerPlayer p, MoveState s, DamageSource src) {
        if (!src.is(DamageTypeTags.IS_PROJECTILE) || s.tick - s.sprintSeen > 5) return false;
        Mv.fire(p, "shot_sprinting");
        if (!s.has(Mech.SIDESTEP) || p.getRandom().nextDouble() * 100 >= s.v(Mech.SIDESTEP)) return false;
        sidestep(p, src);
        return true;
    }

    static void sidestep(ServerPlayer p, DamageSource src) {
        Entity d = src.getDirectEntity();
        Vec3 from = d != null ? d.getDeltaMovement() : p.getLookAngle();
        Vec3 side = new Vec3(-from.z, 0, from.x);
        if (side.lengthSqr() < 1e-6) side = new Vec3(1, 0, 0);
        side = side.normalize().scale(p.getRandom().nextBoolean() ? 0.6 : -0.6);
        Vec3 k = p.getKnownMovement();
        Mv.push(p, new Vec3(k.x + side.x, Math.max(k.y, 0.1), k.z + side.z));
        p.level().playSound(null, p.blockPosition(), SoundEvents.BREEZE_JUMP, SoundSource.PLAYERS, 0.8f, 1.4f);
    }

    /** Last Ledge: a fall that would kill leaves half a heart, then waits out its cooldown (saved with the player). */
    @SubscribeEvent
    public static void onDamagePre(LivingDamageEvent.Pre e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p) || !e.getSource().is(DamageTypes.FALL)) return;
        lastLedge(p, MoveState.of(p), e);
    }

    static final String LEDGE_KEY = "habits_last_ledge";

    static boolean lastLedge(ServerPlayer p, MoveState s, LivingDamageEvent.Pre e) {
        if (!s.has(Mech.LAST_LEDGE)) return false;
        float left = p.getHealth() + p.getAbsorptionAmount();
        if (e.getNewDamage() < left) return false;
        long now = now(p);
        var tag = p.getPersistentData();
        if (now < tag.getLong(LEDGE_KEY)) return false;
        e.setNewDamage(Math.max(0, left - 1));
        tag.putLong(LEDGE_KEY, now + Math.round(s.v(Mech.LAST_LEDGE) * 1200));
        p.level().playSound(null, p.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.5f, 1.6f);
        return true;
    }

    @SubscribeEvent
    public static void onDamaged(LivingDamageEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p) || !p.isAlive()) return;
        hurt(p, MoveState.of(p), e.getSource());
    }

    static void hurt(ServerPlayer p, MoveState s, DamageSource src) {
        long now = now(p);
        boolean projectile = src.is(DamageTypeTags.IS_PROJECTILE);
        if (s.tick - s.sprintSeen <= 5) Mv.fire(p, "sprint_hurt");
        if (projectile && p.isFallFlying()) Mv.fire(p, "shot_gliding");
        if (!(src.getEntity() instanceof LivingEntity attacker) || attacker == p) return;
        if (p.isUnderWater()) {
            Mv.fire(p, "hurt_underwater");
            if (s.has(Mech.INK_JET) && now >= s.nextInk) {
                Vec3 away = p.position().subtract(attacker.position());
                away = away.lengthSqr() < 1e-6 ? p.getLookAngle() : away.normalize();
                Mv.push(p, away.scale(Math.min(3, s.v(Mech.INK_JET) / 5))); // water keeps 80% a tick: blocks = speed * 5
                attacker.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, (int) Math.round(s.p(Mech.INK_JET, "blind") * 20), 0));
                p.serverLevel().sendParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY() + 0.8, p.getZ(), 30, 0.6, 0.6, 0.6, 0.05);
                s.nextInk = now + Math.round(s.p(Mech.INK_JET, "cooldown") * 20);
            }
        }
        if (!projectile && s.has(Mech.EVASION) && now >= s.nextEvade) {
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, (int) Math.round(s.v(Mech.EVASION) * 20),
                    (int) s.p(Mech.EVASION, "amp"), true, false, true));
            s.nextEvade = now + Math.round(s.p(Mech.EVASION, "cooldown") * 20);
        }
    }

    // ------------------------------------------------------------------------------------------- mounts

    @SubscribeEvent
    public static void onMount(EntityMountEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntityMounting() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        if (e.isMounting()) {
            if (s.has(Mech.GIDDY_UP) && e.getEntityBeingMounted() instanceof LivingEntity mount && !(mount instanceof Player)
                    && now(p) >= s.nextGiddy) {
                mount.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, (int) Math.round(s.v(Mech.GIDDY_UP) * 20),
                        (int) s.p(Mech.GIDDY_UP, "amp"), true, false, true));
                s.nextGiddy = now(p) + Math.round(s.p(Mech.GIDDY_UP, "cooldown") * 20);
            }
            return;
        }
        dismount(p, s);
    }

    static void dismount(ServerPlayer p, MoveState s) {
        Vec3 vv = s.vehVel;
        double h = SpecMoves.horiz(vv);
        if (h >= 0.3) Mv.fire(p, "moving_dismount");
        if (!s.has(Mech.FLYING_DISMOUNT) || h < s.p(Mech.FLYING_DISMOUNT, "min_speed")) return;
        double k = s.v(Mech.FLYING_DISMOUNT) / 100, lift = s.p(Mech.FLYING_DISMOUNT, "lift");
        Vec3 at = p.position();
        // a tick later, after vanilla places you beside the ride; a pearl or portal that moved you cancels it
        Later.run(p.serverLevel(), 1, () -> {
            if (p.isAlive() && !p.isPassenger() && p.position().distanceToSqr(at) < 9)
                Mv.push(p, SpecMoves.cap(new Vec3(vv.x * k, Math.max(0, vv.y) + lift, vv.z * k), DashMath.CAP));
        });
    }

    // ------------------------------------------------------------------------------------------- pearls, buckets, potions

    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        if (e.getItemStack().is(Items.ENDER_PEARL) && !p.getCooldowns().isOnCooldown(Items.ENDER_PEARL)) {
            s.pearlFrom = p.position();
            s.pearlDim = p.level().dimension();
            if (p.getVehicle() != null) {
                Mv.fire(p, "mounted_pearl");
                s.pearlMount = new java.lang.ref.WeakReference<>(p.getVehicle());
                s.pearlRideAt = now(p);
            }
        }
        if (e.getItemStack().is(Items.WATER_BUCKET) && p.fallDistance >= 5) Mv.fire(p, "water_clutch");
    }

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getEntity() instanceof ServerPlayer p && Mv.ok(p) && e.getItemStack().is(Items.WATER_BUCKET) && p.fallDistance >= 5)
            Mv.fire(p, "water_clutch");
    }

    @SubscribeEvent
    public static void onPearl(EntityTeleportEvent.EnderPearl e) {
        ServerPlayer p = e.getPlayer();
        if (!Mv.ok(p)) return;
        pearl(p, MoveState.of(p), e.getPearlEntity().getDeltaMovement(), e);
    }

    static void pearl(ServerPlayer p, MoveState s, Vec3 pearlSpeed, EntityTeleportEvent.EnderPearl e) {
        long now = now(p);
        Mv.fire(p, "pearl_teleport");
        if (s.has(Mech.RUBBER_BAND) && s.pearlFrom != null) {
            s.bandFrom = s.pearlFrom;
            s.bandDim = s.pearlDim;
            s.bandUntil = now + Math.round(s.v(Mech.RUBBER_BAND) * 20);
        }
        if (s.has(Mech.PEARL_MOMENTUM)) {
            if (e != null) e.setAttackDamage(e.getAttackDamage() * (float) Math.max(0, 1 - s.p(Mech.PEARL_MOMENTUM, "safe") / 100));
            Vec3 keep = SpecMoves.cap(pearlSpeed.scale(s.v(Mech.PEARL_MOMENTUM) / 100), 2.5);
            Later.run(p.serverLevel(), 1, () -> { if (p.isAlive()) Mv.push(p, keep); });
        }
        Entity mount = s.pearlMount != null ? s.pearlMount.get() : null;
        s.pearlMount = null;
        if (s.has(Mech.PEARL_RIDER) && mount != null && mount.isAlive() && mount.level() == p.level() && now - s.pearlRideAt < 400
                && (e == null || mount.position().distanceTo(new Vec3(e.getTargetX(), e.getTargetY(), e.getTargetZ())) <= s.v(Mech.PEARL_RIDER))) {
            Later.run(p.serverLevel(), 1, () -> {
                if (!p.isAlive() || !mount.isAlive() || mount.level() != p.level() || p.isPassenger()) return;
                mount.teleportTo(p.getX(), p.getY(), p.getZ());
                mount.resetFallDistance();
                p.startRiding(mount, true);
            });
        }
    }

    @SubscribeEvent
    public static void onFinishUse(LivingEntityUseItemEvent.Finish e) {
        if (e.getEntity() instanceof ServerPlayer p && Mv.ok(p) && p.isPassenger() && e.getItem().getItem() instanceof PotionItem)
            Mv.fire(p, "potion_ride");
    }

    @SubscribeEvent
    public static void onFished(ItemFishedEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && Mv.ok(p) && p.getVehicle() instanceof Boat) Mv.fire(p, "boat_catch");
    }
}
