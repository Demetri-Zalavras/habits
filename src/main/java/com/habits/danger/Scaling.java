package com.habits.danger;

import com.habits.Habits;
import com.habits.core.IdMatch;
import com.habits.core.TargetKind;
import com.habits.progression.ProgressionInit;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.List;

/**
 * Zone scaling: a hostile mob or boss gets permanent health and attack damage modifiers once, when it first joins a level
 * (the {@link #MARK} key in its persistent data stops a reload from stacking them). Melee goes through the attack damage
 * attribute; every other hit from a scaled mob (arrows, fireballs, explosions, fangs) is multiplied as it lands.
 * Rolls elites ({@link Elites}) at the same moment.
 */
public final class Scaling {
    private Scaling() {}

    /** Persistent data key: the mob's damage multiplier; present = already scaled. */
    public static final String MARK = "habits_danger";
    /** On the GameTest server only mobs with this tag are scaled, so other packages' tests see vanilla mobs. */
    public static final String TEST_TAG = "habits_danger_test";
    /** GameTests: persistent data key, the distance from spawn to scale a tagged mob as if it stood there. */
    public static final String TEST_DIST = "habits_danger_test_dist";
    static final ResourceLocation HEALTH_ID = Habits.id("danger_health"), DAMAGE_ID = Habits.id("danger_damage");

    private static List<? extends String> excludeFrom;
    private static @org.jetbrains.annotations.Nullable IdMatch exclude;

    static void init(IEventBus bus) {
        bus.addListener(Scaling::onJoin);
        bus.addListener(Scaling::onIncoming);
    }

    public static boolean isBoss(LivingEntity e) {
        return e.getType().is(Tags.EntityTypes.BOSSES) || e.getMaxHealth() >= ProgressionInit.BOSS_HEALTH.get();
    }

    private static boolean excluded(LivingEntity e, ServerLevel level) {
        List<? extends String> src = DangerInit.EXCLUDE.get();
        if (src.isEmpty()) return false;
        if (src != excludeFrom) {
            exclude = IdMatch.parse(String.join(",", src));
            excludeFrom = src;
        }
        return exclude != null && exclude.test(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()), TargetKind.ENTITY, level.registryAccess());
    }

    private static void onJoin(EntityJoinLevelEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof LivingEntity mob) || mob instanceof Player) return;
        if (mob.getPersistentData().contains(MARK)) {
            if (mob instanceof Mob m && Elites.mask(m) != 0) Elites.track(m);
            return;
        }
        boolean boss = isBoss(mob);
        if (!(mob instanceof Enemy) && !boss) return;
        if (level.getServer() instanceof GameTestServer && !mob.getTags().contains(TEST_TAG)) return;
        if (excluded(mob, level)) return;
        Zones.Zone z = level.getServer() instanceof GameTestServer && mob.getPersistentData().contains(TEST_DIST)
                ? Zones.of(level.dimension().location(), mob.getPersistentData().getDouble(TEST_DIST)) : Zones.at(level, mob.getX(), mob.getZ());
        boolean elite = !boss && mob instanceof Mob m && !m.hasCustomName() && mob.getRandom().nextDouble() < DangerInit.ELITE_CHANCE.get();
        apply(mob, z.health() * (elite ? DangerInit.ELITE_HEALTH.get() : 1), z.damage());
        if (elite) Elites.make((Mob) mob, Elites.roll(mob.getRandom(), z.rank()), z.rank());
    }

    /** Scales once: false (and no change) when the mob already carries the mark. Keeps the health ratio. */
    public static boolean apply(LivingEntity e, double health, double damage) {
        CompoundTag tag = e.getPersistentData();
        if (tag.contains(MARK)) return false;
        tag.putFloat(MARK, (float) damage);
        AttributeInstance h = e.getAttribute(Attributes.MAX_HEALTH);
        if (h != null && health != 1) {
            float ratio = e.getHealth() / e.getMaxHealth();
            h.addPermanentModifier(new AttributeModifier(HEALTH_ID, health - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            e.setHealth(e.getMaxHealth() * ratio);
        }
        AttributeInstance d = e.getAttribute(Attributes.ATTACK_DAMAGE);
        if (d != null && damage != 1)
            d.addPermanentModifier(new AttributeModifier(DAMAGE_ID, damage - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        return true;
    }

    /** The damage multiplier of a scaled mob, 0 when unscaled. */
    static float damageMult(Entity e) {
        return e.getPersistentData().getFloat(MARK);
    }

    private static void onIncoming(LivingIncomingDamageEvent e) {
        DamageSource src = e.getSource();
        if (Elites.busy || !(src.getEntity() instanceof LivingEntity a)) return;
        float m = damageMult(a);
        if (m <= 0 || m == 1) return;
        double cut = e.getEntity() instanceof Player v ? Math.max(0, Math.min(1, DangerApi.hooks.scalingCut(v))) : 0;
        float want = (float) (1 + (m - 1) * (1 - cut));
        boolean melee = src.is(DamageTypes.MOB_ATTACK) || src.is(DamageTypes.MOB_ATTACK_NO_AGGRO);
        if (!melee || a.getAttribute(Attributes.ATTACK_DAMAGE) == null) e.setAmount(e.getAmount() * want);
        else if (cut > 0) e.setAmount(e.getAmount() * want / m); // melee already carries m through the attack damage attribute
    }
}
