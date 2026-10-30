package com.habits.perk.survival;

import com.habits.core.HabitsApi;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * Effect rules: when an effect is about to land on a player, survival perks may shorten, lengthen, weaken, strengthen or
 * block it. Done by denying the original and adding a rewritten copy (MobEffectInstance has no setters).
 */
public final class Effects {
    private Effects() {}

    private static boolean rewriting;

    @SubscribeEvent
    public static void applicable(MobEffectEvent.Applicable e) {
        if (rewriting || !(e.getEntity() instanceof Player p) || p.level().isClientSide || !Sk.anyActive(p)) return;
        MobEffectInstance inst = e.getEffectInstance();
        MobEffectInstance out = rewrite(p, inst, e.getEffectSource());
        if (out == inst) return;
        e.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        if (out == null) return;
        rewriting = true;
        try {
            p.addEffect(out, e.getEffectSource());
        } finally {
            rewriting = false;
        }
    }

    /** The instance to apply instead: the same one (no change), a new one, or null (blocked). */
    static MobEffectInstance rewrite(Player p, MobEffectInstance inst, net.minecraft.world.entity.Entity source) {
        Holder<MobEffect> eff = inst.getEffect();
        double mult = 1;
        int amp = inst.getAmplifier();
        ItemStack using = p.isUsingItem() ? p.getUseItem() : ItemStack.EMPTY;
        boolean drinking = using.is(Items.POTION);

        if (eff.is(MobEffects.POISON)) {
            mult *= cutMult(Sk.ANTIVENOM.v(p));
            if (source instanceof Bee) mult *= cut(p, Sk.BEEKEEPERS_HIDE, "poison_cut");
            if (using.is(Items.SPIDER_EYE)) mult *= cut(p, Sk.SPIDER_SENSE, "poison_cut");
            if (using.is(Items.POISONOUS_POTATO) && Sk.roll(p, Sk.BAD_POTATO.v(p))) return null;
            if (using.is(Items.PUFFERFISH)) mult *= cutMult(Sk.PUFFER_PROOF.v(p));
        } else if (eff.is(MobEffects.HUNGER)) {
            if (using.is(ItemTags.MEAT) && isRaw(using) && Sk.roll(p, Sk.RAW_DEAL.v(p))) return null;
            if (using.is(Items.ROTTEN_FLESH) && Sk.roll(p, Sk.GHOUL_GUT.v(p))) return null;
            if (using.is(Items.PUFFERFISH) && Sk.PUFFER_PROOF.maxed(p)) return null;
            mult *= cutMult(Sk.HUNGER_PROOF.v(p));
        } else if (eff.is(MobEffects.CONFUSION)) {
            if (using.is(Items.PUFFERFISH) && Sk.PUFFER_PROOF.maxed(p)) return null;
        } else if (eff.is(MobEffects.WITHER)) {
            mult *= cutMult(Sk.WITHER_WARD.v(p));
        } else if (eff.is(MobEffects.DARKNESS)) {
            mult *= cut(p, Sk.SOUND_PROOF, "darkness_cut");
        } else if (eff.is(MobEffects.LEVITATION)) {
            mult *= cutMult(Math.max(Sk.BULLET_SURFER.v(p), Sk.GRAVITY_ANCHOR.v(p)));
        } else if (eff.is(MobEffects.MOVEMENT_SLOWDOWN)) {
            mult *= cutMult(Sk.STIFF_JOINTS.v(p));
            if (drinking && isTurtle(using)) {
                amp -= (int) Sk.SHELLBACK.v(p);
                if (amp < 0) return null;
            }
        } else if (eff.is(MobEffects.DAMAGE_RESISTANCE)) {
            HabitsApi.PerkHit h = Sk.SHELLBACK.hit(p);
            if (h != null && drinking && isTurtle(using)) amp = Math.min(3, amp + (int) Sk.p(h, "resist_bonus"));
        } else if (eff.is(MobEffects.DIG_SLOWDOWN)) {
            HabitsApi.PerkHit h = Sk.IRON_WILL.hit(p);
            if (h != null) {
                if (h.value() <= 0) return null;
                amp = Math.min(amp, (int) h.value() - 1);
                mult *= cutMult(Sk.p(h, "cut"));
            }
        } else if (eff.is(MobEffects.ABSORPTION)) {
            mult *= 1 + Sk.ABSORBER.v(p) / 100;
            if (using.is(Items.GOLDEN_APPLE)) {
                double hearts = Sk.GOLDEN_GLUTTON.v(p);
                // vanilla gives 2 hearts (amp 0); every 2 extra hearts is one more amplifier level (values are even)
                if (hearts > 0) amp += (int) (hearts / 2);
            }
        } else if (eff.is(MobEffects.HERO_OF_THE_VILLAGE)) {
            HabitsApi.PerkHit h = Sk.VILLAGE_DARLING.hit(p);
            if (h != null) {
                mult *= 1 + h.value() / 100;
                amp += (int) Sk.p(h, "amp");
            }
        } else if (eff.is(MobEffects.WEAKNESS)) {
            if (Sk.UNBOWED.maxed(p)) return null;
        } else if (eff.is(MobEffects.WATER_BREATHING)) {
            if (source == null && inst.getDuration() == 200 && p.getItemBySlot(EquipmentSlot.HEAD).is(Items.TURTLE_HELMET))
                mult *= 1 + Sk.TURTLE_SHELL.v(p) / 10; // 200 ticks = 10 s, value is extra seconds
        }
        if (drinking) mult *= 1 + Sk.LONG_BREW.v(p) / 100;
        if (using.is(Items.SUSPICIOUS_STEW)) mult *= 1 + Sk.SUS_CONNOISSEUR.v(p) / 100;
        if (Sk.data(p).getLong("totemTick") == p.level().getGameTime()) mult *= 1 + Sk.TOTEM_SAINT.v(p) / 100;

        if (eff.value().isInstantenous() || inst.isInfiniteDuration()) mult = 1;
        if (mult == 1 && amp == inst.getAmplifier()) return inst;
        int dur = (int) Math.round(inst.getDuration() * mult);
        if (dur <= 0) return null;
        return new MobEffectInstance(eff, dur, amp, inst.isAmbient(), inst.isVisible(), inst.showIcon());
    }

    static double cutMult(double pct) {
        return Math.max(0, 1 - pct / 100);
    }

    static double cut(Player p, Sk s, String param) {
        HabitsApi.PerkHit h = s.hit(p);
        return h == null ? 1 : cutMult(Sk.p(h, param));
    }

    static boolean isRaw(ItemStack s) {
        return s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.MUTTON) || s.is(Items.CHICKEN) || s.is(Items.RABBIT);
    }

    static boolean isTurtle(ItemStack potion) {
        var c = potion.get(DataComponents.POTION_CONTENTS);
        return c != null && (c.is(Potions.TURTLE_MASTER) || c.is(Potions.LONG_TURTLE_MASTER) || c.is(Potions.STRONG_TURTLE_MASTER));
    }

    @SubscribeEvent
    public static void added(MobEffectEvent.Added e) {
        if (e.getOldEffectInstance() != null || !(e.getEntity() instanceof Player p) || p.level().isClientSide) return;
        e.getEffectInstance().getEffect().unwrapKey().ifPresent(k -> Fx.fire(p, "effect_" + k.location().getPath()));
    }

    @SubscribeEvent
    public static void expired(MobEffectEvent.Expired e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || e.getEffectInstance() == null) return;
        if (e.getEffectInstance().is(MobEffects.WITHER)) {
            HabitsApi.PerkHit h = Sk.WITHER_WARD.hit(p);
            if (h != null) p.heal((float) Sk.p(h, "heal"));
        }
    }
}
