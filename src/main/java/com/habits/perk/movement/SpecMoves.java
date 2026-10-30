package com.habits.perk.movement;

import com.habits.perk.Later;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;

import java.util.List;
import java.util.function.Predicate;

/**
 * The spec-batch movement mechanics (skills {@code habits:movement/<skill>}, docs/mechanics/movement-exploration.md
 * "Spec batch"). Server side unless noted; {@link #local} is the local-player half, the mixin halves are in
 * {@link MoveHooks}. Card trackers for the batch's custom events run for every player in {@link #track}.
 */
final class SpecMoves {
    private SpecMoves() {}

    /** Horizontal speed (blocks/tick) of the mount below which a hop-off or trample does nothing. */
    static double horiz(Vec3 v) {
        return Math.hypot(v.x, v.z);
    }

    static long now(Entity e) {
        return e.level().getGameTime();
    }

    static Vec3 cap(Vec3 v, double max) {
        double l = v.length();
        return l > max ? v.scale(max / l) : v;
    }

    // ------------------------------------------------------------------------------------------- every tick, every player

    /** Before ServerTick updates lastFall / wasInWater. Cheap field checks every tick, unit cards once a second. */
    static void track(ServerPlayer p, MoveState s, long t) {
        long now = now(p);
        Entity veh = p.getVehicle();
        if (veh != null) {
            Vec3 at = veh.position();
            if (s.vehLast != null) s.vehVel = at.subtract(s.vehLast);
            s.vehLast = at;
        } else s.vehLast = null;
        // water landings
        if (p.isInWater() && !s.wasInWater) {
            if (s.lastFall >= 10) Mv.fire(p, "water_landing");
            if (s.has(Mech.CANNONBALL) && s.lastFall >= s.p(Mech.CANNONBALL, "min_fall")) cannonball(p, s);
        }
        boolean moved = s.lastMoveTick == t, ground = p.onGround();
        // setting off after a rest
        if (moved) {
            if (t - s.myLastMove >= 20) {
                s.startedAt = t;
                s.startFired = false;
                if (s.has(Mech.STARTING_BLOCK) && t - s.myLastMove >= s.p(Mech.STARTING_BLOCK, "still") * 20 && now >= s.nextStart) {
                    p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, (int) Math.round(s.v(Mech.STARTING_BLOCK) * 20),
                            (int) s.p(Mech.STARTING_BLOCK, "amp"), true, false, true));
                    s.nextStart = now + Math.round(s.p(Mech.STARTING_BLOCK, "cooldown") * 20);
                }
            }
            s.myLastMove = t;
        }
        if (!s.startFired && p.isSprinting() && t - s.startedAt < 10) {
            s.startFired = true;
            Mv.fire(p, "sprint_start");
        }
        // coiled spring charge: crouched still on the ground
        if (ground && p.isShiftKeyDown()) s.coil = moved ? 0 : s.coil + 1;
        else if (ground) s.coil = 0;
        // crouch edge
        boolean shift = p.isShiftKeyDown();
        if (shift && !s.prevShift) {
            if (s.wasSprinting && ground) {
                Mv.fire(p, "sprint_sneak");
                if (s.has(Mech.POWER_SLIDE)) slide(p, s, now);
            }
            if (p.isFallFlying() && s.has(Mech.AIR_BRAKE)) airBrake(p, s, now);
            if (s.has(Mech.RUBBER_BAND) && now <= s.bandUntil) snapBack(p, s);
        }
        s.prevShift = shift;
        // jump key edge (held keys only arrive for players with a movement skill)
        boolean jump = (s.keys & MovePayloads.K_JUMP) != 0;
        if (jump && !s.prevJumpKey) {
            if (p.isFallFlying() && s.has(Mech.FLAP)) flap(p, s, now);
            else if (!ground && p.onClimbable() && s.has(Mech.KICK_OFF)) kickOff(p, s);
        }
        s.prevJumpKey = jump;
        // riptide start
        boolean spin = p.isAutoSpinAttack();
        if (spin && !s.wasSpin) {
            Mv.fire(p, "riptide");
            if (s.has(Mech.STORM_SURGE)) surge(p, s);
        }
        s.wasSpin = spin;
        // elytra cards
        boolean gliding = p.isFallFlying();
        if (gliding && !s.wasGliding && s.lastFall >= 20) Mv.fire(p, "late_deploy");
        if (gliding) {
            if (!s.steepFired && p.getKnownMovement().y < -1.5) { s.steepFired = true; Mv.fire(p, "steep_dive"); }
        } else s.steepFired = false;
        s.wasGliding = gliding;
        // sprint jump onto something higher
        if (ground && !s.wasOnGround && !Double.isNaN(s.sprintJumpY)) {
            if (p.getY() >= s.sprintJumpY + 0.9) Mv.fire(p, "tall_jump");
            s.sprintJumpY = Double.NaN;
        }
        if (p.isSprinting()) s.sprintSeen = t;
        s.wasSprinting = p.isSprinting();
        if ((t + (p.getId() & 0x1f)) % 20 == 0) second(p, s, t);
    }

    /** Unit cards, once a second, using {@link MoveState#secondDist} from {@link Trackers#second}. */
    static void second(ServerPlayer p, MoveState s, long t) {
        ServerLevel level = p.serverLevel();
        double d = s.secondDist, dy = p.getY() - s.secY;
        s.secY = p.getY();
        if (Math.abs(dy) > 60) dy = 0; // teleported
        BlockPos pos = p.blockPosition();
        boolean ground = p.onGround() && !p.isPassenger(), sprint = p.isSprinting();
        if (ground && d > 0) {
            if (level.isRainingAt(pos) || level.isRainingAt(pos.above())) Mv.fireUnits(p, "rain_walk", d);
            if (snowy(level, pos)) Mv.fireUnits(p, "snow_walk", d);
            if (dy < -1) Mv.fireUnits(p, "downhill", d);
            if (p.isUsingItem()) Mv.fireUnits(p, "walk_and_use", d);
            if (pets(p, 8, true).size() > 0) Mv.fireUnits(p, "pet_walk", d);
        }
        if (p.onClimbable() && dy != 0) {
            BlockState in = p.getInBlockState();
            if (in.is(Blocks.VINE) || in.is(BlockTags.CAVE_VINES) || in.is(Blocks.WEEPING_VINES) || in.is(Blocks.WEEPING_VINES_PLANT)
                    || in.is(Blocks.TWISTING_VINES) || in.is(Blocks.TWISTING_VINES_PLANT)) Mv.fireUnits(p, "vine_climb", Math.abs(dy));
        }
        if (p.isVisuallyCrawling() && d > 0) Mv.fireUnits(p, "crawl", d);
        if (sprint && d > 0) {
            if (p.getHealth() <= p.getMaxHealth() / 2) Mv.fireUnits(p, "hurt_sprint", d);
            if (underground(p)) Mv.fireUnits(p, "cave_sprint", d);
            s.chased = chased(p, s.has(Mech.ESCAPE_ARTIST) ? s.p(Mech.ESCAPE_ARTIST, "range") : 24);
            if (s.chased) Mv.fireUnits(p, "chased_sprint", d);
            if (drafting(p, 3)) Mv.fireUnits(p, "drafting", d);
            if (!level.getEntitiesOfClass(Player.class, p.getBoundingBox().inflate(8), o -> o != p && Mv.ok(o)).isEmpty()
                    || !pets(p, 8, false).isEmpty()) Mv.fireUnits(p, "group_sprint", d);
        } else if (s.has(Mech.ESCAPE_ARTIST)) s.chased = chased(p, s.p(Mech.ESCAPE_ARTIST, "range"));
        if (p.isSwimming() && d > 0 && level.getBiome(pos).is(BiomeTags.IS_OCEAN)) Mv.fireUnits(p, "sea_swim", d);
        if (dy != 0 && shaft(p)) Mv.fireUnits(p, "shaft", Math.abs(dy));
        if (p.isInWater() && dy > 0 && !level.getFluidState(pos).isSource() && level.getFluidState(pos).is(FluidTags.WATER))
            Mv.fireUnits(p, "waterfall", dy);
    }

    static boolean snowy(Level level, BlockPos feet) {
        BlockState at = level.getBlockState(feet), below = level.getBlockState(feet.below());
        return at.is(Blocks.SNOW) || at.is(Blocks.POWDER_SNOW) || below.is(Blocks.SNOW_BLOCK) || below.is(Blocks.POWDER_SNOW)
                || below.is(Blocks.SNOW);
    }

    static boolean underground(Player p) {
        return p.getY() < 50 && p.level().getBrightness(LightLayer.SKY, p.blockPosition()) == 0;
    }

    /** Your tamed pets (not sitting) and mobs you hold on a lead, within r blocks. */
    static List<Mob> pets(Player p, double r, boolean leashToo) {
        return p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(r), m -> m.isAlive()
                && ((m instanceof TamableAnimal ta && p.getUUID().equals(ta.getOwnerUUID()) && !ta.isOrderedToSit())
                || (leashToo && m.getLeashHolder() == p)));
    }

    static boolean chased(Player p, double r) {
        return !p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(r), m -> m.getTarget() == p && m.isAlive()).isEmpty();
    }

    /** Someone moving just ahead of you (within r blocks, in front). */
    static boolean drafting(Player p, double r) {
        Vec3 f = Mv.flatLook(p);
        return !p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r, 1, r), e -> {
            if (e == p || e == p.getVehicle() || !e.isAlive()) return false;
            Vec3 off = e.position().subtract(p.position());
            double len = horiz(off);
            Vec3 mv = e instanceof Player o ? o.getKnownMovement() : e.getDeltaMovement();
            return len > 0.5 && (off.x * f.x + off.z * f.z) / len > 0.7 && horiz(mv) > 0.05;
        }).isEmpty();
    }

    /** Walls on two opposite sides at feet and head: a 1-wide shaft. */
    static boolean shaft(Player p) {
        AABB bb = p.getBoundingBox().deflate(0.01, 0.1, 0.01);
        Level l = p.level();
        boolean x = !l.noCollision(p, bb.move(0.12, 0, 0)) && !l.noCollision(p, bb.move(-0.12, 0, 0));
        boolean z = !l.noCollision(p, bb.move(0, 0, 0.12)) && !l.noCollision(p, bb.move(0, 0, -0.12));
        return x || z;
    }

    // ------------------------------------------------------------------------------------------- bursts (server)

    static void slide(ServerPlayer p, MoveState s, long now) {
        if (now < s.nextSlide || p.isPassenger()) return;
        Vec3 f = Mv.flatLook(p);
        double sp = Math.min(DashMath.CAP, s.v(Mech.POWER_SLIDE) / 2.2); // ground friction: blocks = speed * 2.2
        Mv.push(p, new Vec3(f.x * sp, p.getKnownMovement().y, f.z * sp));
        s.nextSlide = now + Math.round(s.p(Mech.POWER_SLIDE, "cooldown") * 20);
        p.level().playSound(null, p.blockPosition(), SoundEvents.GRAVEL_STEP, SoundSource.PLAYERS, 1f, 0.8f);
    }

    static void airBrake(ServerPlayer p, MoveState s, long now) {
        if (now < s.nextBrake) return;
        Mv.push(p, p.getKnownMovement().scale(1 - s.v(Mech.AIR_BRAKE) / 100));
        s.nextBrake = now + Math.round(s.p(Mech.AIR_BRAKE, "cooldown") * 20);
        p.level().playSound(null, p.blockPosition(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 0.8f, 0.6f);
    }

    static void flap(ServerPlayer p, MoveState s, long now) {
        if (now < s.nextFlap) return;
        Vec3 v = p.getKnownMovement().add(p.getLookAngle().scale(s.v(Mech.FLAP) / 20));
        Mv.push(p, cap(v, 3.0));
        if (!p.getAbilities().instabuild) p.causeFoodExhaustion((float) s.p(Mech.FLAP, "food"));
        s.nextFlap = now + Math.round(s.p(Mech.FLAP, "cooldown") * 20);
        p.level().playSound(null, p.blockPosition(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 1f, 1.2f);
    }

    /** Facing away from the wall you climb, jump off it. */
    static boolean kickOff(ServerPlayer p, MoveState s) {
        Vec3 f = Mv.flatLook(p);
        AABB bb = p.getBoundingBox().deflate(0, 0.2, 0);
        boolean behind = false;
        for (Direction d : Direction.Plane.HORIZONTAL)
            if (f.x * d.getStepX() + f.z * d.getStepZ() < -0.3 && !p.level().noCollision(p, bb.move(d.getStepX() * 0.2, 0, d.getStepZ() * 0.2)))
                behind = true;
        if (!behind) return false;
        double h = s.v(Mech.KICK_OFF) / 8;
        Mv.push(p, new Vec3(f.x * h, 0.42, f.z * h));
        return true;
    }

    static void surge(ServerPlayer p, MoveState s) {
        ItemStack st = p.getMainHandItem().is(Items.TRIDENT) ? p.getMainHandItem() : p.getOffhandItem();
        float f = st.is(Items.TRIDENT) ? EnchantmentHelper.getTridentSpinAttackStrength(st, p) : 0;
        if (f <= 0) f = 1.5f;
        Mv.push(p, cap(p.getLookAngle().scale(f * (1 + s.v(Mech.STORM_SURGE) / 100)), DashMath.CAP));
    }

    static void snapBack(ServerPlayer p, MoveState s) {
        Vec3 to = s.bandFrom;
        s.bandUntil = 0;
        if (to == null || p.level().dimension() != s.bandDim || !p.level().hasChunkAt(BlockPos.containing(to))) return;
        p.teleportTo(to.x, to.y, to.z);
        p.resetFallDistance();
        p.level().playSound(null, p.blockPosition(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1f, 1.4f);
    }

    static void cannonball(ServerPlayer p, MoveState s) {
        double r = s.v(Mech.CANNONBALL);
        float dmg = (float) s.p(Mech.CANNONBALL, "dmg");
        ServerLevel level = p.serverLevel();
        for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r, 2, r),
                e -> e instanceof Enemy && e.isAlive() && e.distanceToSqr(p) <= r * r)) {
            if (dmg > 0) le.hurt(p.damageSources().playerAttack(p), dmg);
            le.knockback(0.6, p.getX() - le.getX(), p.getZ() - le.getZ());
            le.push(0, 0.3, 0);
        }
        level.sendParticles(ParticleTypes.SPLASH, p.getX(), p.getY() + 0.5, p.getZ(), (int) (r * 20), r / 2, 0.3, r / 2, 0.1);
        level.playSound(null, p.blockPosition(), SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1.5f, 0.7f);
    }

    /** Knock (and hurt) mobs in a box along {@code dir}. Never players, your pets or your mount. Returns mobs hit. */
    static int bump(ServerPlayer p, AABB box, float dmg, double kb, Vec3 dir, Predicate<LivingEntity> which) {
        int hits = 0;
        for (LivingEntity le : p.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != p && e != p.getVehicle() && e.isAlive()
                && !(e instanceof Player) && !(e instanceof TamableAnimal ta && p.getUUID().equals(ta.getOwnerUUID())) && which.test(e))) {
            if (hits >= 8) break;
            if (dmg > 0) le.hurt(p.damageSources().playerAttack(p), dmg);
            le.knockback(kb, -dir.x, -dir.z);
            hits++;
        }
        return hits;
    }

    static final Predicate<LivingEntity> HOSTILE = e -> e instanceof Enemy;

    // ------------------------------------------------------------------------------------------- every tick (owners)

    static void tick(ServerPlayer p, MoveState s, long t) {
        long now = now(p);
        boolean ground = p.onGround();
        if (ground) s.shaftTicks = 0;
        else if (MoveHooks.chimney(p, s)) s.shaftTicks++;
        if (s.has(Mech.TORPEDO) && p.isSwimming() && now >= s.nextTorpedo) {
            Vec3 look = p.getLookAngle();
            if (bump(p, p.getBoundingBox().move(look.scale(0.5)).inflate(0.3), (float) s.v(Mech.TORPEDO), 0.8, look, HOSTILE) > 0)
                s.nextTorpedo = now + Math.round(s.p(Mech.TORPEDO, "cooldown") * 20);
        }
        if (s.has(Mech.SHOULDER_CHECK) && p.isSprinting() && ground && now >= s.nextCheck) {
            Vec3 f = Mv.flatLook(p);
            if (bump(p, p.getBoundingBox().move(f.scale(0.6)).inflate(0.1, 0, 0.1), (float) s.p(Mech.SHOULDER_CHECK, "dmg"),
                    s.v(Mech.SHOULDER_CHECK), f, HOSTILE) > 0) {
                s.nextCheck = now + Math.round(s.p(Mech.SHOULDER_CHECK, "cooldown") * 20);
                p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1f, 0.8f);
            }
        }
        if (s.has(Mech.PEREGRINE) && p.isFallFlying() && now >= s.nextDive) {
            Vec3 k = p.getKnownMovement();
            double sp = k.length();
            if (sp >= s.p(Mech.PEREGRINE, "min_speed")
                    && bump(p, p.getBoundingBox().inflate(0.6), (float) (s.v(Mech.PEREGRINE) * Math.min(1, sp / 2)), 1.0, k.normalize(), HOSTILE) > 0)
                s.nextDive = now + 10;
        }
        Entity veh = p.getVehicle();
        if (s.has(Mech.TRAMPLE_CHARGE) && veh instanceof LivingEntity mount && mount.getControllingPassenger() == p && now >= s.nextTrample
                && horiz(s.vehVel) >= s.p(Mech.TRAMPLE_CHARGE, "min_speed")) {
            Vec3 dir = new Vec3(s.vehVel.x, 0, s.vehVel.z).normalize();
            if (bump(p, mount.getBoundingBox().move(dir.scale(0.5)).inflate(0.2), (float) s.p(Mech.TRAMPLE_CHARGE, "dmg"),
                    s.v(Mech.TRAMPLE_CHARGE), dir, HOSTILE) > 0) s.nextTrample = now + 10;
        }
        if (s.has(Mech.PEDAL_POWER) && veh instanceof AbstractMinecart cart && (s.keys & MovePayloads.K_FORWARD) != 0) pedal(p, s, cart);
        if (s.has(Mech.PEGASUS) && veh instanceof AbstractHorse h && h.getControllingPassenger() == p && !h.onGround()
                && (s.keys & MovePayloads.K_JUMP) != 0 && s.vehVel.y > -(pegasusMaxFall(s) + 0.15)) {
            h.resetFallDistance();
            p.resetFallDistance();
        }
        if (s.has(Mech.MANTLE)) mantle(p, s, now);
        if (s.has(Mech.QUICK_WINGS)) quickWings(p, s);
        if (s.has(Mech.DOORBUSTER) && p.isSprinting() && p.horizontalCollision) doors(p, s);
    }

    static double pegasusMaxFall(MoveState s) {
        return 0.6 * (1 - s.v(Mech.PEGASUS) / 100);
    }

    /** Pedal Power: holding forward pushes the cart along its track, up to the vanilla 0.4 blocks/tick. */
    static void pedal(ServerPlayer p, MoveState s, AbstractMinecart cart) {
        Vec3 m = cart.getDeltaMovement();
        double h = horiz(m);
        if (h >= 0.4) return;
        Vec3 dir = h > 0.01 ? new Vec3(m.x / h, 0, m.z / h) : Mv.flatLook(p);
        double add = Math.min(0.4 - h, 0.4 * s.v(Mech.PEDAL_POWER) / 100 / 20);
        cart.setDeltaMovement(m.add(dir.scale(add)));
    }

    /** Mantle: jumping forward into a wall whose top is within reach pops you up onto it. */
    static boolean mantle(ServerPlayer p, MoveState s, long now) {
        if (now < s.nextMantle || p.onGround() || !p.horizontalCollision || p.isShiftKeyDown() || p.isFallFlying() || p.isInWater()
                || p.onClimbable() || p.isPassenger() || p.getAbilities().flying || (s.keys & MovePayloads.K_FORWARD) == 0) return false;
        Level level = p.level();
        Vec3 f = Mv.flatLook(p);
        double max = s.v(Mech.MANTLE), feet = p.getY();
        BlockPos front = BlockPos.containing(p.getX() + f.x * 0.8, feet, p.getZ() + f.z * 0.8);
        for (int dy = 0; dy <= Math.ceil(max) + 1; dy++) {
            BlockPos b = front.above(dy);
            if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty()) continue;
            if (dy == 0) return false; // nothing at your feet to climb
            double rise = b.getY() - feet;
            if (rise > max || rise < 0.6 || !level.getBlockState(b.above()).getCollisionShape(level, b.above()).isEmpty()) return false;
            Mv.push(p, new Vec3(f.x * 0.15, Math.min(1.2, Math.sqrt(2 * 0.08 * (rise + 0.3)) * 1.15), f.z * 0.15));
            s.nextMantle = now + Math.round(s.p(Mech.MANTLE, "cooldown") * 20);
            level.playSound(null, p.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 1f, 1.3f);
            return true;
        }
        return false;
    }

    /** Quick Wings: a long enough fall swaps an elytra from the inventory onto your chest and opens it; landing swaps
     * back only if the slot still holds the very item we put there (never overwrites anything). */
    static void quickWings(ServerPlayer p, MoveState s) {
        Inventory inv = p.getInventory();
        if (s.wingsSlot >= 0) {
            if (!p.onGround() && !p.isInWater()) return;
            ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
            int slot = s.wingsSlot;
            if (slot < inv.items.size() && inv.items.get(slot) == s.wingsOld && chest.is(Items.ELYTRA)) {
                inv.items.set(slot, chest);
                p.setItemSlot(EquipmentSlot.CHEST, s.wingsOld);
            }
            s.wingsSlot = -1;
            s.wingsOld = null;
            return;
        }
        if (p.onGround() || p.isFallFlying() || p.isInWater() || p.isPassenger() || p.getAbilities().flying
                || p.fallDistance < s.v(Mech.QUICK_WINGS)) return;
        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.canElytraFly(p)) {
            p.startFallFlying();
            return;
        }
        if (EnchantmentHelper.has(chest, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE) && !p.isCreative()) return;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack st = inv.items.get(i);
            if (st.is(Items.ELYTRA) && st.canElytraFly(p)) {
                inv.items.set(i, chest);
                p.setItemSlot(EquipmentSlot.CHEST, st);
                p.startFallFlying();
                s.wingsSlot = i;
                s.wingsOld = chest;
                return;
            }
        }
    }

    /** Doorbuster: sprinting into a closed wooden door (tier 2: fence gate) opens it; tier 3 shuts it again after you. */
    static void doors(ServerPlayer p, MoveState s) {
        Level level = p.level();
        Vec3 f = Mv.flatLook(p);
        int tier = (int) s.v(Mech.DOORBUSTER);
        int closeTicks = (int) Math.round(s.p(Mech.DOORBUSTER, "close") * 20);
        for (double h : new double[]{0.5, 1.5}) {
            BlockPos b = BlockPos.containing(p.getX() + f.x * 0.7, p.getY() + h, p.getZ() + f.z * 0.7);
            BlockState st = level.getBlockState(b);
            if (st.getBlock() instanceof DoorBlock door && st.is(BlockTags.WOODEN_DOORS) && !st.getValue(DoorBlock.OPEN)) {
                door.setOpen(p, level, st, b, true);
                if (tier >= 3) Later.run(p.serverLevel(), closeTicks, () -> {
                    BlockState now = level.getBlockState(b);
                    if (now.getBlock() instanceof DoorBlock d && now.getValue(DoorBlock.OPEN)) d.setOpen(null, level, now, b, false);
                });
                return;
            }
            if (tier >= 2 && st.getBlock() instanceof FenceGateBlock && !st.getValue(FenceGateBlock.OPEN)) {
                level.setBlock(b, st.setValue(FenceGateBlock.OPEN, true), 10);
                level.playSound(null, b, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1f, 1f);
                if (tier >= 3) Later.run(p.serverLevel(), closeTicks, () -> {
                    BlockState now = level.getBlockState(b);
                    if (now.getBlock() instanceof FenceGateBlock && now.getValue(FenceGateBlock.OPEN)
                            && level.getEntitiesOfClass(LivingEntity.class, new AABB(b)).isEmpty()) {
                        level.setBlock(b, now.setValue(FenceGateBlock.OPEN, false), 10);
                        level.playSound(null, b, SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1f, 1f);
                    }
                });
                return;
            }
        }
    }

    // ------------------------------------------------------------------------------------------- attributes (5 ticks)

    static void attributes(ServerPlayer p, MoveState s, BlockState below, long t) {
        Level level = p.level();
        boolean ground = p.onGround();
        double y = p.getY();
        boolean descending = ground && y - s.y5 < -0.25;
        s.y5 = y;
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.SNOWSHOES,
                s.has(Mech.SNOWSHOES) && ground && snowy(level, p.blockPosition()) ? s.v(Mech.SNOWSHOES) : 0);
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.DOWNHILL, s.has(Mech.DOWNHILL) && descending ? s.v(Mech.DOWNHILL) : 0);
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.RAIL_RUNNER, s.has(Mech.RAIL_RUNNER) && !p.isPassenger()
                && level.getBlockState(p.blockPosition()).is(BlockTags.RAILS) ? s.v(Mech.RAIL_RUNNER) : 0);
        Mv.attrAdd(p, Attributes.KNOCKBACK_RESISTANCE, Mech.IRON_GRIP, s.has(Mech.IRON_GRIP) && p.onClimbable() ? s.v(Mech.IRON_GRIP) / 100 : 0);
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.CRAWL_SPACE, s.has(Mech.CRAWL_SPACE) && p.isVisuallyCrawling() ? s.v(Mech.CRAWL_SPACE) : 0);
        if (s.has(Mech.SWIM_RHYTHM) && p.isSwimming()) s.swimMom = Math.min(s.v(Mech.SWIM_RHYTHM), s.swimMom + s.p(Mech.SWIM_RHYTHM, "rate") / 4);
        else s.swimMom = 0;
        Mv.attrPct(p, NeoForgeMod.SWIM_SPEED, Mech.SWIM_RHYTHM, s.swimMom);
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.ESCAPE_ARTIST, s.has(Mech.ESCAPE_ARTIST) && s.chased ? s.v(Mech.ESCAPE_ARTIST) : 0);
        s.drafting = s.has(Mech.SLIPSTREAM) && p.isSprinting() && drafting(p, s.p(Mech.SLIPSTREAM, "range"));
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.SLIPSTREAM, s.drafting ? s.v(Mech.SLIPSTREAM) : 0);
    }

    static void clear(ServerPlayer p, MoveState s) {
        for (Mech m : new Mech[]{Mech.SNOWSHOES, Mech.DOWNHILL, Mech.RAIL_RUNNER, Mech.CRAWL_SPACE, Mech.ESCAPE_ARTIST, Mech.SLIPSTREAM})
            Mv.attrPct(p, Attributes.MOVEMENT_SPEED, m, 0);
        Mv.attrAdd(p, Attributes.KNOCKBACK_RESISTANCE, Mech.IRON_GRIP, 0);
        Mv.attrPct(p, NeoForgeMod.SWIM_SPEED, Mech.SWIM_RHYTHM, 0);
        s.swimMom = 0;
    }

    // ------------------------------------------------------------------------------------------- every second (owners)

    private static final List<Holder<MobEffect>> SHARED = List.of(MobEffects.MOVEMENT_SPEED, MobEffects.JUMP, MobEffects.FIRE_RESISTANCE,
            MobEffects.REGENERATION, MobEffects.DAMAGE_RESISTANCE, MobEffects.WATER_BREATHING);

    static void ownerSecond(ServerPlayer p, MoveState s, long t) {
        boolean moving = t - s.lastMoveTick < 20, sprint = p.isSprinting();
        if (s.has(Mech.WALKIES) && moving) {
            int amp = (int) s.p(Mech.WALKIES, "amp");
            for (Mob m : pets(p, s.v(Mech.WALKIES), true))
                m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 40, amp, true, false, true));
        }
        if (s.has(Mech.PACE_SETTER) && sprint) {
            int amp = (int) s.p(Mech.PACE_SETTER, "amp");
            double r = s.v(Mech.PACE_SETTER);
            for (Player o : p.level().getEntitiesOfClass(Player.class, p.getBoundingBox().inflate(r), o -> o != p && Mv.ok(o)))
                o.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 60, amp, true, false, true));
            for (Mob m : pets(p, r, false)) m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 60, amp, true, false, true));
        }
        if (s.has(Mech.SHARED_STRIDE) && p.getVehicle() instanceof LivingEntity mount) share(p, s, mount);
        if (s.has(Mech.RUNNERS_HIGH) && sprint) {
            double every = Math.max(1, s.p(Mech.RUNNERS_HIGH, "every"));
            s.highDist += s.secondDist;
            while (s.highDist >= every) {
                s.highDist -= every;
                p.heal((float) s.v(Mech.RUNNERS_HIGH));
            }
        }
    }

    /** Shared Stride: the first N kinds of {@link #SHARED} you have also go on your mount, same level, kept fresh. */
    static void share(Player p, MoveState s, LivingEntity mount) {
        int n = Math.min(SHARED.size(), (int) s.v(Mech.SHARED_STRIDE));
        for (int i = 0; i < n; i++) {
            MobEffectInstance mine = p.getEffect(SHARED.get(i));
            if (mine != null) mount.addEffect(new MobEffectInstance(SHARED.get(i), 60, mine.getAmplifier(), true, false, true));
        }
    }

    // ------------------------------------------------------------------------------------------- climbing (both sides)

    /** Chain Climber: tier 1 chains, 2 also lightning and end rods, 3 also kelp and seagrass. */
    static boolean thinClimb(Player p, MoveState s) {
        if (!s.has(Mech.CHAIN_CLIMBER)) return false;
        BlockState in = p.getInBlockState();
        int tier = (int) s.v(Mech.CHAIN_CLIMBER);
        return in.is(Blocks.CHAIN)
                || (tier >= 2 && (in.is(Blocks.LIGHTNING_ROD) || in.is(Blocks.END_ROD)))
                || (tier >= 3 && (in.is(Blocks.KELP) || in.is(Blocks.KELP_PLANT) || in.is(Blocks.SEAGRASS) || in.is(Blocks.TALL_SEAGRASS)));
    }

    // ------------------------------------------------------------------------------------------- local player (client)

    static void local(Player p, MoveState s) {
        boolean jump = (s.keys & MovePayloads.K_JUMP) != 0;
        if (p.onGround()) s.shaftTicks = 0;
        else if (MoveHooks.chimney(p, s)) s.shaftTicks++;
        if (s.has(Mech.SALMON_RUN) && jump && p.isInWater()) {
            Vec3 v = salmon(p, s, p.getDeltaMovement());
            if (v != p.getDeltaMovement()) p.setDeltaMovement(v);
        }
        if (s.has(Mech.PEGASUS) && jump && p.getVehicle() instanceof AbstractHorse h && h.getControllingPassenger() == p && !h.onGround()) {
            Vec3 v = h.getDeltaMovement();
            double max = pegasusMaxFall(s);
            if (v.y < -max) h.setDeltaMovement(v.x, -max, v.z);
        }
    }

    /** Salmon Run: in flowing (not still) water, holding jump rises at the skill's speed. */
    static Vec3 salmon(Player p, MoveState s, Vec3 v) {
        var fluid = p.level().getFluidState(p.blockPosition());
        if (!fluid.is(FluidTags.WATER) || fluid.isSource()) return v;
        double rise = s.v(Mech.SALMON_RUN) / 20;
        return v.y < rise ? new Vec3(v.x, rise, v.z) : v;
    }
}
