package com.habits.perk.movement;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Card triggers for moments vanilla has no event for (CUSTOM / CUSTOM_UNITS, ids in docs/mechanics/movement-exploration.md).
 * Runs for every player, owned skills or not. Per tick: a handful of field reads. Once a second: one distance sample,
 * one block-below read, one biome read, then plain comparisons.
 */
final class Trackers {
    private Trackers() {}

    /** Freshly generated chunks per dimension (capped, oldest dropped). The first player to walk in claims it. */
    static final Map<ResourceKey<Level>, LongLinkedOpenHashSet> FRESH = new HashMap<>();
    static final int FRESH_CAP = 8192;
    static final int[] FAR = {1000, 5000, 10000, 30000};
    static final int[] ATLAS = {20, 35, 50, 64};

    static synchronized void chunkGenerated(ResourceKey<Level> dim, long chunk) {
        LongLinkedOpenHashSet set = FRESH.computeIfAbsent(dim, k -> new LongLinkedOpenHashSet());
        set.add(chunk);
        if (set.size() > FRESH_CAP) set.removeFirstLong();
    }

    static synchronized boolean claimFresh(ResourceKey<Level> dim, long chunk) {
        LongLinkedOpenHashSet set = FRESH.get(dim);
        return set != null && set.remove(chunk);
    }

    static void tick(ServerPlayer p, MoveState s, long t) {
        if (p.isInWater() && !s.wasInWater && s.lastFall >= 50) Mv.fire(p, "high_dive");
        if (s.jumpStart != null && p.onGround() && !s.wasOnGround) {
            Vec3 a = s.jumpStart;
            if (Math.hypot(p.getX() - a.x, p.getZ() - a.z) >= 3.0 && p.getY() >= a.y - 0.5) Mv.fire(p, "long_jump");
            s.jumpStart = null;
        }
        if (p.level().dimension() == Level.END) gateway(p, s);
        if (t % 5 == 0 && p.getVehicle() instanceof AbstractMinecart cart) {
            BlockPos rail = cart.getCurrentRailPosition();
            BlockState b = p.level().getBlockState(rail);
            if ((b.is(Blocks.POWERED_RAIL) || b.is(Blocks.DETECTOR_RAIL) || b.is(Blocks.ACTIVATOR_RAIL)) && rail.asLong() != s.lastRail) {
                s.lastRail = rail.asLong();
                Mv.fire(p, "special_rail");
            }
        }
        if ((t + (p.getId() & 0x1f)) % 20 == 0) second(p, s, t);
    }

    /** End gateway use shows up as a jump of more than 12 blocks in one tick right next to a gateway block. */
    static void gateway(ServerPlayer p, MoveState s) {
        double jump = Math.hypot(p.getX() - s.lastX, p.getZ() - s.lastZ);
        if (jump < 12 || s.tick < 5) return;
        BlockPos from = BlockPos.containing(s.lastX, s.lastY, s.lastZ);
        boolean near = false;
        for (BlockPos b : BlockPos.betweenClosed(from.offset(-2, -2, -2), from.offset(2, 3, 2)))
            if (p.level().getBlockState(b).is(Blocks.END_GATEWAY)) { near = true; break; }
        if (!near) return;
        Mv.fire(p, "gateway_jump");
        if (!s.has(Mech.GATEWAY_JUMPER)) return;
        MoveData d = p.getData(MoveData.TYPE);
        GlobalPos here = GlobalPos.of(Level.END, p.blockPosition());
        if (p.isShiftKeyDown() && !d.gateways.isEmpty()) {
            // sneaking through: go to the next remembered gateway instead (skip ones right here)
            for (int i = 0; i < d.gateways.size(); i++) {
                MoveData.Place to = d.gateways.get(s.gatewayIndex++ % d.gateways.size());
                if (to.pos().pos().distSqr(here.pos()) > 64 * 64) {
                    BlockPos b = to.pos().pos();
                    p.teleportTo(b.getX() + 0.5, b.getY(), b.getZ() + 0.5);
                    p.resetFallDistance();
                    Mv.say(p, "habits.movement.gateway", (s.gatewayIndex - 1) % d.gateways.size() + 1, d.gateways.size());
                    s.lastX = p.getX(); s.lastY = p.getY(); s.lastZ = p.getZ();
                    return;
                }
            }
        }
        MoveData.remember(d.gateways, new MoveData.Place("gateway", here), (int) s.v(Mech.GATEWAY_JUMPER));
    }

    /** world_edge and sky_high count again only after this long away. */
    static final long AWAY_TICKS = 1200;

    static void second(ServerPlayer p, MoveState s, long t) {
        ServerLevel level = p.serverLevel();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        double d = s.sampled ? Math.hypot(x - s.sampleX, z - s.sampleZ) : 0, dyv = s.sampled ? y - s.sampleY : 0;
        if (d > 60) d = 0; // teleported
        s.sampleX = x; s.sampleY = y; s.sampleZ = z; s.sampled = true;
        s.secondDist = d;
        BlockPos pos = p.blockPosition();
        Holder<Biome> biome = level.getBiome(pos);
        s.biome = biome;
        MoveData data = p.getData(MoveData.TYPE);
        biome.unwrapKey().ifPresent(k -> {
            if (data.biomes.add(k.location())) {
                s.firstEnteredBiomeAt = t;
                for (int i = 0; i < ATLAS.length; i++) milestone(p, data, 4 + i, data.biomes.size() >= ATLAS[i], "biomes_" + ATLAS[i]);
            }
        });
        BlockState below = level.getBlockState(Mv.below(p));
        boolean ground = p.onGround() && !p.isPassenger();
        boolean sky = level.canSeeSky(pos);
        if (ground && d > 0) {
            if (below.is(BlockTags.ICE)) Mv.fireUnits(p, "ice_walk", d);
            if (below.is(BlockTags.SOUL_SPEED_BLOCKS)) Mv.fireUnits(p, "soul_walk", d);
            if (below.is(Blocks.MUD) || below.is(Blocks.MUDDY_MANGROVE_ROOTS) || biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP))
                Mv.fireUnits(p, "mud_walk", d);
            if (below.is(BlockTags.SAND)) Mv.fireUnits(p, "sand_walk", d);
            if (biome.is(BiomeTags.IS_FOREST) || biome.is(BiomeTags.IS_JUNGLE)) Mv.fireUnits(p, "forest_walk", d);
            if (p.getMainHandItem().is(Items.COMPASS) || p.getOffhandItem().is(Items.COMPASS)) Mv.fireUnits(p, "compass_walk", d);
            if (p.isSprinting() && s.firstEnteredBiomeAt > 0 && t - s.firstEnteredBiomeAt < 2400) Mv.fireUnits(p, "sprint_new_biome", d);
            if (sky && level.isNight()) Mv.fireUnits(p, "night_walk", d);
            if (sky && level.isThundering()) Mv.fireUnits(p, "storm_walk", d);
            if (s.has(Mech.BEACHCOMBER) && (biome.is(BiomeTags.IS_BEACH) || biome.is(Biomes.STONY_SHORE))) beachcomb(p, s, d);
        }
        if (p.isSwimming() && d > 0) {
            if (level.isRainingAt(pos)) Mv.fireUnits(p, "rain_swim", d);
            if (biome.is(BiomeTags.IS_RIVER)) Mv.fireUnits(p, "river_swim", d);
        }
        if (p.getVehicle() instanceof Boat boat && level.getBlockState(boat.blockPosition().below()).is(BlockTags.ICE)) Mv.fireUnits(p, "ice_boat", d);
        verticalUnits(p, s, level, pos, dyv);
        if (p.isScoping()) Mv.fireUnits(p, "scope_seconds", 1);
        dives(p, s, biome);
        edge(p, s);
        sprint(p, s);
        // long walk: 1000 blocks without getting hurt or standing still 5 s
        if (d < 0.5) {
            if ((s.stillTicks += 20) >= 100) s.walkClean = 0;
        } else s.stillTicks = 0;
        if (ground) s.walkClean += d;
        if (s.walkClean >= 1000) { s.walkClean = 0; Mv.fire(p, "long_walk"); }
        // one trip totals
        if (p.isFallFlying()) {
            s.flightDist += d;
            if (thermalBelow(p)) Mv.fire(p, "thermal_glide");
        } else {
            if (p.onGround() && s.flightDist >= 1000) Mv.fire(p, "long_flight", s.flightDist); // blink needs min 10000
            s.flightDist = 0;
        }
        if (p.isPassenger()) s.rideDist += d;
        else { if (s.rideDist >= 1000) Mv.fire(p, "long_ride"); s.rideDist = 0; }
        if (p.getVehicle() instanceof Boat) { if (biome.is(BiomeTags.IS_OCEAN)) s.voyageDist += d; }
        else { if (s.voyageDist >= 1000) Mv.fire(p, "long_voyage"); s.voyageDist = 0; }
        if (t % 1200 < 20) {
            if (level.dimension() == Level.NETHER) Mv.fire(p, "nether_minute");
            if (level.dimension() == Level.END) Mv.fire(p, "end_minute");
        }
        BlockPos spawn = level.getSharedSpawnPos();
        double far = Math.hypot(x - spawn.getX(), z - spawn.getZ());
        if (level.dimension() == Level.OVERWORLD)
            for (int i = 0; i < FAR.length; i++) milestone(p, data, i, far >= FAR[i], "far_" + FAR[i]);
        if (level.getWorldBorder().getDistanceToBorder(p) < 16) {
            if (t - s.worldEdgeAt >= AWAY_TICKS) Mv.fire(p, "world_edge");
            s.worldEdgeAt = t;
        }
        if (level.dimension() == Level.OVERWORLD && y > 256) {
            if (t - s.skyHighAt >= AWAY_TICKS) Mv.fire(p, "sky_high");
            s.skyHighAt = t;
        }
        lost(p, s, level);
        if (t % 200 < 20) marathon(p, data);
        long chunk = p.chunkPosition().toLong();
        if (chunk != s.lastChunk) {
            s.lastChunk = chunk;
            s.inFresh = claimFresh(level.dimension(), chunk);
            if (s.inFresh) {
                Mv.fire(p, "new_chunk");
                if (s.has(Mech.EXPLORERS_EYE) && p.getRandom().nextDouble() * Math.max(1, s.p(Mech.EXPLORERS_EYE, "pouch")) < 1)
                    give(p, s.ps(Mech.EXPLORERS_EYE, "items"));
            }
        }
    }

    static void milestone(ServerPlayer p, MoveData data, int bit, boolean reached, String event) {
        if (!reached || (data.milestones & (1 << bit)) != 0) return;
        data.milestones |= 1 << bit;
        Mv.fire(p, event);
    }

    static void verticalUnits(ServerPlayer p, MoveState s, ServerLevel level, BlockPos pos, double dyv) {
        if (dyv == 0) return;
        BlockState at = level.getBlockState(pos);
        if (at.is(Blocks.BUBBLE_COLUMN)) Mv.fireUnits(p, "bubble_ride", Math.abs(dyv));
        if (at.is(Blocks.SCAFFOLDING) && dyv > 0) Mv.fireUnits(p, "scaffold_climb", dyv);
        if (dyv < 0 && !p.onGround()) {
            for (Direction dir : Direction.Plane.HORIZONTAL)
                if (level.getBlockState(pos.relative(dir)).is(Blocks.HONEY_BLOCK)) { Mv.fireUnits(p, "honey_slide", -dyv); break; }
        }
    }

    static void dives(ServerPlayer p, MoveState s, Holder<Biome> biome) {
        if (!p.isEyeInFluid(FluidTags.WATER)) {
            s.diveTicks = 0;
            s.deepDiveFired = false;
            return;
        }
        if ((s.diveTicks += 20) == 1200) Mv.fire(p, "long_dive");
        if (!s.deepDiveFired && p.getY() < 30 && biome.is(BiomeTags.IS_DEEP_OCEAN)) {
            s.deepDiveFired = true;
            Mv.fire(p, "deep_dive");
        }
    }

    static void edge(ServerPlayer p, MoveState s) {
        if (!p.isShiftKeyDown() || !p.onGround()) { s.edgeFired = false; return; }
        if (s.edgeFired) return;
        BlockPos ahead = BlockPos.containing(p.position().add(Mv.flatLook(p).scale(0.8)));
        for (int i = 0; i <= 10; i++) if (!p.level().getBlockState(ahead.below(i)).getCollisionShape(p.level(), ahead.below(i)).isEmpty()) return;
        s.edgeFired = true;
        Mv.fire(p, "edge_peek");
    }

    /** 30+ blocks of sprinting along one heading. */
    static void sprint(ServerPlayer p, MoveState s) {
        if (!p.isSprinting() || p.isPassenger()) { s.sprintFrom = null; return; }
        Vec3 here = p.position();
        if (s.sprintFrom == null) { s.sprintFrom = here; s.sprintDir = Mv.flatLook(p); return; }
        Vec3 off = here.subtract(s.sprintFrom);
        double len = Math.hypot(off.x, off.z);
        if (len > 2 && (off.x * s.sprintDir.x + off.z * s.sprintDir.z) / len < 0.95) { s.sprintFrom = here; s.sprintDir = Mv.flatLook(p); return; }
        if (len >= 30) { Mv.fire(p, "long_sprint"); s.sprintFrom = null; }
    }

    static boolean thermalBelow(ServerPlayer p) {
        BlockPos.MutableBlockPos m = p.blockPosition().mutable();
        for (int i = 0; i < 16; i++) {
            BlockState b = p.level().getBlockState(m.move(0, -1, 0));
            if (!b.isAir()) return ServerTick.heat(b);
        }
        return false;
    }

    /** Lost: 500+ blocks from bed/spawn with no compass or map anywhere on you. */
    static void lost(ServerPlayer p, MoveState s, ServerLevel level) {
        BlockPos home = p.getRespawnPosition() != null && p.getRespawnDimension() == level.dimension() ? p.getRespawnPosition()
                : level.dimension() == Level.OVERWORLD ? level.getSharedSpawnPos() : null;
        boolean lost = home != null && home.distSqr(p.blockPosition()) > 500 * 500
                && !p.getInventory().contains(st -> st.is(Items.COMPASS) || st.is(Items.RECOVERY_COMPASS) || st.is(Items.FILLED_MAP) || st.is(Items.MAP));
        if (!lost) { s.lostTicks = 0; return; }
        if ((s.lostTicks += 20) == 12000) Mv.fire(p, "lost");
    }

    /** Lost and Found: drop a crumb every 8 blocks; once lost long enough, show the newest crumbs near you. */
    static void crumbs(ServerPlayer p, MoveState s, long t) {
        BlockPos here = p.blockPosition();
        int cap = Math.max(1, Math.min(64, (int) (s.v(Mech.LOST_AND_FOUND) / 8)));
        if (s.crumbs.isEmpty() || s.crumbs.peekLast().distSqr(here) > 64) {
            s.crumbs.addLast(here);
            while (s.crumbs.size() > cap) s.crumbs.pollFirst();
        }
        if (t % 40 >= 20 || s.lostTicks < s.p(Mech.LOST_AND_FOUND, "delay") * 1200) return;
        int sent = 0;
        for (var it = s.crumbs.descendingIterator(); it.hasNext() && sent < 32; ) {
            BlockPos b = it.next();
            if (b.distSqr(here) > 48 * 48) continue;
            p.serverLevel().sendParticles(p, ParticleTypes.GLOW, true, b.getX() + 0.5, b.getY() + 0.3, b.getZ() + 0.5, 2, 0.1, 0.1, 0.1, 0);
            sent++;
        }
    }

    static void marathon(ServerPlayer p, MoveData data) {
        long cm = (long) p.getStats().getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM)) + p.getStats().getValue(Stats.CUSTOM.get(Stats.SPRINT_ONE_CM));
        int tens = (int) (cm / 1_000_000L);
        if (tens > data.marathonKm) {
            data.marathonKm = tens;
            Mv.fire(p, "marathon");
        }
    }

    /** Beachcomber: v finds per 1000 blocks walked on a beach. */
    static void beachcomb(ServerPlayer p, MoveState s, double d) {
        double chance = 1 - Math.pow(1 - s.v(Mech.BEACHCOMBER) / 1000, d);
        if (p.getRandom().nextDouble() < chance) give(p, s.ps(Mech.BEACHCOMBER, "items"));
    }

    static void give(ServerPlayer p, String items) {
        String[] ids = items.split(",");
        if (ids.length == 0 || items.isBlank()) return;
        ResourceLocation id = ResourceLocation.tryParse(ids[p.getRandom().nextInt(ids.length)].trim());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return;
        p.spawnAtLocation(new ItemStack(BuiltInRegistries.ITEM.get(id)));
    }
}
