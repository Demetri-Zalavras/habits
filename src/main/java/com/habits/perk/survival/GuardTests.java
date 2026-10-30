package com.habits.perk.survival;

import com.habits.Habits;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Guardian spec batch (Guard): one test per group. FakePlayers are not in the level's player list, so ally tests call the per-guardian methods. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class GuardTests {
    private GuardTests() {}

    static ServerPlayer g(GameTestHelper h, String skill) {
        return SurvivalTests.player(h, "guardian/" + skill, 10);
    }

    static LivingIncomingDamageEvent incoming(net.minecraft.world.entity.LivingEntity v, net.minecraft.world.damagesource.DamageSource src, float amt) {
        var e = new LivingIncomingDamageEvent(v, new DamageContainer(src, amt));
        Guard.incoming(e);
        return e;
    }

    /** Aegis blocks once then cools down; Shock Absorber softens a big hit; Rested Guard cuts only the first hit after quiet. */
    @GameTest(template = "empty")
    public static void selfDefense(GameTestHelper h) {
        var z = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        var mob = h.getLevel().damageSources().mobAttack(z);
        ServerPlayer aegis = g(h, "aegis");
        aegis.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        h.assertTrue(incoming(aegis, mob, 5).isCanceled(), "aegis blocks the first hit");
        h.assertFalse(incoming(aegis, mob, 5).isCanceled(), "then it cools down");
        h.assertTrue(aegis.getOffhandItem().getDamageValue() > 0, "the shield took the wear");

        ServerPlayer shock = g(h, "shock_absorber");
        var pre = new LivingDamageEvent.Pre(shock, new DamageContainer(mob, 15));
        Guard.pre(pre);
        h.assertTrue(Math.abs(pre.getNewDamage() - 7.5f) < 0.01, "15 on a 20 health player: 5 + 10 * 25% = 7.5, got " + pre.getNewDamage());

        ServerPlayer rest = g(h, "rested_guard");
        h.assertTrue(Math.abs(incoming(rest, mob, 10).getAmount() - 4) < 0.01, "first hit after quiet cut 60%");
        Sk.data(rest).putLong(Guard.LAST_HIT, h.getLevel().getGameTime());
        h.assertTrue(Math.abs(incoming(rest, mob, 10).getAmount() - 10) < 0.01, "a hit mid fight is not cut");
        z.discard();
        h.succeed();
    }

    /** Bodyguard takes half a pet's hit, Guardian Angel stops a killing blow once, Transfusion moves health to the pet. */
    @GameTest(template = "empty")
    public static void coverAllies(GameTestHelper h) {
        var z = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        var mob = h.getLevel().damageSources().mobAttack(z);
        ServerPlayer body = g(h, "bodyguard");
        var wolf = h.spawn(EntityType.WOLF, new BlockPos(2, 2, 1));
        wolf.tame(body);
        var e = new LivingIncomingDamageEvent(wolf, new DamageContainer(mob, 10));
        Guard.cover(e, body, wolf, mob);
        h.assertTrue(Math.abs(e.getAmount() - 5) < 0.01, "bodyguard took half, pet takes " + e.getAmount());
        var stray = h.spawn(EntityType.WOLF, new BlockPos(1, 2, 2));
        var e2 = new LivingIncomingDamageEvent(stray, new DamageContainer(mob, 10));
        Guard.cover(e2, body, stray, mob);
        h.assertTrue(Math.abs(e2.getAmount() - 10) < 0.01, "control: someone else's wolf is not covered");

        ServerPlayer angel = g(h, "guardian_angel");
        wolf.tame(angel);
        var kill = new LivingDamageEvent.Pre(wolf, new DamageContainer(mob, 500));
        h.assertTrue(Guard.angel(kill, angel, wolf), "angel steps in");
        h.assertTrue(Math.abs(kill.getNewDamage() - (wolf.getHealth() - 1)) < 0.01, "pet left at 1 health");
        h.assertFalse(Guard.angel(new LivingDamageEvent.Pre(wolf, new DamageContainer(mob, 500)), angel, wolf), "then on cooldown");

        ServerPlayer medic = g(h, "transfusion");
        wolf.tame(medic);
        wolf.setHealth(2);
        medic.setShiftKeyDown(true);
        Guard.interact(new PlayerInteractEvent.EntityInteract(medic, InteractionHand.MAIN_HAND, wolf));
        h.assertTrue(Math.abs(wolf.getHealth() - 12) < 0.01, "pet got 10 health, has " + wolf.getHealth());
        h.assertTrue(Math.abs(medic.getHealth() - 10) < 0.01, "from you, you have " + medic.getHealth());
        z.discard();
        wolf.discard();
        stray.discard();
        h.succeed();
    }

    /** Provoke pulls a zombie onto you; Shield Rush lunges you forward. */
    @GameTest(template = "empty")
    public static void activeShield(GameTestHelper h) {
        ServerPlayer p = g(h, "provoke");
        var z = h.spawn(EntityType.ZOMBIE, new BlockPos(4, 2, 4));
        z.setTarget(null);
        Guard.provoke(p);
        h.assertTrue(z.getTarget() == p, "the zombie comes for you");
        ServerPlayer r = g(h, "shield_rush");
        r.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        Guard.rush(r);
        h.assertTrue(r.getDeltaMovement().horizontalDistance() > 1, "lunged forward: " + r.getDeltaMovement());
        z.discard();
        h.succeed();
    }

    /** Emergency Flask drinks a healing potion when low; Hearty Meal heals on eating while hurt. */
    @GameTest(template = "empty")
    public static void clutchItems(GameTestHelper h) {
        ServerPlayer p = g(h, "emergency_flask");
        ItemStack pot = new ItemStack(Items.POTION);
        pot.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.HEALING));
        p.getInventory().add(pot);
        p.setHealth(4);
        Guard.flask(p);
        h.assertTrue(p.getHealth() > 4, "healed, at " + p.getHealth());
        h.assertTrue(p.getInventory().countItem(Items.POTION) == 0 && p.getInventory().countItem(Items.GLASS_BOTTLE) == 1, "potion used up");

        ServerPlayer eat = g(h, "hearty_meal");
        eat.setHealth(6);
        ItemStack bread = new ItemStack(Items.BREAD);
        Guard.useFinish(new LivingEntityUseItemEvent.Finish(eat, bread, 0, ItemStack.EMPTY));
        h.assertTrue(Math.abs(eat.getHealth() - 12) < 0.01, "6 health from Lv10, at " + eat.getHealth());
        h.succeed();
    }
}
