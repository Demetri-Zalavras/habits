package com.habits.perk.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** Server handlers for client movement intents. Every check is redone here; the client only says "I pressed it". */
final class Actions {
    private Actions() {}

    static void act(ServerPlayer p, byte kind, int arg) {
        if (!Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        if (!s.any) return;
        switch (kind) {
            case MovePayloads.DOUBLE_JUMP -> doubleJump(p, s);
            case MovePayloads.WALL_JUMP -> { if (!wallJump(p, s)) doubleJump(p, s); } // no kick left: the jump is still a double jump
            case MovePayloads.DASH -> dash(p, s);
            case MovePayloads.BLINK -> blink(p, s);
            case MovePayloads.ISLAND_HOP -> islandHop(p, s);
            case MovePayloads.PING -> ping(p, s);
            case MovePayloads.WHISTLE -> whistle(p, s);
            case MovePayloads.MOUNT -> callMount(p, s);
            case MovePayloads.WAYPOINT -> waypoint(p, s, true);
            case MovePayloads.WAYPOINT_CLEAR -> waypoint(p, s, false);
            case MovePayloads.TRAVEL_LIST -> travelList(p, s);
            case MovePayloads.TRAVEL -> travel(p, s, arg);
            case MovePayloads.MARK_ENTITY -> markEntity(p, s, arg);
            default -> {}
        }
    }

    static long now(Entity e) {
        return e.level().getGameTime();
    }

    /** No midair tricks while flying, gliding, swimming, climbing or riding. */
    static boolean freeAir(ServerPlayer p) {
        return !p.onGround() && !p.isFallFlying() && !p.isInWater() && !p.isInLava() && !p.isPassenger()
                && !p.getAbilities().flying && !p.onClimbable();
    }

    static boolean doubleJump(ServerPlayer p, MoveState s) {
        if (!s.has(Mech.DOUBLE_JUMP) || !freeAir(p) || s.airJumps >= (int) s.v(Mech.DOUBLE_JUMP)) return false;
        s.airJumps++;
        Vec3 k = p.getKnownMovement();
        Mv.push(p, new Vec3(k.x, 0.42 * s.p(Mech.DOUBLE_JUMP, "strength") + 0.1, k.z));
        return true;
    }

    static boolean wallJump(ServerPlayer p, MoveState s) {
        if (!s.has(Mech.WALL_JUMP) || !freeAir(p) || now(p) < s.nextWallKick) return false;
        if (s.wallKicks >= (int) s.p(Mech.WALL_JUMP, "kicks")) return false;
        Direction wall = Mv.wall(p);
        if (wall == null) return false;
        double pw = s.v(Mech.WALL_JUMP) / 100.0;
        s.wallKicks++;
        s.nextWallKick = now(p) + Math.round(s.p(Mech.WALL_JUMP, "cooldown") * 20);
        Direction away = wall.getOpposite();
        double h = 0.25 + 0.3 * pw;
        Mv.push(p, new Vec3(away.getStepX() * h, 0.35 + 0.3 * pw, away.getStepZ() * h));
        Mv.fire(p, "wall_jump");
        return true;
    }

    /** Client-only sound (defined in the movement sounds part, quiet there); played to the dasher alone. */
    static final SoundEvent DASH_NOT_READY = SoundEvent.createVariableRangeEvent(com.habits.Habits.id("movement.dash_not_ready"));

    static boolean dash(ServerPlayer p, MoveState s) {
        // in water or rain the riptide lunge takes the input
        if (s.has(Mech.RIPTIDE_RIDER) && p.isInWaterOrRain() && now(p) >= s.nextLunge) return lunge(p, s);
        if (s.has(Mech.BLINK)) return blinkThrough(p, s); // Blink replaces the dash while on
        if (!s.has(Mech.DASH) || p.isPassenger() || p.isFallFlying() || p.getAbilities().flying) return false;
        if (now(p) < s.nextDash) return notReady(p, s);
        // values = the real ground distance (measured); push = the ground burst that makes it, held for "hold" ticks
        double d = s.v(Mech.DASH);
        Vec3 dir = Mv.flatLook(p);
        Vec3 known = p.getKnownMovement();
        double vy = Math.max(0.05, known.y);
        boolean ground = p.onGround();
        // in the air the burst gets air drag until it lands, then ground friction: size it for that so it goes d either way,
        // and keep the jump's forward momentum (the push replaces velocity, so without this the air dash loses it)
        double speed = Math.min(DashMath.CAP, ground ? s.p(Mech.DASH, "push") * s.boost(Mech.DASH) / 2.2
                : d / DashMath.reach(DashMath.airTicks(dropToFloor(p), vy)) + Math.max(0, known.x * dir.x + known.z * dir.z));
        s.nextDash = now(p) + Math.round(s.p(Mech.DASH, "cooldown") * 20);
        s.iframesUntil = now(p) + Math.max(1, Math.round(s.p(Mech.DASH, "iframes") * 20));
        Mv.push(p, new Vec3(dir.x * speed, vy, dir.z * speed));
        // past the cap a ground dash goes further with a second, smaller burst a tick later (Later 0 = sent next tick)
        double boost = ground ? Math.min(DashMath.CAP, s.p(Mech.DASH, "boost") * s.boost(Mech.DASH)) : 0;
        if (boost > 0)
            com.habits.perk.Later.run(p.server, 0, () -> {
                if (p.isAlive() && !p.isRemoved() && !p.isPassenger() && !p.isFallFlying() && !p.getAbilities().flying)
                    Mv.push(p, new Vec3(dir.x * boost, p.getKnownMovement().y, dir.z * boost));
            });
        return true;
    }

    static boolean notReady(ServerPlayer p, MoveState s) {
        if (now(p) >= s.nextDashNag) {
            s.nextDashNag = now(p) + 10; // at most one click per half second while the tap is spammed
            Mv.say(p, "habits.movement.dash_wait", (s.nextDash - now(p) + 19) / 20);
            p.playNotifySound(DASH_NOT_READY, SoundSource.PLAYERS, 1, 1);
        }
        return false;
    }

    /** Blink (Mythical): the Dash key teleports up to the skill's value where you look, through walls, onto a safe spot. */
    static boolean blinkThrough(ServerPlayer p, MoveState s) {
        if (p.isPassenger() || p.isSleeping()) return false;
        if (now(p) < s.nextDash) return notReady(p, s);
        Vec3 from = p.position(), at = blinkSpot(p, s.v(Mech.BLINK), (int) s.p(Mech.BLINK, "floor"));
        if (at == null) {
            Mv.say(p, "habits.movement.blink_blocked");
            return false;
        }
        s.nextDash = now(p) + Math.round(s.p(Mech.BLINK, "cooldown") * 20);
        p.teleportTo(at.x, at.y, at.z);
        p.resetFallDistance();
        for (Vec3 v : new Vec3[]{from, at})
            p.level().playSound(null, v.x, v.y, v.z, net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.6f, 1.4f);
        return true;
    }

    /**
     * The farthest spot up to {@code dist} blocks along the look ray where a standing player fits: a floor within
     * {@code floor} blocks below (never the void), no lava, inside the world border and build height (under a Nether
     * roof), and only in loaded chunks. Walls in between don't matter. Null when there is none.
     */
    @org.jetbrains.annotations.Nullable
    static Vec3 blinkSpot(ServerPlayer p, double dist, int floor) {
        ServerLevel level = p.serverLevel();
        Vec3 from = p.position(), look = p.getLookAngle();
        int top = level.dimensionType().hasCeiling() ? level.getMinBuildHeight() + level.dimensionType().logicalHeight() : level.getMaxBuildHeight();
        var size = p.getDimensions(net.minecraft.world.entity.Pose.STANDING);
        for (double d = dist; d >= 1; d -= 0.5) {
            Vec3 aim = from.add(look.scale(d));
            // walk down from one block above the aim point (a step up) to the first floor, and only try that floor
            BlockPos.MutableBlockPos at = BlockPos.containing(aim.x, aim.y + 1, aim.z).mutable();
            for (int i = 0; i <= floor + 1 && at.getY() >= level.getMinBuildHeight(); i++, at.move(Direction.DOWN)) {
                if (!level.isLoaded(at)) break;
                var shape = level.getBlockState(at).getCollisionShape(level, at);
                if (shape.isEmpty()) continue;
                Vec3 feet = new Vec3(aim.x, at.getY() + shape.max(Direction.Axis.Y), aim.z);
                AABB box = size.makeBoundingBox(feet);
                if (box.maxY <= top && level.getWorldBorder().isWithinBounds(box) && hasChunks(level, box)
                        && level.noCollision(p, box) && level.getBlockStates(box).noneMatch(b -> b.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)))
                    return feet;
                break;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static boolean hasChunks(ServerLevel level, AABB box) {
        return level.hasChunksAt(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ));
    }

    /** Blocks between the feet and the first solid top straight below (64 when there is none that close). */
    static double dropToFloor(ServerPlayer p) {
        // ponytail: only the column under the feet; a dash that carries you over a ledge lands later than predicted
        BlockPos.MutableBlockPos at = p.blockPosition().mutable();
        for (int i = 0; i < 64; i++, at.move(Direction.DOWN)) {
            var shape = p.level().getBlockState(at).getCollisionShape(p.level(), at);
            if (!shape.isEmpty() && at.getY() + shape.max(Direction.Axis.Y) <= p.getY() + 1e-4) return p.getY() - at.getY() - shape.max(Direction.Axis.Y);
        }
        return 64;
    }

    static boolean lunge(ServerPlayer p, MoveState s) {
        double d = s.v(Mech.RIPTIDE_RIDER);
        Vec3 dir = p.getLookAngle();
        s.nextLunge = now(p) + Math.round(s.p(Mech.RIPTIDE_RIDER, "cooldown") * 20);
        double speed = d / (p.isInWater() ? 4.0 : 9.0);
        p.startAutoSpinAttack(20, 0, ItemStack.EMPTY);
        Mv.push(p, dir.scale(speed));
        return true;
    }

    static boolean blink(ServerPlayer p, MoveState s) {
        if (!s.has(Mech.ENDWALKER) || !p.isShiftKeyDown() || now(p) < s.nextBlink || p.isPassenger()) return false;
        double d = s.v(Mech.ENDWALKER);
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        HitResult hit = p.level().clip(new ClipContext(eye, eye.add(look.scale(d)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        Vec3 end = hit.getType() == HitResult.Type.MISS ? hit.getLocation() : hit.getLocation().subtract(look.scale(0.6));
        Vec3 feet = end.subtract(0, p.getEyeHeight(), 0);
        for (int up = 0; up <= 2; up++) {
            Vec3 at = feet.add(0, up, 0);
            if (p.level().noCollision(p, p.getDimensions(p.getPose()).makeBoundingBox(at)) && p.level().isLoaded(BlockPos.containing(at))) {
                s.nextBlink = now(p) + Math.round(s.p(Mech.ENDWALKER, "cooldown") * 20);
                p.teleportTo(at.x, at.y, at.z);
                p.resetFallDistance();
                p.level().playSound(null, at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                        net.minecraft.sounds.SoundSource.PLAYERS, 0.6f, 1.2f);
                return true;
            }
        }
        return false;
    }

    static boolean islandHop(ServerPlayer p, MoveState s) {
        if (!s.has(Mech.END_ISLANDER) || p.level().dimension() != Level.END || now(p) < s.nextIslandHop || p.isPassenger()
                || p.isFallFlying()) return false;
        // must have just left the ground at an edge: nothing under the spot ahead for 8 blocks
        Vec3 dir = Mv.flatLook(p);
        BlockPos ahead = BlockPos.containing(p.position().add(dir.scale(1.5)));
        if (p.onGround() || p.getKnownMovement().y <= 0) return false; // just jumped, still rising
        for (int i = 1; i <= 8; i++) if (!p.level().getBlockState(ahead.below(i)).isAir()) return false;
        double r = s.v(Mech.END_ISLANDER);
        s.nextIslandHop = now(p) + Math.round(s.p(Mech.END_ISLANDER, "cooldown") * 20);
        Mv.push(p, new Vec3(dir.x * r / 10.4, 0.7, dir.z * r / 10.4));
        return true;
    }

    static boolean ping(ServerPlayer p, MoveState s) {
        if (!s.has(Mech.CAVE_DWELLER) || now(p) < s.nextPing) return false;
        double r = s.v(Mech.CAVE_DWELLER);
        s.nextPing = now(p) + Math.round(s.p(Mech.CAVE_DWELLER, "cooldown") * 20);
        List<Integer> ids = new ArrayList<>();
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r), e -> e != p && e.isAlive())) {
            if (ids.size() >= 128) break;
            ids.add(e.getId());
        }
        PacketDistributor.sendToPlayer(p, new MovePayloads.Glow(ids, 60));
        p.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 0.6f);
        return true;
    }

    static boolean whistle(ServerPlayer p, MoveState s) {
        double r = s.has(Mech.SADDLE_UP) ? s.p(Mech.SADDLE_UP, "whistle") : 0;
        if (r <= 0 || p.isPassenger()) return false;
        MoveData d = p.getData(MoveData.TYPE);
        if (d.mount.isEmpty()) return false;
        Entity e = p.serverLevel().getEntity(d.mount.get());
        if (!(e instanceof LivingEntity mount) || !mount.isAlive() || mount.distanceToSqr(p) > r * r) return false;
        mount.teleportTo(p.getX(), p.getY(), p.getZ());
        p.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.NOTE_BLOCK_FLUTE.value(),
                net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1.5f);
        return true;
    }

    static boolean callMount(ServerPlayer p, MoveState s) {
        if (!s.has(Mech.PACK_MULE)) return false;
        double r = s.v(Mech.PACK_MULE);
        AbstractChestedHorse best = null;
        for (AbstractChestedHorse h : p.level().getEntitiesOfClass(AbstractChestedHorse.class, p.getBoundingBox().inflate(r),
                h -> h.isAlive() && h.hasChest() && h.isTamed() && p.getUUID().equals(h.getOwnerUUID()) && !h.isVehicle())) {
            if (best == null || h.distanceToSqr(p) < best.distanceToSqr(p)) best = h;
        }
        if (best == null) {
            Mv.say(p, "habits.movement.no_mount");
            return false;
        }
        // the inventory menu closes when the animal is far, so it trots over first
        best.teleportTo(p.getX(), p.getY(), p.getZ());
        best.openCustomInventoryScreen(p);
        return true;
    }

    static boolean waypoint(ServerPlayer p, MoveState s, boolean add) {
        int cap = (int) s.v(Mech.PATHFINDER);
        if (cap <= 0) return false;
        MoveData d = p.getData(MoveData.TYPE);
        GlobalPos here = GlobalPos.of(p.level().dimension(), p.blockPosition());
        if (add) {
            MoveData.remember(d.waypoints, new MoveData.Place("waypoint", here), cap);
            Mv.say(p, "habits.movement.waypoint_saved", d.waypoints.size(), cap);
        } else {
            MoveData.Place near = null;
            for (MoveData.Place w : d.waypoints)
                if (w.pos().dimension() == here.dimension() && (near == null || w.pos().pos().distSqr(here.pos()) < near.pos().pos().distSqr(here.pos())))
                    near = w;
            if (near == null) return false;
            d.waypoints.remove(near);
            Mv.say(p, "habits.movement.waypoint_removed");
        }
        Scans.markers(p, s);
        return true;
    }

    static List<MoveData.Place> destinations(ServerPlayer p, MoveState s) {
        boolean cross = s.lv(Mech.WORLD_EDGE) >= s.p(Mech.WORLD_EDGE, "cross_level");
        List<MoveData.Place> out = new ArrayList<>();
        for (MoveData.Place pl : p.getData(MoveData.TYPE).structures)
            if (cross || pl.pos().dimension() == p.level().dimension()) out.add(pl);
        return out;
    }

    static long travelCooldown(ServerPlayer p, MoveState s, MoveData.Place to) {
        long cd = Math.round(s.v(Mech.WORLD_EDGE) * 60 * 20);
        return to.pos().dimension() == p.level().dimension() ? cd : cd * 2;
    }

    static boolean travelList(ServerPlayer p, MoveState s) {
        if (!s.has(Mech.WORLD_EDGE)) return false;
        List<String> labels = new ArrayList<>();
        for (MoveData.Place pl : destinations(p, s)) {
            BlockPos b = pl.pos().pos();
            labels.add(pl.what() + "|" + pl.pos().dimension().location() + "|" + b.getX() + "|" + b.getZ());
        }
        long left = p.getData(MoveData.TYPE).travelReadyAt - now(p);
        PacketDistributor.sendToPlayer(p, new MovePayloads.TravelList(labels, (int) Math.max(0, (left + 1199) / 1200)));
        return true;
    }

    static boolean travel(ServerPlayer p, MoveState s, int index) {
        if (!s.has(Mech.WORLD_EDGE) || p.isPassenger()) return false;
        List<MoveData.Place> dests = destinations(p, s);
        if (index < 0 || index >= dests.size()) return false;
        MoveData d = p.getData(MoveData.TYPE);
        if (now(p) < d.travelReadyAt) {
            Mv.say(p, "habits.movement.cooldown", (d.travelReadyAt - now(p) + 19) / 20);
            return false;
        }
        MoveData.Place to = dests.get(index);
        ServerLevel level = p.server.getLevel(to.pos().dimension());
        if (level == null) return false;
        d.travelReadyAt = now(p) + travelCooldown(p, s, to);
        BlockPos b = to.pos().pos();
        p.teleportTo(level, b.getX() + 0.5, b.getY(), b.getZ() + 0.5, p.getYRot(), p.getXRot());
        p.resetFallDistance();
        p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 4, false, false, true));
        return true;
    }

    static boolean markEntity(ServerPlayer p, MoveState s, int id) {
        double secs = s.has(Mech.SAVANNA_STALKER) ? s.p(Mech.SAVANNA_STALKER, "mark") : 0;
        if (secs <= 0) return false;
        Entity e = p.level().getEntity(id);
        if (!(e instanceof LivingEntity le) || le == p || le.distanceToSqr(p) > 160 * 160 || !p.hasLineOfSight(le)) return false;
        le.addEffect(new MobEffectInstance(MobEffects.GLOWING, (int) (secs * 20), 0, false, false), p);
        return true;
    }

    static boolean markSpot(ServerPlayer p, BlockPos pos) {
        if (!Mv.ok(p)) return false;
        MoveState s = MoveState.of(p);
        if (!s.has(Mech.SPYGLASS_SCOUT) || !p.isScoping() || !p.level().isLoaded(pos)) return false;
        if (pos.distSqr(p.blockPosition()) > 512 * 512) return false;
        int cap = (int) s.v(Mech.SPYGLASS_SCOUT);
        while (s.spyMarks.size() >= cap && !s.spyMarks.isEmpty()) s.spyMarks.remove(0);
        s.spyMarks.add(new long[]{pos.asLong(), now(p) + Math.round(s.p(Mech.SPYGLASS_SCOUT, "seconds") * 20)});
        Scans.markers(p, s);
        Mv.say(p, "habits.movement.marker_placed");
        return true;
    }
}
