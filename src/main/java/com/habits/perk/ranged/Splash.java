package com.habits.perk.ranged;

import com.habits.core.HabitsApi;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.jetbrains.annotations.Nullable;

/** Our own splash for Splash Zone, Field Medic and Harming Echo: vanilla's math with a radius, falloff and strength knob. */
final class Splash {
    private Splash() {}

    static int echoDepth;

    static boolean beneficial(PotionContents pc) {
        boolean any = false;
        for (MobEffectInstance i : pc.getAllEffects()) {
            if (i.getEffect().value().getCategory() != MobEffectCategory.BENEFICIAL) return false;
            any = true;
        }
        return any;
    }

    /**
     * Apply {@code effects} around {@code at}. {@code radius} replaces vanilla's 4; {@code falloff} 1 = vanilla edge
     * fade, 0 = full strength everywhere; {@code strength} scales durations and instant amounts. Returns mobs touched.
     */
    static int apply(ServerLevel level, @Nullable Entity source, @Nullable Entity owner, Vec3 at, Iterable<MobEffectInstance> effects,
                     double radius, double falloff, double strength, @Nullable Entity direct, @Nullable Entity spare) {
        int touched = 0;
        AABB box = new AABB(at, at).inflate(radius, radius * 0.5 + 1, radius);
        for (LivingEntity l : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (!l.isAffectedByPotions() || l == spare) continue;
            double d2 = l.distanceToSqr(at);
            if (d2 >= radius * radius) continue;
            double f = (l == direct ? 1 : 1 - falloff * Math.sqrt(d2) / radius) * strength;
            touched++;
            for (MobEffectInstance in : effects) {
                var eff = in.getEffect();
                if (eff.value().isInstantenous()) {
                    eff.value().applyInstantenousEffect(source, owner, l, in.getAmplifier(), f);
                } else {
                    int dur = in.mapDuration(t -> (int) (f * t + 0.5));
                    MobEffectInstance out = new MobEffectInstance(eff, dur, in.getAmplifier(), in.isAmbient(), in.isVisible());
                    if (!out.endsWithin(20)) l.addEffect(out, source != null ? source : owner);
                }
            }
        }
        return touched;
    }

    /** Harming Echo: a harming-splash kill splashes again from the body at half strength (chains limited per level). */
    static void onDeath(LivingDeathEvent e) {
        var src = e.getSource();
        if (!(src.is(DamageTypes.MAGIC) || src.is(DamageTypes.INDIRECT_MAGIC)) || !(src.getEntity() instanceof ServerPlayer p)) return;
        if (!(src.getDirectEntity() instanceof ThrownPotion) && echoDepth == 0) return;
        HabitsApi.PerkHit h = R.hit(p, "harming_echo");
        if (echoDepth == 0) R.fire(p, "harming_kill", e.getEntity().position());
        if (h == null || echoDepth >= R.p(h, "chain") || !R.roll(p, h.value())) return;
        LivingEntity dead = e.getEntity();
        ServerLevel level = (ServerLevel) dead.level();
        echoDepth++;
        try {
            // the thrower as source keeps the kill credited (and lets a kill chain); the thrower is spared
            apply(level, p, p, dead.position(), java.util.List.of(new MobEffectInstance(MobEffects.HARM, 1, 0)), 4, 1, 0.5, null, p);
            level.levelEvent(2007, dead.blockPosition(), 0xA9_23_23);
        } finally {
            echoDepth--;
        }
    }

    static PotionContents contents(ThrownPotion tp) {
        return tp.getItem().getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
    }
}
