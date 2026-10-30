package com.habits.perk.movement;

import com.habits.Habits;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Small shared helpers. */
final class Mv {
    private Mv() {}

    /** Fake players allowed through (GameTests only). */
    static final Set<UUID> TEST_PLAYERS = new HashSet<>();

    /** Alive, not spectating, and not a fake player (unless a test registered it). */
    static boolean ok(Player p) {
        return p.isAlive() && !p.isSpectator() && (!(p instanceof FakePlayer) || TEST_PLAYERS.contains(p.getUUID()));
    }

    static ResourceLocation event(String name) {
        return Habits.id(name);
    }

    /** Fire a CUSTOM trigger {@code habits:<name>} (rolled once). */
    static void fire(Player p, String name) {
        Triggers.fire(p, TriggerType.CUSTOM, TriggerContext.of(event(name)));
    }

    /** Fire a CUSTOM trigger {@code habits:<name>} carrying {@code amount} (matched against the skill's {@code min}). */
    static void fire(Player p, String name, double amount) {
        Triggers.fire(p, TriggerType.CUSTOM, TriggerContext.of(event(name)).withAmount(amount));
    }

    /** Fire a CUSTOM_UNITS trigger {@code habits:<name>} with {@code amount} units. */
    static void fireUnits(Player p, String name, double amount) {
        if (amount > 0) Triggers.fire(p, TriggerType.CUSTOM_UNITS, TriggerContext.of(event(name)).withAmount(amount));
    }

    /** Add, update or remove (amount 0) this mechanic's transient modifier. Allocates only when the amount changes. */
    static void attr(LivingEntity e, Holder<Attribute> a, Mech m, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst == null) return;
        AttributeModifier cur = inst.getModifier(m.id);
        if (amount == 0) {
            if (cur != null) inst.removeModifier(m.id);
        } else if (cur == null || cur.amount() != amount || cur.operation() != op) {
            inst.addOrUpdateTransientModifier(new AttributeModifier(m.id, amount, op));
        }
    }

    static void attrAdd(LivingEntity e, Holder<Attribute> a, Mech m, double amount) {
        attr(e, a, m, round(amount), AttributeModifier.Operation.ADD_VALUE);
    }

    /** Percent of base value (25 = +25%). */
    static void attrPct(LivingEntity e, Holder<Attribute> a, Mech m, double pct) {
        attr(e, a, m, round(pct / 100.0), AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    }

    private static double round(double d) {
        return Math.round(d * 1000) / 1000.0;
    }

    /** The side a wall touches the player on (the direction pointing INTO the wall), or null. */
    @Nullable
    static Direction wall(Player p) {
        AABB bb = p.getBoundingBox().deflate(0, 0.2, 0);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!p.level().noCollision(p, bb.move(d.getStepX() * 0.2, 0, d.getStepZ() * 0.2))) return d;
        }
        return null;
    }

    /** Horizontal look direction, unit length. */
    static Vec3 flatLook(Player p) {
        Vec3 l = p.getLookAngle();
        Vec3 f = new Vec3(l.x, 0, l.z);
        return f.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : f.normalize();
    }

    /** Send new motion to the client (server authoritative velocity). */
    static void push(Player p, Vec3 v) {
        p.setDeltaMovement(v);
        p.hurtMarked = true;
        p.resetFallDistance();
    }

    static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable(key, args), true);
    }

    /** Block the player stands on (the one that affects movement). */
    static BlockPos below(Player p) {
        return p.getBlockPosBelowThatAffectsMyMovement();
    }

    static ServerPlayer sp(Player p) {
        return (ServerPlayer) p;
    }
}
