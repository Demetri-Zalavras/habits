package com.habits.perk.movement;

import com.habits.Habits;
import com.habits.core.Category;
import com.habits.core.CoreRegistries;
import com.habits.core.Specs;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static com.habits.perk.movement.MovementTests.player;
import static com.habits.perk.movement.MovementTests.state;

/** Movement class passives (Freerunner, Outrider, Stormstrider): one test for the group. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class ClassMoveTests {
    private ClassMoveTests() {}

    static double mod(net.minecraft.world.entity.LivingEntity e, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> a, Mech m) {
        AttributeModifier am = e.getAttribute(a).getModifier(m.id);
        return am == null ? 0 : am.amount();
    }

    @GameTest(template = "empty")
    public static void classPassivesMoveYouAndYourMount(GameTestHelper h) throws ReflectiveOperationException {
        ServerPlayer p = player(h, new Vec3(1.5, 2, 1.5), 10, Mech.FREERUNNER, Mech.OUTRIDER, Mech.STORMSTRIDER);
        MoveState s = state(p);
        h.assertFalse(s.has(Mech.FREERUNNER), "no class held: off");
        p.setData(CoreRegistries.SPECS, new Specs(List.of(Category.WARRIOR, Category.MOVEMENT), false,
                List.of(Mech.FREERUNNER.skillId, Mech.OUTRIDER.skillId, Mech.STORMSTRIDER.skillId)));
        MovementTests.setLevel(p, 10, Mech.FREERUNNER, Mech.OUTRIDER, Mech.STORMSTRIDER); // new collection object: state refreshes
        s = state(p);
        h.assertTrue(s.has(Mech.FREERUNNER) && s.has(Mech.STORMSTRIDER) && s.has(Mech.OUTRIDER), "class held: on");
        p.setSprinting(true);
        ClassMoves.attributes(p, s);
        h.assertTrue(Math.abs(mod(p, Attributes.MOVEMENT_SPEED, Mech.FREERUNNER) - s.v(Mech.FREERUNNER) / 100) < 1e-3, "freerunner sprint speed");
        h.assertTrue(mod(p, Attributes.JUMP_STRENGTH, Mech.FREERUNNER) > 0, "freerunner sprint jump");
        h.assertTrue(Math.abs(mod(p, Attributes.MOVEMENT_SPEED, Mech.STORMSTRIDER) - s.v(Mech.STORMSTRIDER) / 100) < 1e-3, "stormstrider speed");
        h.assertTrue(mod(p, Attributes.STEP_HEIGHT, Mech.STORMSTRIDER) > 0 && mod(p, Attributes.SAFE_FALL_DISTANCE, Mech.STORMSTRIDER) > 0, "step and safe fall");
        p.setSprinting(false);
        ClassMoves.attributes(p, s);
        h.assertTrue(mod(p, Attributes.MOVEMENT_SPEED, Mech.FREERUNNER) == 0, "freerunner only while sprinting");
        Horse horse = h.spawn(EntityType.HORSE, new Vec3(1.5, 2, 1.5));
        horse.equipSaddle(new ItemStack(Items.SADDLE), null);
        // FakePlayer refuses startRiding, so seat it by hand (FarmingTests does the same)
        var vehicle = net.minecraft.world.entity.Entity.class.getDeclaredField("vehicle");
        vehicle.setAccessible(true);
        vehicle.set(p, horse);
        var add = net.minecraft.world.entity.Entity.class.getDeclaredMethod("addPassenger", net.minecraft.world.entity.Entity.class);
        add.setAccessible(true);
        add.invoke(horse, p);
        h.assertTrue(horse.getControllingPassenger() == p, "riding the saddled horse");
        ClassMoves.attributes(p, s);
        h.assertTrue(mod(p, Attributes.MOVEMENT_SPEED, Mech.STORMSTRIDER) == 0, "stormstrider is on foot only");
        Mounts.second(p, s, 0);
        h.assertTrue(Math.abs(mod(horse, Attributes.MOVEMENT_SPEED, Mech.OUTRIDER) - s.v(Mech.OUTRIDER) / 100) < 1e-3, "outrider mount speed");
        h.assertTrue(ClassMoves.mountGuard(s) < 1, "outrider mount takes less damage");
        p.stopRiding();
        Mounts.second(p, s, 1);
        h.assertTrue(mod(horse, Attributes.MOVEMENT_SPEED, Mech.OUTRIDER) == 0, "buff comes off the old mount");
        ClassMoves.clear(p);
        h.assertTrue(mod(p, Attributes.STEP_HEIGHT, Mech.STORMSTRIDER) == 0, "cleared");
        h.succeed();
    }
}
