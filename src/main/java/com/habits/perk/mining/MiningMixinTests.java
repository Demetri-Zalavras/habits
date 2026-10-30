package com.habits.perk.mining;

import com.habits.Habits;
import com.habits.perk.mining.mixin.BeaconInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import java.util.List;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.SculkSensorBlockEntity;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.habits.perk.mining.MiningTests.abs;
import static com.habits.perk.mining.MiningTests.fill;
import static com.habits.perk.mining.MiningTests.player;

/** GameTests for the mining mixins (docs/mechanics/mining-building.md, "Mixins"): each rebuilt skill at Lv1 vs Lv10,
 * plus a player or block without the skill as the vanilla control, so a mixin that failed to apply fails here. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class MiningMixinTests {
    private MiningMixinTests() {}

    /** A plain player (a skill nobody tests here) for the vanilla control. */
    static ServerPlayer control(GameTestHelper h, BlockPos rel) {
        return player(h, rel, "sandman", 1);
    }

    /** Place a block as the player the way NeoForge does it: event first, then onPlace (BuildPerks records owners). */
    static void placeAs(ServerPlayer p, BlockPos at, BlockState s) {
        ServerLevel l = p.serverLevel();
        l.captureBlockSnapshots = true;
        l.setBlock(at, s, 3);
        l.captureBlockSnapshots = false;
        l.capturedBlockSnapshots.clear();
        NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(l.dimension(), l, at), Blocks.STONE.defaultBlockState(), p));
        l.getBlockState(at).onPlace(l, at, Blocks.AIR.defaultBlockState(), false);
    }

    // ---------------------------------------------------------------- golden truce

    @GameTest(template = "empty")
    public static void goldenTruceNoAnger(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        boolean[] angry = new boolean[3];
        String[] skills = {"golden_truce", "golden_truce", null};
        int[] lvls = {1, 10, 0};
        for (int i = 0; i < 3; i++) {
            BlockPos rel = new BlockPos(1 + i * 2, 2, 1);
            ServerPlayer p = skills[i] == null ? control(h, rel) : player(h, rel, skills[i], lvls[i]);
            Piglin pig = EntityType.PIGLIN.create(l);
            pig.moveTo(p.getX() + 1, p.getY(), p.getZ(), 0, 0);
            l.addFreshEntity(pig);
            try {
                PiglinAi.angerNearbyPiglins(p, false);
                angry[i] = pig.getBrain().getMemory(MemoryModuleType.ANGRY_AT).filter(p.getUUID()::equals).isPresent();
            } finally {
                pig.discard();
            }
        }
        h.assertTrue(angry[2], "control: vanilla piglins get angry");
        h.assertFalse(angry[0], "Lv1 piglins stay calm");
        h.assertFalse(angry[1], "Lv10 piglins stay calm");
        h.succeed();
    }

    // ---------------------------------------------------------------- snowplow

    @GameTest(template = "empty")
    public static void snowplowPowderWalk(GameTestHelper h) {
        h.assertFalse(PowderSnowBlock.canEntityWalkOnPowderSnow(control(h, new BlockPos(1, 2, 1))), "control sinks");
        h.assertFalse(PowderSnowBlock.canEntityWalkOnPowderSnow(player(h, new BlockPos(1, 2, 1), "snowplow", 1)), "Lv1 sinks");
        h.assertTrue(PowderSnowBlock.canEntityWalkOnPowderSnow(player(h, new BlockPos(1, 2, 1), "snowplow", 10)), "Lv10 walks");
        h.succeed();
    }

    // ---------------------------------------------------------------- ice carver

    @GameTest(template = "empty")
    public static void iceCarverNoWater(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        boolean[] water = new boolean[3];
        for (int i = 0; i < 3; i++) {
            BlockPos rel = new BlockPos(1 + i * 2, 3, 1);
            ServerPlayer p = i == 2 ? control(h, rel.above()) : player(h, rel.above(), "ice_carver", i == 0 ? 1 : 10);
            BlockPos at = h.absolutePos(rel);
            l.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 3);
            l.setBlock(at, Blocks.ICE.defaultBlockState(), 3);
            p.gameMode.destroyBlock(at);
            water[i] = l.getBlockState(at).is(Blocks.WATER);
        }
        h.assertTrue(water[2], "control: ice melts into water");
        h.assertFalse(water[0], "Lv1 leaves no water");
        h.assertFalse(water[1], "Lv10 leaves no water");
        h.succeed();
    }

    // ---------------------------------------------------------------- muffled tools

    @GameTest(template = "empty")
    public static void muffledToolsRadius(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        boolean[] heard = new boolean[3];
        boolean[] shrieked = new boolean[3];
        for (int i = 0; i < 3; i++) {
            int z = 1 + i * 3;
            ServerPlayer p = i == 2 ? control(h, new BlockPos(0, 2, z)) : player(h, new BlockPos(0, 2, z), "muffled_tools", i == 0 ? 1 : 10);
            BlockPos sensor = abs(h, 7, 2, z);
            l.setBlock(sensor, Blocks.SCULK_SENSOR.defaultBlockState(), 3);
            Vec3 origin = Vec3.atCenterOf(sensor).add(-6, 0, 0); // 6 blocks away: inside Lv10's R 24, outside Lv1's R 4
            SculkSensorBlockEntity be = (SculkSensorBlockEntity) l.getBlockEntity(sensor);
            heard[i] = be.getListener().handleGameEvent(l, GameEvent.BLOCK_DESTROY, GameEvent.Context.of(p, Blocks.STONE.defaultBlockState()), origin);
            BlockPos shriek = abs(h, 9, 2, z);
            l.setBlock(shriek, Blocks.SCULK_SHRIEKER.defaultBlockState(), 3);
            ((SculkShriekerBlockEntity) l.getBlockEntity(shriek)).tryShriek(l, p);
            shrieked[i] = l.getBlockState(shriek).getValue(SculkShriekerBlock.SHRIEKING);
        }
        h.assertTrue(heard[2], "control: a sensor 6 blocks away hears mining");
        h.assertTrue(heard[0], "Lv1 (R 4): a sensor 6 blocks away still hears");
        h.assertFalse(heard[1], "Lv10 (R 24): a sensor 6 blocks away hears nothing");
        h.assertTrue(shrieked[2] && shrieked[0], "control and Lv1 set off shriekers");
        h.assertFalse(shrieked[1], "Lv10 shriekers never warn");
        h.succeed();
    }

    // ---------------------------------------------------------------- tireless tools, goldsmith

    @GameTest(template = "empty")
    public static void tirelessToolsSaves(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int[] damage = new int[3];
        boolean[] broke = new boolean[3];
        for (int i = 0; i < 3; i++) {
            BlockPos rel = new BlockPos(1, 2, 1 + i);
            ServerPlayer p = i == 2 ? control(h, rel) : player(h, rel, "tireless_tools", i == 0 ? 1 : 10);
            ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
            pick.setDamageValue(pick.getMaxDamage() - 1);
            pick.hurtAndBreak(1, l, p, it -> {});
            broke[i] = pick.isEmpty();
            damage[i] = pick.getDamageValue();
        }
        int max = Items.DIAMOND_PICKAXE.getDefaultInstance().getMaxDamage();
        h.assertTrue(broke[2], "control: the tool breaks");
        h.assertFalse(broke[0] || broke[1], "saved at Lv1 and Lv10");
        h.assertValueEqual(damage[0], max - (int) Math.ceil(max * 0.05), "Lv1 restores 5%");
        h.assertValueEqual(damage[1], max - (int) Math.ceil(max * 0.50), "Lv10 restores 50%");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void goldsmithWear(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int[] wear = new int[3];
        for (int i = 0; i < 3; i++) {
            BlockPos rel = new BlockPos(1, 2, 1 + i);
            ServerPlayer p = i == 2 ? control(h, rel) : player(h, rel, "goldsmith", i == 0 ? 1 : 10);
            ItemStack pick = new ItemStack(Items.GOLDEN_PICKAXE);
            pick.hurtAndBreak(30, l, p, it -> {});
            wear[i] = pick.getDamageValue();
        }
        h.assertValueEqual(wear[2], 30, "control: full wear");
        h.assertValueEqual(wear[0], 20, "Lv1 x1.5 durability: 30 wear -> 20");
        h.assertValueEqual(wear[1], 3, "Lv10 x10 durability: 30 wear -> 3");
        h.succeed();
    }

    // ---------------------------------------------------------------- archaeologist

    @GameTest(template = "empty")
    public static void archaeologistBrushSpeed(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int[] brushes = new int[3];
        for (int i = 0; i < 3; i++) {
            BlockPos rel = new BlockPos(1 + i * 2, 2, 1);
            ServerPlayer p = i == 2 ? control(h, rel.above()) : player(h, rel.above(), "archaeologist", i == 0 ? 1 : 10);
            BlockPos at = h.absolutePos(rel);
            l.setBlock(at, Blocks.SUSPICIOUS_SAND.defaultBlockState(), 3);
            BrushableBlockEntity be = (BrushableBlockEntity) l.getBlockEntity(at);
            long t = l.getGameTime();
            int n = 0;
            while (n < 20 && !be.brush(t + 10L * n, p, Direction.UP)) n++;
            brushes[i] = n + 1;
            h.assertTrue(l.getBlockState(at).is(Blocks.SAND), "brushing finished");
        }
        h.assertValueEqual(brushes[2], 10, "control: 10 brushes");
        h.assertValueEqual(brushes[0], 8, "Lv1 x1.3: 8 brushes");
        h.assertValueEqual(brushes[1], 1, "Lv10 x10: one brush");
        h.succeed();
    }

    // ---------------------------------------------------------------- web cutter

    @GameTest(template = "empty")
    public static void webCutterSlow(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        double[] moved = new double[3];
        for (int i = 0; i < 3; i++) {
            BlockPos rel = new BlockPos(3, 6, 1 + i * 2);
            ServerPlayer p = i == 2 ? control(h, rel) : player(h, rel, "web_cutter", i == 0 ? 1 : 10);
            for (BlockPos q : BlockPos.betweenClosed(h.absolutePos(rel), h.absolutePos(rel).offset(2, 1, 0))) l.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
            BlockPos at = h.absolutePos(rel);
            l.setBlock(at, Blocks.COBWEB.defaultBlockState(), 3);
            l.getBlockState(at).entityInside(l, at, p);
            double x = p.getX();
            p.move(MoverType.SELF, new Vec3(1, 0, 0));
            moved[i] = p.getX() - x;
            l.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        }
        h.assertTrue(Math.abs(moved[2] - 0.25) < 1e-6, "control: x0.25 in a web, got " + moved[2]);
        h.assertTrue(Math.abs(moved[0] - 0.4) < 1e-6, "Lv1 20% less slow: x0.4, got " + moved[0]);
        h.assertTrue(Math.abs(moved[1] - 1.0) < 1e-6, "Lv10 no slow, got " + moved[1]);
        h.succeed();
    }

    // ---------------------------------------------------------------- drain

    @GameTest(template = "empty")
    public static void drainSoaksFurther(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int[] left = new int[3];
        boolean[] wet = new boolean[3];
        for (int i = 0; i < 3; i++) {
            int y = 2 + i * 3;
            ServerPlayer p = i == 2 ? control(h, new BlockPos(0, y + 1, 0)) : player(h, new BlockPos(0, y + 1, 0), "drain", i == 0 ? 1 : 10);
            BlockPos start = abs(h, 0, y, 1);
            // a 1-wide channel of 20 water sources east of the sponge
            fill(l, start.offset(0, -1, -1), start.offset(21, -1, 1), Blocks.GLASS.defaultBlockState());
            fill(l, start.offset(0, 0, -1), start.offset(21, 0, -1), Blocks.GLASS.defaultBlockState());
            fill(l, start.offset(0, 0, 1), start.offset(21, 0, 1), Blocks.GLASS.defaultBlockState());
            l.setBlock(start.offset(21, 0, 0), Blocks.GLASS.defaultBlockState(), 3);
            fill(l, start.offset(1, 0, 0), start.offset(20, 0, 0), Blocks.WATER.defaultBlockState());
            placeAs(p, start, Blocks.SPONGE.defaultBlockState());
            int n = 0;
            for (int x = 1; x <= 20; x++) if (l.getBlockState(start.offset(x, 0, 0)).is(Blocks.WATER)) n++;
            left[i] = n;
            wet[i] = l.getBlockState(start).is(Blocks.WET_SPONGE);
        }
        h.assertValueEqual(left[2], 14, "control: vanilla soaks 6 deep");
        h.assertValueEqual(left[0], 12, "Lv1 soaks 8 deep");
        h.assertValueEqual(left[1], 6, "Lv10 soaks 14 deep");
        h.assertTrue(wet[2] && wet[0], "control and Lv1 sponges get wet");
        h.assertFalse(wet[1], "Lv10 sponge stays dry");
        h.succeed();
    }

    // ---------------------------------------------------------------- beacon builder

    @GameTest(template = "empty")
    public static void beaconBuilderRange(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos beacon = abs(h, 1, 2, 1);
        MiningData data = MiningData.get(l);
        int[] amp = new int[3]; // -1 = no effect
        int[] lvls = {1, 10, 0};
        try {
            for (int i = 0; i < 3; i++) {
                data.setOwner("beacon_builder", beacon, lvls[i]);
                Player mock = h.makeMockPlayer(GameType.SURVIVAL);
                mock.moveTo(beacon.getX() + 25.5, beacon.getY(), beacon.getZ() + 0.5, 0, 0); // vanilla level-1 range is 20
                l.addFreshEntity(mock);
                try {
                    BeaconInvoker.habits$applyEffects(l, beacon, 1, MobEffects.MOVEMENT_SPEED, null);
                    MobEffectInstance e = mock.getEffect(MobEffects.MOVEMENT_SPEED);
                    amp[i] = e == null ? -1 : e.getAmplifier();
                } finally {
                    mock.discard();
                }
            }
        } finally {
            data.setOwner("beacon_builder", beacon, 0);
        }
        h.assertValueEqual(amp[2], -1, "control: 25 blocks is out of range");
        h.assertValueEqual(amp[0], 0, "Lv1 (+10) reaches 25 blocks");
        h.assertValueEqual(amp[1], 1, "Lv10 reaches and adds a level");
        h.succeed();
    }

    // ---------------------------------------------------------------- hopper hand

    @GameTest(template = "empty")
    public static void hopperHandSpeedStackFilter(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int[] moved = new int[3];
        int[] lvls = {1, 10, 0};
        for (int i = 0; i < 3; i++) {
            BlockPos hop = abs(h, 1 + i * 2, 3, 1);
            l.setBlock(hop.below(), Blocks.CHEST.defaultBlockState(), 3);
            l.setBlock(hop, Blocks.HOPPER.defaultBlockState(), 3);
            MiningData.get(l).setOwner("hopper_hand", hop, lvls[i]);
            HopperBlockEntity be = (HopperBlockEntity) l.getBlockEntity(hop);
            be.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            for (int t = 0; t < 24; t++) HopperBlockEntity.pushItemsTick(l, hop, l.getBlockState(hop), be);
            Container chest = (Container) l.getBlockEntity(hop.below());
            moved[i] = chest.countItem(Items.COBBLESTONE);
            h.assertValueEqual(moved[i] + be.countItem(Items.COBBLESTONE), 64, "no items made or lost");
            // name filter: only an owned hopper named after an item filters
            ItemStack named = new ItemStack(Items.HOPPER);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Cobblestone"));
            be.applyComponentsFromItemStack(named);
            boolean dirtOk = be.canPlaceItem(0, new ItemStack(Items.DIRT)), cobbleOk = be.canPlaceItem(0, new ItemStack(Items.COBBLESTONE));
            h.assertTrue(cobbleOk, "the named item is always taken");
            h.assertTrue(dirtOk == (lvls[i] == 0), "dirt rejected only by an owned named hopper");
            MiningData.get(l).setOwner("hopper_hand", hop, 0);
        }
        // 24 ticks: vanilla moves on ticks 1, 9, 17; Lv1 (8 / 1.33 = 6) on 1, 7, 13, 19; Lv10 (2 ticks, 16 a move) empties it
        h.assertValueEqual(moved[2], 3, "control: vanilla 8-tick cooldown");
        h.assertValueEqual(moved[0], 4, "Lv1 6-tick cooldown");
        h.assertValueEqual(moved[1], 64, "Lv10 2-tick cooldown, 16 per move");
        h.succeed();
    }

    // ---------------------------------------------------------------- piston power

    // own batch: the 14-block row reaches past the template, into whichever test the grid puts next door (a new test
    // class elsewhere moved a neighbor's barrier wall into the row)
    @GameTest(template = "empty", timeoutTicks = 60, batch = "mining_piston")
    public static void pistonPowerPushLimit(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int row = 14; // over vanilla's 12; Lv1's limit is 12 like vanilla, Lv10's is 32
        BlockPos[] piston = new BlockPos[3];
        boolean[] resolves = new boolean[3];
        // pushes UP from inside the 3x3 plot: a row pushed sideways ran into the next test's barrier wall whenever the
        // grid layout put one there
        for (int i = 0; i < 3; i++) {
            ServerPlayer p = i == 2 ? control(h, new BlockPos(i, 1, 0)) : player(h, new BlockPos(i, 1, 0), "piston_power", i == 0 ? 1 : 10);
            piston[i] = abs(h, i, 1, 1);
            placeAs(p, piston[i], Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
            fill(l, piston[i].above(), piston[i].above(row), Blocks.STONE.defaultBlockState());
            resolves[i] = new PistonStructureResolver(l, piston[i], Direction.UP, true).resolve();
            l.setBlock(piston[i].below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        }
        h.assertFalse(resolves[2], "control: 14 blocks is over vanilla's 12");
        h.assertFalse(resolves[0], "Lv1 (12) can't push 14");
        h.assertTrue(resolves[1], "Lv10 (32) pushes 14");
        h.runAfterDelay(10, () -> {
            for (int i = 0; i < 3; i++) {
                int stone = 0;
                for (int y = 1; y <= row + 1; y++) if (l.getBlockState(piston[i].above(y)).is(Blocks.STONE)) stone++;
                h.assertValueEqual(stone, row, "no blocks made or lost (" + i + ")");
                boolean pushed = l.getBlockState(piston[i].above(row + 1)).is(Blocks.STONE);
                h.assertTrue(pushed == (i == 1), "only the Lv10 piston pushed its row (" + i + ")");
            }
            h.succeed();
        });
    }

    // ---------------------------------------------------------------- hook cost

    /** The hooks that run on hot vanilla paths (every movement tick, every sculk listener per event, every hopper
     * move), timed for a player or block without the skill and with it. Logs ns per call; the budget is generous so
     * a loaded test machine can't fail it, a scan of the collection or the world would. */
    @GameTest(template = "empty")
    public static void mixinHookCost(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer none = control(h, new BlockPos(1, 2, 1));
        ServerPlayer owner = player(h, new BlockPos(1, 2, 1), "muffled_tools", 10);
        BlockPos hop = abs(h, 3, 2, 3);
        Vec3 at = Vec3.atCenterOf(hop);
        var listener = new SculkSensorBlockEntity(hop, Blocks.SCULK_SENSOR.defaultBlockState()).getListener();
        int n = 200_000;
        long sink = 0;
        long[] ns = new long[4];
        for (int round = 0; round < 2; round++) { // round 0 warms the JIT
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) if (MiningHooks.powderWalk(none)) sink++;
            long t1 = System.nanoTime();
            for (int i = 0; i < n; i++) if (MiningHooks.muffled(owner, GameEvent.BLOCK_DESTROY, at, listener, l)) sink++;
            long t2 = System.nanoTime();
            for (int i = 0; i < n; i++) sink += MiningHooks.hopperCooldown(l, hop, 8);
            long t3 = System.nanoTime();
            for (int i = 0; i < n; i++) sink += MiningHooks.pistonLimit(l, hop, true, 12);
            long t4 = System.nanoTime();
            ns = new long[]{(t1 - t0) / n, (t2 - t1) / n, (t3 - t2) / n, (t4 - t3) / n};
        }
        org.slf4j.LoggerFactory.getLogger("habits").info("mining hook cost ns/call: non-owner movement {}, owner sculk listener {}, "
                + "unowned hopper move {}, unowned piston {} ({})", ns[0], ns[1], ns[2], ns[3], sink);
        for (long v : ns) h.assertTrue(v < 5_000, "hook over 5 us per call: " + java.util.Arrays.toString(ns));
        h.succeed();
    }

    // ---------------------------------------------------------------- icicle drop

    @GameTest(template = "empty")
    public static void icicleDropCredit(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        // the credit looks the owner up in the level, and FakePlayers are never in it: use a mock player in the world
        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        p.moveTo(abs(h, 1, 2, 1).getCenter());
        l.addFreshEntity(p);
        PointedDripstoneBlock block = (PointedDripstoneBlock) Blocks.POINTED_DRIPSTONE;
        try {
            for (int i = 0; i < 2; i++) {
                BlockPos at = abs(h, 3 + i, 6, 3);
                l.setBlock(at, Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN), 3);
                FallingBlockEntity f = FallingBlockEntity.fall(l, at, l.getBlockState(at));
                if (i == 0) f.getPersistentData().putUUID(MiningHooks.ICICLE_OWNER, p.getUUID());
                var src = block.getFallDamageSource(f);
                f.discard();
                if (i == 0) h.assertTrue(src.getEntity() == p && src.getDirectEntity() == f, "a dropped icicle is credited to you");
                else h.assertFalse(src.getEntity() instanceof Player, "control: a natural stalactite credits no player");
            }
        } finally {
            p.discard();
        }
        h.succeed();
    }

    // ---------------------------------------------------------------- lapis scholar

    /** Lapis Scholar works like Mending: xp that repairs the held tool is spent, only the rest reaches Mending and then
     * the player. Lv10 repairs 2 per xp, so a 20 xp orb on a tool down 10 spends 5. */
    @GameTest(template = "empty")
    public static void lapisScholarSpendsXpLikeMending(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        var mending = l.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.MENDING);
        // {skill level, tool has Mending, helmet with Mending down 100} -> {tool damage, helmet damage, xp gained}
        // Lv1 repairs at 10%: 0.2 per xp, so 20 xp fix 4 and are all spent
        int[][] cases = {{10, 0, 0}, {10, 0, 1}, {10, 1, 0}, {0, 0, 0}, {1, 0, 0}};
        int[][] want = {{0, 0, 15}, {0, 70, 0}, {0, 0, 15}, {10, 0, 20}, {6, 0, 0}};
        String[] what = {"lapis alone", "lapis then Mending on the helmet", "the tool has Mending too", "control: no skill", "Lv1"};
        for (int i = 0; i < cases.length; i++) {
            ServerPlayer p = cases[i][0] > 0 ? player(h, new BlockPos(1, 1, 1), "lapis_scholar", cases[i][0]) : control(h, new BlockPos(1, 1, 1));
            ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
            tool.setDamageValue(10);
            if (cases[i][1] == 1) tool.enchant(mending, 1);
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, tool);
            ItemStack helmet = new ItemStack(Items.IRON_HELMET);
            if (cases[i][2] == 1) {
                helmet.setDamageValue(100);
                helmet.enchant(mending, 1);
                p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, helmet);
            }
            int before = p.totalExperience;
            new net.minecraft.world.entity.ExperienceOrb(l, p.getX(), p.getY(), p.getZ(), 20).playerTouch(p);
            h.assertValueEqual(tool.getDamageValue(), want[i][0], what[i] + ": tool damage");
            h.assertValueEqual(helmet.getDamageValue(), want[i][1], what[i] + ": helmet damage");
            h.assertValueEqual(p.totalExperience - before, want[i][2], what[i] + ": xp gained");
        }
        h.succeed();
    }

    /** Clumps cancels playerTouch and calls repairPlayerItems itself; Lapis Scholar must still apply there. */
    @GameTest(template = "empty")
    public static void lapisScholarClumpsPath(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(1, 1, 1), "lapis_scholar", 10);
        ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
        tool.setDamageValue(10);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, tool);
        var orb = new net.minecraft.world.entity.ExperienceOrb(l, p.getX(), p.getY(), p.getZ(), 20);
        int left = ((com.habits.perk.mining.mixin.ExperienceOrbInvoker) orb).habits$repairPlayerItems(p, 20);
        h.assertValueEqual(tool.getDamageValue(), 0, "tool damage");
        h.assertValueEqual(left, 15, "xp left");
        h.succeed();
    }

    // ---------------------------------------------------------------- doorkeeper

    /** Doors a Doorkeeper placed stay shut to villagers and zombies; another player's door and a world door stay vanilla.
     * Players still open the locked door, and breaking it drops the mark. */
    @GameTest(template = "empty")
    public static void doorkeeperLocksMobsOut(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        DoorBlock door = (DoorBlock) Blocks.OAK_DOOR;
        BlockState lower = door.defaultBlockState(), upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        ServerPlayer keeper = player(h, new BlockPos(1, 1, 1), "doorkeeper", 1), other = control(h, new BlockPos(1, 1, 1));
        BlockPos mine = abs(h, 2, 1, 2), theirs = abs(h, 5, 1, 2), world = abs(h, 8, 1, 2);
        placeAs(keeper, mine, lower);
        placeAs(other, theirs, lower);
        l.setBlock(world, lower, 3);
        for (BlockPos d : List.of(mine, theirs, world)) l.setBlock(d.above(), upper, 3);

        Villager v = h.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 1));
        for (BlockPos d : List.of(mine, theirs, world)) door.setOpen(v, l, l.getBlockState(d.above()), d.above(), true);
        h.assertFalse(door.isOpen(l.getBlockState(mine)), "a villager can't open the Doorkeeper's door");
        h.assertTrue(door.isOpen(l.getBlockState(theirs)), "control: another player's door opens for a villager");
        h.assertTrue(door.isOpen(l.getBlockState(world)), "control: a world door opens for a villager");
        for (BlockPos d : List.of(theirs, world)) door.setOpen(null, l, l.getBlockState(d), d, false);

        int[] col = {2, 5};
        boolean[] want = {false, true};
        for (int i = 0; i < 2; i++) {
            BlockPos d = abs(h, col[i], 1, 2);
            Zombie z = h.spawn(EntityType.ZOMBIE, new Vec3(col[i] + 0.5, 1, 1.5));
            ((GroundPathNavigation) z.getNavigation()).setCanOpenDoors(true);
            z.getNavigation().moveTo(new Path(List.of(new Node(d.getX(), d.getY(), d.getZ())), d, true), 1.0);
            z.horizontalCollision = true;
            h.assertValueEqual(new BreakDoorGoal(z, diff -> true).canUse(), want[i], i == 0
                    ? "a zombie won't start breaking the Doorkeeper's door" : "control: a zombie breaks another player's door");
            z.discard();
        }

        door.setOpen(keeper, l, l.getBlockState(mine), mine, true);
        h.assertTrue(door.isOpen(l.getBlockState(mine)), "players still open it");
        h.assertTrue(Doors.locked(l, mine, l.getBlockState(mine)), "marked while it stands");
        l.removeBlock(mine, false);
        h.assertFalse(l.getChunkAt(mine).getData(Doors.PLACED_DOORS).contains(mine.asLong()), "breaking the door drops the mark");
        v.discard();
        h.succeed();
    }
}
