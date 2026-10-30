package com.habits.perk.combat;

import com.habits.core.HabitsApi;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/** Warrior class passives (class books, 2026-10-13): Bloodreaver's Blood Frenzy and the Warlord's war banner. The other
 * class skills are stat perks or reuse melee mechanics (docs/mechanics/melee.md, "Warrior classes"). */
final class Classes {
    private Classes() {}

    /** Blood Frenzy: the player's own melee hit deals value% more per tenth of their health missing. */
    static float frenzy(ServerPlayer p, float amt) {
        HabitsApi.PerkHit h = M.hit(p, Mech.BLOOD_FRENZY);
        if (h == null) return amt;
        double missing = 1 - p.getHealth() / p.getMaxHealth();
        return missing <= 0 ? amt : amt * (float) (1 + h.value() * missing * 10 / 100);
    }

    /** War banner, once a second (an effect lasts 2 s): while any foe is within the radius, you and your allies there
     * (other players, your tamed pets) get Strength {@code strength} and Resistance {@code resistance}, and the foes get
     * Weakness {@code weakness} (levels, 0 = none). One box query, only for the class holder. */
    static void banner(ServerPlayer p) {
        if (p.tickCount % 20 != 0) return;
        HabitsApi.PerkHit h = M.hit(p, Mech.WARLORD);
        if (h == null) return;
        double r = h.value();
        List<LivingEntity> foes = new ArrayList<>(), allies = new ArrayList<>(List.of(p));
        for (LivingEntity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r), e -> e != p && e.distanceToSqr(p) <= r * r)) {
            if (M.foe(p, e)) foes.add(e);
            else if (e instanceof Player o && o.isAlive() && !o.isSpectator() || e instanceof TamableAnimal t && t.isOwnedBy(p)) allies.add(e);
        }
        if (foes.isEmpty()) return;
        for (LivingEntity a : allies) {
            give(a, MobEffects.DAMAGE_BOOST, M.p(h, "strength"));
            give(a, MobEffects.DAMAGE_RESISTANCE, M.p(h, "resistance"));
        }
        for (LivingEntity f : foes) give(f, MobEffects.WEAKNESS, M.p(h, "weakness"));
    }

    private static void give(LivingEntity e, Holder<MobEffect> effect, double level) {
        if (level >= 1) e.addEffect(new MobEffectInstance(effect, 40, (int) level - 1, true, false, true));
    }
}
