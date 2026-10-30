package com.habits.perk.mining;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.SkillProgress;
import com.habits.trigger.Triggers;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Mining-building GameTests: every top pick at Lv1 vs Lv10, plus a per-tick cost check for vein mining and scans.
 * Each test builds at its own height so parallel tests never touch each other's blocks. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class MiningTests {
    private MiningTests() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The q-quantile of timing samples: a lone GC pause or JIT compile on a loaded machine lands above it, real cost doesn't. */
    static long pct(java.util.List<Long> ns, double q) {
        long[] s = ns.stream().mapToLong(Long::longValue).sorted().toArray();
        return s.length == 0 ? 0 : s[(int) (q * (s.length - 1))];
    }

    static ServerPlayer player(GameTestHelper h, BlockPos rel, String skill, int level) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-mine"));
        Triggers.TEST_PLAYERS.add(p.getUUID());
        p.setGameMode(GameType.SURVIVAL);
        Vec3 at = h.absoluteVec(Vec3.atBottomCenterOf(rel));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(Habits.id(skill), new SkillProgress(level, 0, true)));
        return p;
    }

    static BlockPos abs(GameTestHelper h, int x, int y, int z) {
        return h.absolutePos(new BlockPos(x, y, z));
    }

    static void fill(ServerLevel l, BlockPos a, BlockPos b, BlockState s) {
        for (BlockPos q : BlockPos.betweenClosed(a, b)) l.setBlock(q, s, 3);
    }

    /** Dig speed with the skill toggled off, then toggled back on. */
    static float baseline(ServerPlayer p, String skill, BlockState s, BlockPos pos) {
        var id = Habits.id(skill);
        SkillProgress on = HabitsApi.collection(p).get(id);
        p.setData(CoreRegistries.COLLECTION, HabitsApi.collection(p).with(id, new SkillProgress(on.level(), 0, false)));
        float v = p.getDigSpeed(s, pos);
        p.setData(CoreRegistries.COLLECTION, HabitsApi.collection(p).with(id, on));
        return v;
    }

    // ---------------------------------------------------------------- stonebreaker

    @GameTest(template = "empty")
    public static void autoToolSwapsFromABlockInHand(GameTestHelper h) {
        BlockPos stone = new BlockPos(1, 1, 1);
        h.setBlock(stone, net.minecraft.world.level.block.Blocks.STONE);
        ServerPlayer p = player(h, new BlockPos(1, 1, 2), "auto_tool", 1);
        var inv = p.getInventory();
        inv.selected = 0;
        inv.items.set(0, new ItemStack(Items.DIRT, 16));
        inv.items.set(3, new ItemStack(Items.IRON_PICKAXE));
        BuildPerks.onLeftClick(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock(p,
                h.absolutePos(stone), net.minecraft.core.Direction.SOUTH,
                net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.START));
        h.assertValueEqual(inv.selected, 3, "switched from the dirt to the pickaxe");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void bareHandMeansNoToolOrWeapon(GameTestHelper h) {
        h.assertTrue(MiningMechanics.bareHand(ItemStack.EMPTY), "empty hand");
        h.assertTrue(MiningMechanics.bareHand(new ItemStack(Items.DIRT)), "a block");
        h.assertTrue(MiningMechanics.bareHand(new ItemStack(Items.STICK)), "a stick");
        h.assertFalse(MiningMechanics.bareHand(new ItemStack(Items.WOODEN_PICKAXE)), "a pickaxe");
        h.assertFalse(MiningMechanics.bareHand(new ItemStack(Items.IRON_SWORD)), "a sword");
        h.assertFalse(MiningMechanics.bareHand(new ItemStack(Items.TRIDENT)), "a trident");
        h.assertFalse(MiningMechanics.bareHand(new ItemStack(Items.SHEARS)), "shears");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void stonebreakerMomentum(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int[] stacks = new int[2];
        float[] speed = new float[2];
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            int y = 10 + i * 4;
            ServerPlayer p = player(h, new BlockPos(0, y, 0), "stonebreaker", lvl);
            BlockPos base = abs(h, 2, y, 0);
            fill(l, base, base.offset(0, 0, 11), Blocks.STONE.defaultBlockState());
            for (int k = 0; k < 12; k++) p.gameMode.destroyBlock(base.offset(0, 0, k));
            stacks[i] = BreakPerks.momentumStacks(p);
            speed[i] = p.getDigSpeed(Blocks.STONE.defaultBlockState(), base) / baseline(p, "stonebreaker", Blocks.STONE.defaultBlockState(), base);
            i++;
        }
        h.assertValueEqual(stacks[0], 5, "Lv1 caps at 5 stacks");
        h.assertValueEqual(stacks[1], 10, "Lv10 caps at 10 stacks");
        // Lv1: 1 + 5 * 3% = 1.15x, Lv10: 1 + 10 * 12% = 2.2x
        h.assertTrue(Math.abs(speed[0] - 1.15f) < 0.01, "Lv1 speed " + speed[0]);
        h.assertTrue(Math.abs(speed[1] - 2.2f) < 0.01, "Lv10 speed " + speed[1]);
        h.succeed();
    }

    // ---------------------------------------------------------------- deep miner

    @GameTest(template = "empty")
    public static void deepMinerBores(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int[] broken = new int[2];
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            int y = 10 + i * 4;
            ServerPlayer p = player(h, new BlockPos(0, y, 0), "deep_miner", lvl);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            p.setYRot(-90); // look +X
            p.setXRot(0);
            BlockPos first = abs(h, 2, y, 0);
            fill(l, first, first.offset(5, 0, 0), Blocks.DEEPSLATE.defaultBlockState());
            // Lv1 rolls 10%: try a few times on fresh rows so the cap (1 extra) is what we measure
            int max = 0;
            for (int t = 0; t < (lvl == 1 ? 30 : 1); t++) {
                fill(l, first, first.offset(5, 0, 0), Blocks.DEEPSLATE.defaultBlockState());
                p.gameMode.destroyBlock(first);
                Work.drain(p);
                int n = 0;
                for (int d = 1; d <= 5; d++) if (l.getBlockState(first.offset(d, 0, 0)).isAir()) n++;
                max = Math.max(max, n);
            }
            broken[i++] = max;
        }
        h.assertTrue(broken[0] <= 1, "Lv1 bores at most 1, got " + broken[0]);
        h.assertValueEqual(broken[1], 3, "Lv10 always bores 3");
        h.succeed();
    }

    // ---------------------------------------------------------------- ore pulse

    @GameTest(template = "empty")
    public static void orePulseRadius(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int[] seen = new int[2];
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            int y = 10 + i * 6;
            ServerPlayer p = player(h, new BlockPos(0, y, 0), "ore_pulse", lvl);
            BlockPos near = abs(h, 3, y, 0), far = abs(h, 12, y, 0);
            l.setBlock(near, Blocks.IRON_ORE.defaultBlockState(), 3);
            l.setBlock(far, Blocks.GOLD_ORE.defaultBlockState(), 3);
            MiningNet.last = null;
            Glow.orePulse(p, HabitsApi.mechanicHit(p, Habits.id("ore_pulse")));
            Scanner.finish(p);
            h.assertTrue(MiningNet.last != null, "pulse sent marks");
            seen[i] = (MiningNet.last.pos().contains(near) ? 1 : 0) + (MiningNet.last.pos().contains(far) ? 2 : 0);
            h.assertValueEqual(MiningNet.last.ticks(), lvl == 1 ? 60 : 240, "glow time");
            i++;
        }
        h.assertValueEqual(seen[0], 1, "Lv1 (r6) sees only the near ore");
        h.assertValueEqual(seen[1], 3, "Lv10 (r32) sees both");
        h.succeed();
    }

    // ---------------------------------------------------------------- debris diver

    @GameTest(template = "empty")
    public static void debrisDiverSpeedAndFireRes(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockState debris = Blocks.ANCIENT_DEBRIS.defaultBlockState();
        float[] speed = new float[2];
        int[] fire = new int[2];
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            int y = 10 + i * 4;
            ServerPlayer p = player(h, new BlockPos(0, y, 0), "debris_diver", lvl);
            BlockPos at = abs(h, 2, y, 0);
            speed[i] = p.getDigSpeed(debris, at) / baseline(p, "debris_diver", debris, at);
            l.setBlock(at, debris, 3);
            p.gameMode.destroyBlock(at);
            var eff = p.getEffect(MobEffects.FIRE_RESISTANCE);
            fire[i++] = eff == null ? 0 : eff.getDuration();
        }
        h.assertTrue(Math.abs(speed[0] - 2f) < 0.01, "Lv1 x2, got " + speed[0]);
        h.assertTrue(Math.abs(speed[1] - 50f) < 0.01, "Lv10 x50, got " + speed[1]);
        h.assertValueEqual(fire[0], 60, "Lv1 3s fire res");
        h.assertValueEqual(fire[1], 300, "Lv10 15s fire res");
        h.succeed();
    }

    // ---------------------------------------------------------------- vein miner + cost

    @GameTest(template = "empty")
    public static void veinMinerMixesStoneAndDeepslateOre(GameTestHelper h) {
        var same = BreakPerks.sameOre(net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState());
        h.assertTrue(same.test(net.minecraft.world.level.block.Blocks.DEEPSLATE_IRON_ORE.defaultBlockState()), "deepslate iron joins");
        h.assertFalse(same.test(net.minecraft.world.level.block.Blocks.COAL_ORE.defaultBlockState()), "coal does not");
        h.succeed();
    }


    @GameTest(template = "empty")
    public static void veinMinerAndBudget(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int[] left = new int[2];
        long floodNs = 0;
        java.util.List<Long> stepNs = new java.util.ArrayList<>();
        int maxPerTick = 0;
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            int y = 10 + i * 6;
            ServerPlayer p = player(h, new BlockPos(0, y, 0), "vein_miner", lvl);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            BlockPos o = abs(h, 2, y, 0);
            fill(l, o, o.offset(4, 2, 4), Blocks.IRON_ORE.defaultBlockState()); // 75 ore
            long t0 = System.nanoTime();
            p.gameMode.destroyBlock(o);
            if (lvl == 10) floodNs = System.nanoTime() - t0; // warm run
            int before = Work.pending(p);
            h.assertValueEqual(before, lvl == 1 ? 2 : 32, "queued");
            while (Work.pending(p) > 0) {
                int was = Work.pending(p);
                long s = System.nanoTime();
                Work.stepOnce(p);
                stepNs.add(System.nanoTime() - s);
                maxPerTick = Math.max(maxPerTick, was - Work.pending(p));
            }
            int n = 0;
            for (BlockPos q : BlockPos.betweenClosed(o, o.offset(4, 2, 4))) if (l.getBlockState(q).is(Blocks.IRON_ORE)) n++;
            left[i++] = n;
        }
        h.assertValueEqual(left[0], 75 - 1 - 2, "Lv1 breaks 1 + 2");
        h.assertValueEqual(left[1], 75 - 1 - 32, "Lv10 breaks 1 + 32");
        h.assertTrue(maxPerTick <= 4, "at most 4 blocks per tick, got " + maxPerTick);
        // budget: a tick is 50 ms; one tick of vein work must stay a small slice of it
        long p90 = pct(stepNs, 0.9);
        h.assertTrue(p90 < 10_000_000L, "vein tick (p90) " + p90 / 1000 + " us");
        h.assertTrue(floodNs < 20_000_000L, "first break (flood fill) " + floodNs / 1000 + " us");
        LOG.info("vein32 p90 tick " + p90 / 1000 + " us, worst " + pct(stepNs, 1) / 1000 + " us, first break " + floodNs / 1000 + " us");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void oreScanBudget(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(0, 10, 0), "ore_pulse", 10);
        BlockPos o = abs(h, 2, 10, 0);
        for (int k = 0; k < 20; k++) l.setBlock(o.offset(k, 0, 0), Blocks.COAL_ORE.defaultBlockState(), 3);
        Scanner.start(p, "test_scan", p.blockPosition(), 32, Integer.MIN_VALUE, Integer.MAX_VALUE,
                s -> s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES), hits -> MiningNet.last = new MiningNet.Marks(hits, java.util.List.of(), 1, 0));
        long total = 0;
        int ticks = 0;
        java.util.List<Long> ns = new java.util.ArrayList<>();
        while (Scanner.running(p, "test_scan")) {
            long s = System.nanoTime();
            Scanner.stepOnce(p);
            long d = System.nanoTime() - s;
            ns.add(d);
            total += d;
            ticks++;
        }
        h.assertTrue(MiningNet.last != null && MiningNet.last.pos().size() >= 20, "scan found the ores");
        long p90 = pct(ns, 0.9);
        h.assertTrue(p90 < 10_000_000L, "scan tick (p90) " + p90 / 1000 + " us");
        LOG.info("r32 scan: " + ticks + " ticks, p90 " + p90 / 1000 + " us, worst " + pct(ns, 1) / 1000 + " us, total " + total / 1000 + " us");
        h.succeed();
    }

    // ---------------------------------------------------------------- masons wand

    @GameTest(template = "empty")
    public static void masonsWandFillsSurface(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        int[] placed = new int[2];
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            int y = 10 + i * 6;
            ServerPlayer p = player(h, new BlockPos(2, y + 4, 2), "masons_wand", lvl);
            p.setXRot(90); // look straight down
            p.setShiftKeyDown(true);
            p.getInventory().add(new ItemStack(Items.STONE, 64));
            BlockPos floor = abs(h, 0, y, 0);
            fill(l, floor, floor.offset(4, 0, 4), Blocks.OAK_PLANKS.defaultBlockState());
            BlockPos at = floor.offset(2, 1, 2);
            BlockSnapshot snap = BlockSnapshot.create(l.dimension(), l, at);
            l.setBlock(at, Blocks.STONE.defaultBlockState(), 3);
            NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(snap, Blocks.OAK_PLANKS.defaultBlockState(), p));
            int k = i++;
            h.runAfterDelay(2, () -> { // the wand runs at the end of the placing tick
                int n = 0;
                for (BlockPos q : BlockPos.betweenClosed(floor.offset(0, 1, 0), floor.offset(4, 1, 4))) if (l.getBlockState(q).is(Blocks.STONE)) n++;
                placed[k] = n - 1;
                h.assertValueEqual(p.getInventory().countItem(Items.STONE), 64 - (n - 1), "used items");
            });
        }
        h.runAfterDelay(3, () -> {
            h.assertValueEqual(placed[0], 1, "Lv1 one extra");
            h.assertValueEqual(placed[1], 24, "Lv10 fills the 5x5");
            h.succeed();
        });
    }

    /** Placed from the hand through vanilla item use: NeoForge resets the held count around the place event. */
    @GameTest(template = "empty")
    public static void masonsWandChargesTheHeldStack(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(2, 14, 2), "masons_wand", 10);
        p.setXRot(90);
        p.setShiftKeyDown(true);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 10));
        BlockPos floor = abs(h, 0, 10, 0);
        fill(l, floor, floor.offset(4, 0, 4), Blocks.OAK_PLANKS.defaultBlockState());
        BlockPos on = floor.offset(2, 0, 2);
        p.gameMode.useItemOn(p, l, p.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(on).add(0, 0.5, 0), net.minecraft.core.Direction.UP, on, false));
        h.runAfterDelay(2, () -> {
            int n = 0;
            for (BlockPos q : BlockPos.betweenClosed(floor.offset(0, 1, 0), floor.offset(4, 1, 4))) if (l.getBlockState(q).is(Blocks.STONE)) n++;
            h.assertValueEqual(n, 10, "10 stone place 10 blocks");
            h.assertValueEqual(p.getMainHandItem().getCount(), 0, "every placed block was paid for");
            h.succeed();
        });
    }

    /** A double slab is two slab items: each wand copy must cost two, or breaking the copies dupes slabs. */
    @GameTest(template = "empty")
    public static void masonsWandDoubleSlabCostsTwo(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(2, 14, 2), "masons_wand", 10);
        p.setXRot(90);
        p.setShiftKeyDown(true);
        p.getInventory().add(new ItemStack(Items.STONE_SLAB, 30));
        BlockPos floor = abs(h, 0, 10, 0);
        fill(l, floor, floor.offset(4, 0, 4), Blocks.OAK_PLANKS.defaultBlockState());
        BlockPos at = floor.offset(2, 1, 2);
        BlockState dbl = Blocks.STONE_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
                net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
        BlockSnapshot snap = BlockSnapshot.create(l.dimension(), l, at);
        l.setBlock(at, dbl, 3);
        NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(snap, Blocks.OAK_PLANKS.defaultBlockState(), p));
        h.runAfterDelay(2, () -> {
            int n = -1;
            for (BlockPos q : BlockPos.betweenClosed(floor.offset(0, 1, 0), floor.offset(4, 1, 4))) if (l.getBlockState(q).equals(dbl)) n++;
            h.assertValueEqual(n, 15, "30 slabs make 15 double slabs");
            h.assertValueEqual(p.getInventory().countItem(Items.STONE_SLAB), 0, "two slabs per double slab");
            h.succeed();
        });
    }

    // ---------------------------------------------------------------- auto bridge

    @GameTest(template = "empty")
    public static void autoBridgeRate(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        boolean[] placed = new boolean[2];
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            int y = 10 + i * 4;
            ServerPlayer p = player(h, new BlockPos(0, y, 0), "auto_bridge", lvl);
            p.setShiftKeyDown(true);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 16));
            p.setKnownMovement(new Vec3(-0.1, 0, 0));
            BlockPos below = p.blockPosition().below();
            var hit = HabitsApi.mechanicHit(p, Habits.id("auto_bridge"));
            for (int t = 0; t < 10; t++) TickPerks.autoBridge(p, hit);
            placed[i++] = l.getBlockState(below).is(Blocks.COBBLESTONE);
        }
        h.assertFalse(placed[0], "Lv1 (0.5/s) has not placed in half a second");
        h.assertTrue(placed[1], "Lv10 (20/s) places at once");
        h.succeed();
    }

    // ---------------------------------------------------------------- candlelight

    @GameTest(template = "empty")
    public static void candlelightStopsSpawns(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        boolean[] cancelled = new boolean[2];
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            int y = 10 + i * 4;
            ServerPlayer p = player(h, new BlockPos(0, y, 0), "candlelight", lvl);
            BlockPos at = abs(h, 0, y, 2 + i * 40);
            BlockSnapshot snap = BlockSnapshot.create(l.dimension(), l, at);
            l.setBlock(at, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true), 3);
            NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(snap, Blocks.AIR.defaultBlockState(), p));
            Zombie z = EntityType.ZOMBIE.create(l);
            BlockPos spawn = at.offset(6, 0, 0);
            z.moveTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            var ev = new FinalizeSpawnEvent(z, l, z.getX(), z.getY(), z.getZ(), l.getCurrentDifficultyAt(spawn), MobSpawnType.NATURAL, null, null);
            NeoForge.EVENT_BUS.post(ev);
            cancelled[i++] = ev.isSpawnCancelled();
            MiningData.get(l).removeCandle(at.asLong());
        }
        h.assertFalse(cancelled[0], "Lv1 (r2) does not reach 6 blocks");
        h.assertTrue(cancelled[1], "Lv10 (r16) stops it");
        h.succeed();
    }

    // ---------------------------------------------------------------- shaped charge

    @GameTest(template = "empty")
    public static void shapedChargeTunnel(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        boolean[] deep = new boolean[2];
        boolean[] side = new boolean[2];
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            int y = 10 + i * 8;
            ServerPlayer p = player(h, new BlockPos(0, y, 0), "shaped_charge", lvl);
            p.setYRot(-90);
            p.setXRot(0);
            p.setShiftKeyDown(true);
            BlockPos tntAt = abs(h, 1, y, 0);
            fill(l, tntAt.offset(1, -2, -3), tntAt.offset(12, 2, 3), Blocks.STONE.defaultBlockState());
            PrimedTnt tnt = new PrimedTnt(l, tntAt.getX() + 0.5, tntAt.getY(), tntAt.getZ() + 0.5, p);
            l.addFreshEntity(tnt);
            l.explode(tnt, net.minecraft.world.level.Explosion.getDefaultDamageSource(l, tnt), null, tnt.getX(), tnt.getY(), tnt.getZ(), 4f, false,
                    Level.ExplosionInteraction.TNT);
            tnt.discard();
            deep[i] = l.getBlockState(tntAt.offset(10, 0, 0)).isAir();
            side[i] = l.getBlockState(tntAt.offset(2, 0, 3)).is(Blocks.STONE);
            h.assertTrue(l.getBlockState(tntAt.offset(3, 0, 0)).isAir(), "tunnel starts at Lv" + lvl);
            i++;
        }
        h.assertFalse(deep[0], "Lv1 tunnel is 4 deep");
        h.assertTrue(deep[1], "Lv10 tunnel reaches 10+");
        h.assertTrue(side[0] && side[1], "no sphere: the side walls stay");
        h.succeed();
    }

    // ---------------------------------------------------------------- drops: hot pick + telekinesis

    @GameTest(template = "empty")
    public static void hotPickAndTelekinesis(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(0, 10, 0), "hot_pick", 10);
        p.setData(CoreRegistries.COLLECTION, HabitsApi.collection(p).with(Habits.id("telekinesis"), new SkillProgress(10, 0, true)));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        BlockPos at = abs(h, 2, 10, 0);
        l.setBlock(at, Blocks.IRON_ORE.defaultBlockState(), 3);
        p.gameMode.destroyBlock(at);
        h.assertTrue(p.getInventory().countItem(Items.IRON_INGOT) >= 1, "Lv10 hot pick + telekinesis puts an ingot in the inventory");
        h.assertValueEqual(p.getInventory().countItem(Items.RAW_IRON), 0, "no raw iron");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void toggledOffDoesNothing(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(0, 10, 0), "vein_miner", 10);
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(Habits.id("vein_miner"), new SkillProgress(10, 0, false)));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        BlockPos o = abs(h, 2, 10, 0);
        fill(l, o, o.offset(2, 0, 0), Blocks.IRON_ORE.defaultBlockState());
        p.gameMode.destroyBlock(o);
        h.assertValueEqual(Work.pending(p), 0, "toggled off: no vein");
        h.succeed();
    }

    /** block_swapper: replanting a crop where it was harvested, or a sapling where a log stood, is no swap; stone to
     * dirt is. */
    @GameTest(template = "empty")
    public static void replantingIsNoSwap(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(0, 2, 0), "block_swapper", 1);
        int[] swaps = new int[1];
        com.habits.trigger.Triggers.Spy spy = (who, type, ctx) -> { if (who == p && Habits.id("swap_block").equals(ctx.target())) swaps[0]++; };
        com.habits.trigger.Triggers.SPIES.add(spy);
        try {
            BlockPos crop = abs(h, 2, 2, 0), log = abs(h, 2, 2, 2), stone = abs(h, 0, 2, 2);
            l.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState(), 3);
            l.setBlock(crop, ((net.minecraft.world.level.block.CropBlock) Blocks.WHEAT).getStateForAge(7), 3);
            l.setBlock(log.below(), Blocks.DIRT.defaultBlockState(), 3);
            l.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), 3);
            l.setBlock(stone.below(), Blocks.STONE.defaultBlockState(), 3);
            l.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
            Object[][] cases = {{crop, Items.WHEAT_SEEDS, 0}, {log, Items.OAK_SAPLING, 0}, {stone, Items.DIRT, 1}};
            for (Object[] c : cases) {
                BlockPos at = (BlockPos) c[0];
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                p.gameMode.destroyBlock(at);
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack((net.minecraft.world.item.Item) c[1]));
                p.getMainHandItem().useOn(new net.minecraft.world.item.context.UseOnContext(p, InteractionHand.MAIN_HAND,
                        new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(at.below()), net.minecraft.core.Direction.UP, at.below(), false)));
                h.assertTrue(!l.getBlockState(at).isAir(), "placed " + c[1]);
                h.assertValueEqual(swaps[0], (int) c[2], "swaps after " + c[1]);
            }
        } finally {
            com.habits.trigger.Triggers.SPIES.remove(spy);
            Triggers.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }

    /** Each extra block a perk breaks rolls once, like a hand break (its own muted BreakEvent never doubles it); a
     * break made directly under a busy flag (the arrow permission check) rolls nothing. */
    @GameTest(template = "empty")
    public static void ownWorkRollsOncePerBlock(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(0, 10, 0), "deep_miner", 10);
        int[] breaks = new int[1];
        com.habits.trigger.Triggers.Spy spy = (who, type, ctx) -> { if (who == p && type == com.habits.core.TriggerType.BREAK_BLOCK) breaks[0]++; };
        com.habits.trigger.Triggers.SPIES.add(spy);
        try {
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            p.setYRot(-90);
            p.setXRot(0);
            BlockPos first = abs(h, 2, 10, 0);
            fill(l, first, first.offset(5, 0, 0), Blocks.DEEPSLATE.defaultBlockState());
            p.gameMode.destroyBlock(first);
            Work.drain(p);
            h.assertTrue(l.getBlockState(first.offset(3, 0, 0)).isAir(), "Lv10 bored 3 more");
            h.assertValueEqual(breaks[0], 4, "the player's break plus one roll per bored block");
            BlockPos stone = abs(h, 0, 12, 2);
            l.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
            com.habits.perk.farming.Farm.busy = true;
            try {
                p.gameMode.destroyBlock(stone);
            } finally {
                com.habits.perk.farming.Farm.busy = false;
            }
            h.assertValueEqual(breaks[0], 4, "a break under farming's busy flag fires nothing");
        } finally {
            com.habits.trigger.Triggers.SPIES.remove(spy);
            Triggers.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }

    /** Oops never deletes block-entity data: a filled chest is refused and keeps its items; a plain block still undoes. */
    @GameTest(template = "empty")
    public static void oopsKeepsBlockEntities(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(1, 1, 1), "oops", 10);
        BlockPos stone = abs(h, 3, 1, 1), chest = abs(h, 4, 1, 1);
        MiningMixinTests.placeAs(p, stone, Blocks.STONE.defaultBlockState());
        MiningMixinTests.placeAs(p, chest, Blocks.CHEST.defaultBlockState());
        ((net.minecraft.world.level.block.entity.ChestBlockEntity) l.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 5));
        BuildPerks.undo(p);
        h.assertTrue(l.getBlockState(chest).is(Blocks.CHEST), "chest refused");
        h.assertValueEqual(((net.minecraft.world.level.block.entity.ChestBlockEntity) l.getBlockEntity(chest)).getItem(0).getCount(), 5, "chest diamonds");
        BuildPerks.undo(p);
        h.assertTrue(l.getBlockState(stone).isAir(), "stone undone");
        h.assertValueEqual(p.getInventory().countItem(Items.STONE), 1, "stone refunded");
        h.succeed();
    }

    // ---------------------------------------------------------------- philosopher's pick (Mythical)

    /** The real card with every level value at 100% and the scrap chance at 100%, so the rolls always hit. */
    static com.habits.core.SkillDef philosopherFixture() {
        var def = com.habits.core.SkillRegistry.get(Habits.id("philosophers_pick"));
        var perk = def.perk();
        var params = new java.util.HashMap<>(perk.params());
        params.put("scrap_chance", "100");
        return com.habits.core.SkillRegistry.testFixture(new com.habits.core.SkillDef(Habits.id("test/philosophers_pick"), def.rarity(),
                def.category(), def.trigger(), def.icon(), def.art(), new com.habits.core.PerkSpec(perk.type(), perk.mechanic(), params, 0, 0,
                java.util.Optional.of(java.util.Collections.nCopies(10, 100.0))), def.name(), def.foundBy(), def.perkText()));
    }

    static java.util.List<ItemStack> dropsAt(ServerLevel l, BlockPos at) {
        var out = new java.util.ArrayList<ItemStack>();
        for (var ie : l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(at).inflate(1.5))) {
            out.add(ie.getItem());
            ie.discard();
        }
        return out;
    }

    @GameTest(template = "empty")
    public static void philosophersPickTransmutesOres(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(0, 16, 0), "philosophers_pick", 10);
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(philosopherFixture().id(), new SkillProgress(10, 0, true)));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        BlockPos at = abs(h, 2, 16, 0);
        l.setBlock(at, Blocks.COAL_ORE.defaultBlockState(), 3);
        p.gameMode.destroyBlock(at);
        var drops = dropsAt(l, at);
        h.assertTrue(drops.stream().anyMatch(d -> d.is(Items.RAW_COPPER)), "coal ore turned into copper drops, got " + drops);
        h.assertFalse(drops.stream().anyMatch(d -> d.is(Items.COAL)), "no coal");
        l.setBlock(at, Blocks.DIAMOND_ORE.defaultBlockState(), 3);
        p.gameMode.destroyBlock(at);
        drops = dropsAt(l, at);
        h.assertTrue(drops.stream().anyMatch(d -> d.is(Items.EMERALD)), "diamond ore turned into an emerald, got " + drops);
        h.assertTrue(drops.stream().anyMatch(d -> d.is(Items.NETHERITE_SCRAP)), "Lv10 scrap from diamond ore, got " + drops);
        // the real card at Lv1: 3%, and Lv1 never rolls scrap
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(Habits.id("philosophers_pick"), new SkillProgress(1, 0, true)));
        h.assertValueEqual(MiningMechanics.val(p, "philosophers_pick"), 3.0, "Lv1 value");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void oreRainbowFiresAfterAllEightUnderARoof(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(1, 22, 1), "philosophers_pick", 1);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        fill(l, abs(h, 0, 25, 0), abs(h, 4, 25, 4), Blocks.STONE.defaultBlockState()); // roof: no sky
        int[] fired = new int[1];
        com.habits.trigger.Triggers.Spy spy = (who, type, ctx) -> { if (who == p && Habits.id("ore_rainbow").equals(ctx.target())) fired[0]++; };
        com.habits.trigger.Triggers.SPIES.add(spy);
        try {
            net.minecraft.world.level.block.Block[] ores = {Blocks.COAL_ORE, Blocks.COPPER_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE,
                    Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE};
            BlockPos at = abs(h, 2, 22, 1);
            for (int i = 0; i < ores.length; i++) {
                h.assertValueEqual(fired[0], 0, "not before ore " + i);
                l.setBlock(at, ores[i].defaultBlockState(), 3);
                p.gameMode.destroyBlock(at);
                if (i == 0) l.setBlock(at, Blocks.COAL_ORE.defaultBlockState(), 3); // a repeat kind changes nothing
                if (i == 0) p.gameMode.destroyBlock(at);
                h.assertTrue(i == ores.length - 1 || PhilosophersPick.RAINBOW.containsKey(p.getUUID()), "trip counted after ore " + i
                        + " (sky " + PhilosophersPick.underSky(p) + ", block now " + l.getBlockState(at) + ")");
            }
            h.assertValueEqual(fired[0], 1, "fired once for the eighth kind");
            h.assertFalse(PhilosophersPick.RAINBOW.containsKey(p.getUUID()), "trip starts over");
        } finally {
            com.habits.trigger.Triggers.SPIES.remove(spy);
            dropsAt(l, abs(h, 2, 22, 1));
            fill(l, abs(h, 0, 25, 0), abs(h, 4, 25, 4), Blocks.AIR.defaultBlockState());
        }
        h.succeed();
    }
}
