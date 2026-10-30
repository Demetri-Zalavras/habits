package com.habits.perf;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.PerkType;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Dev-only server stress harness (docs/PERF.md). Inert unless the env var {@code HABITS_PERF=1} is set on a dedicated
 * server ({@code runServer}). Spawns 20 fake players that drive real game paths every tick, rotates the perk mode
 * (FULL = every skill Lv10 on, OFF = owned but toggled off, NONE = no skills), measures MSPT and server-thread
 * allocation per phase, records one JFR file per phase, then runs the trigger/API microbenchmarks and the
 * login/logout churn leak check, writes {@code perf/results.txt} and stops the server.
 */
@EventBusSubscriber(modid = Habits.MODID)
public final class PerfHarness {
    private PerfHarness() {}

    private static final Logger LOG = LogUtils.getLogger();
    static final boolean ON = "1".equals(System.getenv("HABITS_PERF"));
    static final int PLAYERS = 20;
    static final int WARMUP = intEnv("HABITS_PERF_WARMUP", 1200);
    static final int PHASE_SECONDS = intEnv("HABITS_PERF_SECONDS", 120);
    static final int MAX_TICKS = 20 * 60 * 30;
    static final int ROUNDS = intEnv("HABITS_PERF_ROUNDS", 3);
    static final boolean JFR = !"0".equals(System.getenv("HABITS_PERF_JFR"));

    enum Mode { FULL, OFF, NONE }

    static final List<FakePlayer> players = new ArrayList<>();
    static final Map<FakePlayer, Zombie> zombies = new HashMap<>();
    static final List<String> report = new ArrayList<>();
    static final Map<Mode, List<double[]>> results = new TreeMap<>();
    static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    static MinecraftServer server;
    static ServerLevel level;
    static BlockPos origin;
    static int tick;            // ticks since harness start
    static List<Mode> schedule; // phase -> mode, after warmup
    static long[] tickNs;
    static long[] tickBytes;
    static int phaseIdx = -1, phaseTick;
    static long phaseEnd;
    static int sweptItems, sweptOrbs;
    /** Trigger events per type in the current phase (a spy on the trigger gate): shows per-unit batching. */
    static final int[] FIRED = new int[TriggerType.values().length];
    static long t0, b0;
    static Object recording;    // jdk.jfr.Recording, kept untyped so the class loads without jdk.jfr
    static boolean done;

    static int intEnv(String k, int def) {
        String v = System.getenv(k);
        return v == null ? def : Integer.parseInt(v);
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent e) {
        if (!ON || !e.getServer().isDedicatedServer()) return;
        server = e.getServer();
        level = server.overworld();
        server.setDifficulty(Difficulty.NORMAL, true);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        level.setDayTime(18000);
        BlockPos spawn = level.getSharedSpawnPos();
        origin = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, spawn);
        // flat arena so every phase sees the same blocks
        for (int x = -24; x <= 56; x++) for (int z = -24; z <= 48; z++) {
            level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
            for (int y = 0; y < 4; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        for (int i = 0; i < PLAYERS; i++) players.add(login(i, UUID.nameUUIDFromBytes(("habits-perf-" + i).getBytes())));
        Triggers.SPIES.add((pl, type, ctx) -> FIRED[type.ordinal()]++);
        schedule = new ArrayList<>();
        for (int r = 0; r < ROUNDS; r++) schedule.addAll(List.of(Mode.FULL, Mode.OFF, Mode.NONE));
        setMode(Mode.FULL);
        LOG.info("[habits-perf] started: {} players, {} skills, warmup {} ticks, {} phases x {} s",
                PLAYERS, SkillRegistry.all().size(), WARMUP, schedule.size(), PHASE_SECONDS);
    }

    static FakePlayer login(int i, UUID uuid) {
        // a plain FakePlayer takes no damage and drops stat awards, so the damage, use, pickup and break triggers never ran
        FakePlayer p = new PlaySim.SimPlayer(level, new GameProfile(uuid, "perf" + i));
        p.setGameMode(GameType.SURVIVAL);
        BlockPos b = base(i);
        p.moveTo(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, 0, 0);
        allow(uuid, true);
        giveChannel(p);
        level.addNewPlayer(p);
        playerList().add(p);
        EventHooks.firePlayerLoggedIn(p);
        restock(p);
        return p;
    }

    static void logout(FakePlayer p) {
        EventHooks.firePlayerLoggedOut(p);
        playerList().remove(p);
        Zombie z = zombies.remove(p);
        if (z != null) z.discard();
        if (p.fishing != null) p.fishing.discard();
        p.discard();
        allow(p.getUUID(), false);
    }

    /** Vanilla broadcasts ask every listed player's netty channel for its payload setup; a FakePlayer has none. An
     * empty EmbeddedChannel answers "no channels" and FakePlayer's handler drops every packet anyway. */
    static void giveChannel(FakePlayer p) {
        try {
            Field c = net.minecraft.server.network.ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
            c.setAccessible(true);
            Object conn = c.get(p.connection);
            Field ch = net.minecraft.network.Connection.class.getDeclaredField("channel");
            ch.setAccessible(true);
            ch.set(conn, new io.netty.channel.embedded.EmbeddedChannel());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** PlayerList.players itself (getPlayers() is a read-only view), so per-player server loops see the fakes. */
    @SuppressWarnings("unchecked")
    static List<ServerPlayer> playerList() {
        try {
            Field f = net.minecraft.server.players.PlayerList.class.getDeclaredField("players");
            f.setAccessible(true);
            return (List<ServerPlayer>) f.get(server.getPlayerList());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    static void allow(UUID uuid, boolean add) {
        if (add) Triggers.TEST_PLAYERS.add(uuid); else Triggers.TEST_PLAYERS.remove(uuid);
        for (String cls : List.of("com.habits.perk.movement.Mv", "com.habits.perk.utility.U")) {
            try {
                Field f = Class.forName(cls).getDeclaredField("TEST_PLAYERS");
                f.setAccessible(true);
                Set<UUID> s = (Set<UUID>) f.get(null);
                if (add) s.add(uuid); else s.remove(uuid);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    static BlockPos base(int i) {
        return origin.offset((i % 5) * 8, 0, (i / 5) * 8);
    }

    static void setMode(Mode m) {
        HabitsCollection c = switch (m) {
            case NONE -> HabitsCollection.EMPTY;
            case FULL, OFF -> {
                Map<ResourceLocation, SkillProgress> map = new HashMap<>();
                for (SkillDef d : SkillRegistry.all()) map.put(d.id(), new SkillProgress(10, 0, m == Mode.FULL));
                yield new HabitsCollection(map);
            }
        };
        for (FakePlayer p : players) p.setData(CoreRegistries.COLLECTION, c);
    }

    // ---------------------------------------------------------------- timing

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onTickStart(ServerTickEvent.Pre e) {
        if (server == null) return;
        if (done) { if (churnTick >= 0) churnStep(); return; }
        t0 = System.nanoTime();
        b0 = THREADS.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < players.size(); i++) drive(players.get(i), i);
        tick++;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTickEnd(ServerTickEvent.Post e) {
        if (server == null || done) return;
        long ns = System.nanoTime() - t0, bytes = THREADS.getCurrentThreadAllocatedBytes() - b0;
        if (tick <= WARMUP) {
            if (tick == WARMUP) { if (schedule.isEmpty()) finish(); else startPhase(0); } // ROUNDS=0: benchmarks + churn only
            return;
        }
        tickNs[phaseTick] = ns;
        tickBytes[phaseTick] = bytes;
        if (++phaseTick == MAX_TICKS || System.nanoTime() >= phaseEnd) endPhase();
    }

    static void startPhase(int idx) {
        phaseIdx = idx;
        phaseTick = 0;
        tickNs = new long[MAX_TICKS];
        tickBytes = new long[MAX_TICKS];
        Mode m = schedule.get(idx);
        setMode(m);
        resetArena();
        sweptItems = sweptOrbs = 0;
        Arrays.fill(FIRED, 0);
        phaseEnd = System.nanoTime() + PHASE_SECONDS * 1_000_000_000L;
        if (JFR) recording = Jfr.start("perf/" + (idx / 3 + 1) + "-" + m.name().toLowerCase() + ".jfr");
        LOG.info("[habits-perf] phase {} {} start", idx, m);
    }

    static void endPhase() {
        if (recording != null) Jfr.stop(recording);
        recording = null;
        Mode m = schedule.get(phaseIdx);
        long[] s = Arrays.copyOf(tickNs, phaseTick);
        Arrays.sort(s);
        double mean = Arrays.stream(s).average().orElse(0) / 1e6;
        double p50 = s[s.length / 2] / 1e6, p99 = s[(int) (s.length * 0.99)] / 1e6, max = s[s.length - 1] / 1e6;
        double kb = Arrays.stream(tickBytes, 0, phaseTick).average().orElse(0) / 1024;
        System.gc();
        double heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576.0;
        results.computeIfAbsent(m, k -> new ArrayList<>()).add(new double[]{mean, p50, p99, max, kb, heap});
        String line = String.format("phase %d %-4s %d ticks  mean %.3f ms  p50 %.3f  p99 %.3f  max %.3f  alloc %.1f KB/tick  heap-after-gc %.1f MB  entities %d  swept/tick: %.2f items %.2f orbs",
                phaseIdx, m, phaseTick, mean, p50, p99, max, kb, heap, count(level.getAllEntities()),
                (double) sweptItems / phaseTick, (double) sweptOrbs / phaseTick);
        report.add(line);
        Map<String, Integer> types = new TreeMap<>();
        for (Entity en : level.getAllEntities()) types.merge(EntityType.getKey(en.getType()).getPath(), 1, Integer::sum);
        report.add("    entities: " + types);
        double secs = phaseTick / 20.0;
        StringBuilder fired = new StringBuilder("    triggers per player per second:");
        for (TriggerType t : TriggerType.values())
            if (FIRED[t.ordinal()] > 0) fired.append(String.format(" %s %.2f", t.name().toLowerCase(), FIRED[t.ordinal()] / secs / PLAYERS));
        report.add(fired.toString());
        LOG.info("[habits-perf] {}", line);
        if (phaseIdx + 1 < schedule.size()) startPhase(phaseIdx + 1);
        else finish();
    }

    static int count(Iterable<?> it) {
        int n = 0;
        for (Object o : it) n++;
        return n;
    }

    // ---------------------------------------------------------------- scripted activity, every tick per player

    static final BlockState[] MINE = {Blocks.STONE.defaultBlockState(), Blocks.IRON_ORE.defaultBlockState(),
            Blocks.OAK_LOG.defaultBlockState(), ((CropBlock) Blocks.WHEAT).getStateForAge(7), Blocks.DIRT.defaultBlockState(),
            Blocks.COAL_ORE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState()};

    static void drive(FakePlayer p, int i) {
        int t = tick + i * 3;
        BlockPos b = base(i);
        p.doTick();
        if (p.invulnerableTime > 0) p.invulnerableTime--; // ServerPlayer.tick does this, and a FakePlayer skips it
        if (p.getHealth() < 10) p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);

        // move in a small circle, jump twice a second
        double ang = t * 0.15;
        Vec3 d = new Vec3(Math.cos(ang) * 0.22, 0, Math.sin(ang) * 0.22);
        p.move(MoverType.PLAYER, d);
        p.setOnGround(true);
        p.awardStat(Stats.WALK_ONE_CM, 22); // move() alone awards no distance
        if (t % 10 == 0) {
            p.jumpFromGround(); // awards the jump stat itself
            p.setDeltaMovement(Vec3.ZERO);
        }

        // mine one block every tick, rotating stone / ore / log / crop / dirt / gravel
        BlockState st = MINE[t % MINE.length];
        BlockPos mine = b.offset(3, 0, 0);
        if (st.getBlock() == Blocks.WHEAT) level.setBlock(mine.below(), Blocks.FARMLAND.defaultBlockState(), 2);
        else level.setBlock(mine.below(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(mine, st, 2);
        p.setItemInHand(InteractionHand.MAIN_HAND, st.getBlock() == Blocks.OAK_LOG ? axe : st.getBlock() == Blocks.WHEAT ? hoe
                : st.getBlock() == Blocks.DIRT || st.getBlock() == Blocks.GRAVEL ? shovel : pick);
        p.gameMode.destroyBlock(mine);

        // place a block every 4 ticks through the real use-item path, then break it
        if (t % 4 == 0) {
            BlockPos ground = b.offset(-2, -1, 2);
            ItemStack cobble = new ItemStack(Items.COBBLESTONE, 16);
            p.setItemInHand(InteractionHand.MAIN_HAND, cobble);
            p.gameMode.useItemOn(p, level, cobble, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false));
            p.setItemInHand(InteractionHand.MAIN_HAND, pick);
            p.gameMode.destroyBlock(ground.above());
        }

        // melee: one zombie per player, AI on; hit it every 10 ticks, it hits back every 20
        Zombie z = zombies.get(p);
        if (z == null || !z.isAlive()) {
            z = EntityType.ZOMBIE.create(level);
            z.moveTo(b.getX() - 2.5, b.getY(), b.getZ() + 0.5, 0, 0);
            z.setPersistenceRequired();
            level.addFreshEntity(z);
            zombies.put(p, z);
        }
        if (t % 10 == 0) {
            p.setItemInHand(InteractionHand.MAIN_HAND, sword);
            p.attack(z);
        }
        if (t % 20 == 7) p.hurt(level.damageSources().mobAttack(z), 3);

        // shoot an arrow at it every 10 ticks through BowItem.releaseUsing
        if (t % 10 == 5) {
            ItemStack bow = new ItemStack(Items.BOW);
            p.setItemInHand(InteractionHand.MAIN_HAND, bow);
            p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, z.getEyePosition());
            if (p.getInventory().countItem(Items.ARROW) < 8) p.getInventory().add(new ItemStack(Items.ARROW, 64));
            ((BowItem) Items.BOW).releaseUsing(bow, level, p, bow.getUseDuration(p) - 20);
        }

        // fishing: keep a hook out (rod in the off hand), land a catch every 40 ticks
        if (p.fishing == null && t % 20 == 0) level.addFreshEntity(new FishingHook(p, level, 0, 0));
        if (p.fishing != null && t % 40 == 11)
            NeoForge.EVENT_BUS.post(new ItemFishedEvent(List.of(new ItemStack(Items.COD)), 1, p.fishing));

        // crafting / smelting / eating / fall / xp
        if (t % 20 == 3) EventHooks.firePlayerCraftingEvent(p, new ItemStack(Items.STICK, 4), new TransientCraftingContainer(p.inventoryMenu, 2, 2));
        if (t % 40 == 13) EventHooks.firePlayerSmeltedEvent(p, new ItemStack(Items.IRON_INGOT));
        if (t % 60 == 17) {
            ItemStack bread = new ItemStack(Items.BREAD);
            ItemStack copy = bread.copy();
            EventHooks.onItemUseFinish(p, copy, 0, bread.finishUsingItem(level, p));
        }
        if (t % 40 == 23) {
            p.fallDistance = 6;
            p.causeFallDamage(6, 1, level.damageSources().fall());
            p.fallDistance = 0;
        }
        if (t % 40 == 29) level.addFreshEntity(new ExperienceOrb(level, p.getX(), p.getY(), p.getZ(), 3));

        if (t % 100 == 0) restock(p);
        if (i == 0 && tick % 20 == 0) sweepItems(); // drops pile up 20/tick; a real player walks over them
    }

    static final ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE), axe = new ItemStack(Items.DIAMOND_AXE),
            shovel = new ItemStack(Items.DIAMOND_SHOVEL), hoe = new ItemStack(Items.DIAMOND_HOE), sword = new ItemStack(Items.DIAMOND_SWORD);

    static void restock(FakePlayer p) {
        p.getInventory().clearContent();
        for (ItemStack s : List.of(pick, axe, shovel, hoe, sword)) s.setDamageValue(0);
        p.getInventory().add(new ItemStack(Items.ARROW, 64));
        p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.FISHING_ROD));
        p.setItemInHand(InteractionHand.MAIN_HAND, pick);
    }

    /** Every phase starts from the same world: no leftover drops, orbs, projectiles or mobs from the previous mode. */
    static void resetArena() {
        for (Entity en : level.getEntitiesOfClass(Entity.class, new AABB(origin).inflate(96, 64, 96), en -> !(en instanceof net.minecraft.world.entity.player.Player)))
            en.discard();
        zombies.clear();
    }

    /** Drops and orbs are collected by the sweep (a real player walks over them); the counts show how fast a mode spawns them. */
    static void sweepItems() {
        AABB box = new AABB(origin).inflate(64, 16, 64);
        for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, box)) { it.discard(); sweptItems++; }
        for (ExperienceOrb o : level.getEntitiesOfClass(ExperienceOrb.class, box)) { o.discard(); sweptOrbs++; }
        for (var a : level.getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class, box)) if (a.tickCount > 60) a.discard();
    }

    // ---------------------------------------------------------------- microbenchmarks + churn + report

    static void finish() {
        done = true;
        report.add("");
        report.add("median of " + ROUNDS + " rounds, " + PHASE_SECONDS + " s each (mean / p50 / p99 / max ms, alloc KB/tick, heap MB):");
        results.forEach((m, rs) -> {
            double[] med = new double[6];
            for (int k = 0; k < 6; k++) {
                final int kk = k;
                double[] col = rs.stream().mapToDouble(r -> r[kk]).sorted().toArray();
                med[k] = col[col.length / 2];
            }
            report.add(String.format("%-4s %.3f / %.3f / %.3f / %.3f   %.1f KB/tick   %.1f MB", m, med[0], med[1], med[2], med[3], med[4], med[5]));
        });
        report.add("");
        apiBench();
        triggerBench();
        for (FakePlayer p : players) logout(p);
        players.clear();
        churnTick = 0;
    }

    static void bench(String name, int n, Runnable r) {
        for (int i = 0; i < n; i++) r.run(); // warm
        long b = THREADS.getCurrentThreadAllocatedBytes(), t = System.nanoTime();
        for (int i = 0; i < n; i++) r.run();
        long ns = System.nanoTime() - t, bytes = THREADS.getCurrentThreadAllocatedBytes() - b;
        report.add(String.format("  %-44s %9.1f ns/call  %8.1f B/call", name, (double) ns / n, (double) bytes / n));
    }

    static volatile Object sink;

    static void apiBench() {
        report.add("HabitsApi reads, one player, every skill Lv10 on:");
        setMode(Mode.FULL);
        FakePlayer p = players.get(0);
        ResourceLocation mech = SkillRegistry.all().stream().filter(d -> d.perk().mechanic().isPresent())
                .map(d -> d.perk().mechanic().get()).findFirst().orElse(Habits.id("none"));
        ResourceLocation skill = SkillRegistry.all().iterator().next().id();
        bench("mechanicHit(" + mech.getPath() + ")", 200_000, () -> sink = HabitsApi.mechanicHit(p, mech));
        bench("mechanicHit(unused id)", 200_000, () -> sink = HabitsApi.mechanicHit(p, Habits.id("not_a_mechanic")));
        bench("perkValues(ATTRIBUTE)", 200_000, () -> sink = HabitsApi.perkValues(p, PerkType.ATTRIBUTE));
        bench("perkValue(MINING_SPEED)", 200_000, () -> sink = HabitsApi.perkValue(p, PerkType.MINING_SPEED));
        bench("perkActive", 200_000, () -> sink = HabitsApi.perkActive(p, skill));
        bench("Habits.id(\"vein_miner\")", 200_000, () -> sink = Habits.id("vein_miner"));
        report.add("");
        // a standing player's whole tick (every per-tick perk handler, mixin and mechanic tick), FULL vs NONE:
        // the difference is the mod's per-tick cost per player while idle
        report.add("ServerPlayer.doTick, one idle player (no scripted activity):");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        resetArena();
        Object rec = JFR ? Jfr.start("perf/dotick.jfr") : null;
        for (int round = 0; round < 2; round++) { // second round reported (first warms all three)
            if (round == 1) report.add("  (round 2 of 2)");
            setMode(Mode.NONE);
            bench("doTick, no skills", 20_000, p::doTick);
            setMode(Mode.OFF);
            bench("doTick, every skill owned, all off", 20_000, p::doTick);
            setMode(Mode.FULL);
            bench("doTick, every skill Lv10 on", 20_000, p::doTick);
        }
        if (rec != null) Jfr.stop(rec);
        report.add("");
    }

    static void triggerBench() {
        report.add("Trigger dispatch per event (Triggers.fire gate + HabitsApi.onTrigger), player with no skills, drop rate 0"
                + " (every roll loses, so this is the pure dispatch cost; a won roll adds the card entity):");
        setMode(Mode.NONE);
        FakePlayer p = players.get(0);
        double rate = com.habits.core.CoreInit.DROP_RATE.get();
        com.habits.core.CoreInit.DROP_RATE.set(0.0);
        TriggerContext none = new TriggerContext(null, 1, null, null, null);
        bench("(empty Runnable: harness overhead)", 200_000, () -> {});
        bench("HabitsApi.onTrigger(SWIM_DISTANCE) direct", 200_000, () -> HabitsApi.onTrigger(p, TriggerType.SWIM_DISTANCE, none));
        for (TriggerType type : TriggerType.values()) {
            List<SkillDef> c = SkillRegistry.byTrigger(type);
            ResourceLocation target = c.stream().flatMap(d -> d.trigger().match().stream()).flatMap(m -> m.ids().stream()).findFirst().orElse(null);
            TriggerContext ctx = new TriggerContext(target, 1, null, null, null);
            bench(type.name() + " (" + c.size() + " skills)", 20_000, () -> Triggers.fire(p, type, ctx));
        }
        com.habits.core.CoreInit.DROP_RATE.set(rate);
        sweepItems();
        report.add("");
    }

    static int churnTick = -1;
    static Map<String, Integer> churnBefore;
    static double churnHeap0;
    static final int SETTLE = 300, CYCLES = 50, CYCLE = 10;

    /** Login/logout churn, one step per server tick: settle, snapshot every static collection in com.habits and the
     * heap, log 20 fresh-UUID players in and out 50 times (9 ticks of activity each, alternating FULL and NONE), settle
     * again so entity-keyed maps drain, then diff. */
    static void churnStep() {
        int k = churnTick++;
        if (k == SETTLE - 1) {
            sweepItems();
            churnBefore = staticSizes();
            System.gc();
            churnHeap0 = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576.0;
        } else if (k >= SETTLE && k < SETTLE + CYCLES * CYCLE) {
            int c = (k - SETTLE) / CYCLE, j = (k - SETTLE) % CYCLE;
            if (j == 0) {
                for (int i = 0; i < PLAYERS; i++) players.add(login(i, UUID.randomUUID()));
                setMode(c % 2 == 0 ? Mode.FULL : Mode.NONE);
            }
            if (j < CYCLE - 1) { for (int i = 0; i < players.size(); i++) drive(players.get(i), i); tick++; }
            else { for (FakePlayer p : players) logout(p); players.clear(); }
        } else if (k == 2 * SETTLE + CYCLES * CYCLE) {
            sweepItems();
            System.gc();
            double h1 = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576.0;
            Map<String, Integer> after = staticSizes();
            report.add("Login/logout churn (" + PLAYERS + " players x " + CYCLES + " cycles, fresh UUIDs, " + SETTLE + " idle ticks before and after):");
            report.add(String.format("  heap after gc: %.1f MB -> %.1f MB", churnHeap0, h1));
            int grown = 0;
            for (var en : after.entrySet()) {
                int b = churnBefore.getOrDefault(en.getKey(), 0);
                if (en.getValue() > b) { report.add("  GREW " + en.getKey() + ": " + b + " -> " + en.getValue()); grown++; }
            }
            report.add("  static collections checked: " + after.size() + ", grown: " + grown);
            write();
            churnTick = -1;
            server.halt(false);
        }
    }

    static Map<String, Integer> staticSizes() {
        Map<String, Integer> out = new TreeMap<>();
        var file = ModList.get().getModFileById(Habits.MODID).getFile();
        for (var c : file.getScanResult().getClasses()) {
            String name = c.clazz().getClassName();
            if (name.contains(".mixin.") || name.startsWith("com.habits.perf")) continue;
            Class<?> cls;
            try { cls = Class.forName(name, false, PerfHarness.class.getClassLoader()); } catch (Throwable t) { continue; }
            Field[] fields;
            try { fields = cls.getDeclaredFields(); } catch (Throwable t) { continue; }
            for (Field f : fields) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!Map.class.isAssignableFrom(f.getType()) && !Collection.class.isAssignableFrom(f.getType())
                        && !f.getType().getName().startsWith("it.unimi")) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    int size = v instanceof Map<?, ?> m ? m.size() : v instanceof Collection<?> col ? col.size()
                            : v instanceof it.unimi.dsi.fastutil.Function<?, ?> fn ? fn.size() : -1;
                    if (size >= 0) out.put(name + "." + f.getName(), size);
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    static void write() {
        try {
            Files.createDirectories(Path.of("perf"));
            Files.write(Path.of("perf/results.txt"), report);
        } catch (IOException ex) {
            LOG.error("[habits-perf] write failed", ex);
        }
        report.forEach(l -> LOG.info("[habits-perf] {}", l));
    }

    /** jdk.jfr kept in its own class so a JVM without it only loses the recordings. */
    public static final class Jfr {
        public static Object start(String file) {
            try {
                Files.createDirectories(Path.of("perf"));
                var r = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
                r.setDestination(Path.of(file));
                r.start();
                return r;
            } catch (Throwable t) {
                LOG.warn("[habits-perf] JFR unavailable: {}", t.toString());
                return null;
            }
        }

        public static void stop(Object r) {
            try {
                ((jdk.jfr.Recording) r).stop();
                ((jdk.jfr.Recording) r).close();
            } catch (Throwable t) {
                LOG.warn("[habits-perf] JFR stop failed: {}", t.toString());
            }
        }
    }
}
