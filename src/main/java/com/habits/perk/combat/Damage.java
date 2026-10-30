package com.habits.perk.combat;

import com.habits.core.HabitsApi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Outgoing damage changes, applied in LivingIncomingDamageEvent (before armor). */
final class Damage {
    private Damage() {}

    static boolean weapon(ItemStack s) {
        return s.is(ItemTags.SWORDS) || s.is(ItemTags.AXES) || s.is(Items.MACE) || s.is(Items.TRIDENT);
    }

    /** More than 120 degrees off the mob's facing, seen from the mob. */
    static boolean behind(LivingEntity v, Entity attacker) {
        Vec3 facing = Vec3.directionFromRotation(0, v.getYHeadRot());
        double dx = attacker.getX() - v.getX(), dz = attacker.getZ() - v.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) return false;
        return (facing.x * dx + facing.z * dz) / len < -0.5; // cos 120
    }

    static float scale(LivingEntity v, DamageSource src, Entity owner, float amt) {
        Entity direct = src.getDirectEntity();
        // hit-back projectiles
        if (direct != null && direct.getTags().contains(Special.BATTED)) amt *= Math.max(1f, direct.getPersistentData().getFloat(Special.BATTED));
        amt = Warrior.exposed(v, amt);
        // pets on a target their owner marked
        if (owner instanceof TamableAnimal pet && pet.getOwner() instanceof ServerPlayer o && v.getTags().contains(OnHit.HUNTED)) {
            HabitsApi.PerkHit h = M.hit(o, Mech.PACK_TACTICS);
            if (h != null) {
                amt *= (float) (1 + h.value() / 100);
                if (M.roll(o, M.p(h, "crit"))) amt *= 1.5f;
            }
        }
        if (owner instanceof ServerPlayer op && op != v) {
            HabitsApi.PerkHit h = M.hit(op, Mech.DRAGONSLAYER);
            if (h != null) {
                double over = v.getBbHeight() - 2;
                if (over > 0) amt *= (float) (1 + (M.boss(v) ? Math.min(M.p(h, "boss_cap") / 100, h.value() / 100 * over) : h.value() / 100 * over));
            }
            if (direct instanceof AbstractArrow && (h = M.hit(op, Mech.WALL_SLAM)) != null)
                Hooks.KB.put(v, (float) (1 + M.p(h, "arrow_knockback") * 1.25));
        }
        ServerPlayer p = M.melee(src);
        if (p == null || p == v) return amt;
        PS s = PS.of(p);
        long now = Hooks.now(p);
        boolean swing = s.attackTick == now;
        boolean main = swing && v.getId() == s.mainTarget;
        float c = swing ? s.strength : 1, charge = 0.2f + c * c * 0.8f;
        ItemStack hand = p.getMainHandItem();
        HabitsApi.PerkHit h;

        // floors
        if (hand.isEmpty()) {
            if ((h = M.hit(p, Mech.BRAWLER)) != null) amt = Math.max(amt, (float) h.value() * charge);
        } else if (!weapon(hand) && (h = M.hit(p, Mech.IMPROVISER)) != null) amt = Math.max(amt, (float) h.value() * charge);

        // flat bonuses, main target only
        if (main) {
            if ((h = M.hit(p, Mech.BLOODLUST)) != null && now < s.bloodUntil) amt += (float) (s.bloodStacks * h.value()) * charge;
            if (s.sprintHit && (h = M.hit(p, Mech.CHARGE)) != null)
                amt += (float) Math.min(h.value(), s.sprintDist / Math.max(0.1, M.p(h, "blocks_per_damage")));
            if (p.getVehicle() != null && (h = M.hit(p, Mech.LANCER)) != null) {
                amt += (float) (h.value() * Math.min(1, s.vehicleSpeed / 0.6)) * charge;
                Hooks.KB.put(v, (float) M.p(h, "knockback"));
            }
            if (hand.is(ItemTags.SHOVELS) && (h = M.hit(p, Mech.HOME_RUN)) != null) Hooks.KB.put(v, (float) h.value());
        }

        amt = Warrior.scale(p, s, v, main, now, amt);
        amt = Classes.frenzy(p, amt);

        // multipliers
        if ((h = M.hit(p, Mech.EXECUTION)) != null && v.getHealth() < v.getMaxHealth() * M.p(h, "below") / 100)
            amt *= (float) (1 + h.value() / 100);
        if ((h = M.hit(p, Mech.BACKSTAB)) != null && behind(v, p)) amt *= (float) h.value();
        if ((h = M.hit(p, Mech.BERSERKER)) != null && p.getHealth() < p.getMaxHealth() * M.p(h, "below") / 100)
            amt *= (float) (1 + h.value() / 100);
        if ((h = M.hit(p, Mech.DUELIST)) != null && s.hostiles == 1) amt *= (float) (1 + h.value() / 100);
        if ((h = M.hit(p, Mech.CROWD_FIGHTER)) != null && s.crowd > 0)
            amt *= (float) (1 + Math.min(M.p(h, "cap"), h.value() * s.crowd) / 100);
        if ((h = M.hit(p, Mech.RAMPAGE)) != null && s.rampStacks > 0) amt *= (float) (1 + s.rampStacks * h.value() / 100);
        if ((h = M.hit(p, Mech.SKY_SWATTER)) != null && !v.onGround() && !v.isInWater() && !v.isPassenger()) amt *= (float) h.value();
        if (main && hand.isDamageableItem() && hand.getMaxDamage() - hand.getDamageValue() <= 2 && (h = M.hit(p, Mech.BREAKING_POINT)) != null) {
            amt *= (float) h.value();
            if (M.roll(p, M.p(h, "save"))) hand.setDamageValue(Math.max(0, hand.getMaxDamage() - 3)); // the hit costs 1 or 2
        }

        if (main) {
            s.fullHpHit = v.getHealth() >= v.getMaxHealth();
            s.fullHpTarget = v.getId();
        }
        return amt;
    }
}
