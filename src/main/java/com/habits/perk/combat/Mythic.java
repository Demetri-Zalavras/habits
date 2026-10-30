package com.habits.perk.combat;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.IdMatch;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import com.habits.core.TargetKind;
import com.habits.danger.Elites;
import com.mojang.serialization.Codec;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Mythical melee cards: Kingsbane, Wyrmheart, Untouchable and Quake Punch, plus their trigger events. Modded mobs
 * (Ice and Fire dragons, Cataclysm bosses) are matched by id and read by reflection: no compile dependency. */
public final class Mythic {
    private Mythic() {}

    static final ResourceLocation KINGSBANE_SKILL = Habits.id("kingsbane");
    /** GameTests only: a stand-in mob with this int is read as an Ice and Fire dragon of that stage. */
    static final String TEST_STAGE = "habits_test_dragon_stage";
    static final String TALLY = "habits_boss_tally";
    /** Health a player may lose to one Cataclysm boss and still count the kill as flawless (10 hearts). */
    static final float FLAWLESS_HP = 20;
    /** Wyrmheart's trigger: no other player this close to the kill. */
    static final double SOLO_RADIUS = 64;
    static final Set<EntityType<?>> QUARTET = Set.of(EntityType.WITHER, EntityType.ENDER_DRAGON, EntityType.WARDEN, EntityType.ELDER_GUARDIAN);

    private static final Codec<Set<ResourceLocation>> IDS = ResourceLocation.CODEC.listOf().xmap(HashSet::new, List::copyOf);
    static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Habits.MODID);
    /** Every boss type this player ever killed (Kingsbane). Kept through death. */
    static final Supplier<AttachmentType<Set<ResourceLocation>>> BOSSES = ATTACHMENTS.register("boss_kills",
            () -> AttachmentType.<Set<ResourceLocation>>builder(() -> new HashSet<>()).serialize(IDS).copyOnDeath().build());
    /** Quartet bosses killed this life (Kingsbane's trigger). Not copied on death, so dying resets it. */
    static final Supplier<AttachmentType<Set<ResourceLocation>>> QUARTET_LIFE = ATTACHMENTS.register("boss_quartet",
            () -> AttachmentType.<Set<ResourceLocation>>builder(() -> new HashSet<>()).serialize(IDS).build());

    // ---------------------------------------------------------------- what counts as a boss

    private static SkillDef bossDef;
    private static IdMatch bossMatch;

    /** {@code #c:bosses}, or an id in Kingsbane's {@code bosses} param (vanilla minibosses, Ice and Fire dragons). */
    static boolean boss(Entity e) {
        if (M.boss(e)) return true;
        SkillDef d = SkillRegistry.get(KINGSBANE_SKILL);
        if (d == null) return false;
        if (d != bossDef) { // re-parsed only after a reload
            bossMatch = IdMatch.parse(d.perk().param("bosses", ""));
            bossDef = d;
        }
        return bossMatch.test(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()), TargetKind.ENTITY, e.level().registryAccess());
    }

    // ---------------------------------------------------------------- Ice and Fire dragons

    private static final ClassValue<Method> STAGE = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> c) {
            try {
                return c.getMethod("getDragonStage");
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    };

    /** An Ice and Fire dragon's stage (1 to 5), or 0 for anything else. */
    static int dragonStage(Entity e) {
        if (e.level().getServer() instanceof GameTestServer && e.getPersistentData().contains(TEST_STAGE))
            return e.getPersistentData().getInt(TEST_STAGE);
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        if (!"iceandfire".equals(id.getNamespace()) || !id.getPath().endsWith("_dragon")) return 0;
        Method m = STAGE.get(e.getClass());
        if (m == null) return 0;
        try {
            return ((Number) m.invoke(e)).intValue();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- events

    /** Kingsbane on melee hits, Wyrmheart on anything you deal to an older dragon. After the other melee changes. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onIncoming(LivingIncomingDamageEvent e) {
        LivingEntity v = e.getEntity();
        if (v.level().isClientSide || !(e.getSource().getEntity() instanceof ServerPlayer p) || p == v || !M.active(p)) return;
        float amt = e.getAmount();
        HabitsApi.PerkHit h;
        if (M.melee(e.getSource()) == p && (h = M.hit(p, Mech.KINGSBANE)) != null)
            amt *= (float) (1 + Math.min(M.p(h, "cap"), h.value() * p.getData(BOSSES).size()) / 100);
        if ((h = M.hit(p, Mech.WYRMHEART)) != null) {
            int stage = dragonStage(v);
            if (stage > h.value()) amt *= (float) (1 + M.p(h, "older") / 100);
        }
        e.setAmount(amt);
    }

    /** Untouchable: one boss or elite hit takes at most a share of your max health (after armor). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPre(LivingDamageEvent.Pre e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getSource().getEntity() instanceof LivingEntity atk) || atk == p) return;
        HabitsApi.PerkHit h = M.hit(p, Mech.UNTOUCHABLE);
        if (h != null && (Elites.mask(atk) != 0 || boss(atk)))
            e.setNewDamage(Math.min(e.getNewDamage(), p.getMaxHealth() * (float) h.value() / 100));
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPost(LivingDamageEvent.Post e) {
        LivingEntity v = e.getEntity();
        if (v.level().isClientSide) return;
        float dmg = e.getNewDamage();
        if (dmg <= 0) return;
        if (v instanceof ServerPlayer pv && M.active(pv) && e.getSource().getEntity() instanceof LivingEntity atk && atk != pv && boss(atk)) {
            CompoundTag t = atk.getPersistentData().getCompound(TALLY);
            String k = pv.getStringUUID();
            t.putFloat(k, t.getFloat(k) + dmg);
            atk.getPersistentData().put(TALLY, t);
        }
        ServerPlayer p = M.melee(e.getSource());
        HabitsApi.PerkHit h;
        if (p != null && p != v && p.getMainHandItem().isEmpty() && (h = M.hit(p, Mech.QUAKE_PUNCH)) != null) quake(p, v, h, dmg);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDeath(LivingDeathEvent e) {
        LivingEntity v = e.getEntity();
        if (v.level().isClientSide || e.isCanceled()) return;
        ServerPlayer p = e.getSource().getEntity() instanceof ServerPlayer sp ? sp : v.getKillCredit() instanceof ServerPlayer kc ? kc : null;
        if (p == null || !M.active(p)) return;
        killed(p, v);
    }

    /** Wyrmheart: wild dragons up to the level's stage never pick you as a target. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onTarget(LivingChangeTargetEvent e) {
        if (!(e.getNewAboutToBeSetTarget() instanceof ServerPlayer p)) return;
        LivingEntity d = e.getEntity();
        if (d instanceof TamableAnimal t && t.isTame()) return;
        HabitsApi.PerkHit h = M.hit(p, Mech.WYRMHEART);
        if (h == null) return;
        int stage = dragonStage(d);
        if (stage > 0 && stage <= h.value()) e.setCanceled(true);
    }

    // ---------------------------------------------------------------- logic

    static void killed(ServerPlayer p, LivingEntity v) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(v.getType());
        boolean boss = boss(v);
        if (boss) p.getData(BOSSES).add(id);
        if (QUARTET.contains(v.getType())) {
            Set<ResourceLocation> q = p.getData(QUARTET_LIFE);
            q.add(id);
            if (q.size() >= QUARTET.size()) {
                q.clear();
                M.fire(p, "boss_quartet", QUARTET.size(), p.getMainHandItem(), v);
            }
        }
        int stage = dragonStage(v);
        if (stage > 0 && alone(p)) M.fire(p, "dragon_solo", stage, p.getMainHandItem(), v);
        if (boss) {
            float lost = v.getPersistentData().getCompound(TALLY).getFloat(p.getStringUUID());
            if (lost < FLAWLESS_HP) M.fire(p, "flawless_boss", lost, p.getMainHandItem(), v);
        }
    }

    static boolean alone(ServerPlayer p) {
        double r2 = SOLO_RADIUS * SOLO_RADIUS;
        for (Player o : p.level().players()) if (o != p && !o.isSpectator() && !o.isFakePlayer() && o.distanceToSqr(p) <= r2) return false; // machine fake players are not company
        return true;
    }

    /** Every n-th punch on a main target sends a shockwave around it; Lv10 stuns what it hits. */
    static void quake(ServerPlayer p, LivingEntity v, HabitsApi.PerkHit h, float dmg) {
        PS s = PS.of(p);
        if (s.attackTick != Hooks.now(p) || v.getId() != s.mainTarget) return;
        if (++s.quakePunches < M.p(h, "every")) return;
        s.quakePunches = 0;
        float wave = (float) (dmg * h.value() / 100);
        double r = M.p(h, "radius");
        int stun = M.ticks(M.p(h, "stun"));
        Tracked.later(() -> {
            ServerLevel sl = (ServerLevel) p.level();
            Vec3 c = v.position();
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                M.fx(sl, ParticleTypes.EXPLOSION, c.add(Math.cos(a) * r * 0.6, 0.1, Math.sin(a) * r * 0.6), 1, 0.05);
            }
            sl.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.5f, 1.4f);
            for (LivingEntity f : M.foesNear(p, c, r, v)) {
                M.hurt(f, p.damageSources().playerAttack(p), wave, false);
                if (stun > 0 && !M.boss(f)) OnHit.daze(f, stun);
            }
        });
    }
}
