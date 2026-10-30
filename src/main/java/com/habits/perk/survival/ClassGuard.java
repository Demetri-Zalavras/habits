package com.habits.perk.survival;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.PerkMechanic;
import com.habits.core.PerkMechanics;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Guardian class mechanics (skills in {@code habits_skills/class/guardian/}): Bulwark (shield wall, Counterblow), Oathsworn
 * (cover and Sanctuary), Unbroken (hit cap, no death behind a shield). Table in docs/mechanics/survival.md.
 */
public final class ClassGuard {
    private ClassGuard() {}

    static final ResourceLocation BULWARK = mech("bulwark", "% less damage with a shield up");
    static final ResourceLocation COUNTER = mech("counterblow", "% more damage on the hit after a block");
    static final ResourceLocation OATHSWORN = mech("oathsworn", "% less damage for you and allies near you");
    static final ResourceLocation SANCTUARY = mech("sanctuary", "health healed around you every few seconds");
    static final ResourceLocation UNBROKEN = mech("unbroken", "% of max health one hit can take at most");

    static final String COUNTER_AT = "counterAt";

    private static ResourceLocation mech(String name, String meaning) {
        ResourceLocation id = Habits.id("survival/" + name);
        PerkMechanics.register(id, (PerkMechanic) () -> meaning);
        return id;
    }

    static void init() {}

    /** FakePlayers never tick, so they can't raise a shield for real: GameTests mark them here. */
    static final java.util.Set<java.util.UUID> TEST_BLOCKING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static boolean blocking(Player p) {
        return p.isBlocking() || TEST_BLOCKING.contains(p.getUUID());
    }

    @Nullable
    static HabitsApi.PerkHit hit(Player p, ResourceLocation mech) {
        return Fx.acts(p) ? HabitsApi.mechanicHit(p, mech) : null;
    }

    // ================================================================== before armor

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void incoming(LivingIncomingDamageEvent e) {
        LivingEntity v = e.getEntity();
        if (v.level().isClientSide) return;
        DamageSource src = e.getSource();
        if (src.getEntity() instanceof Player a && src.getDirectEntity() == a) counterblow(e, a);
        if (src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        HabitsApi.PerkHit h;
        if (v instanceof Player p && blocking(p) && (h = hit(p, BULWARK)) != null) Defense.cut(e, h.value());
        if (!Guard.canBeAlly(v)) return;
        double best = v instanceof Player p && (h = hit(p, OATHSWORN)) != null ? h.value() : 0;
        for (Player g : v.level().players()) best = Math.max(best, cover(g, v));
        Defense.cut(e, best);
    }

    /** Oathsworn cover of {@code g} for ally {@code v}: the cut in %, 0 when out of range. */
    static double cover(Player g, LivingEntity v) {
        HabitsApi.PerkHit h;
        if (!Guard.ally(g, v) || (h = hit(g, OATHSWORN)) == null) return 0;
        double r = Sk.p(h, "radius");
        return g.distanceToSqr(v) <= r * r ? h.value() : 0;
    }

    /** Counterblow: the first melee hit within {@code window} seconds of a melee block hits harder, then it is spent. */
    static void counterblow(LivingIncomingDamageEvent e, Player a) {
        HabitsApi.PerkHit h = hit(a, COUNTER);
        long at = Sk.data(a).getLong(COUNTER_AT);
        if (h == null || at <= 0 || a.level().getGameTime() - at > Sk.p(h, "window") * 20) return;
        Sk.data(a).remove(COUNTER_AT);
        e.setAmount(e.getAmount() * (float) (1 + h.value() / 100));
        Guard.puff(e.getEntity(), ParticleTypes.CRIT, SoundEvents.PLAYER_ATTACK_STRONG);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void shield(LivingShieldBlockEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !e.getBlocked()) return;
        DamageSource src = e.getDamageSource();
        if (src.getDirectEntity() instanceof LivingEntity && hit(p, COUNTER) != null) Sk.data(p).putLong(COUNTER_AT, p.level().getGameTime());
    }

    /** Bulwark: no knockback while the shield is up. */
    @SubscribeEvent
    public static void knock(LivingKnockBackEvent e) {
        if (e.getEntity() instanceof Player p && !p.level().isClientSide && blocking(p) && hit(p, BULWARK) != null) e.setCanceled(true);
    }

    // ================================================================== after armor

    /** Unbroken: one hit takes at most value% of max health; behind a raised shield a killing blow leaves 1 health. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void pre(LivingDamageEvent.Pre e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || e.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        HabitsApi.PerkHit h = hit(p, UNBROKEN);
        if (h == null) return;
        e.setNewDamage(Math.min(e.getNewDamage(), p.getMaxHealth() * (float) (h.value() / 100)));
        if (blocking(p) && e.getNewDamage() >= p.getHealth()) {
            e.setNewDamage(Math.max(0, p.getHealth() - 1));
            Guard.puff(p, ParticleTypes.TOTEM_OF_UNDYING, SoundEvents.SHIELD_BLOCK);
        }
    }

    // ================================================================== ticks

    /** Sanctuary: every {@code interval} seconds you and hurt allies within {@code radius} heal. */
    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.level().isClientSide || (p.tickCount + p.getId()) % 20 != 0) return;
        HabitsApi.PerkHit h = hit(p, SANCTUARY);
        if (h == null || (p.tickCount / 20) % Math.max(1, Math.round(Sk.p(h, "interval"))) != 0) return;
        sanctuary(p, h);
    }

    static int sanctuary(Player p, HabitsApi.PerkHit h) {
        List<LivingEntity> hurt = new ArrayList<>(p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(Sk.p(h, "radius")),
                t -> Guard.ally(p, t) && t.getHealth() < t.getMaxHealth()));
        if (p.getHealth() < p.getMaxHealth()) hurt.add(p);
        int healed = 0;
        for (LivingEntity t : hurt) {
            t.heal((float) h.value());
            Guard.puff(t, ParticleTypes.HEART, SoundEvents.AMETHYST_BLOCK_CHIME);
            healed++;
        }
        return healed;
    }
}
