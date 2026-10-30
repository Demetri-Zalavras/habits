package com.habits.perk.movement;

import com.habits.Habits;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.Set;

import static com.habits.perk.movement.MovementTests.player;
import static com.habits.perk.movement.MovementTests.state;

/** The mixin-backed mechanics (habits.movement.mixins.json): vanilla without the skill, Lv1 vs Lv10 with it. Each call
 * below goes through the real vanilla method the mixin patches unless the comment says it calls the hook. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class MovementMixinTests {
    private MovementMixinTests() {}

    @GameTest(template = "empty")
    public static void spiderClimbAnyWall(GameTestHelper h) {
        h.setBlock(new BlockPos(0, 2, 1), Blocks.STONE);
        h.setBlock(new BlockPos(0, 3, 1), Blocks.STONE);
        boolean[] climbs = new boolean[3];
        int[] levels = {0, 1, 10};
        for (int i = 0; i < 3; i++) {
            ServerPlayer p = levels[i] == 0 ? player(h, new Vec3(1.31, 2.2, 1.5)) : player(h, new Vec3(1.31, 2.2, 1.5), levels[i], Mech.SPIDER_CLIMB);
            p.setOnGround(false);
            p.horizontalCollision = true;
            p.setShiftKeyDown(true);
            climbs[i] = p.onClimbable();
            if (levels[i] == 10) {
                p.setShiftKeyDown(false);
                h.assertFalse(p.onClimbable(), "Lv10 without sneaking: a wall is just a wall");
            }
        }
        h.assertFalse(climbs[0], "no skill: a wall is not climbable");
        h.assertFalse(climbs[1], "Lv1: walls need Lv5");
        h.assertTrue(climbs[2], "Lv10: sneaking into a wall climbs it");
        ServerPlayer lv5 = player(h, new Vec3(1.31, 2.2, 1.5), 5, Mech.SPIDER_CLIMB);
        lv5.horizontalCollision = true;
        lv5.setShiftKeyDown(true);
        h.assertTrue(lv5.onClimbable(), "Lv5 climbs");
        state(lv5).climbStamina = 60; // Lv5 stamina is 3 s
        h.assertFalse(lv5.onClimbable(), "Lv5 runs out of stamina after 3 s");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void waterWalkIsAFloor(GameTestHelper h) {
        h.setBlock(new BlockPos(1, 1, 1), Blocks.WATER);
        h.setBlock(new BlockPos(1, 2, 1), Blocks.AIR);
        double surface = h.absolutePos(new BlockPos(1, 1, 1)).getY() + 8 / 9.0;
        Vec3 start = new Vec3(1.5, 1 + 8 / 9.0 + 0.2, 1.5);
        // [skill level, sprinting, sneaking] -> stays on the surface?
        Object[][] cases = {{0, true, false, false}, {1, false, false, false}, {1, true, false, true}, {10, false, false, true}, {10, false, true, false}};
        for (Object[] c : cases) {
            int lv = (int) c[0];
            ServerPlayer p = lv == 0 ? player(h, start) : player(h, start, lv, Mech.JESUS_WALK);
            p.setSprinting((boolean) c[1]);
            p.setShiftKeyDown((boolean) c[2]);
            p.move(MoverType.SELF, new Vec3(0, -0.5, 0));
            boolean onTop = Math.abs(p.getY() - surface) < 1e-6;
            h.assertTrue(onTop == (boolean) c[3], "Lv" + lv + " sprint=" + c[1] + " sneak=" + c[2] + " on top=" + c[3] + ", y " + p.getY() + " vs " + surface);
        }
        h.succeed();
    }

    /** Kelp tops, waterlogged blocks and bubble columns are water at the surface too, not holes. */
    @GameTest(template = "empty")
    public static void waterWalkOverKelpSlabBubbles(GameTestHelper h) {
        BlockPos at = new BlockPos(1, 2, 1);
        double surface = h.absolutePos(at).getY() + 8 / 9.0;
        BlockState[] tops = {Blocks.KELP.defaultBlockState(),
                Blocks.STONE_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true),
                Blocks.BUBBLE_COLUMN.defaultBlockState()};
        h.setBlock(new BlockPos(1, 3, 1), Blocks.AIR);
        for (BlockState top : tops) {
            h.setBlock(new BlockPos(1, 1, 1), top.is(Blocks.BUBBLE_COLUMN) ? Blocks.SOUL_SAND : Blocks.STONE);
            h.setBlock(at, top);
            for (int lv : new int[]{0, 10}) {
                ServerPlayer p = lv == 0 ? player(h, new Vec3(1.5, 2 + 8 / 9.0 + 0.2, 1.5))
                        : player(h, new Vec3(1.5, 2 + 8 / 9.0 + 0.2, 1.5), lv, Mech.JESUS_WALK);
                p.move(MoverType.SELF, new Vec3(0, -0.5, 0));
                boolean onTop = Math.abs(p.getY() - surface) < 1e-6;
                h.assertTrue(onTop == (lv > 0), top + " Lv" + lv + ": on top " + onTop + ", y " + p.getY() + " vs " + surface);
            }
        }
        // the sink rule is the shared fall height, not vanilla fallDistance (which the two sides count differently)
        ServerPlayer p = player(h, new Vec3(1.5, 2 + 8 / 9.0 + 0.2, 1.5), 10, Mech.JESUS_WALK);
        p.fallDistance = 10;
        p.move(MoverType.SELF, new Vec3(0, -0.5, 0));
        h.assertTrue(Math.abs(p.getY() - surface) < 1e-6, "a big vanilla fallDistance alone does not sink: y " + p.getY());
        p.setPos(p.getX(), surface + 0.2, p.getZ());
        state(p).fallTop = surface + 4;
        p.move(MoverType.SELF, new Vec3(0, -0.5, 0));
        h.assertTrue(p.getY() < surface - 0.1, "a 4 block drop goes in: y " + p.getY());
        h.succeed();
    }

    /** A glide (perk or elytra) that comes down on water lands on it; the same 6 block drop without a glide goes in. */
    @GameTest(template = "empty")
    public static void waterWalkGlideLandsOnWater(GameTestHelper h) {
        h.setBlock(new BlockPos(1, 1, 1), Blocks.WATER);
        h.setBlock(new BlockPos(1, 2, 1), Blocks.AIR);
        double surface = h.absolutePos(new BlockPos(1, 1, 1)).getY() + 8 / 9.0;
        // 0 = no glide, 1 = Glider Kick held, 2 = elytra
        boolean[] stays = new boolean[3];
        for (int mode = 0; mode < 3; mode++) {
            ServerPlayer p = mode == 1 ? player(h, new Vec3(1.5, 8, 1.5), 10, Mech.JESUS_WALK, Mech.GLIDER_KICK)
                    : player(h, new Vec3(1.5, 8, 1.5), 10, Mech.JESUS_WALK);
            MoveState s = state(p);
            if (mode == 1) s.keys = MovePayloads.K_JUMP;
            if (mode == 2) p.startFallFlying();
            // a slow descent from 6 blocks up to just above the surface, one trackFall per tick as both sides do
            for (double y = surface + 6; y > surface + 0.2; y -= 0.05) {
                p.setPos(p.getX(), y, p.getZ());
                MoveHooks.trackFall(p, s);
            }
            p.setPos(p.getX(), surface + 0.2, p.getZ());
            p.move(MoverType.SELF, new Vec3(0, -0.5, 0));
            stays[mode] = Math.abs(p.getY() - surface) < 1e-6;
        }
        h.assertFalse(stays[0], "a 6 block drop with no glide goes in");
        h.assertTrue(stays[1], "a Glider Kick glide lands on the water");
        h.assertTrue(stays[2], "an elytra glide lands on the water");
        h.succeed();
    }

    /** Up and down columns leave a player held on the surface alone; a swimmer in the column still gets pushed. */
    @GameTest(template = "empty")
    public static void waterWalkIgnoresBubbleColumnPush(GameTestHelper h) {
        BlockPos col = new BlockPos(1, 2, 1);
        h.setBlock(new BlockPos(1, 3, 1), Blocks.AIR);
        double surface = h.absolutePos(col).getY() + 8 / 9.0;
        for (boolean down : new boolean[]{false, true}) {
            h.setBlock(new BlockPos(1, 1, 1), down ? Blocks.MAGMA_BLOCK : Blocks.SOUL_SAND);
            BlockState column = Blocks.BUBBLE_COLUMN.defaultBlockState().setValue(net.minecraft.world.level.block.BubbleColumnBlock.DRAG_DOWN, down);
            h.setBlock(col, column);
            // [skill level, height above the block bottom] -> pushed?
            Object[][] cases = {{10, 8 / 9.0, false}, {0, 8 / 9.0, true}, {10, 0.3, true}, {0, 0.3, true}};
            for (Object[] c : cases) {
                int lv = (int) c[0];
                Vec3 at = new Vec3(1.5, 2 + (double) c[1], 1.5);
                ServerPlayer p = lv == 0 ? player(h, at) : player(h, at, lv, Mech.JESUS_WALK);
                p.setDeltaMovement(Vec3.ZERO);
                column.entityInside(h.getLevel(), h.absolutePos(col), p);
                boolean pushed = p.getDeltaMovement().y != 0;
                h.assertTrue(pushed == (boolean) c[2], (down ? "down" : "up") + " column, Lv" + lv + " at +" + c[1]
                        + ": pushed " + pushed + " (dy " + p.getDeltaMovement().y + ", surface " + surface + ")");
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void iceSkaterGrips(GameTestHelper h) {
        h.setBlock(new BlockPos(1, 1, 1), Blocks.ICE);
        float ice = Blocks.ICE.getFriction();
        ServerPlayer none = player(h, new Vec3(1.5, 2, 1.5));
        ServerPlayer lo = player(h, new Vec3(1.5, 2, 1.5), 1, Mech.ICE_SKATER);
        ServerPlayer hi = player(h, new Vec3(1.5, 2, 1.5), 10, Mech.ICE_SKATER);
        // LivingEntity.travel only runs for the client's own player, so this calls the hook the travel mixin calls
        h.assertValueEqual(MoveHooks.friction(none, ice), ice, "no skill: slippery");
        h.assertValueEqual(MoveHooks.friction(lo, ice), ice, "Lv1: speed only, control is Lv3");
        h.assertValueEqual(MoveHooks.friction(hi, ice), 0.6f, "Lv10: ice grips like ground");
        h.assertValueEqual(MoveHooks.friction(hi, 0.6f), 0.6f, "ground is untouched");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void mudWaderWebs(GameTestHelper h) {
        BlockState web = Blocks.COBWEB.defaultBlockState();
        double[] dx = new double[3];
        int[] levels = {0, 1, 10};
        for (int i = 0; i < 3; i++) {
            ServerPlayer p = levels[i] == 0 ? player(h, new Vec3(1.5, 3, 1.5)) : player(h, new Vec3(1.5, 3, 1.5), levels[i], Mech.MUD_WADER);
            double x = p.getX();
            p.makeStuckInBlock(web, new Vec3(0.25, 0.05, 0.25)); // what WebBlock.entityInside does
            p.move(MoverType.SELF, new Vec3(0.1, 0, 0)); // the empty plot is 2 wide: stay inside it
            dx[i] = p.getX() - x;
        }
        h.assertTrue(Math.abs(dx[0] - 0.025) < 1e-6, "no skill: web keeps 25%: " + dx[0]);
        h.assertTrue(Math.abs(dx[1] - 0.04) < 1e-6, "Lv1 removes 20% of the slow: " + dx[1]);
        h.assertTrue(Math.abs(dx[2] - 0.1) < 1e-6, "Lv10: the web doesn't hold you at all: " + dx[2]);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void pathlessLeaves(GameTestHelper h) {
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        h.setBlock(new BlockPos(3, 1, 1), leaves);
        BlockPos at = h.absolutePos(new BlockPos(3, 1, 1));
        ServerPlayer none = player(h, new Vec3(1.5, 1, 1.5));
        none.setSprinting(true);
        h.assertFalse(leaves.getCollisionShape(h.getLevel(), at, CollisionContext.of(none)).isEmpty(), "no skill: leaves are solid");
        ServerPlayer lo = player(h, new Vec3(1.5, 1, 1.5), 1, Mech.PATHLESS);
        h.assertFalse(leaves.getCollisionShape(h.getLevel(), at, CollisionContext.of(lo)).isEmpty(), "Lv1 walking: solid");
        lo.setSprinting(true);
        h.assertTrue(leaves.getCollisionShape(h.getLevel(), at, CollisionContext.of(lo)).isEmpty(), "Lv1 sprinting: pass through");
        ServerPlayer hi = player(h, new Vec3(1.5, 1, 1.5), 10, Mech.PATHLESS);
        h.assertTrue(leaves.getCollisionShape(h.getLevel(), at, CollisionContext.of(hi)).isEmpty(), "Lv10 walking: pass through");
        ServerPlayer top = player(h, new Vec3(3.5, 2, 1.5), 10, Mech.PATHLESS);
        h.assertFalse(leaves.getCollisionShape(h.getLevel(), at, CollisionContext.of(top)).isEmpty(), "standing on the canopy still holds you");
        BlockState bamboo = Blocks.BAMBOO.defaultBlockState();
        h.setBlock(new BlockPos(3, 1, 2), bamboo);
        h.assertTrue(bamboo.getCollisionShape(h.getLevel(), at.south(), CollisionContext.of(hi)).isEmpty(), "bamboo too");
        h.assertFalse(Blocks.STONE.defaultBlockState().getCollisionShape(h.getLevel(), at, CollisionContext.of(hi)).isEmpty(), "stone is untouched");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void tombRaiderIgnoresPlates(GameTestHelper h) {
        h.assertFalse(player(h, new Vec3(1.5, 1, 1.5)).isIgnoringBlockTriggers(), "no skill: plates see you");
        h.assertFalse(player(h, new Vec3(1.5, 1, 1.5), 1, Mech.TOMB_RAIDER).isIgnoringBlockTriggers(), "Lv1: glow only");
        h.assertTrue(player(h, new Vec3(1.5, 1, 1.5), 10, Mech.TOMB_RAIDER).isIgnoringBlockTriggers(), "Lv10: plates and tripwires ignore you");
        h.succeed();
    }

    /** Stands a player in the level (so the trap's entity search finds them) on the trap at local, lets the trap check
     * through vanilla entityInside, and says whether it powered. */
    private static boolean fires(GameTestHelper h, BlockPos local, int level) {
        ServerLevel l = h.getLevel();
        BlockPos at = h.absolutePos(local);
        Vec3 feet = new Vec3(local.getX() + 0.5, local.getY(), local.getZ() + 0.5);
        ServerPlayer p = level == 0 ? player(h, feet) : player(h, feet, level, Mech.TOMB_RAIDER);
        l.addFreshEntity(p);
        try {
            l.getBlockState(at).entityInside(l, at, p);
            return l.getBlockState(at).getValue(BlockStateProperties.POWERED);
        } finally {
            p.discard();
        }
    }

    /** Places item on top of the block below local the way a player's right-click does (fires EntityPlaceEvent). */
    private static void placeAsPlayer(GameTestHelper h, BlockPos local, net.minecraft.world.item.Item item) {
        ServerPlayer p = player(h, new Vec3(local.getX() + 0.5, local.getY(), local.getZ() + 2.5));
        ItemStack stack = new ItemStack(item);
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        h.setBlock(local, Blocks.AIR); // the template's barrier border
        BlockPos below = h.absolutePos(local.below());
        stack.useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false)));
    }

    @GameTest(template = "empty")
    public static void tombRaiderIgnoresWorldTraps(GameTestHelper h) {
        BlockPos plate = new BlockPos(1, 2, 1), wire = new BlockPos(3, 2, 1), control = new BlockPos(5, 2, 1);
        for (BlockPos b : new BlockPos[]{plate, wire, control}) h.setBlock(b.below(), Blocks.STONE);
        h.setBlock(plate, Blocks.STONE_PRESSURE_PLATE);
        h.setBlock(wire, Blocks.TRIPWIRE);
        h.setBlock(control, Blocks.STONE_PRESSURE_PLATE);
        h.assertFalse(fires(h, plate, 10), "Lv10: a world plate (temple) ignores you");
        h.assertFalse(fires(h, wire, 10), "Lv10: a world tripwire ignores you");
        h.assertTrue(fires(h, control, 0), "control: the same plate fires for a player without the skill");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void tombRaiderPlayerPlacedTrapsStillFire(GameTestHelper h) {
        BlockPos plate = new BlockPos(1, 2, 1), wire = new BlockPos(3, 2, 1);
        h.setBlock(plate.below(), Blocks.STONE);
        h.setBlock(wire.below(), Blocks.STONE);
        placeAsPlayer(h, plate, Items.OAK_PRESSURE_PLATE);
        placeAsPlayer(h, wire, Items.STRING);
        h.assertBlockPresent(Blocks.OAK_PRESSURE_PLATE, plate);
        h.assertBlockPresent(Blocks.TRIPWIRE, wire);
        h.assertTrue(MoveHooks.placedTrap(h.getLevel(), h.absolutePos(plate)), "placing a plate marks it");
        h.assertTrue(fires(h, plate, 10), "Lv10: a plate a player placed still fires");
        h.assertTrue(fires(h, wire, 10), "Lv10: tripwire a player placed still fires");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void tombRaiderBrokenPlateForgetsItsMark(GameTestHelper h) {
        BlockPos plate = new BlockPos(1, 2, 1);
        h.setBlock(plate.below(), Blocks.STONE);
        placeAsPlayer(h, plate, Items.STONE_PRESSURE_PLATE);
        h.assertTrue(MoveHooks.placedTrap(h.getLevel(), h.absolutePos(plate)), "placed: marked");
        h.destroyBlock(plate);
        h.assertFalse(MoveHooks.placedTrap(h.getLevel(), h.absolutePos(plate)), "broken: mark gone");
        h.setBlock(plate, Blocks.STONE_PRESSURE_PLATE);
        h.assertFalse(fires(h, plate, 10), "a plate regenerated on that spot is a world plate again");
        h.succeed();
    }

    /** FakePlayers can't ride (FakePlayer.startRiding returns false), so the rider hooks are called with the player the
     * mixins would pass (the cart's first passenger). */
    @GameTest(template = "empty")
    public static void railBaronSpeedCap(GameTestHelper h) {
        h.setBlock(new BlockPos(1, 1, 1), Blocks.RAIL);
        Minecart cart = h.spawn(EntityType.MINECART, new Vec3(1.5, 1, 1.5));
        h.assertTrue(Math.abs(cart.getMaxSpeedWithRail() - 0.4) < 1e-6, "empty cart: plain rail cap 0.4 (mixin passes it through)");
        ServerPlayer none = player(h, new Vec3(1.5, 1, 1.5));
        ServerPlayer lo = player(h, new Vec3(1.5, 1, 1.5), 1, Mech.RAIL_BARON);
        ServerPlayer hi = player(h, new Vec3(1.5, 1, 1.5), 10, Mech.RAIL_BARON);
        h.assertTrue(Math.abs(MoveHooks.cartCap(none, 0.4) - 0.4) < 1e-6, "no skill: 0.4");
        h.assertTrue(Math.abs(MoveHooks.cartCap(lo, 0.4) - 0.48) < 1e-6, "Lv1 x1.2");
        h.assertTrue(Math.abs(MoveHooks.cartCap(hi, 0.4) - 1.0) < 1e-6, "Lv10 x2.5");
        h.assertTrue(!MoveHooks.coaster(none) && !MoveHooks.coaster(lo) && MoveHooks.coaster(hi), "only Lv10 coasts");
        h.assertFalse(MoveHooks.coasts(cart), "an empty cart slows down as usual");
        cart.discard();
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void pigJockeyStick(GameTestHelper h) {
        ServerPlayer none = player(h, new Vec3(1.5, 1, 1.5));
        ServerPlayer lo = player(h, new Vec3(1.5, 1, 1.5), 1, Mech.PIG_JOCKEY);
        ServerPlayer hi = player(h, new Vec3(1.5, 1, 1.5), 10, Mech.PIG_JOCKEY);
        int[] saved = new int[3];
        ServerPlayer[] ps = {none, lo, hi};
        for (int k = 0; k < 3; k++) for (int i = 0; i < 400; i++) if (MoveHooks.saves(ps[k])) saved[k]++;
        h.assertValueEqual(saved[0], 0, "no skill: every boost wears the stick");
        h.assertTrue(saved[1] > 15 && saved[1] < 80, "Lv1 saves about 10%: " + saved[1] + "/400");
        h.assertValueEqual(saved[2], 400, "Lv10: the stick never wears");
        h.assertFalse(MoveHooks.stickSaved(hi), "only while riding a pig");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void portalHopperWait(GameTestHelper h) {
        NetherPortalBlock portal = (NetherPortalBlock) Blocks.NETHER_PORTAL;
        int none = portal.getPortalTransitionTime(h.getLevel(), player(h, new Vec3(1.5, 1, 1.5)));
        int lo = portal.getPortalTransitionTime(h.getLevel(), player(h, new Vec3(1.5, 1, 1.5), 1, Mech.PORTAL_HOPPER));
        ServerPlayer hiP = player(h, new Vec3(1.5, 1, 1.5), 10, Mech.PORTAL_HOPPER);
        int hi = portal.getPortalTransitionTime(h.getLevel(), hiP);
        h.assertValueEqual(none, 80, "vanilla 4 s");
        h.assertValueEqual(lo, 80, "Lv1 4 s");
        h.assertValueEqual(hi, 4, "Lv10 0.2 s");
        // client nausea hook (LocalPlayer mixin), checked on the server's copy of the same state
        h.assertTrue(MoveHooks.portalNausea(player(h, new Vec3(1.5, 1, 1.5), 1, Mech.PORTAL_HOPPER), true), "Lv1 still gets nausea");
        h.assertFalse(MoveHooks.portalNausea(hiP, true), "Lv10 no nausea");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void piglinDisguiseCountsAsGold(GameTestHelper h) {
        h.assertFalse(PiglinAi.isWearingGold(player(h, new Vec3(1.5, 1, 1.5))), "no skill, no gold");
        h.assertTrue(PiglinAi.isWearingGold(player(h, new Vec3(1.5, 1, 1.5), 1, Mech.PIGLIN_DISGUISE)), "Lv1 disguise");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void monumentDiverSlowsBeams(GameTestHelper h) {
        // the guardian mixins pass their active target to this hook
        h.assertValueEqual(MoveHooks.beamTime(player(h, new Vec3(1.5, 1, 1.5)), 80), 80, "no skill: 4 s beam");
        h.assertValueEqual(MoveHooks.beamTime(player(h, new Vec3(1.5, 1, 1.5), 1, Mech.MONUMENT_DIVER), 80), 96, "Lv1 +20%");
        h.assertValueEqual(MoveHooks.beamTime(player(h, new Vec3(1.5, 1, 1.5), 10, Mech.MONUMENT_DIVER), 80), 200, "Lv10 +150%");
        h.assertValueEqual(MoveHooks.beamTime(null, 60), 60, "no target");
        h.succeed();
    }

    /** Trial Taker Lv5+: a vault ejects one extra item for the owner. The unique reward table always rolls exactly one
     * item, so the real key insert gives 2 for Lv5, and 1 for Lv4 and for a player without the skill. */
    @GameTest(template = "empty")
    public static void trialTakerVaultRollsOneMore(GameTestHelper h) {
        VaultConfig config = new VaultConfig(BuiltInLootTables.TRIAL_CHAMBERS_REWARD_UNIQUE, 4, 4.5, new ItemStack(Items.TRIAL_KEY), Optional.empty());
        ServerPlayer[] ps = {player(h, new Vec3(1.5, 1, 1.5), 5, Mech.TRIAL_TAKER), player(h, new Vec3(1.5, 1, 1.5), 4, Mech.TRIAL_TAKER),
                player(h, new Vec3(1.5, 1, 1.5))};
        int[] want = {2, 1, 1};
        String[] who = {"Lv5 owner", "Lv4 owner", "no skill"};
        for (int i = 0; i < ps.length; i++) {
            state(ps[i]);
            BlockPos rel = new BlockPos(i * 2, 1, 2);
            h.setBlock(rel, Blocks.VAULT.defaultBlockState().setValue(VaultBlock.STATE, VaultState.ACTIVE));
            VaultBlockEntity be = (VaultBlockEntity) h.getBlockEntity(rel);
            BlockPos at = h.absolutePos(rel);
            VaultBlockEntity.Server.tryInsertKey(h.getLevel(), at, h.getLevel().getBlockState(at), config, be.getServerData(),
                    be.getSharedData(), ps[i], new ItemStack(Items.TRIAL_KEY));
            int items = be.saveWithoutMetadata(h.getLevel().registryAccess()).getCompound("server_data").getInt("total_ejections_needed");
            h.assertValueEqual(items, want[i], who[i] + ": items the vault ejects");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void deepDarkDelverQuiet(GameTestHelper h) {
        ServerPlayer none = player(h, new Vec3(1.5, 1, 1.5));
        ServerPlayer lo = player(h, new Vec3(1.5, 1, 1.5), 1, Mech.DEEP_DARK_DELVER);
        ServerPlayer hi = player(h, new Vec3(1.5, 1, 1.5), 10, Mech.DEEP_DARK_DELVER);
        h.assertTrue(SculkShriekerBlockEntity.tryGetPlayer(none) == none, "no skill: shriekers see you");
        h.assertTrue(SculkShriekerBlockEntity.tryGetPlayer(lo) == null, "Lv1: shriekers ignore you");
        // a real warden 8 blocks from the sound (vanilla listening radius 16)
        Warden warden = h.spawn(EntityType.WARDEN, new Vec3(9.5, 1, 1.5));
        BlockPos soundAt = h.absolutePos(new BlockPos(1, 1, 1));
        VibrationSystem.User ears = warden.getVibrationUser();
        boolean heardNone = ears.canReceiveVibration(h.getLevel(), soundAt, GameEvent.STEP, GameEvent.Context.of(none));
        boolean heardLo = ears.canReceiveVibration(h.getLevel(), soundAt, GameEvent.STEP, GameEvent.Context.of(lo));
        boolean heardHi = ears.canReceiveVibration(h.getLevel(), soundAt, GameEvent.STEP, GameEvent.Context.of(hi));
        warden.discard();
        h.assertTrue(heardNone, "no skill: heard at 8");
        h.assertTrue(heardLo, "Lv1 (14.4 range): heard at 8");
        h.assertFalse(heardHi, "Lv10 (3.2 range): not heard at 8");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void clientHooks(GameTestHelper h) {
        // the LocalPlayer and LightTexture mixins read these; the server's copy of the state answers the same
        ServerPlayer lo = player(h, new Vec3(1.5, 1, 1.5), 1, Mech.RUNNERS_LUNGS, Mech.NIGHT_OWL);
        ServerPlayer hi = player(h, new Vec3(1.5, 1, 1.5), 10, Mech.RUNNERS_LUNGS, Mech.NIGHT_OWL);
        h.assertFalse(MoveHooks.sprintAnyFood(lo, false), "Lv1: no sprinting at 6 food");
        h.assertTrue(MoveHooks.sprintAnyFood(hi, false), "Lv10: sprint at any food");
        boolean night = Physics.nightSky(lo);
        Physics.tick(lo);
        float g1 = MoveHooks.nightGamma;
        Physics.tick(hi);
        float g10 = MoveHooks.nightGamma;
        MoveHooks.nightGamma = 0;
        h.assertTrue(night ? Math.abs(g1 - 0.1f) < 1e-6 && Math.abs(g10 - 0.8f) < 1e-6 : g1 == 0 && g10 == 0,
                "night owl brightness +10% / +80% at night only (night=" + night + "): " + g1 + ", " + g10);
        h.succeed();
    }

    /** Physics.boats (local player, needs a rider) applies this on ice for Ice Road owners at any level. */
    @GameTest(template = "empty")
    public static void iceRoadGrip(GameTestHelper h) {
        Boat boat = h.spawn(EntityType.BOAT, new Vec3(1.5, 2, 1.5));
        boat.setYRot(0);
        boat.deltaRotation = 10;
        Vec3 in = new Vec3(0.4, 0, 0.1); // sliding mostly sideways, bow toward +z
        Vec3 v = Physics.grip(boat, in, Vec3.directionFromRotation(0, boat.getYRot()));
        h.assertValueEqual(boat.deltaRotation, 9f, "the spin dies off like on water");
        h.assertTrue(v.z > 0.15 && v.x < 0.3 && Math.hypot(v.x, v.z) <= in.horizontalDistance() + 1e-9,
                "the slide bends toward the bow, no extra speed: " + v);
        boat.discard();
        h.succeed();
    }

    /** Per-call cost of the hottest hooks. Collision runs for every block an entity touches, so the non-owner path must
     * stay a couple of instanceof checks. */
    @GameTest(template = "empty")
    public static void mixinHookCost(GameTestHelper h) {
        ServerPlayer none = player(h, new Vec3(1.5, 1, 1.5));
        ServerPlayer all = player(h, new Vec3(1.5, 1, 1.5), 10, Mech.ALL);
        BlockPos at = h.absolutePos(new BlockPos(3, 1, 1));
        BlockState stone = Blocks.STONE.defaultBlockState(), leaves = Blocks.OAK_LEAVES.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        CollisionContext ctxNone = CollisionContext.of(none), ctxAll = CollisionContext.of(all);
        int n = 100_000;
        double[] ns = new double[6];
        for (int pass = 0; pass < 2; pass++) { // pass 0 warms up the JIT
            long t = System.nanoTime();
            for (int i = 0; i < n; i++) stone.getCollisionShape(h.getLevel(), at, ctxAll);
            ns[0] = (System.nanoTime() - t) / (double) n;
            t = System.nanoTime();
            for (int i = 0; i < n; i++) leaves.getCollisionShape(h.getLevel(), at, ctxNone);
            ns[1] = (System.nanoTime() - t) / (double) n;
            t = System.nanoTime();
            for (int i = 0; i < n; i++) leaves.getCollisionShape(h.getLevel(), at, ctxAll);
            ns[2] = (System.nanoTime() - t) / (double) n;
            t = System.nanoTime();
            for (int i = 0; i < n; i++) water.getCollisionShape(h.getLevel(), at, ctxAll);
            ns[3] = (System.nanoTime() - t) / (double) n;
            t = System.nanoTime();
            for (int i = 0; i < n; i++) none.onClimbable();
            ns[4] = (System.nanoTime() - t) / (double) n;
            t = System.nanoTime();
            for (int i = 0; i < n; i++) all.onClimbable();
            ns[5] = (System.nanoTime() - t) / (double) n;
        }
        LogUtils.getLogger().info("[habits movement perf] per call ns: stone collision {}, leaves no-skill {}, leaves owner {}, water owner {}, onClimbable no-skill {}, owner {}",
                String.format("%.0f", ns[0]), String.format("%.0f", ns[1]), String.format("%.0f", ns[2]), String.format("%.0f", ns[3]),
                String.format("%.0f", ns[4]), String.format("%.0f", ns[5]));
        for (double d : ns) h.assertTrue(d < 2000, "every hook under 2 us a call: " + java.util.Arrays.toString(ns));
        h.succeed();
    }
}
