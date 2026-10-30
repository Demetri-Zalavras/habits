package com.habits.perk.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.ref.WeakReference;

/** Riding perks, plus the timed self-buffs (recovery run, lodestone warp, elytra repair). */
final class Mounts {
    private Mounts() {}

    static void second(ServerPlayer p, MoveState s, long t) {
        Entity v = p.getVehicle();
        LivingEntity mount = v instanceof LivingEntity le && v.getControllingPassenger() == p ? le : null;
        LivingEntity old = s.buffedMount == null ? null : (LivingEntity) s.buffedMount.get();
        if (old != null && old != mount) unbuff(s);
        if (mount != null) {
            Mv.attrPct(mount, Attributes.MOVEMENT_SPEED, Mech.HORSE_WHISPERER, mount instanceof Horse ? s.v(Mech.HORSE_WHISPERER) : 0);
            Mv.attrPct(mount, Attributes.JUMP_STRENGTH, Mech.HORSE_WHISPERER, mount instanceof Horse ? s.v(Mech.HORSE_WHISPERER) : 0);
            Mv.attrPct(mount, Attributes.MOVEMENT_SPEED, Mech.PIG_JOCKEY, mount instanceof Pig ? s.v(Mech.PIG_JOCKEY) : 0);
            Mv.attrPct(mount, Attributes.MOVEMENT_SPEED, Mech.LAVA_STRIDER, mount instanceof Strider ? s.v(Mech.LAVA_STRIDER) : 0);
            Mv.attrPct(mount, Attributes.MOVEMENT_SPEED, Mech.OUTRIDER, s.v(Mech.OUTRIDER));
            Mv.attrPct(mount, Attributes.JUMP_STRENGTH, Mech.OUTRIDER, s.v(Mech.OUTRIDER));
            s.buffedMount = new WeakReference<>(mount);
            if (s.has(Mech.SADDLE_UP) && t % 100 < 20) mount.heal((float) s.p(Mech.SADDLE_UP, "regen"));
            if (mount instanceof Camel camel && s.has(Mech.CAMEL_CARAVAN)) camelRecharge(camel, s.v(Mech.CAMEL_CARAVAN));
            if (mount instanceof Strider && fireProof(s)) {
                mount.clearFire();
                p.clearFire();
            }
        }
        if (s.has(Mech.CAPTAIN) && v instanceof Boat boat && boat.getControllingPassenger() == p) {
            for (Entity pass : boat.getPassengers())
                if (pass != p && pass instanceof LivingEntity le) {
                    le.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 60, 0, true, false, true));
                    le.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 60, 0, true, false, true));
                }
        }
        if (s.has(Mech.FLIGHT_DECK) && p.onGround() && !p.isPassenger() && s.secondDist > 0) repairElytra(p, s);
    }

    static boolean fireProof(MoveState s) {
        return s.has(Mech.LAVA_STRIDER) && s.lv(Mech.LAVA_STRIDER) >= s.p(Mech.LAVA_STRIDER, "fire_level");
    }

    static void unbuff(MoveState s) {
        LivingEntity old = s.buffedMount == null ? null : (LivingEntity) s.buffedMount.get();
        s.buffedMount = null;
        if (old == null) return;
        Mv.attrPct(old, Attributes.MOVEMENT_SPEED, Mech.HORSE_WHISPERER, 0);
        Mv.attrPct(old, Attributes.JUMP_STRENGTH, Mech.HORSE_WHISPERER, 0);
        Mv.attrPct(old, Attributes.MOVEMENT_SPEED, Mech.PIG_JOCKEY, 0);
        Mv.attrPct(old, Attributes.MOVEMENT_SPEED, Mech.LAVA_STRIDER, 0);
        Mv.attrPct(old, Attributes.MOVEMENT_SPEED, Mech.OUTRIDER, 0);
        Mv.attrPct(old, Attributes.JUMP_STRENGTH, Mech.OUTRIDER, 0);
    }

    /** Camel dash cooldown ticks down {@code 1/(1-v%)} times as fast (vanilla: 1 per tick). Called once a second. */
    static void camelRecharge(Camel camel, double pct) {
        int extra = (int) Math.round(20 * (1 / (1 - Math.min(95, pct) / 100) - 1));
        if (camel.dashCooldown > 0) camel.dashCooldown = Math.max(0, camel.dashCooldown - extra);
    }

    static void repairElytra(ServerPlayer p, MoveState s) {
        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA) || !chest.isDamaged()) return;
        s.walkedForRepair += s.secondDist;
        double per = 100 / Math.max(0.1, s.v(Mech.FLIGHT_DECK));
        int fix = (int) (s.walkedForRepair / per);
        if (fix <= 0) return;
        s.walkedForRepair -= fix * per;
        chest.setDamageValue(Math.max(0, chest.getDamageValue() - fix));
    }

    /** Recovery Run: Speed until you reach your death spot or time runs out. Returns the amplifier, or -1 when done. */
    static int recovery(ServerPlayer p, MoveState s, long t) {
        BlockPos to = s.recoverTarget;
        if (to == null || t > s.recoverUntil || to.distSqr(p.blockPosition()) < 64) {
            s.recoverTarget = null;
            return -1;
        }
        return Math.max(0, (int) s.v(Mech.RECOVERY_RUN) - 1);
    }

    /** Lodestone Link channel: stand still; moving or getting hurt breaks it. */
    static void channel(ServerPlayer p, MoveState s, long t) {
        long now = p.level().getGameTime();
        if (s.channelFrom == null || s.channelTarget == null || p.position().distanceToSqr(s.channelFrom) > 0.36) {
            s.channelUntil = 0;
            Mv.say(p, "habits.movement.channel_broken");
            return;
        }
        if (t % 5 == 0) p.serverLevel().sendParticles(ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1, p.getZ(), 6, 0.4, 0.6, 0.4, 0.02);
        if (now < s.channelUntil) return;
        s.channelUntil = 0;
        BlockPos b = s.channelTarget;
        p.teleportTo(b.getX() + 0.5, b.getY() + 1, b.getZ() + 0.5);
        p.resetFallDistance();
        p.getData(MoveData.TYPE).lodestoneReadyAt = now + Math.round(s.p(Mech.LODESTONE_LINK, "cooldown") * 60 * 20);
        p.level().playSound(null, b, net.minecraft.sounds.SoundEvents.LODESTONE_COMPASS_LOCK, net.minecraft.sounds.SoundSource.PLAYERS, 1, 1);
    }
}
