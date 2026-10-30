package com.habits.perk.nature;

import com.habits.core.HabitsApi.PerkHit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/** Nature class passives: Beastcaller's pack bond, Stormherald's storm call. */
public final class Callings {
    private Callings() {}

    /** Pets near you get Strength (value level) and Resistance (param {@code resist}, amplifier). */
    static void packBond(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 40 != 0) return;
        int str = (int) h.value() - 1, res = (int) Math.round(Nat.lerp(h, "resist", 0, 1));
        double r = Nat.lerp(h, "radius", 16, 32);
        // owner by UUID, not Wild.pets' isOwnedBy: that one needs the owner listed in the level
        for (TamableAnimal t : p.level().getEntitiesOfClass(TamableAnimal.class, p.getBoundingBox().inflate(r), t -> t.isAlive() && Wild.mine(t, p))) {
            Nat.effect(t, MobEffects.DAMAGE_BOOST, 60, str);
            Nat.effect(t, MobEffects.DAMAGE_RESISTANCE, 60, res);
        }
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post e) {
        if (e.getSource().getEntity() instanceof Player p && e.getEntity() != p) stormcall(p, e.getEntity());
    }

    /** While rain falls on you, a hit has value% chance to bring lightning down on the target (cooldown param). */
    static boolean stormcall(Player p, LivingEntity target) {
        PerkHit h = Nat.hit(p, "stormcall");
        if (h == null || !(p.level() instanceof ServerLevel l) || !target.isAlive()
                || !(l.isRainingAt(p.blockPosition()) || l.isRainingAt(p.blockPosition().above()))) return false;
        var d = Nat.data(p);
        if (l.getGameTime() < d.getLong("habits_stormcall") || !Nat.roll(l, h.value())) return false;
        d.putLong("habits_stormcall", l.getGameTime() + (long) (Nat.lerp(h, "cooldown", 3, 1) * 20));
        strike(l, p, target, h);
        return true;
    }

    /** The bolt: visual only (no fires, never hits you), damage and a short burn on the target. */
    static void strike(ServerLevel l, Player p, LivingEntity target, PerkHit h) {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(l);
        if (bolt != null) {
            bolt.moveTo(target.position());
            bolt.setVisualOnly(true);
            if (p instanceof ServerPlayer sp) bolt.setCause(sp);
            l.addFreshEntity(bolt);
        }
        target.hurt(l.damageSources().lightningBolt(), (float) Nat.lerp(h, "damage", 6, 14));
        target.igniteForSeconds(3);
    }
}
