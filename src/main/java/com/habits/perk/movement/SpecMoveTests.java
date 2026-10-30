package com.habits.perk.movement;

import com.habits.Habits;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.habits.perk.movement.MovementTests.player;
import static com.habits.perk.movement.MovementTests.state;

/** One test per spec-batch mechanic group (docs/mechanics/movement-exploration.md "Spec batch"). */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class SpecMoveTests {
    private SpecMoveTests() {}

    /** Landings and jumps: Adrenaline buffs, Coiled Spring launches and cushions, Last Ledge saves once then waits. */
    @GameTest(template = "empty")
    public static void specLandingsAndJumps(GameTestHelper h) {
        ServerPlayer p = player(h, new Vec3(1.5, 2, 1.5), 10, Mech.ADRENALINE, Mech.COILED_SPRING, Mech.LAST_LEDGE);
        MoveState s = state(p);
        LivingFallEvent fall = new LivingFallEvent(p, 12, 1);
        SpecEvents.land(p, s, fall);
        h.assertTrue(p.hasEffect(MobEffects.MOVEMENT_SPEED) && p.hasEffect(MobEffects.JUMP), "adrenaline after a big landing");
        s.coil = 100;
        SpecEvents.jump(p, s);
        h.assertTrue(p.getDeltaMovement().y > SpecEvents.jumpSpeed(4), "coiled jump is high: " + p.getDeltaMovement().y);
        LivingFallEvent after = new LivingFallEvent(p, 7, 1);
        SpecEvents.land(p, s, after);
        h.assertTrue(after.getDamageMultiplier() == 0, "coiled jump lands softly");
        p.setHealth(4);
        DamageSource fallSrc = p.damageSources().fall();
        LivingDamageEvent.Pre big = new LivingDamageEvent.Pre(p, new DamageContainer(fallSrc, 30));
        h.assertTrue(SpecEvents.lastLedge(p, s, big) && big.getNewDamage() == 3, "fatal fall leaves half a heart: " + big.getNewDamage());
        LivingDamageEvent.Pre again = new LivingDamageEvent.Pre(p, new DamageContainer(fallSrc, 30));
        h.assertFalse(SpecEvents.lastLedge(p, s, again), "then it waits out its cooldown");
        h.succeed();
    }

    /** Bursts: Power Slide, Air Brake, Flap and Kick Off push you; cooldowns hold the second press. */
    @GameTest(template = "empty")
    public static void specBursts(GameTestHelper h) {
        ServerPlayer p = player(h, new Vec3(1.5, 2, 1.5), 10, Mech.POWER_SLIDE, Mech.AIR_BRAKE, Mech.FLAP, Mech.KICK_OFF);
        MoveState s = state(p);
        long now = p.level().getGameTime();
        p.setYRot(0);
        SpecMoves.slide(p, s, now);
        double slide = p.getDeltaMovement().z;
        h.assertTrue(slide > 3.5, "slide pushes along the look: " + slide);
        p.setDeltaMovement(Vec3.ZERO);
        SpecMoves.slide(p, s, now);
        h.assertTrue(p.getDeltaMovement().lengthSqr() == 0, "slide on cooldown");
        p.setKnownMovement(new Vec3(0, 0, 2));
        SpecMoves.airBrake(p, s, now);
        h.assertTrue(p.getDeltaMovement().z < 0.5, "air brake sheds speed: " + p.getDeltaMovement().z);
        p.setKnownMovement(new Vec3(0, 0, 1));
        p.setXRot(0);
        SpecMoves.flap(p, s, now);
        h.assertTrue(p.getDeltaMovement().z > 2, "flap adds speed along the look: " + p.getDeltaMovement().z);
        h.setBlock(new BlockPos(1, 2, 2), Blocks.STONE); // the wall is south (+z); yRot 0 faces south
        p.moveTo(h.absoluteVec(new Vec3(1.5, 2, 1.7)));
        p.setYRot(0);
        h.assertFalse(SpecMoves.kickOff(p, s), "facing the wall: no kick");
        p.setYRot(180);
        h.assertTrue(SpecMoves.kickOff(p, s) && p.getDeltaMovement().z < -0.5, "facing away: kick off " + p.getDeltaMovement());
        h.succeed();
    }

    /** Mounts: Sure Hooves cuts a horse's fall damage, Shared Stride copies your speed to it, Giddy Up needs no test. */
    @GameTest(template = "empty")
    public static void specMounts(GameTestHelper h) {
        // fake players can't ride, so the mount halves are called directly
        ServerPlayer p = player(h, new Vec3(1.5, 2, 1.5), 1, Mech.SURE_HOOVES, Mech.SHARED_STRIDE);
        h.assertTrue(Math.abs(SpecEvents.mountFall(state(p), 10) - 8) < 1e-4, "Lv1 sure hooves: -20% fall damage");
        MovementTests.setLevel(p, 10, Mech.SURE_HOOVES, Mech.SHARED_STRIDE);
        h.assertTrue(SpecEvents.mountFall(state(p), 10) == 0, "Lv10 sure hooves: none");
        h.assertTrue(SpecEvents.mountFall(state(player(h, new Vec3(1.5, 2, 1.5))), 10) == 10, "no skill: all of it");
        Horse horse = h.spawn(EntityType.HORSE, new Vec3(1.5, 2, 1.5));
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 1));
        p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, 200, 0));
        SpecMoves.share(p, state(p), horse);
        MobEffectInstance shared = horse.getEffect(MobEffects.MOVEMENT_SPEED);
        h.assertTrue(shared != null && shared.getAmplifier() == 1, "shared stride copies speed II to the horse");
        h.assertTrue(horse.hasEffect(MobEffects.WATER_BREATHING), "Lv10 shares the sixth kind too");
        h.succeed();
    }

    /** Mixin hooks: Fireman's Pole slides, Chain Climber climbs chains, Snowshoes walk on powder snow, Thunder Spin riptides dry. */
    @GameTest(template = "empty")
    public static void specHooks(GameTestHelper h) {
        h.setBlock(new BlockPos(1, 2, 1), Blocks.LADDER);
        h.setBlock(new BlockPos(1, 2, 2), Blocks.STONE);
        ServerPlayer none = player(h, new Vec3(3.5, 2, 3.5));
        ServerPlayer p = player(h, new Vec3(1.5, 2, 1.5), 10, Mech.FIREMAN_POLE, Mech.CHAIN_CLIMBER, Mech.SNOWSHOES, Mech.THUNDER_SPIN);
        MoveState s = state(p);
        p.setXRot(80);
        h.assertTrue(p.onClimbable(), "on the ladder");
        Vec3 v = MoveHooks.pole(p, new Vec3(0, -0.15, 0));
        h.assertTrue(Math.abs(v.y + s.v(Mech.FIREMAN_POLE) / 20) < 1e-9, "pole slide speed " + v.y);
        p.setXRot(0);
        h.assertTrue(MoveHooks.pole(p, new Vec3(0, -0.15, 0)).y == -0.15, "looking ahead: vanilla");
        h.setBlock(new BlockPos(3, 2, 1), Blocks.CHAIN);
        p.moveTo(h.absoluteVec(new Vec3(3.5, 2, 1.5)));
        none.moveTo(h.absoluteVec(new Vec3(3.5, 2, 1.5)));
        h.assertTrue(p.onClimbable() && !none.onClimbable(), "chains climbable for the owner only");
        h.assertTrue(PowderSnowBlock.canEntityWalkOnPowderSnow(p) && !PowderSnowBlock.canEntityWalkOnPowderSnow(none), "snowshoes");
        h.assertTrue(MoveHooks.dryRiptide(p, false, true), "dry riptide allowed");
        h.assertTrue(p.getCooldowns().isOnCooldown(Items.TRIDENT), "dry riptide puts tridents on cooldown");
        h.assertFalse(MoveHooks.dryRiptide(none, false, true), "no skill: vanilla");
        h.succeed();
    }

    /** Situational speed: Rail Runner on rails, Snowshoes on snow, Swim Rhythm building up; cleared when owned skills go. */
    @GameTest(template = "empty")
    public static void specAttributes(GameTestHelper h) {
        h.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        h.setBlock(new BlockPos(1, 2, 1), Blocks.RAIL);
        ServerPlayer p = player(h, new Vec3(1.5, 2, 1.5), 10, Mech.RAIL_RUNNER, Mech.SNOWSHOES);
        p.setOnGround(true);
        MoveState s = state(p);
        double base = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        SpecMoves.attributes(p, s, Blocks.STONE.defaultBlockState(), 0);
        double rail = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        h.assertTrue(Math.abs(rail / base - (1 + s.v(Mech.RAIL_RUNNER) / 100)) < 1e-3, "rail speed " + rail / base);
        h.setBlock(new BlockPos(1, 2, 1), Blocks.SNOW);
        SpecMoves.attributes(p, s, Blocks.STONE.defaultBlockState(), 5);
        double snow = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        h.assertTrue(Math.abs(snow / base - (1 + s.v(Mech.SNOWSHOES) / 100)) < 1e-3, "snow speed " + snow / base);
        SpecMoves.clear(p, s);
        h.assertTrue(Math.abs(p.getAttributeValue(Attributes.MOVEMENT_SPEED) - base) < 1e-9, "cleared");
        h.succeed();
    }

    /** Fights: Sidestep dodges a sprint-time arrow, Evasion and Ink Jet answer a hit, Doorbuster opens a door. */
    @GameTest(template = "empty")
    public static void specHitsAndDoors(GameTestHelper h) {
        ServerPlayer p = player(h, new Vec3(1.5, 2, 1.5), 10, Mech.SIDESTEP, Mech.EVASION, Mech.INK_JET, Mech.DOORBUSTER);
        MoveState s = state(p);
        Zombie z = h.spawn(EntityType.ZOMBIE, new Vec3(3.5, 2, 1.5));
        DamageSource arrow = p.damageSources().arrow(null, z);
        s.sprintSeen = s.tick;
        boolean dodged = false;
        for (int i = 0; i < 50 && !dodged; i++) dodged = SpecEvents.dodged(p, s, arrow);
        h.assertTrue(dodged, "Lv10 sidestep dodges within 50 tries");
        s.sprintSeen = s.tick - 100;
        h.assertFalse(SpecEvents.dodged(p, s, arrow), "not sprinting: no dodge");
        SpecEvents.hurt(p, s, p.damageSources().mobAttack(z));
        h.assertTrue(p.hasEffect(MobEffects.MOVEMENT_SPEED), "evasion speed after a hit");
        h.assertTrue(s.nextInk == 0, "ink jet only underwater");
        BlockPos door = new BlockPos(1, 2, 3);
        h.setBlock(door.below(), Blocks.STONE);
        h.setBlock(door, Blocks.OAK_DOOR);
        h.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        p.moveTo(h.absoluteVec(new Vec3(1.5, 2, 2.5)));
        p.setYRot(0);
        SpecMoves.doors(p, s);
        h.assertTrue(h.getBlockState(door).getValue(DoorBlock.OPEN), "doorbuster opens the door");
        h.succeed();
    }
}
