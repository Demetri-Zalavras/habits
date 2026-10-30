package com.habits.perk.combat;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shared helpers: the per-player mechanic snapshot, level params, foe checks, guarded damage, particles, triggers. */
final class M {
    private M() {}

    record Snap(HabitsCollection col, EnumMap<Mech, HabitsApi.PerkHit> hits) {}

    /** Both sides touch it in singleplayer, so concurrent. Cleared on logout, server stop and datapack sync. */
    static final Map<UUID, Snap> SNAPS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Mech> BY_ID = new HashMap<>();

    static {
        for (Mech m : Mech.values()) BY_ID.put(m.id, m);
    }

    /** True while one of our mechanics deals damage, so the hit doesn't re-trigger melee mechanics. */
    static boolean dealing;

    /** Alive, not spectating, and a real player (fake players only when a GameTest let them through the trigger gate). */
    static boolean active(Player p) {
        return p.isAlive() && !p.isSpectator() && (!p.isFakePlayer() || Triggers.TEST_PLAYERS.contains(p.getUUID()));
    }

    /** The player's active skill for this mechanic (highest level wins), or null. O(1) after the first call per
     * collection change: the snapshot is rebuilt only when the collection object changes. */
    @Nullable
    static HabitsApi.PerkHit hit(Player p, Mech m) {
        if (!active(p)) return null;
        HabitsCollection col = HabitsApi.collection(p);
        Snap s = SNAPS.get(p.getUUID());
        if (s == null || s.col != col) {
            EnumMap<Mech, HabitsApi.PerkHit> map = new EnumMap<>(Mech.class);
            col.skills().forEach((id, prog) -> {
                if (prog.level() <= 0 || !prog.enabled()) return;
                SkillDef def = com.habits.progression.Effects.def(p, SkillRegistry.get(id)); // progression: sigil/charm boosts
                if (def == null || def.perk().mechanic().isEmpty() || !com.habits.core.Specs.allows(p, def)) return;
                Mech mech = BY_ID.get(def.perk().mechanic().get());
                if (mech == null) return;
                HabitsApi.PerkHit old = map.get(mech);
                if (old == null || prog.level() > old.level()) map.put(mech, new HabitsApi.PerkHit(def, prog.level(), def.perkValue(prog.level())));
            });
            s = new Snap(col, map);
            SNAPS.put(p.getUUID(), s);
        }
        return s.hits.get(m);
    }

    /** A level param: "x" constant, "a,b" linear from Lv1 to Lv10, or 10 comma values (one per level). */
    static double p(HabitsApi.PerkHit h, String key) {
        String[] a = h.skill().perk().param(key, "0").split(",");
        int lv = Math.max(1, Math.min(10, h.level()));
        if (a.length >= 10) return Double.parseDouble(a[lv - 1].trim());
        if (a.length == 2) {
            double lo = Double.parseDouble(a[0].trim()), hi = Double.parseDouble(a[1].trim());
            return lo + (hi - lo) * (lv - 1) / 9.0;
        }
        return Double.parseDouble(a[0].trim());
    }

    static boolean roll(Player p, double percent) {
        return p.getRandom().nextDouble() * 100 < percent;
    }

    static int ticks(double seconds) {
        return (int) Math.round(seconds * 20);
    }

    /** The player behind a direct melee hit (player_attack, dealt by the player's own hand), else null. */
    @Nullable
    static ServerPlayer melee(DamageSource src) {
        if (dealing || !src.is(DamageTypes.PLAYER_ATTACK)) return null;
        return src.getDirectEntity() instanceof ServerPlayer p && src.getEntity() == p && active(p) ? p : null;
    }

    static boolean boss(Entity e) {
        return e.getType().is(Tags.EntityTypes.BOSSES);
    }

    /** Something our area effects may hit: a hostile, or anything fighting or being fought by this player. Never players,
     * pets, armor stands or our own zombie allies. */
    static boolean foe(Player p, Entity e) {
        if (!(e instanceof LivingEntity le) || le == p || !le.isAlive() || le instanceof Player || le instanceof ArmorStand) return false;
        if (le instanceof TamableAnimal t && t.isTame()) return false;
        if (Allies.isAlly(le)) return false;
        return le instanceof Enemy || (le instanceof Mob m && m.getTarget() == p) || p.getLastHurtMob() == le;
    }

    /** Most mobs one area effect touches in one go; past this, only the nearest are hit (keeps a big pack cheap). */
    static final int MAX_AOE = 8;

    /** The nearest (up to {@link #MAX_AOE}) foes within {@code r} of {@code c}. One AABB query; callers keep r small. */
    static List<LivingEntity> foesNear(Player p, Vec3 c, double r, @Nullable Entity except) {
        double r2 = r * r;
        List<LivingEntity> out = p.level().getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(r),
                e -> e != except && foe(p, e) && e.distanceToSqr(c) <= r2);
        if (out.size() <= MAX_AOE) return out;
        out.sort((a, b) -> Double.compare(a.distanceToSqr(c), b.distanceToSqr(c)));
        return new java.util.ArrayList<>(out.subList(0, MAX_AOE));
    }

    static DamageSource src(Player p, ResourceKey<DamageType> type) {
        return new DamageSource(p.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type), p);
    }

    static DamageSource src(Player p, ResourceKey<DamageType> type, Entity direct) {
        return new DamageSource(p.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type), direct, p);
    }

    /** Deal damage without re-triggering melee mechanics; ignores the hurt cooldown when {@code pierce}. */
    static boolean hurt(LivingEntity t, DamageSource s, float amount, boolean pierce) {
        if (amount <= 0) return false;
        boolean was = dealing;
        dealing = true;
        try {
            if (pierce) t.invulnerableTime = 0;
            return t.hurt(s, amount);
        } finally {
            dealing = was;
        }
    }

    /** Same, but keeps the target's motion (damage over time must not shove). */
    static void hurtStill(LivingEntity t, DamageSource s, float amount) {
        Vec3 v = t.getDeltaMovement();
        hurt(t, s, amount, true);
        t.setDeltaMovement(v);
    }

    /** Capped particle burst; vanilla only sends it to players within 32 blocks. */
    static void fx(Entity at, ParticleOptions type, int count, double spread) {
        if (at.level() instanceof ServerLevel sl)
            sl.sendParticles(type, at.getX(), at.getY(0.5), at.getZ(), Math.min(count, 24), spread, spread, spread, 0.05);
    }

    static void fx(ServerLevel sl, ParticleOptions type, Vec3 c, int count, double spread) {
        sl.sendParticles(type, c.x, c.y, c.z, Math.min(count, 24), spread, spread, spread, 0.05);
    }

    /** At most 9 particles along a line. */
    static void line(ServerLevel sl, ParticleOptions type, Vec3 a, Vec3 b) {
        int n = (int) Math.min(8, Math.max(2, a.distanceTo(b)));
        for (int i = 0; i <= n; i++) {
            Vec3 c = a.lerp(b, i / (double) n);
            sl.sendParticles(type, c.x, c.y, c.z, 1, 0, 0, 0, 0);
        }
    }

    static ResourceLocation itemId(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem());
    }

    /** Fire a custom trigger (this package's event ids are listed in docs/mechanics/melee.md). */
    static void fire(Player p, String event, double amount, ItemStack item, @Nullable Entity victim) {
        fire(p, TriggerType.CUSTOM, event, amount, item, victim);
    }

    static void fire(Player p, TriggerType type, String event, double amount, @Nullable ItemStack item, @Nullable Entity victim) {
        Triggers.fire(p, type, new TriggerContext(Habits.id(event), amount, item == null ? null : itemId(item),
                victim == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()), victim == null ? null : victim.position()));
    }
}
