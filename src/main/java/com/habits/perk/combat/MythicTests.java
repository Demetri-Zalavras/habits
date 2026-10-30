package com.habits.perk.combat;

import com.habits.Habits;
import com.habits.core.TriggerContext;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static com.habits.perk.combat.MeleeTests.*;

/** Mythical melee cards, one test each. Modded mobs don't exist in dev: Ice and Fire dragons are zombies carrying
 * {@link Mythic#TEST_STAGE}, a Cataclysm boss is the Wither (both are {@code #c:bosses}). */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class MythicTests {
    private MythicTests() {}

    static void kill(ServerPlayer p, LivingEntity v) {
        Mythic.onDeath(new LivingDeathEvent(v, p.damageSources().playerAttack(p)));
    }

    static float swordHit(GameTestHelper h, ServerPlayer p, LivingEntity v) {
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        float before = v.getHealth();
        swing(p, v, false);
        return before - v.getHealth();
    }

    /** Kingsbane: the boss quartet in one life fires the trigger once; each boss type ever killed adds damage, capped. */
    @GameTest(template = ARENA)
    public static void kingsbaneQuartetAndTrophyDamage(GameTestHelper h) {
        ServerPlayer p = player(h);
        List<TriggerContext> got = spy(p, "boss_quartet");
        var l = h.getLevel();
        kill(p, EntityType.WITHER.create(l));
        kill(p, EntityType.ENDER_DRAGON.create(l));
        kill(p, EntityType.WARDEN.create(l));
        kill(p, EntityType.ZOMBIE.create(l));
        h.assertTrue(got.isEmpty(), "three of four bosses don't fire");
        kill(p, EntityType.ELDER_GUARDIAN.create(l));
        h.assertValueEqual(got.size(), 1, "the fourth fires boss_quartet");
        h.assertTrue(p.getData(Mythic.QUARTET_LIFE).isEmpty(), "quartet progress starts over");
        h.assertValueEqual(p.getData(Mythic.BOSSES).size(), 4, "four boss types remembered, the zombie isn't one");

        Pig pig = tank(h.spawnWithNoFreeWill(EntityType.PIG, new Vec3(8.5, 1, 6)));
        clearSkills(p); // the quartet card may have auto-added Kingsbane Lv1
        float base = swordHit(h, p, pig);
        give(p, "kingsbane", 10);
        float four = swordHit(h, p, pig);
        h.assertTrue(Math.abs(four / base - 1.08f) < 0.01f, "Lv10, 4 bosses: +8%, got " + four / base);
        for (int i = 0; i < 30; i++) p.getData(Mythic.BOSSES).add(ResourceLocation.fromNamespaceAndPath("test", "boss" + i));
        float capped = swordHit(h, p, pig);
        h.assertTrue(Math.abs(capped / base - 1.4f) < 0.01f, "34 bosses cap at +40%, got " + capped / base);
        pig.discard();
        done(h, p);
    }

    /** Wyrmheart: young wild dragons can't target you, older ones take more damage; a solo stage 4+ kill fires the trigger. */
    @GameTest(template = ARENA)
    public static void wyrmheartFearAndDragonSolo(GameTestHelper h) {
        ServerPlayer p = player(h);
        give(p, "pack/wyrmheart", 3); // stage 2
        Zombie young = tank(zombie(h, 8.5, 6));
        young.getPersistentData().putInt(Mythic.TEST_STAGE, 2);
        young.setTarget(p);
        h.assertTrue(young.getTarget() == null, "a stage 2 dragon can't target a Lv3 owner");
        Zombie old = tank(zombie(h, 10.5, 6));
        old.getPersistentData().putInt(Mythic.TEST_STAGE, 4);
        old.setTarget(p);
        h.assertTrue(old.getTarget() == p, "a stage 4 dragon still can");
        Zombie plain = tank(zombie(h, 6.5, 6));
        float base = swordHit(h, p, plain), more = swordHit(h, p, old);
        double want = 1 + (10 + 30 * 2 / 9.0) / 100;
        h.assertTrue(Math.abs(more / base - want) < 0.02, "older dragons take " + want + "x, got " + more / base);

        List<TriggerContext> got = spy(p, "dragon_solo");
        kill(p, plain);
        kill(p, old);
        h.assertValueEqual(got.size(), 1, "only the dragon kill fires");
        h.assertValueEqual(got.get(0).amount(), 4.0, "amount is the stage (the card needs min 4)");
        List.of(young, old, plain).forEach(Zombie::discard);
        done(h, p);
    }

    /** Untouchable: a boss hit is clamped to the level's share of max health; a kill losing under 10 hearts to the boss
     * fires flawless_boss, a messier one doesn't. */
    @GameTest(template = ARENA)
    public static void untouchableClampAndFlawless(GameTestHelper h) {
        ServerPlayer p = player(h);
        var wither = EntityType.WITHER.create(h.getLevel());
        var zombie = EntityType.ZOMBIE.create(h.getLevel());
        for (int lv : new int[]{1, 10}) {
            give(p, "pack/untouchable", lv);
            var e = new LivingDamageEvent.Pre(p, new DamageContainer(p.damageSources().mobAttack(wither), 30));
            Mythic.onPre(e);
            h.assertValueEqual(e.getNewDamage(), lv == 1 ? 10f : 4f, "Lv" + lv + " boss hit clamp");
            var z = new LivingDamageEvent.Pre(p, new DamageContainer(p.damageSources().mobAttack(zombie), 30));
            Mythic.onPre(z);
            h.assertValueEqual(z.getNewDamage(), 30f, "a normal mob is not clamped");
        }
        List<TriggerContext> got = spy(p, "flawless_boss");
        Mythic.onPost(new LivingDamageEvent.Post(p, new DamageContainer(p.damageSources().mobAttack(wither), 12)));
        kill(p, wither);
        h.assertValueEqual(got.size(), 1, "12 health lost is flawless");
        Mythic.onPost(new LivingDamageEvent.Post(p, new DamageContainer(p.damageSources().mobAttack(wither), 12)));
        kill(p, wither);
        h.assertValueEqual(got.size(), 1, "24 health lost is not");
        done(h, p);
    }

    /** Quake Punch: every third punch sends a shockwave into mobs near the target; Lv10's shockwave stuns. */
    @GameTest(template = ARENA)
    public static void quakePunchEveryThirdPunch(GameTestHelper h) {
        ServerPlayer p = player(h);
        give(p, "pack/quake_punch", 10);
        Zombie main = tank(zombie(h, 8.5, 6));
        Zombie near = tank(zombie(h, 10.5, 6));
        for (int i = 1; i <= 3; i++) {
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); // a won card lands in the empty hand
            swing(p, main, false);
            if (i < 3) h.assertFalse(hurt(near), "punch " + i + " sends no shockwave");
        }
        h.assertTrue(hurt(near), "the third punch's shockwave hits a mob 2 blocks away");
        h.assertTrue(Tracked.has(near, OnHit.DAZE), "Lv10 shockwave stuns");
        List.of(main, near).forEach(Zombie::discard);
        done(h, p);
    }
}
