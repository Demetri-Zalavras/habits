package com.habits.perk.farming;

import net.minecraft.tags.BlockTags;
import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import com.habits.trigger.Triggers;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

/** Farming-nature GameTests: every top pick shows its effect and its Lv1 vs Lv10 difference, plus the per-tick cost checks. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class FarmingTests {
    private FarmingTests() {}

    static final Logger LOG = LogUtils.getLogger();

    /** The q-quantile of timing samples: a lone GC pause or JIT compile on a loaded machine lands above it, real cost doesn't. */
    static long pct(List<Long> ns, double q) {
        long[] s = ns.stream().mapToLong(Long::longValue).sorted().toArray();
        return s.length == 0 ? 0 : s[(int) (q * (s.length - 1))];
    }

    static ServerPlayer player(GameTestHelper h, BlockPos rel) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-farm"));
        Triggers.TEST_PLAYERS.add(p.getUUID());
        BlockPos a = h.absolutePos(rel);
        p.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
        return p;
    }

    static void own(ServerPlayer p, String skill, int level) {
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(Habits.id(skill), new SkillProgress(level, 0, true)));
    }

    static HabitsApi.PerkHit hit(String skill, int level) {
        SkillDef def = SkillRegistry.get(Habits.id(skill));
        return new HabitsApi.PerkHit(def, level, def.perkValue(level));
    }

    static BlockState ripeWheat() {
        return ((CropBlock) Blocks.WHEAT).getStateForAge(7);
    }

    /** A 9x9 field of {@code crop} on farmland, centred on rel (4,1,4). */
    static void field(GameTestHelper h, BlockState crop) {
        for (int x = 0; x < 9; x++)
            for (int z = 0; z < 9; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.FARMLAND);
                h.setBlock(new BlockPos(x, 1, z), crop);
            }
    }

    static int countWheat(GameTestHelper h) {
        int n = 0;
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) if (h.getBlockState(new BlockPos(x, 1, z)).is(Blocks.WHEAT)) n++;
        return n;
    }

    /** Death builds a new player; the pet revive queue, air bank and mount carry over. */
    @GameTest(template = "empty")
    public static void farmDataSurvivesDeath(GameTestHelper h) {
        ServerPlayer old = player(h, BlockPos.ZERO), now = player(h, BlockPos.ZERO);
        Farm.data(old).putInt("habits_air", 120);
        Farm.data(old).put(Animals.PETS, new net.minecraft.nbt.ListTag());
        Farm.data(old).getList(Animals.PETS, 10).add(new net.minecraft.nbt.CompoundTag());
        Farm.keepOnClone(new net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone(now, old, true));
        h.assertTrue(Farm.data(now).getInt("habits_air") == 120, "air bank kept");
        h.assertTrue(Farm.data(now).getList(Animals.PETS, 10).size() == 1, "pet revive queue kept");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void greenThumbHarvestsAnArea(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos mid = new BlockPos(4, 1, 4);
        for (int lv : new int[]{1, 10}) {
            field(h, ripeWheat());
            ServerPlayer p = player(h, new BlockPos(4, 2, 4));
            own(p, "green_thumb", lv);
            Crops.greenThumb(p, l, h.absolutePos(mid), ripeWheat());
            BlockJobs.drain(p);
            int left = countWheat(h);
            if (lv == 1) {
                h.assertTrue(left >= 81 - 8, "Lv1 only touches the 3x3 ring, left " + left);
                for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++)
                    if (Math.max(Math.abs(x - 4), Math.abs(z - 4)) > 1) h.assertBlockPresent(Blocks.WHEAT, new BlockPos(x, 1, z));
            } else h.assertValueEqual(left, 1, "Lv10 picks the whole 9x9 except the broken one");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void harvestMoonGrowsCrops(GameTestHelper h) {
        BlockState young = ((CropBlock) Blocks.WHEAT).getStateForAge(0);
        int[][] cases = {{1, 0, 1}, {10, 0, 3}, {1, 1, 7}}; // level, full moon, expected age
        for (int[] c : cases) {
            h.setBlock(new BlockPos(1, 0, 1), Blocks.FARMLAND);
            h.setBlock(new BlockPos(1, 1, 1), young);
            ServerPlayer p = player(h, new BlockPos(1, 1, 1));
            Crops.harvestMoon(p, hit("harvest_moon", c[0]), c[1] == 1);
            Queue<BlockJobs.Job> q = BlockJobs.JOBS.get(p);
            int ticks = 0;
            List<Long> ns = new ArrayList<>();
            while (q != null && !q.isEmpty() && ticks < 10_000) {
                long t = System.nanoTime();
                BlockJobs.runTick(p, q);
                ns.add(System.nanoTime() - t);
                ticks++;
            }
            BlockJobs.JOBS.remove(p);
            long p90 = pct(ns, 0.9);
            LOG.info("[habits perf] harvest moon Lv{}: {} ticks, p90 {} us/tick, worst {} us/tick", c[0], ticks, p90 / 1000, pct(ns, 1) / 1000);
            h.assertTrue(p90 < 8_000_000L, "harvest moon tick (p90) under 8 ms, was " + p90 / 1000 + " us");
            int age = ((CropBlock) Blocks.WHEAT).getAge(h.getBlockState(new BlockPos(1, 1, 1)));
            h.assertValueEqual(age, c[2], "Lv" + c[0] + (c[1] == 1 ? " full moon" : "") + " age");
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void cocoaClimberClimbsLogs(GameTestHelper h) {
        h.setBlock(new BlockPos(1, 1, 0), Blocks.JUNGLE_LOG);
        h.setBlock(new BlockPos(1, 2, 0), Blocks.JUNGLE_LOG);
        double[] up = new double[2];
        for (int i = 0; i < 2; i++) {
            ServerPlayer p = player(h, new BlockPos(1, 1, 1));
            BlockPos a = h.absolutePos(new BlockPos(1, 1, 1));
            p.moveTo(a.getX() + 0.5, a.getY(), a.getZ() + 0.31);
            p.horizontalCollision = true;
            Crops.cocoaClimb(p, hit("cocoa_climber", i == 0 ? 1 : 10));
            up[i] = p.getDeltaMovement().y;
        }
        h.assertTrue(Math.abs(up[0] - 0.1) < 1e-6, "Lv1 climbs 0.1/tick, got " + up[0]);
        h.assertTrue(Math.abs(up[1] - 0.35) < 1e-6, "Lv10 climbs 0.35/tick, got " + up[1]);
        h.succeed();
    }

    /** Real vanilla 2x2 trees, broken at each of the four bottom logs: Lv10 timber fells all but the hit log. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void timberFellsTwoByTwoTrees(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        var reg = l.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE);
        StringBuilder out = new StringBuilder();
        var feats = List.of(net.minecraft.data.worldgen.features.TreeFeatures.DARK_OAK, net.minecraft.data.worldgen.features.TreeFeatures.MEGA_SPRUCE,
                net.minecraft.data.worldgen.features.TreeFeatures.MEGA_JUNGLE_TREE, net.minecraft.data.worldgen.features.TreeFeatures.MEGA_PINE);
        int fi = 0;
        for (var key : feats) {
            for (int corner = 0; corner < 4; corner++) {
                BlockPos base = h.absolutePos(new BlockPos(40 + fi * 40, 1, 40 + corner * 40));
                for (int x = -1; x <= 2; x++) for (int z = -1; z <= 2; z++) l.setBlock(base.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                boolean placed = reg.getHolderOrThrow(key).value().place(l, l.getChunkSource().getGenerator(), net.minecraft.util.RandomSource.create(7 + corner), base);
                BlockPos hitAt = base.offset(corner & 1, 0, corner >> 1);
                int before = countLogs(l, base);
                ServerPlayer p = player(h, new BlockPos(5, 1, 5));
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_AXE));
                HabitsApi.PerkHit hh = hit("timber", 10);
                int queued = Trees.fell(p, l, hitAt, (int) hh.value(), 8, p.getMainHandItem());
                Queue<BlockJobs.Job> q = BlockJobs.JOBS.getOrDefault(p, new ArrayDeque<>());
                for (int t = 0; t < 2000 && !q.isEmpty(); t++) BlockJobs.runTick(p, q);
                BlockJobs.JOBS.remove(p);
                int after = countLogs(l, base);
                out.append(String.format("%n  %s corner %d: placed=%s hitLog=%s logs %d, queued %d, left %d",
                        key.location().getPath(), corner, placed, l.getBlockState(hitAt).is(BlockTags.LOGS), before, queued, after));
                if (placed && before > 0) h.assertValueEqual(after, 1, key.location().getPath() + " corner " + corner + " fully felled");
            }
            fi++;
        }
        LOG.info("[habits timber 2x2]{}", out);
        h.succeed();
    }

    /** Lv2 timber on real 2x2 trees: always fells the 16 logs nearest the hit one (never skips the tree). */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void timberLowLevelTwoByTwo(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        var reg = l.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE);
        StringBuilder out = new StringBuilder();
        var feats = List.of(net.minecraft.data.worldgen.features.TreeFeatures.DARK_OAK, net.minecraft.data.worldgen.features.TreeFeatures.MEGA_SPRUCE,
                net.minecraft.data.worldgen.features.TreeFeatures.MEGA_JUNGLE_TREE, net.minecraft.data.worldgen.features.TreeFeatures.MEGA_PINE);
        int fi = 0, skipped = 0;
        for (var key : feats) {
            for (int corner = 0; corner < 4; corner++) for (int seed = 0; seed < 3; seed++) {
                BlockPos base = h.absolutePos(new BlockPos(-200 + fi * 40, 1, -200 + (corner * 3 + seed) * 40));
                for (int x = -1; x <= 2; x++) for (int z = -1; z <= 2; z++) l.setBlock(base.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                boolean placed = reg.getHolderOrThrow(key).value().place(l, l.getChunkSource().getGenerator(), net.minecraft.util.RandomSource.create(31L * seed + corner), base);
                BlockPos hitAt = base.offset(corner & 1, 0, corner >> 1);
                if (!placed || !l.getBlockState(hitAt).is(BlockTags.LOGS)) continue;
                ServerPlayer p = player(h, new BlockPos(5, 1, 5));
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_AXE));
                HabitsApi.PerkHit hh = hit("timber", 2);
                int queued = Trees.fell(p, l, hitAt, (int) hh.value(), 8, p.getMainHandItem());
                BlockJobs.JOBS.remove(p);
                if (queued != 16) { skipped++; out.append(String.format("%n  %s corner %d seed %d: queued %d", key.location().getPath(), corner, seed, queued)); }
            }
            fi++;
        }
        LOG.info("[habits timber lv2]{}", out);
        h.assertValueEqual(skipped, 0, "trees not felled 16 at Lv2:" + out);
        h.succeed();
    }

    /** Lv2 on real 2x2 trees: after the first 16 fall, chopping the lowest log left fells the next 16 (until gone). */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void timberSecondChop(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        var reg = l.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE);
        StringBuilder out = new StringBuilder();
        var feats = List.of(net.minecraft.data.worldgen.features.TreeFeatures.DARK_OAK, net.minecraft.data.worldgen.features.TreeFeatures.MEGA_SPRUCE,
                net.minecraft.data.worldgen.features.TreeFeatures.MEGA_JUNGLE_TREE, net.minecraft.data.worldgen.features.TreeFeatures.MEGA_PINE);
        int fi = 0, stuck = 0;
        for (var key : feats) {
            for (int seed = 0; seed < 4; seed++) {
                BlockPos base = h.absolutePos(new BlockPos(-300 + fi * 40, 1, 200 + seed * 40));
                for (int x = -1; x <= 2; x++) for (int z = -1; z <= 2; z++) l.setBlock(base.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                if (!reg.getHolderOrThrow(key).value().place(l, l.getChunkSource().getGenerator(), net.minecraft.util.RandomSource.create(77L * seed), base)) continue;
                ServerPlayer p = player(h, new BlockPos(5, 1, 5));
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_AXE));
                HabitsApi.PerkHit hh = hit("timber", 2);
                StringBuilder row = new StringBuilder();
                for (int chop = 0; chop < 12; chop++) {
                    BlockPos low = null;
                    for (BlockPos q : BlockPos.betweenClosed(base.offset(-8, 0, -8), base.offset(9, 40, 9)))
                        if (l.getBlockState(q).is(BlockTags.LOGS) && (low == null || q.getY() < low.getY())) low = q.immutable();
                    if (low == null) break;
                    int queued = Trees.fell(p, l, low, (int) hh.value(), 8, p.getMainHandItem());
                    l.destroyBlock(low, false);
                    BlockJobs.drain(p);
                    row.append(queued).append(' ');
                    if (queued == 0 && countLogs(l, base) > 0) { stuck++; out.append(String.format("%n  %s seed %d: chops %s(stuck at y+%d, %d logs left)", key.location().getPath(), seed, row,
                            low.getY() - base.getY(), countLogs(l, base))); break; }
                }
            }
            fi++;
        }
        LOG.info("[habits timber second chop]{}", out);
        h.assertValueEqual(stuck, 0, "trees that stop felling:" + out);
        h.succeed();
    }

    /** A bent 2x2 trunk (shifts sideways halfway up) with leaves only at the top: low-level Timber still knows it is a
     * tree; the same trunk with no leaves (a build) is never felled. */
    @GameTest(template = "empty")
    public static void timberBentTrunk(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        for (int built = 0; built < 2; built++) {
            BlockPos base = h.absolutePos(new BlockPos(2 + built * 8, 1, 2));
            for (int y = 0; y < 30; y++) {
                int sx = y < 10 ? 0 : 2; // the bend: the upper trunk sits two blocks over
                for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) l.setBlock(base.offset(sx + x, y, z), Blocks.SPRUCE_LOG.defaultBlockState(), 2);
                if (y == 10) for (int z = 0; z < 2; z++) l.setBlock(base.offset(1, y, z), Blocks.SPRUCE_LOG.defaultBlockState(), 2);
            }
            if (built == 0) for (int x = 1; x < 5; x++) for (int z = -1; z < 3; z++) l.setBlock(base.offset(x, 30, z), Blocks.SPRUCE_LEAVES.defaultBlockState(), 2);
            ServerPlayer p = player(h, new BlockPos(1, 1, 1));
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
            int queued = Trees.fell(p, l, base, (int) hit("timber", 2).value(), 8, p.getMainHandItem());
            BlockJobs.JOBS.remove(p);
            if (built == 0) h.assertValueEqual(queued, 16, "bent tree: 16 more logs queued");
            else h.assertValueEqual(queued, 0, "no leaves: a build, never felled");
        }
        h.succeed();
    }

    static int countLogs(ServerLevel l, BlockPos base) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(base.offset(-12, 0, -12), base.offset(13, 40, 13))) if (l.getBlockState(q).is(BlockTags.LOGS)) n++;
        return n;
    }

    /** A 4x4 trunk, 16 tall (256 logs) with a natural leaf on top. */
    static void trunk(GameTestHelper h) {
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) for (int y = 1; y <= 16; y++) h.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
        h.setBlock(new BlockPos(0, 17, 0), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false));
    }

    static int logs(GameTestHelper h) {
        int n = 0;
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) for (int y = 1; y <= 16; y++) if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.OAK_LOG)) n++;
        return n;
    }

    /** Timber: Lv1 vs Lv10, and a 256-log fell stays inside the per-tick block budget and time. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void timberFellsWithinBudget(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        for (int lv : new int[]{1, 10}) {
            trunk(h);
            ServerPlayer p = player(h, new BlockPos(5, 1, 5));
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_AXE));
            HabitsApi.PerkHit hh = hit("timber", lv);
            int[] rolls = new int[1];
            Triggers.Spy spy = (who, type, ctx) -> { if (who == p && type == com.habits.core.TriggerType.BREAK_BLOCK) rolls[0]++; };
            Triggers.SPIES.add(spy);
            long t0 = System.nanoTime();
            int queued = Trees.fell(p, l, h.absolutePos(new BlockPos(0, 1, 0)), (int) hh.value(), (int) Farm.lerp(hh, "per_tick", 2, 8), p.getMainHandItem());
            long scan = System.nanoTime() - t0;
            Queue<BlockJobs.Job> q = BlockJobs.JOBS.getOrDefault(p, new ArrayDeque<>());
            int ticks = 0, maxOps = 0;
            long sumNs = 0;
            List<Long> ns = new ArrayList<>();
            while (!q.isEmpty() && ticks < 1000) {
                long s = System.nanoTime();
                int ops = BlockJobs.runTick(p, q);
                long dt = System.nanoTime() - s;
                ns.add(dt);
                sumNs += dt;
                maxOps = Math.max(maxOps, ops);
                ticks++;
            }
            BlockJobs.JOBS.remove(p);
            Triggers.SPIES.remove(spy);
            int left = logs(h);
            h.assertValueEqual(rolls[0], 256 - left, "every felled log rolls a break_block once");
            long maxNs = pct(ns, 1), p90 = pct(ns, 0.9);
            LOG.info("[habits perf] timber Lv{}: {} logs, scan {} us, {} ticks, max {} ops/tick, avg {} us/tick, max {} us/tick", lv, queued, scan / 1000, ticks, maxOps,
                    ticks == 0 ? 0 : sumNs / ticks / 1000, maxNs / 1000);
            if (lv == 1) h.assertValueEqual(left, 256 - 8, "Lv1 fells 8 more logs");
            else h.assertValueEqual(left, 1, "Lv10 fells all 256 (the one you hit stays for you)");
            h.assertTrue(maxOps <= BlockJobs.BUDGET, "ops per tick " + maxOps + " <= budget " + BlockJobs.BUDGET);
            h.assertTrue(scan < 20_000_000L, "tree scan under 20 ms, was " + scan / 1000 + " us");
            h.assertTrue(p90 < 8_000_000L, "tick (p90) under 8 ms, was " + p90 / 1000 + " us");
        }
        h.succeed();
    }

    /** Growth aura: Lv10 gives more ticks per second than Lv1, and stays cheap. */
    @GameTest(template = "empty")
    public static void growthAuraIsSampledAndCheap(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        field(h, ((CropBlock) Blocks.WHEAT).getStateForAge(0));
        int[] best = new int[2];
        long total = 0;
        List<Long> ns = new ArrayList<>();
        for (int i = 0; i < 2; i++) { // the Lv1 pass doubles as the JIT warm-up
            ServerPlayer p = player(h, new BlockPos(4, 1, 4));
            HabitsApi.PerkHit hh = hit("growth_aura", i == 0 ? 1 : 10);
            for (int s = 0; s < 200; s++) {
                long t = System.nanoTime();
                int done = Crops.growthAura(l, p, hh);
                long dt = System.nanoTime() - t;
                if (i == 1) { ns.add(dt); total += dt; }
                best[i] = Math.max(best[i], done);
                h.assertTrue(done <= hh.value(), "never more than value ticks");
            }
        }
        long p95 = pct(ns, 0.95);
        LOG.info("[habits perf] growth aura Lv10: max {} ticks/s, {} us avg, {} us p95, {} us worst per second-step", best[1], total / 200 / 1000.0,
                p95 / 1000, pct(ns, 1) / 1000);
        h.assertTrue(best[0] <= 1, "Lv1 gives at most 1 tick a second");
        h.assertTrue(best[1] >= 2, "Lv10 gives several ticks a second, best " + best[1]);
        h.assertTrue(p95 < 2_000_000L, "aura step (p95) under 2 ms, was " + p95 / 1000 + " us");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void leashMasterLeashesMoreAtHigherLevels(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        Villager v = h.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 1));
        Zombie z = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 1));
        h.assertFalse(Animals.leash(p, v, hit("leash_master", 1)), "Lv1 can't leash villagers");
        h.assertFalse(Animals.leash(p, z, hit("leash_master", 9)), "Lv9 can't leash monsters");
        h.assertTrue(Animals.leash(p, v, hit("leash_master", 5)), "Lv5 leashes villagers");
        h.assertTrue(Animals.leash(p, z, hit("leash_master", 10)), "Lv10 leashes monsters");
        h.assertTrue(v.getLeashHolder() == p && z.getLeashHolder() == p, "held by the player");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void catPersonScaresCreepers(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        Creeper c = h.spawn(EntityType.CREEPER, new BlockPos(6, 1, 1));
        h.assertTrue(c.goalSelector.getAvailableGoals().stream().anyMatch(w -> w.getGoal() instanceof AvoidEntityGoal<?>), "creeper got the avoid goal");
        own(p, "cat_person", 1);
        h.assertFalse(Animals.scares(p, c), "Lv1 (2 blocks) does not reach 5 blocks");
        own(p, "cat_person", 10);
        h.assertTrue(Animals.scares(p, c), "Lv10 (16 blocks) scares at 5 blocks");
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(Habits.id("cat_person"), new SkillProgress(10, 0, false)));
        h.assertFalse(Animals.scares(p, c), "toggled off does nothing");
        h.succeed();
    }

    /** A piper the level knows about: Pied Piper checks every player in the level, and a FakePlayer is not in it. */
    static ServerPlayer piper(GameTestHelper h, BlockPos rel) {
        ServerPlayer p = player(h, rel);
        h.getLevel().addNewPlayer(p);
        return p;
    }

    /** Cows that only move along a path (no wandering). The chunks around the test are force-loaded: only the 3x3
     * template's own chunk ticks entities, and a herd walking a few blocks out crosses its edge. Never unforced, since
     * the test runner forces chunks the same way and an unforce would drop its ticket too. */
    static List<Cow> herd(GameTestHelper h, BlockPos... at) {
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) h.getLevel().setChunkForced((o.getX() >> 4) + x, (o.getZ() >> 4) + z, true);
        List<Cow> cows = new ArrayList<>();
        for (BlockPos b : at) {
            Cow c = h.spawn(EntityType.COW, b);
            c.goalSelector.removeAllGoals(g -> true);
            cows.add(c);
        }
        return cows;
    }

    static void leave(GameTestHelper h, ServerPlayer... ps) {
        for (ServerPlayer p : ps) h.getLevel().removePlayerImmediately(p, Entity.RemovalReason.DISCARDED);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void piedPiperGathersTheWholeHerd(GameTestHelper h) {
        ServerPlayer p = piper(h, new BlockPos(0, 1, 0));
        List<Cow> cows = herd(h, new BlockPos(5, 1, 0), new BlockPos(0, 1, 5), new BlockPos(5, 1, 5), new BlockPos(4, 1, 2));
        h.succeedWhen(() -> {
            if (h.getTick() % 10 == 0) Animals.pipe(p, 16);
            for (Cow c : cows) h.assertTrue(c.distanceToSqr(p) <= 9, "every cow reaches the piper");
            leave(h, p);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void piedPiperAnimalsStickToOnePiper(GameTestHelper h) {
        ServerPlayer a = piper(h, new BlockPos(0, 1, 2)), b = piper(h, new BlockPos(6, 1, 2));
        List<Cow> cows = herd(h, new BlockPos(1, 1, 6), new BlockPos(2, 1, 6), new BlockPos(3, 1, 6), new BlockPos(4, 1, 6));
        java.util.Map<Cow, BlockPos> goal = new java.util.HashMap<>();
        Runnable sameGoal = () -> {
            for (Cow c : cows) {
                var nav = c.getNavigation();
                if (nav.isDone()) continue;
                BlockPos was = goal.putIfAbsent(c, nav.getTargetPos());
                h.assertTrue(was == null || was.equals(nav.getTargetPos()), "a cow walking to one piper is not pulled to the other");
            }
        };
        h.onEachTick(() -> {
            if (h.getTick() % 10 != 0 || h.getTick() >= 300) return;
            Animals.pipe(a, 12); // both pulse on one tick: before, b re-pathed every cow a had just sent to a
            sameGoal.run();
            Animals.pipe(b, 12);
            sameGoal.run();
        });
        h.runAtTickTime(300, () -> {
            for (Cow c : cows) h.assertTrue(c.distanceToSqr(a) <= 9 || c.distanceToSqr(b) <= 9, "every cow reached a piper");
            leave(h, a, b);
            h.succeed();
        });
    }

    /** A powered dispenser releases a critter bucket's animal in front and keeps the empty bucket (it used to scoop as an
     * empty bucket and lose the animal). Control: a plain empty bucket in a dispenser still picks up water. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void critterBucketDispenserReleasesTheAnimal(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(0, 1, 0));
        var rabbit = h.spawn(EntityType.RABBIT, new BlockPos(4, 1, 4));
        h.assertTrue(Animals.scoop(p, rabbit, new ItemStack(Items.BUCKET), 3), "scooped");
        ItemStack full = ItemStack.EMPTY;
        for (ItemStack s : p.getInventory().items) if (Animals.isCritterBucket(s)) full = s;
        h.assertTrue(Animals.isCritterBucket(full), "got a critter bucket");
        BlockState east = Blocks.DISPENSER.defaultBlockState().setValue(net.minecraft.world.level.block.DispenserBlock.FACING, net.minecraft.core.Direction.EAST);
        BlockPos d1 = new BlockPos(1, 1, 1), d2 = new BlockPos(1, 1, 3);
        h.setBlock(d1, east);
        h.setBlock(d2, east);
        var be1 = (net.minecraft.world.level.block.entity.DispenserBlockEntity) h.getBlockEntity(d1);
        var be2 = (net.minecraft.world.level.block.entity.DispenserBlockEntity) h.getBlockEntity(d2);
        be1.setItem(0, full.copy());
        be2.setItem(0, new ItemStack(Items.BUCKET));
        h.setBlock(d2.east(), Blocks.WATER);
        h.setBlock(d1.west(), Blocks.REDSTONE_BLOCK);
        h.setBlock(d2.west(), Blocks.REDSTONE_BLOCK);
        h.succeedWhen(() -> {
            h.assertTrue(be1.getItem(0).is(Items.BUCKET) && !Animals.isCritterBucket(be1.getItem(0)), "the dispenser keeps an empty bucket");
            var rabbits = h.getEntities(EntityType.RABBIT);
            h.assertValueEqual(rabbits.size(), 1, "the rabbit is back");
            h.assertTrue(rabbits.get(0).position().closerThan(h.absolutePos(d1.east()).getBottomCenter(), 1.5), "released in front of the dispenser");
            h.assertTrue(be2.getItem(0).is(Items.WATER_BUCKET), "control: a plain bucket still scoops water");
        });
    }

    /** Pied Piper toggles on a sneak press only with an empty main hand; sneaking with blocks in hand never flips it. */
    @GameTest(template = "empty")
    public static void piedPiperTogglesOnlyEmptyHanded(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(0, 1, 0));
        own(p, "pied_piper", 5);
        HabitsApi.PerkHit hit = hit("pied_piper", 5);
        java.util.function.BooleanSupplier off = () -> Farm.data(p).getBoolean("habits_piper_off");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < 3; i++) {
            p.setShiftKeyDown(true);
            Animals.piedPiper(p, hit);
            p.setShiftKeyDown(false);
            Animals.piedPiper(p, hit);
        }
        h.assertFalse(off.getAsBoolean(), "three sneak presses with blocks in hand leave it on");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setShiftKeyDown(true);
        Animals.piedPiper(p, hit);
        h.assertTrue(off.getAsBoolean(), "an empty-handed sneak press switches it off");
        Animals.piedPiper(p, hit);
        h.assertTrue(off.getAsBoolean(), "holding sneak does not flip it back");
        p.setShiftKeyDown(false);
        Animals.piedPiper(p, hit);
        p.setShiftKeyDown(true);
        Animals.piedPiper(p, hit);
        h.assertFalse(off.getAsBoolean(), "the next empty-handed press switches it on");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void piedPiperLeavesSwarmBees(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(0, 1, 0));
        Bee wild = h.spawn(EntityType.BEE, new BlockPos(5, 2, 0)), summon = h.spawn(EntityType.BEE, new BlockPos(0, 2, 5));
        summon.getPersistentData().putBoolean("habits_swarm", true);
        for (Bee b : List.of(wild, summon)) b.goalSelector.removeAllGoals(g -> true);
        Animals.pipe(p, 12);
        h.assertTrue(!wild.getNavigation().isDone() && wild.getNavigation().getTargetPos().closerToCenterThan(p.position(), 3), "a wild bee follows the piper");
        h.assertTrue(summon.getNavigation().isDone(), "a Swarm bee is left alone");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void swarmKeepsItsSize(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(1, 2, 1));
        Animals.swarm(l, p, hit("swarm", 1));
        h.assertValueEqual(Animals.SWARM.get(p.getUUID()).size(), 1, "Lv1 swarm");
        Animals.swarm(l, p, hit("swarm", 10));
        h.assertValueEqual(Animals.SWARM.get(p.getUUID()).size(), 6, "Lv10 swarm");
        Animals.SWARM.get(p.getUUID()).get(0).discard();
        Animals.swarm(l, p, hit("swarm", 10));
        h.assertValueEqual(Animals.SWARM.get(p.getUUID()).size(), 6, "a lost bee is replaced");
        Animals.dismissSwarm(p.getUUID());
        h.assertTrue(Animals.SWARM.get(p.getUUID()) == null, "dismissed on logout");
        h.succeed();
    }

    static FishingHook bite(ServerLevel l, ServerPlayer p) {
        FishingHook hook = new FishingHook(p, l, 0, 0);
        l.addFreshEntity(hook);
        hook.nibble = 20; // opened by the access transformer
        return hook;
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void anglerHooksBites(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
        // Lv10: every bite reels, then the rod recasts
        FishingHook hook = bite(l, p);
        h.assertTrue(p.fishing == hook, "hook is the player's");
        Fishing.anglerTick(p, hit("angler", 10));
        h.assertTrue(hook.isRemoved(), "Lv10 reeled in");
        h.assertTrue(p.fishing != null && p.fishing != hook, "Lv10 recast");
        p.fishing.discard();
        // Lv1: about one bite in five, no recast
        int reeled = 0;
        for (int i = 0; i < 60; i++) {
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
            FishingHook b = bite(l, p);
            Fishing.anglerTick(p, hit("angler", 1));
            if (b.isRemoved()) {
                reeled++;
                h.assertTrue(p.fishing == null, "Lv1 does not recast");
            } else b.discard();
        }
        h.assertTrue(reeled >= 2 && reeled <= 30, "Lv1 reels about 20%, got " + reeled + "/60");
        h.succeed();
    }

    /** A 3x3 lava pool at y 0..1 under rel (1,*,1), and a bobber dropped into its middle. */
    static FishingHook lavaHook(GameTestHelper h, ServerPlayer p) {
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.LAVA);
            h.setBlock(new BlockPos(x, 1, z), Blocks.LAVA);
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
        FishingHook hook = new FishingHook(p, h.getLevel(), 0, 0);
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        hook.moveTo(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        hook.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        return hook;
    }

    @GameTest(template = "empty")
    public static void lavaAnglerFloatsBitesAndPaysNetherLoot(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(1, 3, 6));
        BlockPos pool = h.absolutePos(new BlockPos(1, 1, 1));
        // FishingHookMixin: without the skill the bobber sinks through the lava, with it it bobs at the top and burns not
        FishingHook sinker = lavaHook(h, p);
        for (int i = 0; i < 40; i++) sinker.tick();
        h.assertTrue(sinker.getY() < pool.getY(), "no skill: sinks, y " + sinker.getY());
        own(p, "lava_angler", 1);
        FishingHook hook = lavaHook(h, p);
        for (int i = 0; i < 40; i++) {
            if (Fishing.inLava(hook)) hook.lavaHurt(); // what baseTick does in lava; EntityMixin keeps it unlit
            hook.tick();
        }
        h.assertTrue(hook.getY() > pool.getY() + 0.3 && Fishing.inLava(hook), "skill: floats on top, y " + hook.getY());
        h.assertFalse(hook.isOnFire(), "the bobber does not burn");
        // the wait: Lv1 gives back about 2 ticks in 3 (3x slower), Lv10 none
        for (int lv : new int[]{1, 10}) {
            hook.timeUntilLured = 10_000;
            for (int i = 0; i < 300; i++) Fishing.lavaTick(p, hit("lava_angler", lv));
            int back = hook.timeUntilLured - 10_000;
            if (lv == 1) h.assertTrue(back > 150 && back < 250, "Lv1 slows the wait ~3x, gave back " + back + "/300");
            else h.assertValueEqual(back, 0, "Lv10 is vanilla speed");
        }
        // a lava bite pays from the Nether table (vanilla fish emptied, our loot spawned instead)
        own(p, "lava_angler", 10);
        hook.nibble = 20;
        hook.retrieve(p.getMainHandItem());
        var caught = l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, hook.getBoundingBox().inflate(3), e -> !e.getItem().isEmpty());
        h.assertTrue(!caught.isEmpty(), "something was caught");
        // raw water fish only: the lava table pays cooked cod on purpose, and cooked cod is in #minecraft:fishes
        for (var e : caught) h.assertFalse(e.getItem().is(Items.COD) || e.getItem().is(Items.SALMON)
                || e.getItem().is(Items.TROPICAL_FISH) || e.getItem().is(Items.PUFFERFISH), "no water fish from lava: " + e.getItem());
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void leashMasterHoldsPastTheSnap(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        for (int lv : new int[]{1, 10}) {
            own(p, "leash_master", lv);
            var cow = h.spawn(EntityType.COW, new BlockPos(1, 1, 1));
            cow.setLeashedTo(p, true);
            p.moveTo(cow.getX() + 20, cow.getY(), cow.getZ()); // past vanilla's 10, past Lv1's 15, inside Lv10's 40
            net.minecraft.world.entity.Leashable.tickLeash(cow); // MobMixin on leashTooFarBehaviour
            if (lv == 1) h.assertFalse(cow.isLeashed(), "Lv1 (15 blocks) snaps at 20");
            else h.assertTrue(cow.getLeashHolder() == p, "Lv10 (40 blocks) holds at 20");
            cow.discard();
        }
        h.succeed();
    }

    static Object phantomSwoop(net.minecraft.world.entity.monster.Phantom ph) throws ReflectiveOperationException {
        var phase = net.minecraft.world.entity.monster.Phantom.class.getDeclaredField("attackPhase");
        phase.setAccessible(true);
        for (Object c : phase.getType().getEnumConstants()) if (((Enum<?>) c).name().equals("SWOOP")) phase.set(ph, c);
        return ph.goalSelector.getAvailableGoals().stream().map(w -> w.getGoal())
                .filter(g -> g.getClass().getSimpleName().equals("PhantomSweepAttackGoal")).findFirst().orElseThrow();
    }

    @GameTest(template = "empty")
    public static void catPersonScaresPhantoms(GameTestHelper h) throws ReflectiveOperationException {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        for (int lv : new int[]{0, 1, 10}) {
            if (lv == 0) p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY);
            else own(p, "cat_person", lv);
            var ph = h.spawn(EntityType.PHANTOM, new BlockPos(1, 1, 6)); // 5 blocks off
            ph.setTarget(p);
            var goal = (net.minecraft.world.entity.ai.goal.Goal) phantomSwoop(ph);
            boolean swoops = goal.canContinueToUse(); // PhantomSweepAttackGoalMixin
            if (lv == 10) h.assertFalse(swoops, "Lv10 (16 blocks) breaks off the swoop");
            else h.assertTrue(swoops, "Lv" + lv + " keeps swooping at 5 blocks");
            ph.discard();
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void beekeeperFillsOnEachReturn(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        l.setDayTime(1000);
        ServerPlayer p = player(h, new BlockPos(1, 1, 3));
        l.players().add(p); // beeReturned looks through the level's players; fake players are not in it
        try {
            BlockPos rel = new BlockPos(1, 1, 1);
            int[] extra = new int[11];
            for (int lv : new int[]{1, 10}) {
                own(p, "beekeeper", lv);
                for (int i = 0; i < 40; i++) {
                    h.setBlock(rel, Blocks.BEEHIVE.defaultBlockState().setValue(net.minecraft.world.level.block.BeehiveBlock.FACING, net.minecraft.core.Direction.SOUTH));
                    var hive = (net.minecraft.world.level.block.entity.BeehiveBlockEntity) l.getBlockEntity(h.absolutePos(rel));
                    hive.addOccupant(h.spawn(EntityType.BEE, new BlockPos(1, 2, 3)));
                    hive.emptyAllLivingFromHive(null, h.getBlockState(rel), net.minecraft.world.level.block.entity.BeehiveBlockEntity.BeeReleaseStatus.HONEY_DELIVERED);
                    extra[lv] += h.getBlockState(rel).getValue(net.minecraft.world.level.block.BeehiveBlock.HONEY_LEVEL) - 1; // vanilla adds 1
                }
                l.getEntitiesOfClass(net.minecraft.world.entity.animal.Bee.class, new net.minecraft.world.phys.AABB(h.absolutePos(rel)).inflate(8)).forEach(b -> b.discard());
            }
            h.assertTrue(extra[1] >= 3 && extra[1] <= 22, "Lv1 adds about 1 level in 4 returns: " + extra[1] + "/40");
            h.assertTrue(extra[10] >= 80, "Lv10 adds 2 levels each return (3x fill): " + extra[10] + "/40");
        } finally {
            l.players().remove(p);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void sneezeHarvestMultipliesSneezes(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        var pd = h.spawn(EntityType.PANDA, new BlockPos(3, 1, 1));
        var goal = (net.minecraft.world.entity.ai.goal.Goal) pd.goalSelector.getAvailableGoals().stream().map(w -> w.getGoal())
                .filter(g -> g.getClass().getSimpleName().equals("PandaSneezeGoal")).findFirst().orElseThrow();
        int[] n = new int[11];
        for (int lv : new int[]{1, 10}) {
            own(p, "sneeze_harvest", lv);
            pd.getPersistentData().remove(FarmHooks.SNEEZE_UNTIL);
            FarmHooks.stampPanda(pd, hit("sneeze_harvest", lv)); // what sneezeTick does to pandas near the owner
            for (int i = 0; i < 30_000; i++) if (goal.canUse()) n[lv]++; // PandaSneezeGoalMixin (an adult: vanilla never sneezes)
        }
        h.assertTrue(n[1] >= 5 && n[1] <= 35, "Lv1 1.5x: about 15 in 30000, got " + n[1]);
        h.assertTrue(n[10] >= 65 && n[10] <= 140, "Lv10 10x: about 100 in 30000, got " + n[10]);
        pd.getPersistentData().remove(FarmHooks.SNEEZE_UNTIL);
        h.assertFalse(goal.canUse(), "no harvester near: vanilla adult never sneezes");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void ghostRiderStandsOnFluids(GameTestHelper h) throws ReflectiveOperationException {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        var horse = h.spawn(EntityType.HORSE, new BlockPos(1, 1, 1));
        horse.setTamed(true);
        horse.equipSaddle(new ItemStack(Items.SADDLE), null);
        // FakePlayer refuses startRiding, so seat it by hand (what Entity.startRiding does)
        var vehicle = net.minecraft.world.entity.Entity.class.getDeclaredField("vehicle");
        vehicle.setAccessible(true);
        vehicle.set(p, horse);
        var add = net.minecraft.world.entity.Entity.class.getDeclaredMethod("addPassenger", net.minecraft.world.entity.Entity.class);
        add.setAccessible(true);
        add.invoke(horse, p);
        h.assertTrue(horse.getControllingPassenger() == p, "player steers: saddled " + horse.isSaddled() + " passengers " + horse.getPassengers() + " vehicle " + p.getVehicle());
        var water = net.minecraft.world.level.material.Fluids.WATER.defaultFluidState();
        var lava = net.minecraft.world.level.material.Fluids.LAVA.defaultFluidState();
        int[][] cases = {{1, 0, 0}, {5, 1, 0}, {10, 1, 1}}; // level, stands on water, stands on lava
        for (int[] c : cases) {
            own(p, "ghost_rider", c[0]);
            h.assertValueEqual(horse.canStandOnFluid(water), c[1] == 1, "water at Lv" + c[0]); // LivingEntityMixin
            h.assertValueEqual(horse.canStandOnFluid(lava), c[2] == 1, "lava at Lv" + c[0]);
            horse.clearFire();
            horse.lavaHurt(); // EntityMixin
            h.assertValueEqual(horse.isOnFire(), c[2] == 0, "burns in lava at Lv" + c[0]);
        }
        p.stopRiding();
        h.assertFalse(horse.canStandOnFluid(water), "riderless: vanilla");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void shoulderLookoutOutlinesAtLv10(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        p.setYRot(0); // looking +z
        var z = h.spawn(EntityType.ZOMBIE, new BlockPos(1, 1, -4)); // 5 blocks behind
        h.assertTrue(Animals.lookout(p, hit("shoulder_lookout", 1)).isEmpty(), "Lv1 is sound only");
        var ids = Animals.lookout(p, hit("shoulder_lookout", 10));
        h.assertTrue(ids.contains(z.getId()), "Lv10 outlines the mob behind");
        // every hostile, not only Monster subclasses; a cow is not one
        var slime = h.spawn(EntityType.SLIME, new BlockPos(2, 1, -3));
        var phantom = h.spawn(EntityType.PHANTOM, new BlockPos(0, 3, -3));
        var cow = h.spawn(EntityType.COW, new BlockPos(1, 1, -2));
        var all = Animals.lookout(p, hit("shoulder_lookout", 10));
        h.assertTrue(all.contains(slime.getId()) && all.contains(phantom.getId()), "slime and phantom outlined");
        h.assertFalse(all.contains(cow.getId()), "cow not outlined");
        // what the client does with the packet (MinecraftMixin asks lookoutGlow)
        long now = h.getLevel().getGameTime();
        FarmHooks.outline(ids, now);
        h.assertTrue(FarmHooks.lookoutGlow(z), "outlined right after the packet");
        FarmHooks.outline(List.of(), now + 16);
        h.assertFalse(FarmHooks.lookoutGlow(z), "fades when the server stops refreshing it");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void allayPalFetchesFromFurther(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        l.players().add(p); // the allay finds its liked player through the level's list
        try {
            var allay = h.spawn(EntityType.ALLAY, new BlockPos(1, 8, 1)); // above the test's barrier roof, so the sky is clear
            allay.setNoAi(true);
            allay.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND));
            allay.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.LIKED_PLAYER, p.getUUID());
            var item = new net.minecraft.world.entity.item.ItemEntity(l, allay.getX(), allay.getY() + 30, allay.getZ(), new ItemStack(Items.DIAMOND));
            item.setNoGravity(true);
            l.addFreshEntity(item);
            var sensor = new net.minecraft.world.entity.ai.sensing.NearestItemSensor();
            var wanted = net.minecraft.world.entity.ai.memory.MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM;
            for (int lv : new int[]{0, 1, 10}) { // vertical reach: vanilla 16, Lv1 24, Lv10 48
                if (lv == 0) p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY);
                else own(p, "allay_pal", lv);
                allay.getBrain().eraseMemory(wanted);
                for (int i = 0; i < 25; i++) sensor.tick(l, allay); // NearestItemSensorMixin
                boolean seen = allay.getBrain().getMemory(wanted).isPresent();
                h.assertValueEqual(seen, lv == 10, "item 30 blocks up seen at Lv" + lv + " range x" + FarmHooks.allayRange(allay) + " wants " + allay.wantsToPickUp(item.getItem()) + " sight " + allay.hasLineOfSight(item));
            }
            item.discard();
        } finally {
            l.players().remove(p);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ Eden's Touch

    /** Lv10: about 12% of carrot harvests add a golden carrot, plus Lv10's rare enchanted golden apple. Lv1 never rolls the apple. */
    @GameTest(template = "empty")
    public static void edensTouchGildsHarvests(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(1, 1, 1));
        int[] gold = new int[2], notch = new int[2];
        int[] lv = {1, 10};
        for (int i = 0; i < 2; i++) {
            ServerPlayer p = player(h, new BlockPos(1, 1, 1));
            own(p, "edens_touch", lv[i]);
            List<net.minecraft.world.entity.item.ItemEntity> drops = new ArrayList<>();
            for (int n = 0; n < 4000; n++) Crops.edensTouch(p, l, pos, Blocks.CARROTS, drops);
            for (var d : drops) {
                if (d.getItem().is(Items.GOLDEN_CARROT)) gold[i]++;
                if (d.getItem().is(Items.ENCHANTED_GOLDEN_APPLE)) notch[i]++;
            }
            Triggers.TEST_PLAYERS.remove(p.getUUID());
        }
        h.assertTrue(gold[0] > 40 && gold[0] < 140, "Lv1 about 2% of 4000 = 80, got " + gold[0]);
        h.assertTrue(gold[1] > 380 && gold[1] < 580, "Lv10 about 12% of 4000 = 480, got " + gold[1]);
        h.assertValueEqual(notch[0], 0, "no enchanted apples below Lv10");
        h.assertTrue(notch[1] >= 4 && notch[1] <= 50, "Lv10 about 0.5% of 4000 = 20 enchanted apples, got " + notch[1]);
        // a placed pumpkin is no harvest; one next to a grown stem is
        h.assertFalse(Crops.grownOnStem(l, pos), "no stem, no harvest");
        h.setBlock(new BlockPos(2, 1, 1), Blocks.PUMPKIN_STEM.defaultBlockState().setValue(net.minecraft.world.level.block.StemBlock.AGE, 7));
        h.assertTrue(Crops.grownOnStem(l, pos), "next to a grown stem");
        h.succeed();
    }

    /** eight_crops_day fires once when the 8th different crop is harvested in one day, not again that day. */
    @GameTest(template = "empty")
    public static void eightCropsDayFiresOnce(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(1, 1, 1));
        int[] fired = new int[1];
        Triggers.Spy spy = (who, type, ctx) -> {
            if (who == p && ctx.target() != null && ctx.target().getPath().equals("eight_crops_day")) fired[0]++;
        };
        Triggers.SPIES.add(spy);
        try {
            for (int i = 0; i < 7; i++) FarmTriggers.harvested(p, h.getLevel(), FarmTriggers.EIGHT[i], net.minecraft.world.phys.Vec3.ZERO);
            FarmTriggers.harvested(p, h.getLevel(), Blocks.WHEAT, net.minecraft.world.phys.Vec3.ZERO);
            FarmTriggers.harvested(p, h.getLevel(), Blocks.SWEET_BERRY_BUSH, net.minecraft.world.phys.Vec3.ZERO);
            h.assertValueEqual(fired[0], 0, "7 crops is not enough");
            FarmTriggers.harvested(p, h.getLevel(), Blocks.NETHER_WART, net.minecraft.world.phys.Vec3.ZERO);
            h.assertValueEqual(fired[0], 1, "the 8th crop fires");
            for (var b : FarmTriggers.EIGHT) FarmTriggers.harvested(p, h.getLevel(), b, net.minecraft.world.phys.Vec3.ZERO);
            h.assertValueEqual(fired[0], 1, "once per day");
        } finally {
            Triggers.SPIES.remove(spy);
            Triggers.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }
}
