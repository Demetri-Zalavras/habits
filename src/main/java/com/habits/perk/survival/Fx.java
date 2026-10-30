package com.habits.perk.survival;

import com.habits.Habits;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Small shared helpers for the survival mechanics. */
final class Fx {
    private Fx() {}

    /** Live, real and acting: every mechanic is gated on this. */
    static boolean acts(Player p) {
        return p.isAlive() && !p.isSpectator();
    }

    static void fire(Player p, String event) {
        fire(p, event, null);
    }

    static void fire(Player p, String event, @Nullable Vec3 pos) {
        Triggers.fire(p, TriggerType.CUSTOM, TriggerContext.of(Habits.id(event)).withPos(pos));
    }

    /** A CUSTOM event with an amount, checked against the skill's {@code trigger.min}. */
    static void fire(Player p, String event, double amount) {
        Triggers.fire(p, TriggerType.CUSTOM, new TriggerContext(Habits.id(event), amount, null, null));
    }

    static void fireUnits(Player p, String event, double amount) {
        if (amount > 0) Triggers.fire(p, TriggerType.CUSTOM_UNITS, new TriggerContext(Habits.id(event), amount, null, null));
    }

    /** Add an effect for {@code seconds}; amp below 0 or no time means "none at this level". */
    static void effect(LivingEntity e, Holder<MobEffect> effect, double seconds, double amp) {
        if (amp < 0 || seconds <= 0) return;
        e.addEffect(new MobEffectInstance(effect, (int) Math.round(seconds * 20), (int) Math.round(amp)));
    }

    /** Make an attribute modifier match {@code amount} (0 removes it). Permanent so max health survives relogs. */
    static void attr(Player p, Holder<Attribute> attr, ResourceLocation id, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        AttributeModifier mod = inst.getModifier(id);
        if (amount == 0) {
            if (mod != null) inst.removeModifier(id);
            return;
        }
        if (mod != null && mod.amount() == amount) return;
        if (mod != null) inst.removeModifier(id);
        inst.addPermanentModifier(new AttributeModifier(id, amount, op));
    }

    /** Run on the next server tick (after the event that called us finished). */
    static void later(@Nullable MinecraftServer server, Runnable r) {
        com.habits.perk.Later.run(server, 1, r);
    }

    static void give(Player p, ItemStack stack) {
        if (!p.getInventory().add(stack)) p.drop(stack, false);
    }

    static Vec3 knockAway(LivingEntity from, LivingEntity target, double strength) {
        Vec3 d = target.position().subtract(from.position());
        if (d.lengthSqr() < 1.0E-4) d = new Vec3(1, 0, 0);
        target.knockback(strength, -d.x, -d.z);
        return d;
    }
}
