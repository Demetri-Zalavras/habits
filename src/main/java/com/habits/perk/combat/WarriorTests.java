package com.habits.perk.combat;

import com.habits.Habits;
import com.habits.trigger.Triggers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.habits.perk.combat.MeleeTests.*;

/** Warrior spec batch: one test per mechanic group (hit debuffs, kill rewards, defense, active moves, timed effects). */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class WarriorTests {
    private WarriorTests() {}

    /** Crippling Blow weakens on a crit; Expose stacks and raises damage from any source. */
    @GameTest(template = ARENA)
    public static void warriorHitDebuffs(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        give(p, "warrior/crippling_blow", 10);
        give(p, "warrior/expose", 10);
        Zombie z = tank(zombie(h, 8.5, 6));
        swing(p, z, true);
        h.assertTrue(z.hasEffect(MobEffects.WEAKNESS), "a crit weakens the mob");
        swing(p, z, false);
        h.assertTrue(Warrior.exposed(z, 10) >= 10 * (1 + 2 * 8 / 100f) - 0.01f, "two sword hits: two Expose stacks at 8% each");
        z.discard();
        done(h, p);
    }

    /** Bloodied Plate gives absorption and Ravenous fills hunger on a monster kill. */
    @GameTest(template = ARENA)
    public static void warriorKillRewards(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        give(p, "warrior/bloodied_plate", 10);
        give(p, "warrior/ravenous", 10);
        p.getFoodData().setFoodLevel(10);
        p.setAbsorptionAmount(0);
        Zombie z = zombie(h, 8.5, 6);
        z.hurt(p.damageSources().playerAttack(p), 1000); // a plain swing can fall short on a far-zone elite
        Hooks.endTick();
        h.assertFalse(z.isAlive(), "the zombie died");
        h.assertValueEqual(p.getAbsorptionAmount(), 4f, "Lv10: 2 hearts of absorption");
        h.assertValueEqual(p.getFoodData().getFoodLevel(), 14, "Lv10: 4 hunger filled");
        done(h, p);
    }

    /** Soul Harvest's absorption lands without the Absorption effect (max_absorption is 0 by default). */
    @GameTest(template = ARENA)
    public static void soulHarvestAbsorption(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        give(p, "soul_harvest", 10);
        p.setAbsorptionAmount(0);
        Zombie z = zombie(h, 8.5, 6);
        z.hurt(p.damageSources().playerAttack(p), 1000);
        Hooks.endTick();
        h.assertFalse(z.isAlive(), "the zombie died");
        h.assertValueEqual(p.getAbsorptionAmount(), 2f, "one soul adds 1 heart of absorption");
        done(h, p);
    }

    /** Undying Fury turns a lethal blow into 1 health; Blade Guard cuts a frontal hit while sneaking with a sword. */
    @GameTest(template = ARENA)
    public static void warriorDefense(GameTestHelper h) {
        ServerPlayer p = player(h);
        Zombie z = tank(zombie(h, 8.5, 6));
        give(p, "warrior/undying_fury", 1);
        p.setHealth(20);
        var lethal = new LivingDamageEvent.Pre(p, new DamageContainer(p.damageSources().mobAttack(z), 30));
        Warrior.lethal(p, lethal);
        h.assertValueEqual(lethal.getNewDamage(), 19f, "a killing blow leaves 1 health");
        give(p, "warrior/blade_guard", 10);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        p.setShiftKeyDown(true);
        var in = new LivingIncomingDamageEvent(p, new DamageContainer(p.damageSources().mobAttack(z), 10));
        Defense.incoming(p, in);
        h.assertValueEqual(in.getAmount(), 4.5f, "Lv10 guard cuts 55% of a hit from the front");
        p.setShiftKeyDown(false);
        z.discard();
        done(h, p);
    }

    /** Lunge throws you at a mob ahead; Hatchet Throw hurts the first mob along your look. */
    @GameTest(template = ARENA)
    public static void warriorActiveMoves(GameTestHelper h) {
        ServerPlayer p = player(h);
        Zombie z = tank(zombie(h, 8.5, 10));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        give(p, "warrior/lunge", 10);
        p.setSprinting(true);
        h.assertTrue(Warrior.lunge(p), "a sprint swing at the air lunges");
        h.assertTrue(p.getDeltaMovement().z > 0.5, "toward the mob, vz=" + p.getDeltaMovement().z);
        p.setSprinting(false);
        p.setDeltaMovement(0, 0, 0);
        ItemStack axe = new ItemStack(Items.IRON_AXE);
        p.setItemInHand(InteractionHand.MAIN_HAND, axe);
        give(p, "warrior/hatchet_throw", 1);
        p.setShiftKeyDown(true);
        h.assertTrue(Warrior.hatchet(p, InteractionHand.MAIN_HAND, axe), "sneak right-click throws");
        p.setShiftKeyDown(false);
        h.assertTrue(hurt(z), "the throw hits the zombie 5.5 blocks ahead");
        z.discard();
        done(h, p);
    }

    /** Death Mark echoes the marked damage when it fades; Spirit Blades cut a nearby foe every second. */
    @GameTest(template = ARENA)
    public static void warriorTimedEffects(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        give(p, "warrior/death_mark", 10);
        Zombie z = tank(zombie(h, 8.5, 6));
        swing(p, z, false);
        float afterHit = z.getHealth();
        h.assertTrue(Tracked.has(z, Warrior.MARK), "the first hit marks");
        for (int i = 0; i < 90; i++) Hooks.endTick();
        h.assertTrue(z.getHealth() < afterHit, "the mark echoes damage when it fades");
        z.discard();

        give(p, "warrior/spirit_blades", 10);
        Zombie y = tank(zombie(h, 9.5, 6));
        PS s = PS.of(p);
        s.blades = 2;
        s.bladesUntil = Hooks.now(p) + 100;
        p.tickCount = 20;
        y.invulnerableTime = 0;
        Warrior.tick(p, s, Hooks.now(p));
        h.assertTrue(hurt(y), "a spirit blade cuts the zombie");
        y.discard();
        done(h, p);
    }
    /** Warrior class passives: Blood Frenzy scales with missing health, the Warlord's banner buffs you and weakens foes in
     * range; neither works without the class. (Specs before skills: the mechanic snapshot only rebuilds on a new collection.) */
    @GameTest(template = ARENA)
    public static void warriorClassPassives(GameTestHelper h) {
        ServerPlayer none = player(h);
        give(none, "class/warrior/bloodreaver", 10);
        none.setHealth(none.getMaxHealth() / 2);
        h.assertValueEqual(Classes.frenzy(none, 10), 10f, "no class, no frenzy");

        ServerPlayer p = player(h);
        p.setData(com.habits.core.CoreRegistries.SPECS, new com.habits.core.Specs(java.util.List.of(com.habits.core.Category.WARRIOR,
                com.habits.core.Category.MINING), false, java.util.List.of(Habits.id("class/warrior/bloodreaver"), Habits.id("class/warrior/warlord"))));
        give(p, "class/warrior/bloodreaver", 10);
        give(p, "class/warrior/warlord", 10);
        p.setHealth(p.getMaxHealth() / 2);
        h.assertTrue(Math.abs(Classes.frenzy(p, 10) - 14f) < 0.01f, "Lv10 at half health: 5 tenths x 8% = +40%");
        Zombie z = tank(zombie(h, 8.5, 6));
        p.tickCount = 20;
        Classes.banner(p);
        h.assertTrue(p.hasEffect(MobEffects.DAMAGE_BOOST) && p.hasEffect(MobEffects.DAMAGE_RESISTANCE), "the banner buffs its holder");
        h.assertTrue(z.hasEffect(MobEffects.WEAKNESS), "the banner weakens the foe");
        z.discard();
        Triggers.TEST_PLAYERS.remove(none.getUUID());
        done(h, p);
    }
}
