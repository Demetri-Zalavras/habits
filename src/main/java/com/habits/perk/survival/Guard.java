package com.habits.perk.survival;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Guardian spec batch (skills in {@code habits_skills/guardian/}): shield play, covering allies, clutch saves. An ally is
 * another living player or a pet you own. Card events read their radius/window from the dropping skill's params
 * ({@link #tp}); thresholds use the skill's {@code trigger.min}.
 */
public final class Guard {
    private Guard() {}

    /** True while we hand damage to a guardian, so the hit we deal never gets redirected again. */
    private static boolean redirecting;

    static final String LAST_HIT = "guardHit";

    /** A trigger param of a guardian skill at Lv1 (0 when the skill is missing). */
    static double tp(String skill, String key) {
        SkillDef d = SkillRegistry.get(Habits.id("guardian/" + skill));
        return d == null ? 0 : Sk.parse(d.perk().param(key, "0"), 1);
    }

    static boolean ally(Player g, Entity v) {
        if (v == g) return false;
        if (v instanceof Player o) return Fx.acts(o);
        return v instanceof OwnableEntity o && g.getUUID().equals(o.getOwnerUUID());
    }

    static boolean canBeAlly(Entity v) {
        return v instanceof Player || v instanceof OwnableEntity o && o.getOwnerUUID() != null;
    }

    /** % of max health missing. */
    static double missing(LivingEntity e) {
        return 100 * (1 - e.getHealth() / Math.max(1, e.getMaxHealth()));
    }

    static boolean ready(Player p, String key) {
        return p.level().getGameTime() >= Sk.data(p).getLong(key);
    }

    static void cooldown(Player p, String key, double seconds) {
        Sk.data(p).putLong(key, p.level().getGameTime() + (long) (seconds * 20));
    }

    static void puff(Entity at, ParticleOptions particle, SoundEvent sound) {
        if (!(at.level() instanceof ServerLevel l)) return;
        l.sendParticles(particle, at.getX(), at.getY() + 1, at.getZ(), 8, 0.4, 0.5, 0.4, 0.05);
        l.playSound(null, at.getX(), at.getY(), at.getZ(), sound, SoundSource.PLAYERS, 0.8f, 1.2f);
    }

    static boolean hasShield(Player p) {
        return p.getOffhandItem().getItem() instanceof ShieldItem || p.getMainHandItem().getItem() instanceof ShieldItem;
    }

    // ================================================================== before armor

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void incoming(LivingIncomingDamageEvent e) {
        LivingEntity v = e.getEntity();
        if (v.level().isClientSide) return;
        DamageSource src = e.getSource();
        if (v instanceof Player p && Fx.acts(p) && Sk.anyActive(p)) {
            if (aegis(e, p, src)) return;
            HabitsApi.PerkHit rest = Sk.RESTED_GUARD.hit(p);
            if (rest != null && p.level().getGameTime() - Sk.data(p).getLong(LAST_HIT) >= Sk.p(rest, "quiet") * 20)
                Defense.cut(e, rest.value());
        }
        if (redirecting || !canBeAlly(v) || src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        for (Player g : v.level().players()) cover(e, g, v, src);
    }

    /** Phalanx and Bodyguard of guardian {@code g} for a hit on {@code v}. */
    static void cover(LivingIncomingDamageEvent e, Player g, LivingEntity v, DamageSource src) {
        if (!ally(g, v) || !Fx.acts(g) || !Sk.anyActive(g)) return;
        double d2 = g.distanceToSqr(v);
        HabitsApi.PerkHit ph = Sk.PHALANX.hit(g);
        if (ph != null && g.isBlocking() && d2 <= ph.value() * ph.value())
            e.setAmount(e.getAmount() * (float) (1 - Sk.p(ph, "cut") / 100));
        HabitsApi.PerkHit bg = Sk.BODYGUARD.hit(g);
        double r = bg == null ? 0 : Sk.p(bg, "radius");
        if (bg != null && src.getEntity() != g && d2 <= r * r && e.getAmount() > 0) {
            float share = e.getAmount() * (float) Math.min(1, bg.value() / 100);
            e.setAmount(e.getAmount() - share);
            redirecting = true;
            try { g.hurt(src, share); } finally { redirecting = false; }
        }
    }

    /** Aegis: a shield you carry but don't raise blocks one hit from a mob or projectile, then cools down. */
    static boolean aegis(LivingIncomingDamageEvent e, Player p, DamageSource src) {
        HabitsApi.PerkHit h = Sk.AEGIS.hit(p);
        if (h == null || p.isBlocking() || src.getEntity() == null && src.getDirectEntity() == null
                || src.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || !hasShield(p) || !ready(p, "aegisReady")) return false;
        cooldown(p, "aegisReady", h.value());
        EquipmentSlot slot = p.getOffhandItem().getItem() instanceof ShieldItem ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
        p.getItemBySlot(slot).hurtAndBreak(Math.max(1, (int) Math.ceil(e.getAmount())), p, slot);
        e.setCanceled(true);
        puff(p, ParticleTypes.ENCHANTED_HIT, SoundEvents.SHIELD_BLOCK);
        return true;
    }

    // ================================================================== after armor

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void pre(LivingDamageEvent.Pre e) {
        LivingEntity v = e.getEntity();
        if (v.level().isClientSide) return;
        if (v instanceof Player p && Sk.anyActive(p)) {
            HabitsApi.PerkHit h = Sk.SHOCK_ABSORBER.hit(p);
            if (h != null) {
                float cap = p.getMaxHealth() * (float) (h.value() / 100);
                if (e.getNewDamage() > cap) e.setNewDamage(cap + (e.getNewDamage() - cap) * (float) (1 - Sk.p(h, "cut") / 100));
            }
        }
        if (redirecting || e.getNewDamage() < v.getHealth() || !canBeAlly(v) || e.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        for (Player g : v.level().players()) if (angel(e, g, v)) return;
    }

    /** Guardian Angel of {@code g}: a killing blow on ally {@code v} leaves it at 1 health and {@code g} takes a share. */
    static boolean angel(LivingDamageEvent.Pre e, Player g, LivingEntity v) {
        HabitsApi.PerkHit h;
        if (!ally(g, v) || !Fx.acts(g) || !Sk.anyActive(g) || (h = Sk.GUARDIAN_ANGEL.hit(g)) == null) return false;
        double r = Sk.p(h, "radius");
        if (g.distanceToSqr(v) > r * r || !ready(g, "angelReady")) return false;
        cooldown(g, "angelReady", h.value() * 60);
        float took = e.getNewDamage();
        e.setNewDamage(Math.max(0, v.getHealth() - 1));
        puff(v, ParticleTypes.TOTEM_OF_UNDYING, SoundEvents.TOTEM_USE);
        redirecting = true;
        try { g.hurt(g.damageSources().magic(), took * (float) (Sk.p(h, "share") / 100)); } finally { redirecting = false; }
        return true;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void post(LivingDamageEvent.Post e) {
        LivingEntity v = e.getEntity();
        if (v.level().isClientSide || e.getNewDamage() <= 0 || !v.isAlive()) return;
        long now = v.level().getGameTime();
        if (v instanceof Player p && Fx.acts(p)) {
            CompoundTag d = Sk.data(p);
            long last = d.getLong(LAST_HIT);
            if (last > 0) Fx.fire(p, "ambushed", (now - last) / 20.0);
            d.putLong(LAST_HIT, now);
            Fx.fire(p, "big_hit", e.getNewDamage());
            if (Sk.anyActive(p)) {
                rally(p);
                flask(p);
            }
        }
        if (!(e.getSource().getEntity() instanceof Enemy) || !canBeAlly(v)) return;
        double hurtR = tp("bodyguard", "notice"), callR = tp("guardian_angel", "notice");
        for (Player g : v.level().players()) {
            if (!ally(g, v) || !Fx.acts(g)) continue;
            double d2 = g.distanceToSqr(v);
            if (d2 <= hurtR * hurtR) Fx.fire(g, "ally_hurt_nearby");
            if (d2 <= callR * callR) Fx.fire(g, "ally_close_call", missing(v));
        }
    }

    /** Rallying Cry: dropping low buffs you and allies around you. */
    static void rally(Player p) {
        HabitsApi.PerkHit h = Sk.RALLYING_CRY.hit(p);
        if (h == null || p.getHealth() > Sk.p(h, "threshold") || !ready(p, "rallyReady")) return;
        cooldown(p, "rallyReady", Sk.p(h, "cooldown"));
        double secs = Sk.p(h, "seconds");
        for (LivingEntity t : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(h.value()), t -> t == p || ally(p, t))) {
            Fx.effect(t, MobEffects.DAMAGE_RESISTANCE, secs, Sk.p(h, "resist_amp"));
            Fx.effect(t, MobEffects.DAMAGE_BOOST, secs, Sk.p(h, "strength_amp"));
        }
        puff(p, ParticleTypes.ANGRY_VILLAGER, SoundEvents.RAVAGER_ROAR);
    }

    /** Emergency Flask: dropping low drinks the first healing or regeneration potion in your inventory. */
    static void flask(Player p) {
        HabitsApi.PerkHit h = Sk.EMERGENCY_FLASK.hit(p);
        if (h == null || p.getHealth() > Sk.p(h, "threshold") || !ready(p, "flaskReady")) return;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            PotionContents c = s.is(Items.POTION) ? s.get(DataComponents.POTION_CONTENTS) : null;
            if (c == null || !healing(c)) continue;
            cooldown(p, "flaskReady", h.value());
            for (MobEffectInstance m : c.getAllEffects()) {
                if (m.getEffect().value().isInstantenous()) m.getEffect().value().applyInstantenousEffect(p, p, p, m.getAmplifier(), 1);
                else p.addEffect(new MobEffectInstance(m));
            }
            s.shrink(1);
            Fx.give(p, new ItemStack(Items.GLASS_BOTTLE));
            puff(p, ParticleTypes.HAPPY_VILLAGER, SoundEvents.GENERIC_DRINK);
            return;
        }
    }

    static boolean healing(PotionContents c) {
        for (MobEffectInstance m : c.getAllEffects())
            if (m.getEffect().is(MobEffects.HEAL) || m.getEffect().is(MobEffects.REGENERATION)) return true;
        return false;
    }

    // ================================================================== shields

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void shield(LivingShieldBlockEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !e.getBlocked() || !Fx.acts(p)) return;
        long now = p.level().getGameTime();
        Fx.fire(p, "guard_hurt", missing(p));
        double crowd = tp("provoke", "notice");
        Fx.fire(p, "block_surrounded", p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(crowd), m -> m instanceof Enemy).size());
        double cover = tp("phalanx", "notice");
        if (!p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(cover), t -> ally(p, t)).isEmpty())
            Fx.fire(p, "block_for_ally");
        CompoundTag d = Sk.data(p);
        if (now - d.getLong("streakStart") > tp("aegis", "streak_window") * 20) { d.putLong("streakStart", now); d.putInt("streak", 0); }
        d.putInt("streak", d.getInt("streak") + 1);
        Fx.fire(p, "block_streak", d.getInt("streak"));
        if (!Sk.anyActive(p)) return;
        double patch = Sk.PATCH_UP.v(p);
        if (patch > 0) p.heal(e.getBlockedDamage() * (float) (patch / 100));
    }

    // ================================================================== using items

    @SubscribeEvent
    public static void useStart(LivingEntityUseItemEvent.Start e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !Fx.acts(p)) return;
        ItemStack s = e.getItem();
        if (s.is(Items.POTION)) Fx.fire(p, "drink_hurt", missing(p));
        if (!Sk.anyActive(p)) return;
        if (s.getItem() instanceof ShieldItem) {
            if (p.isShiftKeyDown()) provoke(p);
            else if (p.isSprinting()) rush(p);
        } else if (s.has(DataComponents.FOOD)) {
            HabitsApi.PerkHit h = Sk.IRON_RATIONS.hit(p);
            if (h != null && inFight(p, Sk.p(h, "fight_seconds"))) Food.cutDuration(e, h.value());
        }
    }

    static boolean inFight(Player p, double seconds) {
        long last = Sk.data(p).getLong(LAST_HIT);
        return last > 0 && p.level().getGameTime() - last <= seconds * 20;
    }

    /** Provoke: sneak and raise a shield, monsters around you come for you. */
    static void provoke(Player p) {
        HabitsApi.PerkHit h = Sk.PROVOKE.hit(p);
        if (h == null || !ready(p, "provokeReady")) return;
        cooldown(p, "provokeReady", Sk.p(h, "cooldown"));
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(h.value()), m -> m instanceof Enemy && m.isAlive()))
            m.setTarget(p);
        puff(p, ParticleTypes.ANGRY_VILLAGER, SoundEvents.RAVAGER_ROAR);
    }

    /** Shield Rush: raise a shield while sprinting to lunge forward and knock mobs in front of you back. */
    static void rush(Player p) {
        HabitsApi.PerkHit h = Sk.SHIELD_RUSH.hit(p);
        if (h == null || !ready(p, "rushReady")) return;
        cooldown(p, "rushReady", Sk.p(h, "cooldown"));
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1.0E-4) return;
        flat = flat.normalize();
        p.setDeltaMovement(p.getDeltaMovement().add(flat.scale(h.value())));
        p.hurtMarked = true;
        AABB box = p.getBoundingBox().expandTowards(flat.scale(Sk.p(h, "reach"))).inflate(0.5);
        float dmg = (float) Sk.p(h, "damage");
        double knock = Sk.p(h, "knock");
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, box, m -> m.isAlive() && !ally(p, m))) {
            m.hurt(p.damageSources().playerAttack(p), dmg);
            m.knockback(knock, -flat.x, -flat.z);
        }
        puff(p, ParticleTypes.CLOUD, SoundEvents.PLAYER_ATTACK_KNOCKBACK);
    }

    @SubscribeEvent
    public static void useFinish(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !Fx.acts(p)) return;
        FoodProperties food = e.getItem().get(DataComponents.FOOD);
        if (food == null) return;
        double miss = missing(p);
        Fx.fire(p, "eat_hurt", miss);
        if (!p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(tp("potluck", "notice")), t -> ally(p, t)).isEmpty())
            Fx.fire(p, "eat_near_ally");
        if (inFight(p, tp("iron_rations", "fight_seconds"))) Fx.fire(p, "eat_in_fight");
        if (!Sk.anyActive(p)) return;
        HabitsApi.PerkHit hearty = Sk.HEARTY_MEAL.hit(p);
        if (hearty != null && miss >= Sk.p(hearty, "below")) p.heal((float) hearty.value());
        HabitsApi.PerkHit pot = Sk.POTLUCK.hit(p);
        if (pot != null) {
            float heal = food.nutrition() * (float) (Sk.p(pot, "share") / 100);
            for (LivingEntity t : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(pot.value()), t -> ally(p, t))) {
                t.heal(heal);
                puff(t, ParticleTypes.HEART, SoundEvents.GENERIC_EAT);
            }
        }
    }

    // ================================================================== other

    /** Transfusion: sneak and right-click an ally with an empty hand to give them some of your health. */
    @SubscribeEvent
    public static void interact(PlayerInteractEvent.EntityInteract e) {
        Player p = e.getEntity();
        if (p.level().isClientSide || e.getHand() != InteractionHand.MAIN_HAND || !p.isShiftKeyDown() || !p.getMainHandItem().isEmpty()
                || !Fx.acts(p) || !(e.getTarget() instanceof LivingEntity t) || !ally(p, t) || !Sk.anyActive(p)) return;
        HabitsApi.PerkHit h = Sk.TRANSFUSION.hit(p);
        if (h == null || !ready(p, "transfuseReady")) return;
        float give = (float) Math.min(h.value(), Math.min(t.getMaxHealth() - t.getHealth(), p.getHealth() - Sk.p(h, "floor")));
        if (give <= 0) return;
        cooldown(p, "transfuseReady", Sk.p(h, "cooldown"));
        p.setHealth(p.getHealth() - give);
        t.heal(give);
        puff(t, ParticleTypes.HEART, SoundEvents.AMETHYST_BLOCK_CHIME);
        e.setCancellationResult(InteractionResult.SUCCESS);
        e.setCanceled(true);
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent e) {
        if (e.getSource().getEntity() instanceof Player p && e.getEntity() instanceof Enemy && !p.level().isClientSide && Fx.acts(p))
            Fx.fire(p, "kill_while_low", missing(p));
    }

    /** Once a second: shield-up time card units and Shell Rest healing. */
    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.level().isClientSide || (p.tickCount + p.getId()) % 20 != 0 || !p.isBlocking() || !Fx.acts(p)) return;
        Fx.fireUnits(p, "shield_up_seconds", 1);
        HabitsApi.PerkHit h = Sk.SHELL_REST.hit(p);
        if (h != null && !inFight(p, Sk.p(h, "calm"))) p.heal((float) h.value());
    }
}
