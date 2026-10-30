package com.habits.perk.survival;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsCollection;
import com.habits.core.SkillProgress;
import com.habits.core.Specs;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Guardian class mechanics (ClassGuard): one test for the shield classes, one for the ally class. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class ClassGuardTests {
    private ClassGuardTests() {}

    /** A player of {@code clazz} owning the given class skills at Lv10 ("" = the class passive itself). */
    static ServerPlayer classed(GameTestHelper h, String clazz, String... skills) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-class"));
        p.moveTo(h.absoluteVec(new Vec3(1.5, 2, 1.5)));
        ResourceLocation cls = Habits.id("class/guardian/" + clazz);
        HabitsCollection c = HabitsCollection.EMPTY;
        for (String s : skills) c = c.with(s.isEmpty() ? cls : Habits.id("class/guardian/" + clazz + "_" + s), new SkillProgress(10, 0, true));
        p.setData(CoreRegistries.SPECS, new Specs(List.of(), false, List.of(cls)));
        p.setData(CoreRegistries.COLLECTION, c);
        return p;
    }

    static LivingIncomingDamageEvent incoming(net.minecraft.world.entity.LivingEntity v, net.minecraft.world.damagesource.DamageSource src, float amt) {
        var e = new LivingIncomingDamageEvent(v, new DamageContainer(src, amt));
        ClassGuard.incoming(e);
        return e;
    }

    /** Bulwark cuts hits only with the shield up; Counterblow boosts one hit after a block; Unbroken caps hits and saves a blocker. */
    @GameTest(template = "empty")
    public static void shieldClasses(GameTestHelper h) {
        var z = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        var mob = h.getLevel().damageSources().mobAttack(z);
        ServerPlayer wall = classed(h, "bulwark", "", "counterblow");
        h.assertTrue(Math.abs(incoming(wall, mob, 10).getAmount() - 10) < 0.01, "no cut with the shield down");
        ClassGuard.TEST_BLOCKING.add(wall.getUUID());
        try {
            h.assertTrue(Math.abs(incoming(wall, mob, 10).getAmount() - 7) < 0.01, "Lv10 bulwark cuts 30%");
        } finally {
            ClassGuard.TEST_BLOCKING.remove(wall.getUUID());
        }
        Sk.data(wall).putLong(ClassGuard.COUNTER_AT, h.getLevel().getGameTime());
        var melee = h.getLevel().damageSources().playerAttack(wall);
        h.assertTrue(Math.abs(incoming(z, melee, 10).getAmount() - 18) < 0.01, "counterblow +80% once");
        h.assertTrue(Math.abs(incoming(z, melee, 10).getAmount() - 10) < 0.01, "then spent");

        ServerPlayer tank = classed(h, "unbroken", "");
        var pre = new LivingDamageEvent.Pre(tank, new DamageContainer(mob, 15));
        ClassGuard.pre(pre);
        h.assertTrue(Math.abs(pre.getNewDamage() - 3) < 0.01, "Lv10 caps a hit at 15% of 20 health, got " + pre.getNewDamage());
        tank.setHealth(2);
        ClassGuard.TEST_BLOCKING.add(tank.getUUID());
        try {
            pre = new LivingDamageEvent.Pre(tank, new DamageContainer(mob, 15));
            ClassGuard.pre(pre);
            h.assertTrue(Math.abs(pre.getNewDamage() - 1) < 0.01, "a blocker is left at 1 health");
        } finally {
            ClassGuard.TEST_BLOCKING.remove(tank.getUUID());
        }
        z.discard();
        h.succeed();
    }

    /** Oathsworn covers its owner and a pet in range; Sanctuary heals both. */
    @GameTest(template = "empty")
    public static void oathswornCoversAllies(GameTestHelper h) {
        var z = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        var mob = h.getLevel().damageSources().mobAttack(z);
        ServerPlayer g = classed(h, "oathsworn", "", "sanctuary");
        Wolf w = h.spawn(EntityType.WOLF, new BlockPos(2, 2, 2));
        w.tame(g);
        h.assertTrue(Math.abs(incoming(g, mob, 10).getAmount() - 7.5) < 0.01, "Lv10 oathsworn cuts 25% for its owner");
        h.assertTrue(Math.abs(ClassGuard.cover(g, w) - 25) < 0.01, "and for a pet in range");
        w.setHealth(2);
        g.setHealth(10);
        int healed = ClassGuard.sanctuary(g, com.habits.core.HabitsApi.mechanicHit(g, ClassGuard.SANCTUARY));
        h.assertTrue(healed == 2 && w.getHealth() > 2 && g.getHealth() > 10, "sanctuary heals owner and pet, healed " + healed);
        z.discard();
        w.discard();
        h.succeed();
    }
}
