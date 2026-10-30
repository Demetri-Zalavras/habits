package com.habits.perk.survival;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.ArmorHurtEvent;
import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.level.ExplosionKnockbackEvent;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Damage, knockback, projectile, shield and armor mechanics. Every handler bails out on the first cheap check (client side,
 * victim not a player, player owns no survival mechanic), so a hit on a zombie costs one instanceof.
 */
public final class Defense {
    private Defense() {}

    /** Damage types whose skill is a plain "cut this damage by value %" (plus extras handled below). ResourceKeys are interned. */
    static final Map<ResourceKey<DamageType>, Sk> RESIST = new IdentityHashMap<>();
    static {
        RESIST.put(DamageTypes.LAVA, Sk.LAVA_WALKER);
        RESIST.put(DamageTypes.IN_WALL, Sk.WALL_PHASER);
        RESIST.put(DamageTypes.LIGHTNING_BOLT, Sk.THUNDERSTRUCK);
        RESIST.put(DamageTypes.BAD_RESPAWN_POINT, Sk.BAD_DREAMS);
        RESIST.put(DamageTypes.CACTUS, Sk.CACTUS_SKIN);
        RESIST.put(DamageTypes.CAMPFIRE, Sk.CAMP_COOK);
        RESIST.put(DamageTypes.CRAMMING, Sk.ELBOW_ROOM);
        RESIST.put(DamageTypes.DRAGON_BREATH, Sk.BREATH_BOTTLER);
        RESIST.put(DamageTypes.FALLING_ANVIL, Sk.HARD_HAT);
        RESIST.put(DamageTypes.FALLING_BLOCK, Sk.HARD_HAT);
        RESIST.put(DamageTypes.FALLING_STALACTITE, Sk.DRIP_DODGER);
        RESIST.put(DamageTypes.STALAGMITE, Sk.SPIKE_LANDER);
        RESIST.put(DamageTypes.FIREWORKS, Sk.GRAND_FINALE);
        RESIST.put(DamageTypes.FLY_INTO_WALL, Sk.CRUMPLE_ZONE);
        RESIST.put(DamageTypes.FREEZE, Sk.SNOW_BORN);
        RESIST.put(DamageTypes.HOT_FLOOR, Sk.MAGMA_SOLES);
        RESIST.put(DamageTypes.IN_FIRE, Sk.FLAME_DANCER);
        RESIST.put(DamageTypes.INDIRECT_MAGIC, Sk.HEX_WARD);
        RESIST.put(DamageTypes.MOB_ATTACK_NO_AGGRO, Sk.GOAT_FOOTING);
        RESIST.put(DamageTypes.OUTSIDE_BORDER, Sk.EDGE_WALKER);
        RESIST.put(DamageTypes.SONIC_BOOM, Sk.SOUND_PROOF);
        RESIST.put(DamageTypes.SPIT, Sk.SPIT_SHINE);
        RESIST.put(DamageTypes.STING, Sk.BEEKEEPERS_HIDE);
        RESIST.put(DamageTypes.SWEET_BERRY_BUSH, Sk.BRAMBLE_WALKER);
        RESIST.put(DamageTypes.THORNS, Sk.THORN_PROOF);
        RESIST.put(DamageTypes.PLAYER_ATTACK, Sk.GRUDGE_BEARER);
        RESIST.put(DamageTypes.GENERIC, Sk.ODD_BRUISE);
    }

    static final String REFLECTED = "habits_reflected";

    // ================================================================== incoming (before armor)

    @SubscribeEvent
    public static void incoming(LivingIncomingDamageEvent e) {
        LivingEntity victim = e.getEntity();
        if (victim.level().isClientSide) return;
        DamageSource src = e.getSource();
        if (src.getEntity() instanceof Player && Life.isAlly(victim)) {
            e.setCanceled(true); // Slime Heart allies are on the players' side
            return;
        }
        if (src.getEntity() instanceof Player a && a != victim) attacker(e, a, victim, src);
        if (victim instanceof Player p) victim(e, p, src);
    }

    /** Perks of the player dealing the hit. */
    static void attacker(LivingIncomingDamageEvent e, Player a, LivingEntity victim, DamageSource src) {
        Entity direct = src.getDirectEntity();
        if (direct != null && direct != a && direct.getPersistentData().contains(REFLECTED)) {
            e.setAmount(e.getAmount() * direct.getPersistentData().getFloat(REFLECTED));
        }
        if (direct != a || !Sk.anyActive(a)) return;
        CompoundTag d = Sk.data(a);
        int charges = d.getInt("thunder");
        if (charges > 0) {
            HabitsApi.PerkHit h = Sk.THUNDERSTRUCK.hit(a);
            d.putInt("thunder", charges - 1);
            if (h != null) chainLightning(a, victim, h);
        }
        if (a.isOnFire()) {
            HabitsApi.PerkHit h = Sk.EMBER_SKIN.hit(a);
            if (h != null) victim.igniteForSeconds((float) Sk.p(h, "ignite"));
        }
        if (a.getFoodData().getFoodLevel() == 0) {
            HabitsApi.PerkHit h = Sk.IRON_STOMACH.hit(a);
            if (h != null) e.setAmount(e.getAmount() * (float) (1 + Sk.p(h, "rage") / 100));
        }
        float rage = d.getFloat("rage");
        if (rage > 0 && Sk.PAIN_FUEL.hit(a) != null) {
            e.setAmount(e.getAmount() + rage);
            d.putFloat("rage", 0);
            a.displayClientMessage(Component.translatable("habits.survival.rage", 0), true);
        }
        if (a.hasEffect(MobEffects.DAMAGE_BOOST)) {
            HabitsApi.PerkHit h = Sk.HEAVY_HANDS.hit(a);
            if (h != null) Fx.effect(victim, MobEffects.MOVEMENT_SLOWDOWN, h.value(), Sk.p(h, "amp"));
        }
        var weak = a.getEffect(MobEffects.WEAKNESS);
        if (weak != null) {
            double v = Sk.UNBOWED.v(a);
            if (v > 0) e.setAmount(e.getAmount() + (float) (4 * (weak.getAmplifier() + 1) * Math.min(v, 100) / 100));
        }
    }

    static void chainLightning(Player a, LivingEntity first, HabitsApi.PerkHit h) {
        ServerLevel level = (ServerLevel) a.level();
        int chain = (int) Math.round(Sk.p(h, "chain"));
        float dmg = (float) Sk.p(h, "chain_damage");
        double range = Sk.p(h, "chain_range");
        LivingEntity from = first;
        bolt(level, first.position());
        for (int i = 0; i < chain; i++) {
            LivingEntity cur = from;
            LivingEntity next = level.getNearestEntity(LivingEntity.class, net.minecraft.world.entity.ai.targeting.TargetingConditions.forCombat()
                            .selector(t -> t != a && t != cur && t != first && t instanceof Enemy),
                    a, cur.getX(), cur.getY(), cur.getZ(), cur.getBoundingBox().inflate(range));
            if (next == null) break;
            next.hurt(level.damageSources().indirectMagic(a, a), dmg);
            bolt(level, next.position());
            from = next;
        }
    }

    static void bolt(ServerLevel level, Vec3 at) {
        LightningBolt b = EntityType.LIGHTNING_BOLT.create(level);
        if (b == null) return;
        b.moveTo(at);
        b.setVisualOnly(true);
        level.addFreshEntity(b);
    }

    /** Perks of the player being hit. */
    static void victim(LivingIncomingDamageEvent e, Player p, DamageSource src) {
        var key = src.typeHolder().unwrapKey().orElse(null);
        if (key == DamageTypes.FELL_OUT_OF_WORLD && p.getY() < p.level().getMinBuildHeight()) voidFall(e, p);
        if (!Sk.anyActive(p)) return;
        if (lichShield(e, p, src)) return;
        Sk s = RESIST.get(key);
        if (s != null) {
            HabitsApi.PerkHit h = s.hit(p);
            if (h != null) resistExtras(e, p, src, s, h);
        }
        if (src.is(DamageTypeTags.IS_EXPLOSION)) explosion(e, p, src);
        if (key == DamageTypes.MOB_ATTACK) mobHit(e, p, src);
        if (key == DamageTypes.DROWN) {
            HabitsApi.PerkHit g = Sk.GILLS.hit(p);
            if (g != null) e.setAmount(e.getAmount() * (float) Sk.parse(g.skill().perk().param("drown_mult", "1"), g.level()));
        }
        if (key == DamageTypes.ARROW) arrowHit(e, p, src);
        if (key == DamageTypes.TRIDENT) tridentHit(e, p, src);
        if (src.getEntity() instanceof LivingEntity att && src.getDirectEntity() == att && att != p) meleeBack(p, att);
        if (!e.isCanceled()) {
            double hardy = Sk.HARDY.v(p);
            if (hardy > 0) e.setInvulnerabilityTicks(20 + (int) hardy);
        }
    }

    /** Phylactery: a shield soaks up one whole hit from a mob, player or projectile (not falls, fire or /kill). */
    static boolean lichShield(LivingIncomingDamageEvent e, Player p, DamageSource src) {
        if (src.getEntity() == null && src.getDirectEntity() == null || src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        HabitsApi.PerkHit h = Sk.PHYLACTERY.hit(p);
        if (h == null) return false;
        CompoundTag d = Sk.data(p);
        int n = d.getInt("phyShields");
        if (n <= 0) return false;
        // the regrow clock starts with the first shield lost; while one is already missing it keeps running
        if (n >= (int) h.value()) d.putLong("phyNext", p.level().getGameTime() + (long) (Sk.p(h, "regen") * 20));
        d.putInt("phyShields", n - 1);
        e.setCanceled(true);
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1, 0.6f);
        if (p.level() instanceof ServerLevel l)
            l.sendParticles(ParticleTypes.WITCH, p.getX(), p.getY() + 1, p.getZ(), 16, 0.5, 0.5, 0.5, 0.05);
        return true;
    }

    /** Gills: underwater air drains value times slower (fractional loss carried over, so the rate is exact). */
    @SubscribeEvent
    public static void breathe(LivingBreatheEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || e.canBreathe() || e.getConsumeAirAmount() <= 0) return;
        double v = Sk.GILLS.v(p);
        if (v <= 1) return;
        CompoundTag d = Sk.data(p);
        double owed = d.getDouble("gillsAir") + e.getConsumeAirAmount() / v;
        int take = (int) owed;
        d.putDouble("gillsAir", owed - take);
        e.setConsumeAirAmount(take);
    }

    static void cut(LivingIncomingDamageEvent e, double pct) {
        if (pct >= 100) e.setCanceled(true);
        else if (pct > 0) e.setAmount(e.getAmount() * (float) (1 - pct / 100));
    }

    static void resistExtras(LivingIncomingDamageEvent e, Player p, DamageSource src, Sk s, HabitsApi.PerkHit h) {
        switch (s) {
            case WALL_PHASER -> popOut(p, (int) Math.round(Sk.p(h, "search")));
            case THUNDERSTRUCK -> Sk.data(p).putInt("thunder", Math.max(Sk.data(p).getInt("thunder"), (int) Math.round(Sk.p(h, "charges"))));
            case BAD_DREAMS -> {
                launch(p, Sk.p(h, "launch"));
                Sk.data(p).putBoolean("noFall", true);
                double mob = Sk.p(h, "mob_blast");
                if (mob > 0) {
                    double r = Sk.p(h, "mob_radius");
                    float dmg = e.getAmount() * (float) mob;
                    for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(r), m -> m instanceof Enemy))
                        m.hurt(p.damageSources().explosion(p, p), dmg);
                }
            }
            case GRAND_FINALE -> {
                p.heal((float) Sk.p(h, "heal"));
                Fx.effect(p, MobEffects.GLOWING, Sk.p(h, "glow_seconds"), 0);
            }
            case CRUMPLE_ZONE -> {
                p.stopFallFlying();
                p.setDeltaMovement(Vec3.ZERO);
                p.hurtMarked = true;
                Fx.effect(p, MobEffects.SLOW_FALLING, Sk.p(h, "cling"), 0);
            }
            case HARD_HAT -> {
                if (Sk.p(h, "no_chip") > 0 && src.getDirectEntity() instanceof net.minecraft.world.entity.item.FallingBlockEntity fb)
                    fb.getPersistentData().putBoolean(SurvivalHooks.NO_CHIP, true); // read by FallingBlockEntityMixin right after the hits
            }
            case FLAME_DANCER -> { if (Sk.maxed(h)) p.clearFire(); }
            case HEX_WARD -> { if (Sk.roll(p, Sk.p(h, "fizzle"))) { e.setCanceled(true); return; } }
            case BEEKEEPERS_HIDE -> { if (Sk.maxed(h) && src.getEntity() instanceof Bee bee) bee.stopBeingAngry(); }
            case THORN_PROOF -> { if (src.getEntity() instanceof LivingEntity att) Fx.knockAway(p, att, Sk.p(h, "push")); }
            case SPIT_SHINE -> {
                if (src.getEntity() instanceof Llama llama && !llama.isTamed())
                    llama.modifyTemper((int) (Sk.p(h, "tame") / 100 * llama.getMaxTemper()));
            }
            case GOAT_FOOTING -> {
                if (src.getEntity() instanceof Goat goat) {
                    Sk.data(p).putLong("goatHit", p.level().getGameTime());
                    if (Sk.roll(p, Sk.p(h, "horn"))) goat.dropHorn();
                }
                return; // the ram's damage is not cut, only its knockback
            }
            default -> {}
        }
        cut(e, h.value());
    }

    /** Wall Phaser: nearest spot within {@code search} blocks with two free blocks to stand in. */
    @org.jetbrains.annotations.Nullable
    static BlockPos popOut(Player p, int search) {
        BlockPos base = p.blockPosition();
        var level = p.level();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -search; dx <= search; dx++) for (int dy = -search; dy <= search; dy++) for (int dz = -search; dz <= search; dz++) {
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist >= bestD || dist == 0) continue;
            m.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
            if (!level.isLoaded(m) || !free(level, m) || !free(level, m.above())) continue;
            best = m.immutable();
            bestD = dist;
        }
        if (best != null) p.teleportTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5);
        return best;
    }

    static boolean free(net.minecraft.world.level.Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty();
    }

    static void launch(Player p, double blocks) {
        if (blocks <= 0) return;
        // v^2 = 2 g h with MC's 0.08 gravity, plus a little for drag
        double vy = Math.sqrt(2 * 0.08 * blocks) * 1.15;
        p.setDeltaMovement(p.getDeltaMovement().x, vy, p.getDeltaMovement().z);
        p.hurtMarked = true;
    }

    static void explosion(LivingIncomingDamageEvent e, Player p, DamageSource src) {
        if (src.is(DamageTypes.BAD_RESPAWN_POINT)) return;
        if (src.getEntity() == p) {
            double v = Sk.ROCKET_JUMPER.v(p);
            if (v > 0) { cut(e, v); return; }
        }
        double v = Sk.BLAST_BRACED.v(p);
        if (v > 0) {
            cut(e, v);
            Sk.data(p).putLong("blastHit", p.level().getGameTime());
        }
    }

    static void mobHit(LivingIncomingDamageEvent e, Player p, DamageSource src) {
        HabitsApi.PerkHit hide = Sk.THICK_HIDE.hit(p);
        if (hide != null) {
            if (Sk.roll(p, hide.value())) {
                e.setCanceled(true);
                p.level().playSound(null, p.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.6f, 1.8f);
                return;
            }
            cut(e, Sk.p(hide, "resist"));
        }
        if (p.hasEffect(MobEffects.MOVEMENT_SPEED) && Sk.roll(p, Sk.AFTERIMAGE.v(p))) e.setCanceled(true);
    }

    static void arrowHit(LivingIncomingDamageEvent e, Player p, DamageSource src) {
        if (!(src.getDirectEntity() instanceof AbstractArrow arrow)) return;
        double chain = Sk.CHAIN_MAIL_GRACE.v(p);
        if (chain > 0) {
            int pieces = 0;
            for (ItemStack s : p.getArmorSlots()) if (s.getItem() instanceof ArmorItem ai && ai.getMaterial().is(ArmorMaterials.CHAIN)) pieces++;
            if (Sk.roll(p, chain * pieces)) { e.setCanceled(true); return; }
        }
        if (Sk.roll(p, Sk.PINCUSHION.v(p))) {
            e.setCanceled(true);
            ItemStack caught = arrow.getPickupItemStackOrigin().copyWithCount(1);
            arrow.discard();
            if (!caught.isEmpty()) Fx.give(p, caught);
        }
    }

    static void tridentHit(LivingIncomingDamageEvent e, Player p, DamageSource src) {
        HabitsApi.PerkHit h = Sk.TRIDENT_CATCHER.hit(p);
        if (h == null || !(src.getDirectEntity() instanceof ThrownTrident t) || t.getOwner() instanceof Player) return;
        cut(e, Sk.p(h, "resist"));
        if (Sk.roll(p, h.value())) {
            ItemStack fresh = new ItemStack(Items.TRIDENT);
            fresh.setDamageValue(p.getRandom().nextInt(fresh.getMaxDamage()));
            t.discard();
            Fx.give(p, fresh);
        }
    }

    /** Things that happen to whoever just hit the player in melee. */
    static void meleeBack(Player p, LivingEntity att) {
        HabitsApi.PerkHit cactus = Sk.CACTUS_SKIN.hit(p);
        if (cactus != null) att.hurt(p.damageSources().thorns(p), (float) Sk.p(cactus, "prick"));
        HabitsApi.PerkHit web = Sk.WEB_SPINNER.hit(p);
        if (web != null && Sk.roll(p, Sk.p(web, "web_chance"))) {
            BlockPos at = att.blockPosition();
            if (p.level().getBlockState(at).isAir()) p.level().setBlockAndUpdate(at, Blocks.COBWEB.defaultBlockState());
        }
    }

    // ================================================================== void

    static void voidFall(LivingIncomingDamageEvent e, Player p) {
        CompoundTag d = Sk.data(p);
        if (!d.getBoolean("voidFired")) {
            d.putBoolean("voidFired", true);
            Fx.fire(p, "void_fall", safePos(p));
        }
        HabitsApi.PerkHit h = Sk.VOID_BOUNCE.hit(p);
        Vec3 safe = safePos(p);
        if (h == null || safe == null) return;
        long now = p.level().getGameTime();
        int cost = (int) Math.round(Sk.p(h, "xp_cost"));
        if (now < d.getLong("voidReady") || p.experienceLevel < cost) return;
        e.setCanceled(true);
        p.giveExperienceLevels(-cost);
        d.putLong("voidReady", now + (long) (h.value() * 60 * 20));
        p.teleportTo(safe.x, safe.y, safe.z);
        p.setDeltaMovement(Vec3.ZERO);
        p.hurtMarked = true;
        p.resetFallDistance();
        p.displayClientMessage(Component.translatable("habits.survival.void_bounce"), true);
    }

    /** Last solid ground in the current dimension (tracked once a second by {@link Ticks}), or null. */
    static Vec3 safePos(Player p) {
        CompoundTag d = Sk.data(p);
        if (!d.contains("safeX") || !p.level().dimension().location().toString().equals(d.getString("safeDim"))) return null;
        return new Vec3(d.getDouble("safeX"), d.getDouble("safeY"), d.getDouble("safeZ"));
    }

    // ================================================================== after armor

    @SubscribeEvent
    public static void pre(LivingDamageEvent.Pre e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !Sk.anyActive(p)) return;
        DamageSource src = e.getSource();
        float health = p.getHealth();
        if (src.is(DamageTypes.STARVE)) {
            double floor = Sk.IRON_STOMACH.v(p);
            if (floor > 0) e.setNewDamage((float) Math.max(0, Math.min(e.getNewDamage(), health - floor)));
        }
        if (src.is(DamageTypes.MAGIC)) {
            HabitsApi.PerkHit h = Sk.ANTIVENOM.hit(p);
            if (h != null) e.setNewDamage((float) Math.max(0, Math.min(e.getNewDamage(), health - Sk.p(h, "floor"))));
        }
        if (e.getNewDamage() >= health && !src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            HabitsApi.PerkHit h = Sk.LAST_STAND.hit(p);
            CompoundTag d = Sk.data(p);
            long now = p.level().getGameTime();
            if (h != null && now >= d.getLong("lastStandReady")) {
                e.setNewDamage(Math.max(0, health - 1));
                d.putLong("lastStandReady", now + (long) (h.value() * 60 * 20));
                p.displayClientMessage(Component.translatable("habits.survival.last_stand"), true);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void post(LivingDamageEvent.Post e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !p.isAlive()) return;
        float dmg = e.getNewDamage();
        if (dmg <= 0) return;
        CompoundTag d = Sk.data(p);
        long now = p.level().getGameTime();
        // card triggers (every player)
        Fx.fireUnits(p, "damage_taken_hp", dmg);
        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() instanceof ArmorItem ai && (ai.getMaterial().is(ArmorMaterials.DIAMOND) || ai.getMaterial().is(ArmorMaterials.NETHERITE)))
            Fx.fireUnits(p, "diamond_armor_damage", dmg);
        if (heavyArmor(p)) Fx.fire(p, "hit_in_heavy_armor");
        if (p.getHealth() <= 1) Fx.fire(p, "survive_1hp");
        // the burst_damage card trigger reads its numbers from Pain Fuel's params (the skill it drops)
        var pf = com.habits.core.SkillRegistry.get(com.habits.Habits.id("pain_fuel"));
        double burstAt = pf == null ? 50 : Sk.parse(pf.perk().param("burst", "50"), 1);
        double window = pf == null ? 10 : Sk.parse(pf.perk().param("window_seconds", "10"), 1);
        if (now - d.getLong("burstStart") > window * 20) { d.putLong("burstStart", now); d.putFloat("burst", 0); }
        float burst = d.getFloat("burst") + dmg;
        d.putFloat("burst", burst);
        if (burst >= burstAt) { Fx.fire(p, "burst_damage"); d.putFloat("burst", 0); }
        if (!Sk.anyActive(p)) return;

        HabitsApi.PerkHit pain = Sk.PAIN_FUEL.hit(p);
        if (pain != null) {
            float rage = (float) Math.min(Sk.p(pain, "cap"), d.getFloat("rage") + dmg * pain.value() / 100);
            d.putFloat("rage", rage);
            p.displayClientMessage(Component.translatable("habits.survival.rage", String.format(java.util.Locale.ROOT, "%.1f", rage)), true);
        }
        HabitsApi.PerkHit wind = Sk.SECOND_WIND.hit(p);
        if (wind != null && p.getHealth() <= Sk.p(wind, "threshold") && now >= d.getLong("secondWindReady")) {
            d.putLong("secondWindReady", now + (long) (wind.value() * 20));
            double secs = Sk.p(wind, "regen_seconds");
            Fx.effect(p, MobEffects.REGENERATION, secs, Sk.p(wind, "regen_amp"));
            Fx.effect(p, MobEffects.MOVEMENT_SPEED, secs, Sk.p(wind, "speed_amp"));
        }
        HabitsApi.PerkHit gust = Sk.GUST_GUTS.hit(p);
        if (gust != null && p.hasEffect(MobEffects.WIND_CHARGED) && now >= d.getLong("gustReady")) {
            d.putLong("gustReady", now + (long) (Sk.p(gust, "cooldown") * 20));
            launch(p, gust.value());
            d.putBoolean("noFall", true);
        }
        HabitsApi.PerkHit ooze = Sk.SLIME_HEART.hit(p);
        if (ooze != null && p.hasEffect(MobEffects.OOZING) && Sk.roll(p, Sk.p(ooze, "ooze_chance"))) Life.oozeSlime(p, ooze);
        HabitsApi.PerkHit blast = Sk.BLAST_BRACED.hit(p);
        if (blast != null && d.getLong("blastHit") == now) {
            double absorb = Sk.p(blast, "absorb");
            if (absorb > 0) Fx.effect(p, MobEffects.ABSORPTION, Sk.p(blast, "absorb_seconds"), Math.ceil(absorb / 4) - 1);
        }
    }

    static boolean heavyArmor(Player p) {
        for (ItemStack s : p.getArmorSlots()) {
            if (!(s.getItem() instanceof ArmorItem ai)) return false;
            var m = ai.getMaterial();
            if (!m.is(ArmorMaterials.IRON) && !m.is(ArmorMaterials.DIAMOND) && !m.is(ArmorMaterials.NETHERITE)) return false;
        }
        return true;
    }

    // ================================================================== heal

    @SubscribeEvent
    public static void heal(LivingHealEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide) return;
        if (!SurvivalHooks.naturalRegen) return; // only FoodData's own regen, not effects, perks or other mods' heals
        Fx.fireUnits(p, "natural_regen_hp", e.getAmount());
        HabitsApi.PerkHit h = Sk.NATURAL_HEALER.hit(p);
        if (h == null) return;
        // FoodData adds 6 exhaustion per health right after this heal (saturation heals are fractions); Ticks refunds part of it next tick
        Sk.data(p).putFloat("healRefund", (float) (6 * e.getAmount() * Sk.p(h, "exhaustion_cut") / 100));
        e.setAmount(e.getAmount() * (float) (1 + h.value() / 100));
    }

    // ================================================================== falls and knockback

    @SubscribeEvent
    public static void fall(LivingFallEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !Sk.anyActive(p)) return;
        CompoundTag d = Sk.data(p);
        if (d.getBoolean("noFall")) {
            d.putBoolean("noFall", false);
            e.setCanceled(true);
            return;
        }
        HabitsApi.PerkHit ff = Sk.FEATHERFALL.hit(p);
        if (ff != null) {
            e.setDamageMultiplier(e.getDamageMultiplier() * (float) (1 - ff.value() / 100));
            double r = Sk.p(ff, "slam_radius");
            if (r > 0 && e.getDistance() >= Sk.p(ff, "slam_min_fall")) {
                double kb = Sk.p(ff, "slam_knockback");
                for (LivingEntity m : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r), m -> m != p && !(m instanceof Player)))
                    Fx.knockAway(p, m, kb);
                ((ServerLevel) p.level()).sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, p.getX(), p.getY(), p.getZ(), 12, r / 2, 0.1, r / 2, 0.02);
            }
        }
        HabitsApi.PerkHit spike = Sk.SPIKE_LANDER.hit(p);
        if (Sk.maxed(spike)) {
            BlockPos at = p.blockPosition();
            BlockState s = p.level().getBlockState(at);
            if (s.is(Blocks.POINTED_DRIPSTONE) && s.getValue(PointedDripstoneBlock.TIP_DIRECTION) == net.minecraft.core.Direction.UP)
                p.level().destroyBlock(at, true, p);
        }
    }

    @SubscribeEvent
    public static void knockback(LivingKnockBackEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !Sk.anyActive(p)) return;
        float s = e.getStrength();
        if (Sk.data(p).getLong("goatHit") == p.level().getGameTime()) s *= (float) Math.max(0, 1 - Sk.GOAT_FOOTING.v(p) / 100);
        if (p.hasEffect(MobEffects.REGENERATION)) s *= (float) (1 - Sk.STEADY_PULSE.v(p) / 100);
        e.setStrength(s);
    }

    @SubscribeEvent
    public static void explosionKnockback(ExplosionKnockbackEvent e) {
        if (!(e.getAffectedEntity() instanceof Player p) || p.level().isClientSide || !Sk.anyActive(p)) return;
        var ex = e.getExplosion();
        Vec3 v = e.getKnockbackVelocity();
        if (ex.getDirectSourceEntity() instanceof AbstractWindCharge wc && !(wc.getOwner() instanceof Player)) {
            HabitsApi.PerkHit h = Sk.WIND_RIDER.hit(p);
            if (h == null) return;
            e.setKnockbackVelocity(v.scale(h.value()));
            Sk.data(p).putBoolean("noFall", true);
            Fx.effect(p, MobEffects.SLOW_FALLING, Sk.p(h, "slow_fall"), 0);
            return;
        }
        if (ex.getIndirectSourceEntity() == p) {
            HabitsApi.PerkHit h = Sk.ROCKET_JUMPER.hit(p);
            if (h == null) return;
            e.setKnockbackVelocity(v.scale(Sk.p(h, "fling")));
            if (Sk.p(h, "no_fall") > 0) Sk.data(p).putBoolean("noFall", true);
            return;
        }
        HabitsApi.PerkHit h = Sk.BLAST_BRACED.hit(p);
        if (h != null) e.setKnockbackVelocity(v.scale(1 - Sk.p(h, "knockback_cut") / 100));
    }

    // ================================================================== projectiles

    @SubscribeEvent
    public static void impact(ProjectileImpactEvent e) {
        Projectile proj = e.getProjectile();
        if (proj.level().isClientSide || !(e.getRayTraceResult() instanceof EntityHitResult hit) || !(hit.getEntity() instanceof Player p)) return;
        Entity owner = proj.getOwner();
        if (owner == p || !Fx.acts(p) || !Sk.anyActive(p)) return;
        if (proj instanceof Snowball || proj instanceof ThrownEgg) {
            if (Sk.roll(p, Sk.SNOWBALL_CATCHER.v(p))) {
                e.setCanceled(true);
                Fx.give(p, new ItemStack(proj instanceof Snowball ? Items.SNOWBALL : Items.EGG));
                proj.discard();
            }
            return;
        }
        if (!(owner instanceof LivingEntity shooter) || owner instanceof Player || proj instanceof ShulkerBullet) return;
        if (proj instanceof WitherSkull skull) {
            HabitsApi.PerkHit h = Sk.SKULL_JUGGLER.hit(p);
            if (h != null && Sk.roll(p, skull.isDangerous() && Sk.p(h, "blue") > 0 ? 100 : h.value())) {
                e.setCanceled(true);
                reflect(proj, p, shooter, 1);
                return;
            }
        }
        HabitsApi.PerkHit h = Sk.RETURN_TO_SENDER.hit(p);
        if (h != null && Sk.roll(p, h.value())) {
            e.setCanceled(true);
            reflect(proj, p, shooter, (float) Sk.p(h, "damage_mult"));
        }
    }

    /** Send a projectile straight back at its shooter, now owned by {@code p}, hitting {@code mult} times as hard. */
    static void reflect(Projectile proj, Player p, LivingEntity shooter, float mult) {
        Vec3 to = shooter.getEyePosition().subtract(proj.position());
        double speed = Math.max(proj.getDeltaMovement().length(), 0.8);
        proj.setDeltaMovement(to.normalize().scale(speed));
        proj.setOwner(p);
        proj.hasImpulse = true;
        if (mult != 1) proj.getPersistentData().putFloat(REFLECTED, mult);
    }

    // ================================================================== shields

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void shield(LivingShieldBlockEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !e.getBlocked()) return;
        DamageSource src = e.getDamageSource();
        Entity direct = src.getDirectEntity();
        ItemStack shield = p.getUseItem();
        boolean disables = direct instanceof LivingEntity att && att.getMainHandItem().canDisableShield(shield, p, att);
        if (disables) Fx.fire(p, "shield_disabled");
        if (!Sk.anyActive(p)) return;
        double wall = Sk.SHIELD_WALL.v(p);
        if (wall > 0 && direct instanceof LivingEntity att && att != p && att.distanceToSqr(p) < 36)
            att.hurt(p.damageSources().thorns(p), e.getBlockedDamage() * (float) (wall / 100));
        if (direct instanceof Projectile proj && proj.getOwner() instanceof LivingEntity shooter && shooter != p
                && !(proj instanceof ShulkerBullet) && Sk.roll(p, Sk.ARROW_WALL.v(p))) {
            Fx.later(p.getServer(), () -> { if (proj.isAlive() && shooter.isAlive()) reflect(proj, p, shooter, 1); });
        }
        double axe = Sk.AXE_CATCHER.v(p);
        if (disables && axe > 0) {
            var item = shield.getItem();
            Fx.later(p.getServer(), () -> {
                if (p.getCooldowns().isOnCooldown(item)) p.getCooldowns().addCooldown(item, (int) Math.round(100 * (1 - axe / 100)));
            });
        }
    }

    // ================================================================== armor wear

    @SubscribeEvent
    public static void armorHurt(ArmorHurtEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !Sk.anyActive(p)) return;
        double tough = Sk.TOUGH_PLATE.v(p), trim = Sk.ARMOR_TRIMMER.v(p);
        if (tough <= 0 && trim <= 0) return;
        for (var entry : e.getArmorMap().entrySet()) {
            EquipmentSlot slot = entry.getKey();
            ItemStack stack = e.getArmorItemStack(slot);
            float dmg = e.getNewDamage(slot);
            if (tough > 0) {
                double scaled = dmg * (1 - tough / 100);
                int whole = (int) scaled;
                dmg = whole + (p.getRandom().nextDouble() < scaled - whole ? 1 : 0);
            }
            if (trim > 0 && stack.isDamageableItem() && stack.getDamageValue() + dmg >= stack.getMaxDamage() && Sk.roll(p, trim))
                dmg = Math.max(0, stack.getMaxDamage() - 1 - stack.getDamageValue());
            e.setNewDamage(slot, dmg);
        }
    }
}
