package com.habits.perk.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.EnderManAngerEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.ExplosionKnockbackEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Event-driven halves of the mechanics plus the event-based card triggers. Registered on the game bus. */
public final class WorldEvents {
    private WorldEvents() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (!Mv.ok(p)) return;
        if (p instanceof ServerPlayer sp) ServerTick.tick(sp);
        else if (p.isLocalPlayer()) Physics.tick(p);
    }

    static long now(Entity e) {
        return e.level().getGameTime();
    }

    // ------------------------------------------------------------------------------------------- damage

    /** Tomb Raider: mark plates and tripwire a player places, so they still fire for the owner. Lowest priority so a
     * cancelled place is never marked. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTrapPlaced(BlockEvent.EntityPlaceEvent e) {
        if (e.getEntity() instanceof Player && e.getLevel() instanceof Level l && !l.isClientSide && MoveHooks.trap(e.getPlacedBlock()))
            MoveHooks.markTrap(l, e.getPos(), true);
    }

    @SubscribeEvent
    public static void onIncoming(LivingIncomingDamageEvent e) {
        LivingEntity victim = e.getEntity();
        DamageSource src = e.getSource();
        if (victim.level().isClientSide()) return;
        if (victim instanceof ServerPlayer p && Mv.ok(p)) {
            MoveState s = MoveState.of(p);
            if (s.channelUntil > 0) {
                s.channelUntil = 0;
                Mv.say(p, "habits.movement.channel_broken");
            }
            if (s.any) {
                boolean bypass = src.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
                if (!bypass && now(p) < s.iframesUntil) { e.setCanceled(true); return; }
                if (Mounts.fireProof(s) && p.getVehicle() instanceof Strider && src.is(DamageTypeTags.IS_FIRE)) { e.setCanceled(true); return; }
            }
        }
        // mounts and passengers of a perk player's ride
        Entity vehicle = victim.getVehicle();
        if (vehicle instanceof Camel camel && camel.getControllingPassenger() instanceof ServerPlayer rider && rider != victim && Mv.ok(rider)
                && MoveState.of(rider).has(Mech.CAMEL_CARAVAN)) { e.setCanceled(true); return; }
        if (victim.getControllingPassenger() instanceof ServerPlayer rider && Mv.ok(rider)) {
            MoveState s = MoveState.of(rider);
            if (victim instanceof Strider && Mounts.fireProof(s) && src.is(DamageTypeTags.IS_FIRE)) { e.setCanceled(true); return; }
            if (s.has(Mech.SADDLE_UP)) e.setAmount(e.getAmount() * (float) (1 - s.v(Mech.SADDLE_UP) / 100));
            if (s.has(Mech.OUTRIDER)) e.setAmount(e.getAmount() * (float) ClassMoves.mountGuard(s));
        }
    }

    @SubscribeEvent
    public static void onDamaged(LivingDamageEvent.Post e) {
        LivingEntity victim = e.getEntity();
        if (victim.level().isClientSide()) return;
        if (victim instanceof ServerPlayer p && Mv.ok(p)) {
            MoveState s = MoveState.of(p);
            s.walkClean = 0;
            s.momentum = 0;
            if (e.getSource().is(DamageTypes.FALL) && p.isAlive() && p.getHealth() < 4 && p.fallDistance >= 20) Mv.fire(p, "close_fall");
        }
        if (e.getSource().getEntity() instanceof ServerPlayer p && p != victim && Mv.ok(p)) {
            MoveState s = MoveState.of(p);
            if (s.has(Mech.TAIGA_TRACKER)) {
                s.tracked.removeIf(t -> t.ref().get() == victim);
                if (s.tracked.size() >= 20) s.tracked.pollFirst();
                s.tracked.addLast(new MoveState.Tracked(new java.lang.ref.WeakReference<>(victim), s.tick + Math.round(s.v(Mech.TAIGA_TRACKER) * 20)));
            }
        }
    }

    // ------------------------------------------------------------------------------------------- landing

    @SubscribeEvent
    public static void onFall(LivingFallEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        float dist = e.getDistance();
        BlockState landing = p.level().getBlockState(p.getOnPos());
        if (landing.is(Blocks.SLIME_BLOCK) && dist >= 3) Mv.fire(p, "slime_bounce");
        MoveState s = MoveState.of(p);
        if (!s.any) return;
        land(p, s, e, dist, landing);
    }

    /** Every landing perk, in order. Public to the package for tests. */
    static void land(ServerPlayer p, MoveState s, LivingFallEvent e, float dist, BlockState landing) {
        float mult = e.getDamageMultiplier();
        if (s.windLaunched) { s.windLaunched = false; mult = 0; }
        double safe = p.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
        if (s.has(Mech.LEAP_OF_FAITH)) {
            if (softLanding(p, landing)) mult = 0;
            else if (dist > safe && now(p) >= p.getData(MoveData.TYPE).softReadyAt && mult > 0) {
                mult = 0;
                p.getData(MoveData.TYPE).softReadyAt = now(p) + Math.round(s.v(Mech.LEAP_OF_FAITH) * 20);
                p.serverLevel().sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.1, p.getZ(), 12, 0.4, 0.05, 0.4, 0.02);
            }
        }
        if (s.has(Mech.FOREST_RANGER) && landing.is(BlockTags.LEAVES)) mult *= (float) (1 - Math.min(100, s.v(Mech.FOREST_RANGER)) / 100);
        if (s.has(Mech.CAT_LANDING) && s.lv(Mech.CAT_LANDING) >= s.p(Mech.CAT_LANDING, "roll_level") && p.isShiftKeyDown() && dist > safe) {
            mult = 0;
            Vec3 f = Mv.flatLook(p);
            Mv.push(p, new Vec3(f.x * 0.5, 0.1, f.z * 0.5));
        }
        if (s.has(Mech.SLIME_RIDER) && !p.isShiftKeyDown() && dist >= s.p(Mech.SLIME_RIDER, "min_fall")) {
            mult = 0;
            double vy = Math.min(3, Math.sqrt(2 * 0.08 * dist * s.v(Mech.SLIME_RIDER) / 100) * 1.1);
            Vec3 k = p.getKnownMovement();
            Mv.push(p, new Vec3(k.x, vy, k.z));
        }
        if (s.has(Mech.GROUND_SLAM) && dist >= s.p(Mech.GROUND_SLAM, "min_fall")) {
            int hits = slam(p, s, dist);
            mult *= (float) Math.max(0, 1 - 0.2 * hits);
        }
        e.setDamageMultiplier(mult);
    }

    static boolean softLanding(Player p, BlockState b) {
        return b.is(Blocks.HAY_BLOCK) || b.is(Blocks.SLIME_BLOCK) || b.is(BlockTags.BEDS) || b.is(Blocks.POWDER_SNOW)
                || b.is(Blocks.SNOW_BLOCK) || b.is(Blocks.SNOW) || b.is(Blocks.COBWEB) || b.is(Blocks.HONEY_BLOCK)
                || p.level().getFluidState(p.blockPosition()).isSource() || p.isInWater();
    }

    /** Ground Slam: damage + knockback to mobs in the radius. Never hurts players, pets or the rider's own mount. */
    static int slam(ServerPlayer p, MoveState s, float dist) {
        double r = s.v(Mech.GROUND_SLAM);
        float dmg = (float) Math.min(s.p(Mech.GROUND_SLAM, "cap"), dist * s.p(Mech.GROUND_SLAM, "dmg"));
        List<LivingEntity> near = p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r, 1.5, r),
                le -> le != p && le.isAlive() && !(le instanceof Player) && le.isPickable()
                        && !(le instanceof net.minecraft.world.entity.TamableAnimal t && p.getUUID().equals(t.getOwnerUUID()))
                        && le.distanceToSqr(p) <= r * r);
        int hits = 0;
        for (LivingEntity le : near) {
            if (hits >= 16) break;
            if (le.hurt(p.damageSources().playerAttack(p), dmg)) {
                le.knockback(0.4 + r * 0.05, p.getX() - le.getX(), p.getZ() - le.getZ());
                hits++;
            }
        }
        p.serverLevel().sendParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY() + 0.1, p.getZ(), 1, 0, 0, 0, 0);
        p.serverLevel().sendParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.1, p.getZ(), (int) (r * 6), r / 2, 0.05, r / 2, 0.05);
        p.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.MACE_SMASH_GROUND_HEAVY,
                net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
        return hits;
    }

    // ------------------------------------------------------------------------------------------- mobs noticing you

    private static final Map<Mob, Long> BRUTE_FIRST_SEEN = new WeakHashMap<>();

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent e) {
        if (!(e.getNewAboutToBeSetTarget() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        if (!s.any) return;
        LivingEntity mob = e.getEntity();
        var follow = mob.getAttribute(Attributes.FOLLOW_RANGE);
        double range = follow == null ? 16 : follow.getValue(), d = mob.distanceTo(p);
        boolean cancel = false;
        if (s.has(Mech.GHOST_STEP) && p.isShiftKeyDown() && d > range * (1 - s.v(Mech.GHOST_STEP) / 100)) cancel = true;
        if (s.has(Mech.CAPTAIN) && (mob instanceof Drowned || mob instanceof Guardian) && p.getVehicle() instanceof Boat
                && p.getRandom().nextDouble() * 100 < s.v(Mech.CAPTAIN)) cancel = true;
        if (s.has(Mech.VILLAGE_GUIDE) && mob instanceof IronGolem) cancel = true;
        if (s.has(Mech.CRIMSON_WANDERER) && (mob instanceof AbstractPiglin || mob instanceof Hoglin) && d > range * (1 - s.v(Mech.CRIMSON_WANDERER) / 100))
            cancel = true;
        if ((s.has(Mech.MEADOW_BREEZE) || s.has(Mech.ATLAS)) && mob instanceof Bee) cancel = true;
        if (s.has(Mech.DESERT_NOMAD) && mob instanceof Husk && d > range * (1 - s.p(Mech.DESERT_NOMAD, "husk") / 100)) cancel = true;
        // plain piglins: MoveHooks.wearsGold. Brain memory only on the brute: other mobs' brains don't register ANGRY_AT and throw
        if (s.has(Mech.PIGLIN_DISGUISE) && mob instanceof PiglinBrute brute) {
            boolean angry = brute.getBrain().getMemory(MemoryModuleType.ANGRY_AT).map(p.getUUID()::equals).orElse(false);
            if (!angry) {
                long first = BRUTE_FIRST_SEEN.computeIfAbsent(brute, b -> now(p));
                if (now(p) - first < s.v(Mech.PIGLIN_DISGUISE) * 20) cancel = true;
            }
        }
        if (cancel) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onVisibility(LivingEvent.LivingVisibilityEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && p.isShiftKeyDown() && Mv.ok(p)) {
            MoveState s = MoveState.of(p);
            if (s.has(Mech.GHOST_STEP)) e.modifyVisibility(1 - s.v(Mech.GHOST_STEP) / 100);
        }
    }

    @SubscribeEvent
    public static void onEnderman(EnderManAngerEvent e) {
        Player p = e.getPlayer();
        if (!Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        if (s.has(Mech.ENDWALKER) || (s.has(Mech.CRIMSON_WANDERER) && p.level().getBiome(p.blockPosition()).is(Biomes.WARPED_FOREST)))
            e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onKnockback(LivingKnockBackEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        if (!s.any) return;
        float k = 1;
        LivingEntity by = p.getLastHurtByMob();
        boolean fresh = by != null && p.tickCount - p.getLastHurtByMobTimestamp() < 2;
        if (fresh && by instanceof MagmaCube && s.has(Mech.DELTA_WALKER)) k *= (float) (1 - s.v(Mech.DELTA_WALKER) / 100);
        if (fresh && by instanceof Goat && s.has(Mech.PEAK_CLIMBER)) k *= (float) (1 - s.p(Mech.PEAK_CLIMBER, "goat") / 100);
        if (s.has(Mech.FORTRESS_CRAWLER)) {
            BlockState b = p.level().getBlockState(p.getOnPos());
            if (b.is(Blocks.NETHER_BRICKS) || b.is(Blocks.NETHER_BRICK_SLAB) || b.is(Blocks.NETHER_BRICK_STAIRS) || b.is(Blocks.NETHER_BRICK_FENCE)
                    || b.is(Blocks.CRACKED_NETHER_BRICKS) || b.is(Blocks.CHISELED_NETHER_BRICKS))
                k *= (float) (1 - s.v(Mech.FORTRESS_CRAWLER) / 100);
        }
        if (k < 1) e.setStrength(e.getStrength() * Math.max(0, k));
    }

    @SubscribeEvent
    public static void onGameEvent(VanillaGameEvent e) {
        if (!(e.getCause() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        if (!s.any) return;
        if (s.has(Mech.ANCIENT_ECHO) && p.getRandom().nextDouble() * 100 < s.v(Mech.ANCIENT_ECHO)) { e.setCanceled(true); return; }
        if (s.has(Mech.GHOST_STEP) && p.isShiftKeyDown() && s.lv(Mech.GHOST_STEP) >= s.p(Mech.GHOST_STEP, "sculk_level")) e.setCanceled(true);
    }

    // ------------------------------------------------------------------------------------------- effects

    private static boolean reapplying;

    @SubscribeEvent
    public static void onEffectApplicable(MobEffectEvent.Applicable e) {
        if (reapplying || !(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        if (!s.any) return;
        MobEffectInstance inst = e.getEffectInstance();
        var effect = inst.getEffect();
        if (effect.is(MobEffects.DIG_SLOWDOWN) && s.has(Mech.MONUMENT_DIVER)) {
            int amp = inst.getAmplifier() - (int) s.v(Mech.MONUMENT_DIVER);
            e.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
            int duration = inst.getDuration();
            boolean haste = s.lv(Mech.MONUMENT_DIVER) >= 10;
            com.habits.perk.Later.run(p.server, 0, () -> {
                reapplying = true;
                try {
                    if (amp >= 0) p.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, duration, amp));
                    else if (haste) p.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, duration, 1));
                } finally {
                    reapplying = false;
                }
            });
            return;
        }
        if (effect.is(MobEffects.POISON) && s.has(Mech.MINE_RAT) && s.inMineshaft && s.lv(Mech.MINE_RAT) >= s.p(Mech.MINE_RAT, "poison_level"))
            e.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        if (effect.is(MobEffects.DARKNESS) && s.has(Mech.ANCIENT_ECHO) && s.p(Mech.ANCIENT_ECHO, "darkness") >= 100)
            e.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
    }

    @SubscribeEvent
    public static void onEffectAdded(MobEffectEvent.Added e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        if (!s.any) return;
        MobEffectInstance inst = e.getEffectInstance();
        var effect = inst.getEffect();
        double cut = 0;
        if (s.has(Mech.SWAMP_WADER) && (effect.is(MobEffects.POISON) || effect.is(MobEffects.MOVEMENT_SLOWDOWN))
                && (e.getEffectSource() instanceof Mob || e.getEffectSource() instanceof net.minecraft.world.entity.projectile.Projectile))
            cut = s.v(Mech.SWAMP_WADER);
        if (s.has(Mech.ANCIENT_ECHO) && effect.is(MobEffects.DARKNESS)) cut = s.p(Mech.ANCIENT_ECHO, "darkness");
        if (cut <= 0 || inst.isInfiniteDuration()) return;
        inst.duration = Math.max(1, (int) (inst.getDuration() * (1 - cut / 100)));
    }

    // ------------------------------------------------------------------------------------------- world

    @SubscribeEvent
    public static void onSpawnCheck(MobSpawnEvent.PositionCheck e) {
        if (!(e.getEntity() instanceof Enemy) || e.getSpawnType() != net.minecraft.world.entity.MobSpawnType.NATURAL) return;
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        for (ServerPlayer p : level.players()) {
            if (!Mv.ok(p)) continue;
            MoveState s = MoveState.SERVER.get(p.getUUID());
            if (s == null || !s.has(Mech.MUSHROOM_PILGRIM) || s.tick - s.lastMoveTick < 100) continue;
            double r = s.v(Mech.MUSHROOM_PILGRIM);
            if (p.distanceToSqr(e.getX(), e.getY(), e.getZ()) <= r * r) {
                e.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onLightning(EntityStruckByLightningEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && Mv.ok(p)) {
            MoveState s = MoveState.of(p);
            if (s.has(Mech.STORM_CHASER) && s.lv(Mech.STORM_CHASER) >= s.p(Mech.STORM_CHASER, "redirect_level")) e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent e) {
        if (e.getProjectile() instanceof ThrownPotion potion && potion.getOwner() instanceof Witch
                && e.getRayTraceResult() instanceof EntityHitResult hit && hit.getEntity() instanceof ServerPlayer p && Mv.ok(p)) {
            MoveState s = MoveState.of(p);
            if (s.has(Mech.WITCH_HUNTER) && p.getRandom().nextDouble() * 100 < s.v(Mech.WITCH_HUNTER)) e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplosionKnockback(ExplosionKnockbackEvent e) {
        if (!(e.getAffectedEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        if (!(e.getExplosion().getDirectSourceEntity() instanceof AbstractWindCharge)) return;
        Mv.fire(p, "wind_burst");
        MoveState s = MoveState.of(p);
        if (s.has(Mech.TRIAL_TAKER)) {
            e.setKnockbackVelocity(e.getKnockbackVelocity().scale(1 + s.v(Mech.TRIAL_TAKER) / 100));
            s.windLaunched = true;
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.loadedFromDisk()) return;
        Entity ent = e.getEntity();
        if (ent instanceof EyeOfEnder eye) {
            Player p = e.getLevel().getNearestPlayer(eye, 3);
            if (p == null || !Mv.ok(p)) return;
            MoveState s = MoveState.of(p);
            if (!s.has(Mech.STRONGHOLD_SEEKER)) return;
            if (!eye.surviveAfterDeath && p.getRandom().nextDouble() * 100 < s.v(Mech.STRONGHOLD_SEEKER)) eye.surviveAfterDeath = true;
            double m = s.p(Mech.STRONGHOLD_SEEKER, "distance");
            eye.tx = eye.getX() + (eye.tx - eye.getX()) * m;
            eye.tz = eye.getZ() + (eye.tz - eye.getZ()) * m;
        } else if (ent instanceof FireworkRocketEntity rocket && rocket.getOwner() instanceof ServerPlayer p && p.isFallFlying() && Mv.ok(p)) {
            MoveState s = MoveState.of(p);
            if (!s.has(Mech.ROCKET_SAVER)) return;
            rocket.lifetime = (int) (rocket.lifetime * (1 + s.p(Mech.ROCKET_SAVER, "duration") / 100));
        } else if (ent instanceof Enemy && ent instanceof Mob && e.getLevel() instanceof ServerLevel level && level.isNight()) {
            for (ServerPlayer p : level.players()) {
                MoveState s = MoveState.SERVER.get(p.getUUID());
                if (s == null || !s.has(Mech.NIGHT_OWL) || s.lv(Mech.NIGHT_OWL) < s.p(Mech.NIGHT_OWL, "glow_level")) continue;
                double r = s.p(Mech.NIGHT_OWL, "glow");
                if (p.distanceToSqr(ent) <= r * r) PacketDistributor.sendToPlayer(p, new MovePayloads.Glow(List.of(ent.getId()), 100));
            }
        }
    }

    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        ItemStack stack = e.getItemStack();
        MoveState s = MoveState.of(p);
        if (stack.is(Items.FIREWORK_ROCKET) && p.isFallFlying()) {
            Mv.fire(p, "glide_rocket");
            // vanilla shrinks the stack right after this event: growing it first makes the boost free
            if (s.has(Mech.ROCKET_SAVER) && !p.getAbilities().instabuild && p.getRandom().nextDouble() * 100 < s.v(Mech.ROCKET_SAVER)) stack.grow(1);
        } else if (stack.is(Items.RECOVERY_COMPASS)) {
            if (p.getLastDeathLocation().map(GlobalPos::dimension).filter(d -> d == p.level().dimension()).isPresent()) Mv.fire(p, "recovery_compass");
        } else if (stack.is(Items.COMPASS) && p.isShiftKeyDown() && s.has(Mech.LODESTONE_LINK)) {
            LodestoneTracker lt = stack.get(DataComponents.LODESTONE_TRACKER);
            if (lt == null || lt.target().isEmpty() || lt.target().get().dimension() != p.level().dimension()) return;
            MoveData d = p.getData(MoveData.TYPE);
            if (now(p) < d.lodestoneReadyAt) {
                Mv.say(p, "habits.movement.cooldown", (d.lodestoneReadyAt - now(p) + 19) / 20);
                return;
            }
            s.channelTarget = lt.target().get().pos();
            s.channelFrom = p.position();
            s.channelUntil = now(p) + Math.round(s.v(Mech.LODESTONE_LINK) * 20);
            Mv.say(p, "habits.movement.channel");
            e.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        ServerLevel level = p.serverLevel();
        BlockPos pos = e.getPos();
        BlockState state = level.getBlockState(pos);
        ItemStack held = e.getItemStack();
        if (state.is(Blocks.CHEST) && level.getBlockEntity(pos) instanceof ChestBlockEntity chest && chest.getLootTable() != null
                && Scans.insideAny(level, pos, Scans.TREASURE)) Mv.fire(p, "buried_treasure");
        MoveState s = MoveState.of(p);
        if (state.getBlock() instanceof VaultBlock && (held.is(Items.TRIAL_KEY) || held.is(Items.OMINOUS_TRIAL_KEY))) {
            s.vaultPos = pos;
            s.vaultKeys = held.getCount();
            s.vaultOminous = state.getValue(VaultBlock.OMINOUS);
            s.vaultHand = e.getHand();
        }
        if ((held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE)) && s.has(Mech.PORTAL_RESTORER) && e.getFace() != null)
            Portals.restore(p, s, pos.relative(e.getFace()));
    }

    /** Key Turner: the key was used up the tick after clicking a vault, so the vault unlocked for this player. */
    static void checkVault(ServerPlayer p, MoveState s) {
        if (s.vaultPos == null) return;
        BlockPos pos = s.vaultPos;
        s.vaultPos = null;
        if (p.getItemInHand(s.vaultHand).getCount() >= s.vaultKeys && !p.getItemInHand(s.vaultHand).isEmpty()) return;
        Mv.fire(p, "vault_unlock");
        if (!s.has(Mech.KEY_TURNER) || p.getRandom().nextDouble() * 100 >= s.v(Mech.KEY_TURNER)) return;
        if (!(p.level().getBlockEntity(pos) instanceof VaultBlockEntity vault)) return;
        ServerLevel level = p.serverLevel();
        var table = level.getServer().reloadableRegistries().getLootTable(vault.getConfig().lootTable());
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withLuck(p.getLuck()).withParameter(LootContextParams.THIS_ENTITY, p).create(LootContextParamSets.VAULT);
        Vec3 out = Vec3.atCenterOf(pos).add(0, 0.8, 0);
        for (ItemStack st : table.getRandomItems(params)) drop(level, out, st);
        if (s.vaultOminous && p.getRandom().nextDouble() * 100 < s.p(Mech.KEY_TURNER, "heavy_core")) drop(level, out, new ItemStack(Items.HEAVY_CORE));
    }

    static void drop(ServerLevel level, Vec3 at, ItemStack st) {
        ItemEntity it = new ItemEntity(level, at.x, at.y, at.z, st);
        it.setDefaultPickUpDelay();
        level.addFreshEntity(it);
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntityMounting() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        if (e.isMounting()) {
            Mv.fire(p, "mount");
            if (e.getEntityBeingMounted() instanceof LivingEntity le) p.getData(MoveData.TYPE).mount = java.util.Optional.of(le.getUUID());
        } else Mounts.unbuff(MoveState.of(p));
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        if (s.tick - s.jumpWindowStart > 1200) { s.jumpWindowStart = s.tick; s.jumpsInWindow = 0; }
        if (++s.jumpsInWindow == 50) Mv.fire(p, "jump_frenzy");
        if (Mv.wall(p) != null) Mv.fire(p, "wall_jump");
        if (p.isSprinting()) s.jumpStart = p.position();
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        long t = p.server.overworld().getGameTime();
        if (e.getTo() == Level.OVERWORLD) {
            if (s.leftOverworldAt >= 0 && t - s.leftOverworldAt >= 36000) Mv.fire(p, "homecoming");
            s.leftOverworldAt = -1;
        } else if (e.getFrom() == Level.OVERWORLD) s.leftOverworldAt = t;
        s.sampled = false;
        Mounts.unbuff(s);
        double res = s.has(Mech.PORTAL_HOPPER) ? s.p(Mech.PORTAL_HOPPER, "resistance") : 0;
        if (res > 0) p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, (int) (res * 20), 1, false, false, true));
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.isEndConquered() || !(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        if (!s.has(Mech.RECOVERY_RUN)) return;
        p.getLastDeathLocation().filter(g -> g.dimension() == p.level().dimension()).ifPresent(g -> {
            s.recoverTarget = g.pos();
            s.recoverUntil = s.tick + Math.round(s.p(Mech.RECOVERY_RUN, "duration") * 20);
        });
    }

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Mv.ok(p)) return;
        MoveState s = MoveState.of(p);
        if (s.has(Mech.RECOVERY_RUN) && s.lv(Mech.RECOVERY_RUN) >= s.p(Mech.RECOVERY_RUN, "keep_level"))
            for (ItemEntity it : e.getDrops()) it.setUnlimitedLifetime();
    }

    @SubscribeEvent
    public static void onChunk(ChunkEvent.Load e) {
        if (e.isNewChunk() && e.getLevel() instanceof ServerLevel level) Trackers.chunkGenerated(level.dimension(), e.getChunk().getPos().toLong());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        MoveState s = MoveState.SERVER.get(e.getEntity().getUUID());
        if (s != null) Mounts.unbuff(s);
        MoveState.SERVER.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent e) {
        MoveState.SERVER.clear();
        synchronized (Trackers.class) {
            Trackers.FRESH.clear();
        }
        BRUTE_FIRST_SEEN.clear();
        Mv.TEST_PLAYERS.clear();
    }
}
