package com.habits.perk.ranged;

import com.habits.Habits;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.habits.perk.ranged.RangedTests.archer;
import static com.habits.perk.ranged.RangedTests.done;
import static com.habits.perk.ranged.RangedTests.hit;
import static com.habits.perk.ranged.RangedTests.own;

/** Ranger spec batch ({@link Ranger}): one test per mechanic group, Lv10 values from the shipped skill JSON. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class RangerTests {
    private RangerTests() {}

    /** Arrow damage group: Porcupine (11% per stuck arrow, 3 arrows) + High Ground (5.5% per block, 10 blocks above). */
    @GameTest(template = "empty")
    public static void arrowBonusesStack(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(1.5, 2, 1.5));
        own(p, "ranger/porcupine", 10);
        own(p, "ranger/high_ground", 10);
        Husk z = h.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5, 2, 3.5));
        z.setArrowCount(3);
        Arrow a = new Arrow(h.getLevel(), p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        Shots.Shot s = new Shots.Shot(p, a, z.position().add(0, 10, 0));
        double pct = Ranger.bonus(s, a, p, z);
        h.assertTrue(Math.abs(pct - (33 + 55)) < 0.01, "porcupine 33 + high ground 55, got " + pct);
        done(h, p);
    }

    /** Kill group: Hair Trigger loads an empty crossbow from the archer's arrows; Chain Shot fires at the next husk. */
    @GameTest(template = RangedTests.LANE, batch = "ranger_kill")
    public static void killEffects(GameTestHelper h) {
        RangedTests.floor(h, 8, 16, 0, 20, Blocks.STONE);
        ServerPlayer p = archer(h, new Vec3(12.5, 2, 1.5));
        own(p, "ranger/chain_shot", 10);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CROSSBOW));
        p.getInventory().add(new ItemStack(Items.ARROW, 5));
        h.assertTrue(Ranger.reload(p) && CrossbowItem.isCharged(p.getMainHandItem()), "crossbow loaded");
        h.assertValueEqual(p.getInventory().countItem(Items.ARROW), 4, "one arrow used");
        Husk dead = h.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(12.5, 2, 6.5));
        Husk next = h.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(12.5, 2, 10.5));
        Arrow a = new Arrow(h.getLevel(), p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        int before = Shots.SHOTS.size();
        Ranger.chain(p, a, dead, hit(p, "ranged/chain_shot"));
        h.assertTrue(Shots.SHOTS.size() == before + 1, "a chained arrow flies");
        h.succeedWhen(() -> {
            h.assertTrue(next.getHealth() < next.getMaxHealth(), "the chained arrow hits the next husk");
            com.habits.trigger.Triggers.TEST_PLAYERS.remove(p.getUUID());
        });
    }

    /** Dodge group: Sidestep Lv10 (21% + 12% sprinting) dodges some arrow hits, never all, never none. */
    @GameTest(template = "empty")
    public static void sidestepDodgesSome(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(1.5, 2, 1.5));
        own(p, "ranger/sidestep", 10);
        Husk shooter = h.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(4.5, 2, 4.5));
        Arrow a = new Arrow(h.getLevel(), shooter, new ItemStack(Items.ARROW), null);
        int dodged = 0;
        for (int i = 0; i < 200; i++) {
            // a FakePlayer is invulnerable, so the hit never reaches the event: post it the way LivingEntity.hurt would
            var e = new LivingIncomingDamageEvent(p, new DamageContainer(p.damageSources().arrow(a, shooter), 1));
            Ranger.onIncoming(e);
            if (e.isCanceled()) dodged++;
        }
        h.assertTrue(dodged > 10 && dodged < 150, "about a fifth dodged, got " + dodged);
        done(h, p);
    }

    /** Stealth group: Low Profile keeps a far husk from targeting a sneaking archer; Ghillie hides you in tall grass. */
    @GameTest(template = "empty")
    public static void stealth(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(1.5, 2, 1.5));
        own(p, "ranger/low_profile", 10);
        own(p, "ranger/ghillie", 10);
        p.setShiftKeyDown(true);
        Husk far = h.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(1.5, 2, 1.5));
        far.moveTo(p.getX() + 20, p.getY(), p.getZ());
        Husk near = h.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(4.5, 2, 1.5));
        far.setTarget(p);
        near.setTarget(p);
        h.assertTrue(far.getTarget() == null, "20 blocks is past the shrunk sight range");
        h.assertTrue(near.getTarget() == p, "3 blocks is inside it");
        h.setBlock(new BlockPos(1, 1, 1), Blocks.GRASS_BLOCK);
        h.setBlock(new BlockPos(1, 2, 1), Blocks.SHORT_GRASS);
        Ranger.stealthTick(p);
        h.assertTrue(p.hasEffect(MobEffects.INVISIBILITY), "crouching in grass hides you");
        done(h, p);
    }

    /** Trident group: Lanyard brings a stuck trident back to the inventory. */
    @GameTest(template = "empty")
    public static void lanyardReturns(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(1.5, 2, 1.5));
        own(p, "ranger/lanyard", 10);
        Vec3 at = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        ThrownTrident t = new ThrownTrident(h.getLevel(), at.x, at.y, at.z, new ItemStack(Items.TRIDENT));
        h.getLevel().addFreshEntity(t);
        t.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.ALLOWED;
        h.assertTrue(Ranger.lanyard(p, t), "lanyard pulled it back");
        h.assertTrue(t.isRemoved() && p.getInventory().countItem(Items.TRIDENT) == 1, "trident in the inventory");
        done(h, p);
    }

    /** Big hits group: Dragonfire bursts on husks in its radius; Sonic Shot's beam hits a husk on the line only. */
    @GameTest(template = "empty")
    public static void bigHits(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(1.5, 2, 1.5));
        own(p, "ranger/dragonfire_quiver", 10);
        own(p, "ranger/sonic_shot", 10);
        Husk inRange = h.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(5.5, 2, 5.5));
        Husk outside = h.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(1.5, 2, 7.5));
        int n = Ranger.dragonfire(p, inRange.position(), hit(p, "ranged/dragonfire"));
        h.assertTrue(n == 1 && outside.getHealth() == outside.getMaxHealth(), "only the husk within 3 blocks burns, hurt " + n);
        Arrow a = new Arrow(h.getLevel(), p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        a.shoot(0, 0, 1, 3, 0);
        float before = outside.getHealth();
        Ranger.sonic(p, a, hit(p, "ranged/sonic_shot"));
        h.assertTrue(outside.getHealth() < before, "the husk straight ahead takes the beam");
        done(h, p);
    }

    /** Ranger classes: Ambush (60, husk not after you) + Galeborn (70, moving shot) + Heavenpiercer (100) at Lv10; nothing without the class. */
    @GameTest(template = "empty")
    public static void classPassivesAddArrowDamage(GameTestHelper h) {
        ServerPlayer p = archer(h, new Vec3(1.5, 2, 1.5));
        java.util.List<String> classes = java.util.List.of("class/ranger/shade_hunter", "class/ranger/galeborn", "class/ranger/heavenpiercer");
        classes.forEach(c -> own(p, c, 10));
        Husk z = h.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5, 2, 3.5));
        Arrow a = new Arrow(h.getLevel(), p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        Shots.Shot s = new Shots.Shot(p, a, z.position());
        s.moving = true;
        h.assertTrue(Ranger.bonus(s, a, p, z) == 0, "class passives do nothing without the class");
        p.setData(com.habits.core.CoreRegistries.SPECS, new com.habits.core.Specs(java.util.List.of(com.habits.core.Category.RANGER,
                com.habits.core.Category.FARMING), false, classes.stream().map(Habits::id).toList()));
        com.habits.core.HabitsApi.refreshPerks(p); // the perk cache is keyed on the collection, not the specs
        double pct = Ranger.bonus(s, a, p, z);
        h.assertTrue(Math.abs(pct - 230) < 0.01, "60 + 70 + 100, got " + pct);
        h.assertValueEqual((int) R.p(hit(p, "ranged/heavenpiercer"), "pierce"), 6, "Lv10 pierce");
        done(h, p);
    }
}
