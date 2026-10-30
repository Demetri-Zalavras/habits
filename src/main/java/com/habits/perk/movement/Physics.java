package com.habits.perk.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Continuous movement feel, applied to the LOCAL player only (player movement is client-driven in vanilla, so this is
 * where smooth physics belongs). Nothing here grants a burst of height on demand except small, block-gated pushes
 * (vine fling, ledge grab, seabed jump); the big ones go through {@link Actions} on the server. The server separately
 * decides fall damage. Common code: no client classes, reads held keys from {@link MoveState#keys}.
 */
public final class Physics {
    private Physics() {}

    public static void tick(Player p) {
        MoveState s = MoveState.of(p);
        MoveHooks.nightGamma = s.has(Mech.NIGHT_OWL) && nightSky(p) ? (float) (s.v(Mech.NIGHT_OWL) / 100) : 0;
        if (!s.any) {
            s.jumpEdge = false;
            return;
        }
        s.tick++;
        MoveHooks.trackFall(p, s);
        apply(p, s);
        SpecMoves.local(p, s);
        s.jumpEdge = false;
        s.wasOnGround = p.onGround();
        s.wasInWater = p.isInWater();
    }

    static void apply(Player p, MoveState s) {
        Level level = p.level();
        Vec3 v = p.getDeltaMovement();
        boolean ground = p.onGround(), water = p.isInWater();
        boolean jumpHeld = (s.keys & MovePayloads.K_JUMP) != 0, fwd = (s.keys & MovePayloads.K_FORWARD) != 0;
        boolean free = !p.getAbilities().flying && !p.isPassenger() && !p.isFallFlying();
        if (ground) {
            s.climbStamina = 0;
            s.grabbed = false;
            s.dolphinLeapt = false;
            s.lastGround = p.position();
        }
        if (p.isPassenger()) {
            boats(p, s);
            return;
        }
        if (p.isFallFlying()) {
            elytra(p, s, v);
            return;
        }
        if (!free) return;
        BlockState feet = level.getBlockState(p.blockPosition());
        // ---- climbing
        if (p.onClimbable()) {
            double up = 0;
            boolean pushing = p.horizontalCollision || jumpHeld;
            if (s.has(Mech.SPIDER_CLIMB) && pushing) up = Math.max(up, 0.2 * (1 + s.v(Mech.SPIDER_CLIMB) / 100));
            boolean scaffold = feet.is(Blocks.SCAFFOLDING), ladder = feet.is(Blocks.LADDER);
            if (s.has(Mech.SCAFFOLD_MONKEY) && (scaffold || ladder)) {
                double mult = s.v(Mech.SCAFFOLD_MONKEY);
                if (pushing) up = Math.max(up, 0.15 * mult);
                else if (scaffold && p.isShiftKeyDown()) v = new Vec3(v.x, -0.15 * mult, v.z);
                else if (ladder && !p.isShiftKeyDown() && v.y < 0) v = new Vec3(v.x, -0.15 * mult, v.z);
            }
            if (up > 0 && v.y > 0) v = new Vec3(v.x, Math.min(0.7, up), v.z);
            // Jungle Explorer: jump off a vine to fling forward
            if (s.has(Mech.JUNGLE_EXPLORER) && s.jumpEdge && feet.is(Blocks.VINE)) {
                Vec3 f = Mv.flatLook(p);
                double h = s.v(Mech.JUNGLE_EXPLORER) / 10.4;
                v = new Vec3(f.x * h, 0.5, f.z * h);
            }
        }
        // any-wall climbing is LivingEntity.onClimbable (MoveHooks.climbable); this only spends its stamina
        if (!ground && MoveHooks.wallClimb(p, s)) s.climbStamina++;
        // ---- water
        if (water) {
            if (s.has(Mech.BUBBLE_ELEVATOR)) {
                double rise = s.v(Mech.BUBBLE_ELEVATOR) / 20;
                if (jumpHeld) v = new Vec3(v.x, rise, v.z);
                else if (p.isShiftKeyDown()) v = new Vec3(v.x, -rise, v.z);
            }
            if (s.has(Mech.SEABED_WALKER) && ground && s.jumpEdge && s.lv(Mech.SEABED_WALKER) >= s.p(Mech.SEABED_WALKER, "jump_level"))
                v = new Vec3(v.x, 0.45, v.z);
            if (s.has(Mech.CURRENT_SURFER)) v = current(p, s, v);
        } else if (s.wasInWater && !s.dolphinLeapt && s.has(Mech.DOLPHIN_KICK) && p.isSprinting() && v.y > 0
                && s.lv(Mech.DOLPHIN_KICK) >= s.p(Mech.DOLPHIN_KICK, "leap_level")) {
            s.dolphinLeapt = true;
            v = new Vec3(v.x * 1.3, 0.6, v.z * 1.3);
        }
        // ---- air
        if (!ground && !water && !p.onClimbable()) {
            if (s.has(Mech.GLIDER_KICK) && jumpHeld && v.y < 0) {
                double max = ServerTick.glideMaxFall(s);
                if (v.y < -max) v = new Vec3(v.x, -max, v.z);
                Vec3 f = Mv.flatLook(p);
                double drift = s.p(Mech.GLIDER_KICK, "drift"), h = Math.hypot(v.x, v.z);
                if (h < drift) v = v.add(f.x * drift * 0.15, 0, f.z * drift * 0.15);
            }
            if (s.has(Mech.HONEY_GLIDER) && fwd && p.horizontalCollision && v.y < 0)
                v = new Vec3(v.x, Math.max(v.y, -s.v(Mech.HONEY_GLIDER) / 20), v.z);
            if (s.has(Mech.SKY_WATCHER) && v.y < -0.2 && p.getY() >= s.p(Mech.SKY_WATCHER, "min_y")) {
                if (p.getXRot() < -20) v = new Vec3(v.x, Math.max(v.y, -ServerTick.skyFloatMaxFall(s)), v.z);
                else if (p.getXRot() > 60) v = new Vec3(v.x, Math.max(-7.8, v.y - 0.04), v.z);
            }
            if (s.has(Mech.PARKOUR)) v = parkour(p, s, v, fwd);
            if (s.has(Mech.EDGE_SENSE)) v = edge(p, s, v, jumpHeld);
        }
        // ---- ground (ice control is LivingEntity.travel friction: MoveHooks.friction)
        if (s.has(Mech.BUNNY_HOP)) v = bunny(p, s, v);
        if (!v.equals(p.getDeltaMovement())) p.setDeltaMovement(v);
    }

    /** Night Owl: night time (13000-23000) under open sky. */
    static boolean nightSky(Player p) {
        long day = p.level().getDayTime() % 24000;
        return day >= 13000 && day <= 23000 && p.level().canSeeSky(p.blockPosition());
    }

    /** Currents push x mult with you, and only (1 - resist) against you. */
    static Vec3 current(Player p, MoveState s, Vec3 v) {
        FluidState fs = p.level().getFluidState(p.blockPosition());
        if (fs.isEmpty()) return v;
        Vec3 flow = fs.getFlow(p.level(), p.blockPosition());
        if (flow.lengthSqr() < 1e-6) return v;
        double with = v.x * flow.x + v.z * flow.z;
        double k = with >= 0 ? 0.014 * (s.v(Mech.CURRENT_SURFER) - 1) : -0.014 * s.p(Mech.CURRENT_SURFER, "resist") / 100;
        return v.add(flow.x * k, 0, flow.z * k);
    }

    /** Parkour: longer sprint jumps, plus a pull-up when you barely miss a ledge. */
    static Vec3 parkour(Player p, MoveState s, Vec3 v, boolean fwd) {
        if (s.wasOnGround && v.y > 0.3 && p.isSprinting()) {
            double k = 1 + s.v(Mech.PARKOUR) / 100, h = Math.hypot(v.x, v.z);
            if (h * k < 1.0) v = new Vec3(v.x * k, v.y, v.z * k);
        }
        if (!s.grabbed && fwd && p.horizontalCollision && v.y < 0) {
            Direction face = p.getDirection();
            BlockPos front = BlockPos.containing(p.getX(), p.getY(), p.getZ()).relative(face);
            BlockState b = p.level().getBlockState(front);
            double top = front.getY() + b.getCollisionShape(p.level(), front).max(Direction.Axis.Y);
            double gap = top - p.getY();
            if (gap > 0 && gap <= s.p(Mech.PARKOUR, "grab") + 0.6 && p.level().getBlockState(front.above()).getCollisionShape(p.level(), front.above()).isEmpty()
                    && p.level().getBlockState(front.above(2)).getCollisionShape(p.level(), front.above(2)).isEmpty()) {
                s.grabbed = true;
                v = new Vec3(v.x, 0.42, v.z);
            }
        }
        return v;
    }

    /** Edge Sense: while sneaking, slipping off an edge snaps you back onto it if it's within reach. */
    static Vec3 edge(Player p, MoveState s, Vec3 v, boolean jumpHeld) {
        if (!p.isShiftKeyDown() || jumpHeld || s.lastGround == null || v.y >= 0 || p.fallDistance > 1.2f) return v;
        Vec3 g = s.lastGround;
        if (Math.hypot(p.getX() - g.x, p.getZ() - g.z) > s.v(Mech.EDGE_SENSE) + 0.3 || g.y - p.getY() > 1.2) return v;
        p.setPos(g.x, g.y, g.z);
        p.resetFallDistance();
        return Vec3.ZERO;
    }

    /** Bunny Hop: jumping again within 3 ticks of landing keeps the speed you landed with. */
    static Vec3 bunny(Player p, MoveState s, Vec3 v) {
        if (p.onGround() && !s.wasOnGround) {
            s.landTick = s.tick;
            s.preLandSpeed = Math.hypot(v.x, v.z);
        }
        if (s.wasOnGround && !p.onGround() && v.y > 0.3 && s.tick - s.landTick <= 3) {
            double h = Math.hypot(v.x, v.z), want = s.preLandSpeed * s.p(Mech.BUNNY_HOP, "keep") / 100;
            if (h > 0.01 && want > h) v = new Vec3(v.x * want / h, v.y, v.z * want / h);
        }
        return v;
    }

    static void elytra(Player p, MoveState s, Vec3 v) {
        if (!s.has(Mech.SKY_GLIDER)) return;
        double keep = 1 + 0.01 * s.v(Mech.SKY_GLIDER) / 100; // vanilla drag is 1% a tick
        v = new Vec3(v.x * keep, v.y, v.z * keep);
        if (s.lv(Mech.SKY_GLIDER) >= s.p(Mech.SKY_GLIDER, "level_glide_level") && Math.abs(p.getXRot()) < 20) {
            Vec3 f = Mv.flatLook(p);
            double h = Math.max(0.4, Math.hypot(v.x, v.z));
            v = new Vec3(f.x * h, Math.max(v.y, -0.03), f.z * h);
        }
        p.setDeltaMovement(v);
    }

    /** Ice Road grip: spin dies off like on water (ice keeps 98.9% a tick) and the slide bends toward where the boat
     * points, at the same speed (never adds speed). */
    static Vec3 grip(Boat boat, Vec3 v, Vec3 forward) {
        boat.deltaRotation *= 0.9f;
        double h = Math.hypot(v.x, v.z);
        if (h < 1e-4) return v;
        double sign = v.x * forward.x + v.z * forward.z >= 0 ? 1 : -1; // reversing keeps reversing
        return new Vec3(v.x + (forward.x * h * sign - v.x) * 0.3, v.y, v.z + (forward.z * h * sign - v.z) * 0.3);
    }

    /** Oarsman speed and shore climb, Ice Road hops. The local player drives the boat. */
    static void boats(Player p, MoveState s) {
        Entity veh = p.getVehicle();
        if (!(veh instanceof Boat boat) || boat.getControllingPassenger() != p) return;
        Vec3 v = boat.getDeltaMovement();
        Vec3 f = Vec3.directionFromRotation(0, boat.getYRot());
        boolean onIce = p.level().getBlockState(boat.blockPosition().below()).is(BlockTags.ICE)
                || p.level().getBlockState(boat.blockPosition()).is(BlockTags.ICE);
        boolean onWater = p.level().getFluidState(boat.blockPosition()).is(FluidTags.WATER)
                || p.level().getFluidState(boat.blockPosition().below()).is(FluidTags.WATER);
        if (s.has(Mech.OARSMAN) && onWater && !onIce && (s.keys & MovePayloads.K_FORWARD) != 0) {
            v = v.add(f.x * 0.04 * s.v(Mech.OARSMAN) / 100, 0, f.z * 0.04 * s.v(Mech.OARSMAN) / 100);
            if (s.lv(Mech.OARSMAN) >= s.p(Mech.OARSMAN, "shore_level") && boat.horizontalCollision) {
                BlockPos ahead = boat.blockPosition().relative(boat.getDirection());
                if (p.level().getBlockState(ahead.above()).isAir() && p.level().getBlockState(ahead.above(2)).isAir())
                    boat.setPos(boat.getX(), boat.getY() + 1.05, boat.getZ());
            }
        }
        if (s.has(Mech.ICE_ROAD) && onIce) {
            v = grip(boat, v, f);
            if (s.jumpEdge && s.tick >= s.nextBoatHop) {
                double h = s.v(Mech.ICE_ROAD);
                v = new Vec3(v.x, Math.sqrt(2 * 0.04 * h) + 0.02, v.z);
                s.nextBoatHop = s.tick + Math.round(s.p(Mech.ICE_ROAD, "cooldown") * 20);
            }
        }
        boat.setDeltaMovement(v);
    }
}
