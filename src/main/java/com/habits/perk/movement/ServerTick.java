package com.habits.perk.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.stats.Stats;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;

/**
 * Server half of every continuous mechanic. One call per player tick; work is spread: every tick only cheap field
 * checks, attributes every 5 ticks, effects/mounts every 20, scans every 100 (staggered by entity id).
 */
final class ServerTick {
    private ServerTick() {}

    static void tick(ServerPlayer p) {
        // never be the reason a chunk loads: every read below is near the player, so check the player's own chunk
        if (!p.level().hasChunkAt(p.blockPosition())) return;
        MoveState s = MoveState.of(p);
        long t = ++s.tick;
        double x = p.getX(), y = p.getY(), z = p.getZ();
        s.dy = y - s.lastY;
        boolean moved = Math.abs(x - s.lastX) + Math.abs(z - s.lastZ) > 0.01;
        if (moved) s.lastMoveTick = t;
        Trackers.tick(p, s, t);
        SpecMoves.track(p, s, t);
        s.lastX = x; s.lastY = y; s.lastZ = z;
        s.wasInWater = p.isInWater();
        s.lastFall = p.fallDistance;
        if (!s.any) {
            if (s.hadAttrs) clearAttributes(p, s);
            // the last movement skill is gone, so Scans no longer runs: send the empty list once or the bar keeps its markers
            if (s.markerHash != 0) {
                Scans.markers(p, s);
                s.markerHash = 0;
            }
            s.wasOnGround = p.onGround();
            return;
        }
        MoveHooks.trackFall(p, s);
        int phase = p.getId() & 0x1f;
        everyTick(p, s, t);
        if ((t + phase) % 5 == 0) attributes(p, s, t);
        if ((t + phase) % 20 == 0) second(p, s, t);
        if ((t + phase) % 100 == 0) Scans.run(p, s);
        s.wasOnGround = p.onGround();
    }

    // ------------------------------------------------------------------------------------------- every tick

    static void everyTick(ServerPlayer p, MoveState s, long t) {
        boolean ground = p.onGround();
        if (ground) {
            s.airJumps = 0;
            s.wallKicks = 0;
            s.climbStamina = 0;
        }
        // glide / wall slide / float: only cancel fall damage while the server SEES a slow descent
        if (MoveHooks.perkGlide(p, s, s.dy)) p.resetFallDistance();
        if (!ground && MoveHooks.wallClimb(p, s)) s.climbStamina++;
        frost(p, s);
        wings(p, s, t);
        hunger(p, s);
        if (s.channelUntil > 0) Mounts.channel(p, s, t);
        WorldEvents.checkVault(p, s);
        SpecMoves.tick(p, s, t);
    }

    static double glideMaxFall(MoveState s) {
        return 0.6 * (1 - s.v(Mech.GLIDER_KICK) / 100);
    }

    static double skyFloatMaxFall(MoveState s) {
        return 3.92 * (1 - s.v(Mech.SKY_WATCHER) / 100);
    }

    /** Snow Scout slows freezing; Igloo Finder stops it. Vanilla adds 1 per tick in powder snow. */
    static void frost(ServerPlayer p, MoveState s) {
        int f = p.getTicksFrozen();
        if (f <= 0) return;
        if (s.has(Mech.IGLOO_FINDER)) p.setTicksFrozen(0);
        else if (s.has(Mech.SNOW_SCOUT) && p.isInPowderSnow && p.getRandom().nextDouble() * 100 < s.v(Mech.SNOW_SCOUT))
            p.setTicksFrozen(f - 1);
    }

    /** Wings of Wind: hold sneak on the ground with an elytra, launch up, then open the elytra near the top. */
    static void wings(ServerPlayer p, MoveState s, long t) {
        if (!s.has(Mech.WINGS_OF_WIND)) return;
        long now = p.level().getGameTime();
        if (s.launchedAt > 0) {
            if (!p.onGround() && s.dy <= 0.2 && t >= s.launchedAt) {
                if (!p.isFallFlying() && p.getItemBySlot(EquipmentSlot.CHEST).canElytraFly(p)) p.startFallFlying();
                s.launchedAt = -1;
            } else if (t > s.launchedAt + 60) s.launchedAt = -1;
            return;
        }
        boolean elytra = p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        if (!(p.onGround() && p.isShiftKeyDown() && elytra && !p.isPassenger())) {
            s.sneakHeld = 0;
            return;
        }
        if (++s.sneakHeld < s.p(Mech.WINGS_OF_WIND, "hold") * 20) return;
        s.sneakHeld = 0;
        int food = (int) Math.round(s.p(Mech.WINGS_OF_WIND, "food"));
        if (now < s.nextLaunch) {
            Mv.say(p, "habits.movement.cooldown", (s.nextLaunch - now + 19) / 20);
            return;
        }
        if (p.getFoodData().getFoodLevel() < food + 1 && !p.getAbilities().instabuild) {
            Mv.say(p, "habits.movement.too_hungry");
            return;
        }
        launch(p, s, t);
    }

    static void launch(ServerPlayer p, MoveState s, long t) {
        double h = s.v(Mech.WINGS_OF_WIND);
        // v^2 = 2gh with gravity 0.08 b/t^2; x1.15 makes up for air drag
        double vy = Math.min(3.9, Math.sqrt(2 * 0.08 * h) * 1.15);
        if (!p.getAbilities().instabuild) {
            FoodData fd = p.getFoodData();
            fd.setFoodLevel(Math.max(0, fd.getFoodLevel() - (int) Math.round(s.p(Mech.WINGS_OF_WIND, "food"))));
        }
        s.nextLaunch = p.level().getGameTime() + Math.round(s.p(Mech.WINGS_OF_WIND, "cooldown") * 20);
        s.launchedAt = t + 6;
        Mv.push(p, new Vec3(0, vy, 0));
        p.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.BREEZE_WIND_CHARGE_BURST.value(),
                net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
    }

    /** Runner's Lungs refunds sprint hunger at the start of each sprint; Desert Nomad refunds part of it in hot places. */
    static void hunger(ServerPlayer p, MoveState s) {
        FoodData fd = p.getFoodData();
        float cur = fd.getExhaustionLevel();
        if (p.isSprinting()) s.sprintTicks++;
        else s.sprintTicks = 0;
        // vanilla sprint cost: 0.1 per meter sprinted plus 0.2 per sprint jump; regen, mining and hits are not refunded
        int cm = p.getStats().getValue(Stats.CUSTOM.get(Stats.SPRINT_ONE_CM)), jumps = p.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP));
        float sprintCost = (cm - s.lastSprintCm) * 0.001f + (jumps - s.lastJumps) * 0.2f;
        s.lastSprintCm = cm;
        s.lastJumps = jumps;
        if (p.isSprinting() && cur > s.lastExhaustion && sprintCost > 0) {
            double keep = 1;
            if (s.has(Mech.RUNNERS_LUNGS) && s.sprintTicks <= s.v(Mech.RUNNERS_LUNGS) * 20) keep = 0;
            double hot = bv(s, Mech.DESERT_NOMAD, s.biome != null && s.biome.value().getBaseTemperature() >= 1.5f);
            if (hot > 0) keep = Math.min(keep, 1 - hot / 100);
            if (keep < 1) {
                cur = (float) (cur - Math.min(cur - s.lastExhaustion, sprintCost) * (1 - keep));
                fd.setExhaustion(cur);
            }
        }
        s.lastExhaustion = cur;
    }

    // ------------------------------------------------------------------------------------------- attributes (5 ticks)

    static void attributes(ServerPlayer p, MoveState s, long t) {
        s.hadAttrs = true;
        Level level = p.level();
        BlockPos belowPos = Mv.below(p);
        BlockState below = level.getBlockState(belowPos);
        boolean onGround = p.onGround();
        boolean moving = t - s.lastMoveTick < 5;
        s.biome = level.getBiome(p.blockPosition());

        double step = 0;
        // Wanderer: roads
        double road = 0;
        if (s.has(Mech.WANDERER)) {
            if (onGround && (below.is(Blocks.DIRT_PATH) || below.is(Blocks.GRAVEL) || below.is(BlockTags.STAIRS) || below.is(BlockTags.SLABS)))
                road = s.v(Mech.WANDERER);
            step = Math.max(step, s.p(Mech.WANDERER, "step"));
        }
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.WANDERER, road);
        Mv.attrAdd(p, Attributes.SNEAKING_SPEED, Mech.CAT_FEET, s.has(Mech.CAT_FEET) ? s.v(Mech.CAT_FEET) / 100 - 0.3 : 0);
        if (s.has(Mech.LONG_LEGS)) step = Math.max(step, s.v(Mech.LONG_LEGS) - 0.6);
        // Marathon momentum (+rate% per second while moving, reset by stopping or damage)
        if (s.has(Mech.MARATHON) && moving && !p.isPassenger())
            s.momentum = Math.min(s.v(Mech.MARATHON), s.momentum + s.p(Mech.MARATHON, "rate") / 4);
        else s.momentum = 0;
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.MARATHON, s.momentum);
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.ICE_SKATER, s.has(Mech.ICE_SKATER) && below.is(BlockTags.ICE) ? s.v(Mech.ICE_SKATER) : 0);
        boolean soul = s.has(Mech.SOUL_RUNNER) && below.is(BlockTags.SOUL_SPEED_BLOCKS);
        Mv.attrAdd(p, Attributes.MOVEMENT_EFFICIENCY, Mech.SOUL_RUNNER, soul ? 1 : 0);
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.SOUL_RUNNER, soul ? s.v(Mech.SOUL_RUNNER) : 0);
        Mv.attrAdd(p, Attributes.MOVEMENT_EFFICIENCY, Mech.MUD_WADER,
                s.has(Mech.MUD_WADER) && below.is(Blocks.HONEY_BLOCK) ? s.v(Mech.MUD_WADER) / 100 : 0);
        boolean sand = s.has(Mech.SAND_STRIDER) && below.is(BlockTags.SAND);
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.SAND_STRIDER, sand ? s.v(Mech.SAND_STRIDER) : 0);
        if (sand) step = Math.max(step, s.p(Mech.SAND_STRIDER, "step"));
        Mv.attrPct(p, Attributes.GRAVITY, Mech.FEATHER_BODY, -s.v(Mech.FEATHER_BODY));
        // Bunny Hop: jump height +v blocks; base 0.42 reaches about 1.25 blocks and height grows with velocity squared
        Mv.attrAdd(p, Attributes.JUMP_STRENGTH, Mech.BUNNY_HOP,
                s.has(Mech.BUNNY_HOP) ? 0.42 * Math.sqrt((1.25 + s.v(Mech.BUNNY_HOP)) / 1.25) - 0.42 : 0);
        Mv.attrAdd(p, Attributes.SAFE_FALL_DISTANCE, Mech.CAT_LANDING, s.v(Mech.CAT_LANDING));
        Mv.attrPct(p, NeoForgeMod.SWIM_SPEED, Mech.DOLPHIN_KICK, s.v(Mech.DOLPHIN_KICK));
        Mv.attrAdd(p, Attributes.WATER_MOVEMENT_EFFICIENCY, Mech.SEABED_WALKER, s.v(Mech.SEABED_WALKER) / 100);
        Mv.attrAdd(p, Attributes.OXYGEN_BONUS, Mech.DEEP_BREATH, s.v(Mech.DEEP_BREATH));
        double peak = bv(s, Mech.PEAK_CLIMBER, p.getY() >= (s.has(Mech.PEAK_CLIMBER) ? s.p(Mech.PEAK_CLIMBER, "min_y") : 100));
        Mv.attrPct(p, Attributes.JUMP_STRENGTH, Mech.PEAK_CLIMBER, peak);
        Mv.attrPct(p, Attributes.GRAVITY, Mech.PEAK_CLIMBER, -peak / 4);
        double nether = bv(s, Mech.NETHER_NATIVE, level.dimension() == Level.NETHER && onGround);
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.NETHER_NATIVE, nether);
        double haul = 0;
        if (s.has(Mech.LONG_HAUL)) {
            BlockPos sp = level.getSharedSpawnPos();
            double dist = Math.sqrt(Math.pow(p.getX() - sp.getX(), 2) + Math.pow(p.getZ() - sp.getZ(), 2));
            haul = Math.min(s.v(Mech.LONG_HAUL), dist / 1000 * s.p(Mech.LONG_HAUL, "rate"));
        }
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.LONG_HAUL, haul);
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.RIVER_RUNNER, bv(s, Mech.RIVER_RUNNER, s.biome.is(BiomeTags.IS_RIVER)));
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.MINE_RAT,
                s.has(Mech.MINE_RAT) && s.inMineshaft && (below.is(BlockTags.PLANKS) || level.getBlockState(p.blockPosition()).is(BlockTags.RAILS))
                        ? s.v(Mech.MINE_RAT) : 0);
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.EXPLORERS_EYE,
                s.has(Mech.EXPLORERS_EYE) && s.inFresh ? s.v(Mech.EXPLORERS_EYE) : 0);
        Mv.attrAdd(p, Attributes.STEP_HEIGHT, Mech.LONG_LEGS, Math.min(step, 2.5 - 0.6));
        SpecMoves.attributes(p, s, below, t);
        ClassMoves.attributes(p, s);
        // Trailblazer trail points
        if (s.has(Mech.TRAILBLAZER) && p.isSprinting() && onGround) {
            if (s.trail.size() >= 64) s.trail.pollFirst();
            s.trail.addLast(new long[]{p.blockPosition().asLong(), t + Math.round(s.v(Mech.TRAILBLAZER) * 20)});
        }
    }

    static void clearAttributes(ServerPlayer p, MoveState s) {
        for (Mech m : new Mech[]{Mech.WANDERER, Mech.MARATHON, Mech.ICE_SKATER, Mech.SOUL_RUNNER, Mech.SAND_STRIDER, Mech.NETHER_NATIVE,
                Mech.LONG_HAUL, Mech.RIVER_RUNNER, Mech.MINE_RAT, Mech.EXPLORERS_EYE})
            Mv.attrPct(p, Attributes.MOVEMENT_SPEED, m, 0);
        Mv.attrAdd(p, Attributes.SNEAKING_SPEED, Mech.CAT_FEET, 0);
        Mv.attrAdd(p, Attributes.MOVEMENT_EFFICIENCY, Mech.SOUL_RUNNER, 0);
        Mv.attrAdd(p, Attributes.MOVEMENT_EFFICIENCY, Mech.MUD_WADER, 0);
        Mv.attrPct(p, Attributes.GRAVITY, Mech.FEATHER_BODY, 0);
        Mv.attrAdd(p, Attributes.JUMP_STRENGTH, Mech.BUNNY_HOP, 0);
        Mv.attrAdd(p, Attributes.SAFE_FALL_DISTANCE, Mech.CAT_LANDING, 0);
        Mv.attrPct(p, NeoForgeMod.SWIM_SPEED, Mech.DOLPHIN_KICK, 0);
        Mv.attrAdd(p, Attributes.WATER_MOVEMENT_EFFICIENCY, Mech.SEABED_WALKER, 0);
        Mv.attrAdd(p, Attributes.OXYGEN_BONUS, Mech.DEEP_BREATH, 0);
        Mv.attrPct(p, Attributes.JUMP_STRENGTH, Mech.PEAK_CLIMBER, 0);
        Mv.attrPct(p, Attributes.GRAVITY, Mech.PEAK_CLIMBER, 0);
        Mv.attrAdd(p, Attributes.STEP_HEIGHT, Mech.LONG_LEGS, 0);
        SpecMoves.clear(p, s);
        ClassMoves.clear(p);
        Mounts.unbuff(s);
        s.momentum = 0;
        s.hadAttrs = false;
    }

    /** Biome-gated value, or Atlas's share of it outside the biome (owned skill's value, else its Lv1 value). */
    static double bv(MoveState s, Mech m, boolean inside) {
        if (inside) return s.v(m);
        if (!s.has(Mech.ATLAS)) return 0;
        double base = s.has(m) ? s.v(m) : lv1(m);
        return base * s.v(Mech.ATLAS) / 100;
    }

    private static final double[] LV1 = new double[Mech.N];
    private static Object lv1Of;

    static double lv1(Mech m) {
        var all = com.habits.core.SkillRegistry.all();
        if (lv1Of != all) {
            for (Mech x : Mech.ALL) {
                var def = com.habits.core.SkillRegistry.get(x.skillId);
                LV1[x.ordinal()] = def == null ? 0 : def.perkValue(1);
            }
            lv1Of = all;
        }
        return LV1[m.ordinal()];
    }

    // ------------------------------------------------------------------------------------------- every second

    static void second(ServerPlayer p, MoveState s, long t) {
        ServerLevel level = p.serverLevel();
        // effects: one decision per effect so two skills never fight over it
        boolean nv = (s.has(Mech.PEARL_DIVER) && p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER))
                || (s.has(Mech.MOUNTED_EXPLORER) && p.isPassenger());
        keep(p, MobEffects.NIGHT_VISION, nv ? 0 : -1);
        int speed = -1;
        if (s.has(Mech.STORM_CHASER) && level.isThundering()) speed = Math.max(speed, (int) s.v(Mech.STORM_CHASER) - 1);
        if (s.recoverTarget != null) speed = Math.max(speed, Mounts.recovery(p, s, t));
        if (s.has(Mech.DESERT_NOMAD) && s.lv(Mech.DESERT_NOMAD) >= s.p(Mech.DESERT_NOMAD, "storm_level") && level.isRaining()
                && s.biome != null && s.biome.is(net.minecraft.world.level.biome.Biomes.DESERT)) speed = Math.max(speed, 0);
        keep(p, MobEffects.MOVEMENT_SPEED, speed);
        keep(p, MobEffects.DOLPHINS_GRACE, bv(s, Mech.RIVER_RUNNER, s.biome != null && s.biome.is(BiomeTags.IS_RIVER)) > 0 && p.isInWater() ? 0 : -1);
        if (s.has(Mech.VILLAGE_GUIDE) && s.v(Mech.VILLAGE_GUIDE) > 0 && t % 60 < 20
                && !level.getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class, p.getBoundingBox().inflate(12)).isEmpty())
            p.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, 200, (int) s.v(Mech.VILLAGE_GUIDE) - 1, true, false, true));
        // Meadow Breeze: flowers underfoot
        if (s.has(Mech.MEADOW_BREEZE) || s.has(Mech.ATLAS)) {
            BlockState feet = level.getBlockState(p.blockPosition());
            double secs = bv(s, Mech.MEADOW_BREEZE, true);
            if (secs > 0 && feet.is(BlockTags.FLOWERS))
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, (int) (secs * 20) + 20,
                        s.has(Mech.MEADOW_BREEZE) ? (int) s.p(Mech.MEADOW_BREEZE, "amp") : 0, true, false, true));
        }
        if (s.has(Mech.DEEP_BREATH) && s.lv(Mech.DEEP_BREATH) >= s.p(Mech.DEEP_BREATH, "bubble_level")
                && level.getBlockState(p.blockPosition()).is(Blocks.BUBBLE_COLUMN)) p.setAirSupply(p.getMaxAirSupply());
        if (s.has(Mech.IGLOO_FINDER) && t - s.lastMoveTick > 40 && s.biome != null
                && s.biome.value().coldEnoughToSnow(p.blockPosition())
                && t % Math.max(20, Math.round(s.p(Mech.IGLOO_FINDER, "interval") * 20)) < 20) p.heal((float) s.v(Mech.IGLOO_FINDER));
        if (s.has(Mech.MINE_RAT) && t % 40 < 20) s.inMineshaft = Scans.insideAny(level, p.blockPosition(), Scans.MINESHAFTS);
        dropTargets(p, s);
        trail(p, s, t);
        footprints(p, s, t);
        Mounts.second(p, s, t);
        if (s.has(Mech.THERMAL_RIDER) && p.isFallFlying()) thermal(p, s);
        if (s.has(Mech.CHEST_CART_HAULER) && p.getVehicle() instanceof AbstractMinecart) magnet(p, s.v(Mech.CHEST_CART_HAULER));
        if (s.has(Mech.LOST_AND_FOUND)) Trackers.crumbs(p, s, t);
        SpecMoves.ownerSecond(p, s, t);
    }

    /** Keep a hidden ambient effect on (amp >= 0) or take ours off (amp < 0). Never touches visible effects. */
    static void keep(LivingEntity e, Holder<MobEffect> effect, int amp) {
        MobEffectInstance cur = e.getEffect(effect);
        if (amp >= 0) {
            if (cur == null || (cur.isAmbient() && !cur.isVisible() && (cur.getAmplifier() != amp || cur.getDuration() < 300)))
                e.addEffect(new MobEffectInstance(effect, 400, amp, true, false, false));
        } else if (cur != null && cur.isAmbient() && !cur.isVisible() && !cur.showIcon() && cur.getDuration() <= 400) {
            e.removeEffect(effect);
        }
    }

    /** Ghasts, blazes out of sight and husks lose interest sooner. */
    static void dropTargets(ServerPlayer p, MoveState s) {
        boolean ghast = s.has(Mech.NETHER_NATIVE) || (s.has(Mech.ATLAS) && p.level().dimension() == Level.NETHER);
        boolean blaze = s.has(Mech.FORTRESS_CRAWLER), husk = s.has(Mech.DESERT_NOMAD);
        if (!ghast && !blaze && !husk) return;
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(64), m -> m.getTarget() == p)) {
            double d = m.distanceTo(p), range = m.getAttributeValue(Attributes.FOLLOW_RANGE);
            if (ghast && m instanceof Ghast && d > range * (1 - s.p(Mech.NETHER_NATIVE, "ghast") / 100)) m.setTarget(null);
            else if (husk && m instanceof Husk && d > range * (1 - s.p(Mech.DESERT_NOMAD, "husk") / 100)) m.setTarget(null);
            else if (blaze && m instanceof Blaze && !m.getSensing().hasLineOfSight(p)
                    && p.getRandom().nextDouble() < 1 / Math.max(1, s.p(Mech.FORTRESS_CRAWLER, "blaze"))) m.setTarget(null);
        }
    }

    /** Trailblazer: anyone walking on your trail gets Speed; a few particles mark it. */
    static void trail(ServerPlayer p, MoveState s, long t) {
        if (s.trail.isEmpty()) return;
        while (!s.trail.isEmpty() && s.trail.peekFirst()[1] < t) s.trail.pollFirst();
        int amp = s.has(Mech.TRAILBLAZER) ? (int) s.p(Mech.TRAILBLAZER, "amp") : 0;
        ServerLevel level = p.serverLevel();
        int i = 0;
        for (long[] pt : s.trail) {
            BlockPos b = BlockPos.of(pt[0]);
            if ((i++ & 3) == 0) level.sendParticles(ParticleTypes.END_ROD, b.getX() + 0.5, b.getY() + 0.1, b.getZ() + 0.5, 1, 0.2, 0, 0.2, 0);
        }
        for (ServerPlayer o : level.players()) {
            if (o.distanceToSqr(p) > 96 * 96 || !Mv.ok(o)) continue;
            Entity mover = o.getVehicle() != null ? o.getVehicle() : o;
            BlockPos ob = mover.blockPosition();
            for (long[] pt : s.trail) {
                if (BlockPos.of(pt[0]).distManhattan(ob) <= 2) {
                    o.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 40, amp, true, false, true));
                    if (mover instanceof LivingEntity le && mover != o)
                        le.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 40, amp, true, false, true));
                    break;
                }
            }
        }
    }

    /** Taiga Tracker: footprints under mobs you hit, sent to you only. */
    static void footprints(ServerPlayer p, MoveState s, long t) {
        if (s.tracked.isEmpty()) return;
        var it = s.tracked.iterator();
        int sent = 0;
        while (it.hasNext()) {
            MoveState.Tracked tr = it.next();
            LivingEntity e = tr.ref().get();
            if (e == null || !e.isAlive() || e.level() != p.level() || e.distanceToSqr(p) > 128 * 128 || tr.until() < t) {
                it.remove();
                continue;
            }
            if (sent++ < 20) p.serverLevel().sendParticles(p, ParticleTypes.GLOW, true, e.getX(), e.getY() + 0.05, e.getZ(), 2, 0.15, 0, 0.15, 0);
        }
    }

    /** Thermal Rider: heat, water or rain under a glider lifts it. Checks a 16 block column. */
    static void thermal(ServerPlayer p, MoveState s) {
        Level level = p.level();
        boolean lift = level.isRainingAt(p.blockPosition());
        long day = level.getDayTime() % 24000;
        if (s.lv(Mech.THERMAL_RIDER) >= s.p(Mech.THERMAL_RIDER, "anywhere_level") && (day > 22500 || (day > 11500 && day < 13500))) lift = true;
        BlockPos.MutableBlockPos m = p.blockPosition().mutable();
        for (int i = 0; i < 16 && !lift; i++) {
            BlockState b = level.getBlockState(m.move(0, -1, 0));
            if (b.isAir()) continue;
            lift = heat(b) || b.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
            break;
        }
        if (!lift) return;
        Vec3 k = p.getKnownMovement();
        // applied once a second: a second's worth of lift, capped so it can't become a rocket
        Mv.push(p, new Vec3(k.x, Math.min(1.0, Math.max(k.y, 0) + s.v(Mech.THERMAL_RIDER) / 20 * 8), k.z));
    }

    static boolean heat(BlockState b) {
        return b.is(Blocks.LAVA) || b.is(BlockTags.FIRE) || b.is(Blocks.MAGMA_BLOCK)
                || (b.getBlock() instanceof CampfireBlock && b.getValue(CampfireBlock.LIT));
    }

    /** Cart Hauler: items in range jump into your inventory (normal pickup rules). */
    static void magnet(ServerPlayer p, double r) {
        for (ItemEntity it : p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(r), e -> e.isAlive() && !e.hasPickUpDelay()))
            it.playerTouch(p);
    }
}
