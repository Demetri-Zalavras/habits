package com.habits.danger;

import com.habits.Habits;
import com.habits.core.Category;
import com.habits.core.Rarity;
import com.habits.progression.CharmData;
import com.habits.progression.Drops;
import com.habits.progression.PMath;
import com.habits.progression.SigilData;
import com.habits.progression.SigilType;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Elites: about 1 in 20 hostile mobs, with 1 to 3 affixes by zone, a floating name in rarity color, affix particles and
 * a guaranteed sigil. The affix mask, zone rank and last shield block live in the mob's persistent data, so they survive
 * chunk reloads; every hook reads the mask first and returns at once for ordinary mobs.
 */
public final class Elites {
    private Elites() {}

    public enum Affix {
        FIERY(ParticleTypes.FLAME, 0.01), FROZEN(ParticleTypes.SNOWFLAKE, 0.01), SHOCKING(ParticleTypes.ELECTRIC_SPARK, 0.05),
        SHIELDED(ParticleTypes.ENCHANTED_HIT, 0.05), THORNY(null, 0.1);

        static final Affix[] VALUES = values();
        private final ParticleOptions particle;
        private final double speed;

        Affix(ParticleOptions particle, double speed) {
            this.particle = particle == null ? new BlockParticleOption(ParticleTypes.BLOCK, Blocks.CACTUS.defaultBlockState()) : particle;
            this.speed = speed;
        }

        public int bit() { return 1 << ordinal(); }
        public boolean in(int mask) { return (mask & bit()) != 0; }
        public Component displayName() { return Component.translatable("danger.habits.affix." + name().toLowerCase(Locale.ROOT)); }
    }

    static final String ELITE = "habits_elite", TIER = "habits_tier", SHIELD = "habits_shield";
    static final SoundEvent SHIELD_SOUND = SoundEvent.createVariableRangeEvent(Habits.id("danger.shield_block"));

    /** Loaded elites, for the particle puffs. Server thread only; dropped on leave and server stop. */
    private static final List<Mob> TRACKED = new ArrayList<>();
    /** True while an affix deals its own damage, so zaps and thorns never trigger more affixes or zone scaling. */
    static boolean busy;

    static void init(IEventBus bus) {
        bus.addListener(Elites::onIncoming);
        bus.addListener(Elites::onDamaged);
        bus.addListener(Elites::onDrops);
        bus.addListener(Elites::onTick);
        bus.addListener((EntityLeaveLevelEvent e) -> { if (e.getEntity() instanceof Mob m && !e.getLevel().isClientSide()) TRACKED.remove(m); });
        bus.addListener((ServerStoppedEvent e) -> { TRACKED.clear(); busy = false; });
    }

    public static int mask(Entity e) {
        return e.getPersistentData().getByte(ELITE);
    }

    /** 1..max affixes for zone {@code rank}, all different. */
    static int roll(RandomSource r, int rank) {
        int max = Math.max(1, Math.min(Affix.VALUES.length, (int) ZoneMath.at(DangerInit.MAX_AFFIXES.get(), rank, 1)));
        int n = 1 + r.nextInt(max), mask = 0;
        while (Integer.bitCount(mask) < n) mask |= 1 << r.nextInt(Affix.VALUES.length);
        return mask;
    }

    /** Turns {@code m} into an elite (does not scale it; {@link Scaling#apply} does that). */
    public static void make(Mob m, int mask, int rank) {
        CompoundTag t = m.getPersistentData();
        t.putByte(ELITE, (byte) mask);
        t.putByte(TIER, (byte) rank);
        m.setCustomName(name(m, mask, rank));
        m.setCustomNameVisible(true);
        track(m);
    }

    static void track(Mob m) {
        if (!m.level().isClientSide() && !TRACKED.contains(m)) TRACKED.add(m);
    }

    /** "Fiery Shielded Zombie" in Uncommon (1 affix), Rare (2) or Epic (3) color, one step higher in a listed dimension. */
    static Component name(Mob m, int mask, int rank) {
        MutableComponent affixes = Component.empty();
        for (Affix a : Affix.VALUES) {
            if (!a.in(mask)) continue;
            if (!affixes.getSiblings().isEmpty()) affixes.append(" ");
            affixes.append(a.displayName());
        }
        int r = Math.min(Rarity.LEGENDARY.ordinal(), Integer.bitCount(mask) + (rank > DangerInit.TIER_STARTS.get().size() ? 1 : 0));
        return Component.translatable("danger.habits.elite_name", affixes, m.getType().getDescription())
                .withStyle(s -> s.withColor(Rarity.values()[r].color).withItalic(false));
    }

    private static boolean canHit(LivingEntity e) {
        return e.isAlive() && !e.isSpectator();
    }

    // ------------------------------------------------------------------ defense: Fiery immunity, Shielded

    private static void onIncoming(LivingIncomingDamageEvent e) {
        LivingEntity v = e.getEntity();
        int m = mask(v);
        if (m == 0 || !(v.level() instanceof ServerLevel level)) return;
        DamageSource src = e.getSource();
        if (Affix.FIERY.in(m) && src.is(DamageTypeTags.IS_FIRE)) {
            e.setCanceled(true);
            v.clearFire();
            return;
        }
        if (Affix.SHIELDED.in(m) && src.getEntity() != null && !busy) {
            CompoundTag t = v.getPersistentData();
            long now = level.getGameTime();
            if (t.contains(SHIELD) && now - t.getLong(SHIELD) < DangerInit.SHIELD_SECONDS.get() * 20) return;
            t.putLong(SHIELD, now);
            e.setCanceled(true);
            level.playSound(null, v.getX(), v.getY(), v.getZ(), SHIELD_SOUND, SoundSource.HOSTILE, DangerInit.SHIELD_VOLUME.get().floatValue(), 1f);
            level.sendParticles(ParticleTypes.ENCHANTED_HIT, v.getX(), v.getY(0.6), v.getZ(), DangerInit.PARTICLE_COUNT.get() * 4,
                    v.getBbWidth() * 0.5, v.getBbHeight() * 0.3, v.getBbWidth() * 0.5, 0.3);
        }
    }

    // ------------------------------------------------------------------ on hit: Thorny (as victim), Fiery, Frozen, Shocking (as attacker)

    private static void onDamaged(LivingDamageEvent.Post e) {
        if (busy || e.getNewDamage() <= 0) return;
        LivingEntity v = e.getEntity();
        if (!(v.level() instanceof ServerLevel level)) return;
        DamageSource src = e.getSource();
        int vm = mask(v);
        if (Affix.THORNY.in(vm) && src.getEntity() instanceof LivingEntity a && src.getDirectEntity() == a && a != v && canHit(a)) {
            busy = true;
            try { a.hurt(level.damageSources().thorns(v), (float) (e.getNewDamage() * DangerInit.THORNS_SHARE.get())); } finally { busy = false; }
        }
        if (!(src.getEntity() instanceof Mob elite) || elite == v || !canHit(v)) return;
        int am = mask(elite);
        if (am == 0) return;
        if (Affix.FIERY.in(am)) v.igniteForSeconds(DangerInit.FIRE_SECONDS.get());
        if (Affix.FROZEN.in(am))
            v.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, DangerInit.FROZEN_TICKS.get(), DangerInit.FROZEN_LEVEL.get() - 1), elite);
        if (Affix.SHOCKING.in(am)) zap(level, elite, v);
    }

    /** Zaps the target, then jumps to the nearest other creature that is not a monster. No bolt, so no fire. */
    private static void zap(ServerLevel level, Mob elite, LivingEntity target) {
        float dmg = DangerInit.SHOCK_DAMAGE.get().floatValue();
        double r = DangerInit.SHOCK_RADIUS.get();
        LivingEntity next = null;
        double best = r * r;
        for (LivingEntity c : level.getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(r))) {
            if (c == elite || c == target || c instanceof Enemy || !canHit(c)) continue;
            double d = c.distanceToSqr(target);
            if (d <= best) { best = d; next = c; }
        }
        busy = true;
        try {
            target.invulnerableTime = 0; // it was just hit; the zap is a second, separate hit
            target.hurt(level.damageSources().lightningBolt(), dmg);
            sparks(level, elite.position().add(0, elite.getBbHeight() * 0.6, 0), target.position().add(0, target.getBbHeight() * 0.5, 0));
            if (next != null) {
                next.hurt(level.damageSources().lightningBolt(), dmg);
                sparks(level, target.position().add(0, target.getBbHeight() * 0.5, 0), next.position().add(0, next.getBbHeight() * 0.5, 0));
            }
        } finally {
            busy = false;
        }
    }

    private static void sparks(ServerLevel level, Vec3 a, Vec3 b) {
        int steps = Math.max(1, (int) Math.ceil(a.distanceTo(b) * 2));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t,
                    DangerInit.PARTICLE_COUNT.get(), 0.05, 0.05, 0.05, 0.02);
        }
    }

    // ------------------------------------------------------------------ particles

    private static void onTick(ServerTickEvent.Post e) {
        if (TRACKED.isEmpty() || e.getServer().getTickCount() % DangerInit.PARTICLE_INTERVAL.get() != 0) return;
        double range = DangerInit.PARTICLE_RANGE.get();
        int count = DangerInit.PARTICLE_COUNT.get();
        for (int i = TRACKED.size() - 1; i >= 0; i--) {
            Mob m = TRACKED.get(i);
            if (m.isRemoved()) { TRACKED.remove(i); continue; }
            if (!m.isAlive() || !(m.level() instanceof ServerLevel level) || !level.hasNearbyAlivePlayer(m.getX(), m.getY(), m.getZ(), range)) continue;
            int mask = mask(m);
            double w = m.getBbWidth() * 0.4, h = m.getBbHeight() * 0.3;
            for (Affix a : Affix.VALUES)
                if (a.in(mask)) level.sendParticles(a.particle, m.getX(), m.getY(0.55), m.getZ(), count, w, h, w, a.speed);
        }
    }

    // ------------------------------------------------------------------ loot

    private static void onDrops(LivingDropsEvent e) {
        LivingEntity dead = e.getEntity();
        if (mask(dead) == 0 || !(dead.level() instanceof ServerLevel level)) return;
        if (!(e.getSource().getEntity() instanceof ServerPlayer p) || (p.isFakePlayer() && !Drops.TEST_PLAYERS.contains(p.getUUID()))) return;
        int rank = dead.getPersistentData().getByte(TIER);
        List<? extends String> lw = DangerInit.LOOT_WEIGHTS.get();
        List<Double> w = ZoneMath.weights(lw.isEmpty() ? "1" : lw.get(Math.min(rank, lw.size() - 1)));
        RandomSource r = p.getRandom();
        DangerApi.Hooks hooks = DangerApi.hooks;
        int sigils = r.nextDouble() < hooks.extraSigilChance(p) ? 2 : 1;
        for (int i = 0; i < sigils; i++)
            Drops.grant(p, new SigilData(SigilType.values()[r.nextInt(SigilType.values().length)], rarity(w, r)), 1); // straight into the book
        if (r.nextDouble() < ZoneMath.at(DangerInit.CHARM_CHANCE.get(), rank, 0))
            Drops.grantCharm(p, CharmData.roll(com.habits.core.Specs.randomCategory(p, r), rarity(w, r), r)); // straight into the book
        if (r.nextDouble() < com.habits.core.CoreInit.RESPEC_ELITE_CHANCE.get())
            e.getDrops().add(new net.minecraft.world.entity.item.ItemEntity(level, dead.getX(), dead.getY(), dead.getZ(),
                    new net.minecraft.world.item.ItemStack(com.habits.core.CoreRegistries.RESPEC_SCROLL.get())));
        hooks.eliteKilled(p, Integer.bitCount(mask(dead)), rank == DangerInit.TIER_STARTS.get().size() || level.dimension() == net.minecraft.world.level.Level.END);
    }

    private static Rarity rarity(List<Double> w, RandomSource r) {
        return Rarity.values()[Math.min(Rarity.values().length - 1, PMath.pickWeighted(w, r.nextDouble()))];
    }
}
