package com.habits.perf;

import com.habits.Habits;
import com.habits.core.CardData;
import com.habits.core.CardItems;
import com.habits.core.Category;
import com.habits.core.CoreInit;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerSpec;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Dev-only play-session drop-rate sim (docs/DROPRATES_SESSION.md). Inert unless {@code HABITS_SIM=1} on a dedicated
 * server ({@code runServer}). Runs N fresh players (no skills) in parallel through a scripted session of real game
 * actions (block breaks, placements, attacks, mob hits, fishing reels, menus, stats) at one sim tick per server tick,
 * with the server in /tick sprint. Counts every card that lands in each player's inventory, plus the expected count
 * (sum of win odds over every trigger that reached core), writes {@code sim/session.txt} and stops the server.
 * <p>Env: {@code HABITS_SIM_SESSIONS} (5), {@code HABITS_SIM_MINUTES} (60), {@code HABITS_SIM_SEED} (first seed, 1).
 */
@EventBusSubscriber(modid = Habits.MODID)
public final class PlaySim {
    private PlaySim() {}

    private static final Logger LOG = LogUtils.getLogger();
    static final boolean ON = "1".equals(System.getenv("HABITS_SIM"));
    static final int SESSIONS = PerfHarness.intEnv("HABITS_SIM_SESSIONS", 5);
    static final int MINUTES = PerfHarness.intEnv("HABITS_SIM_MINUTES", 60);
    static final int SEED = PerfHarness.intEnv("HABITS_SIM_SEED", 1);

    /** One 10-minute cycle, repeated; seconds per activity. The session mix lives here. */
    enum Act {
        TRAVEL(100), MINE(150), CHOP(50), FIGHT(80), BUILD(80), FARM(60), FISH(40), CHORES(10), IDLE(30);
        final int secs;
        Act(int secs) { this.secs = secs; }
    }
    static final int CYCLE_TICKS = 600 * 20;

    /** A FakePlayer that behaves like a real one where it matters for triggers: it records stats (vanilla awards
     * item use, pickups, breaks and custom stats this way), takes damage, and opens menus. */
    static final class SimPlayer extends FakePlayer {
        SimPlayer(ServerLevel level, GameProfile profile) {
            super(level, profile);
            try {
                Field inv = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime"); // only ServerPlayer.tick counts it down
                inv.setAccessible(true);
                inv.setInt(this, 0);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override public void awardStat(Stat<?> stat, int amount) { getStats().increment(this, stat, amount); }

        @Override public boolean isInvulnerableTo(DamageSource source) { return isRemoved(); }

        @Override
        public OptionalInt openMenu(@Nullable MenuProvider provider, @Nullable Consumer<RegistryFriendlyByteBuf> extra) {
            if (provider == null) return OptionalInt.empty();
            if (containerMenu != inventoryMenu) closeContainer();
            AbstractContainerMenu menu = provider.createMenu(1, getInventory(), this);
            if (menu == null) return OptionalInt.empty();
            containerMenu = menu;
            NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Open(this, menu)); // what ServerPlayer.openMenu posts
            return OptionalInt.of(1);
        }
    }

    /** One simulated player and everything counted for it. */
    static final class S {
        final int idx, seed;
        final Random rng;
        SimPlayer p;
        BlockPos home;
        Vec3 at;                         // where the player stands this tick (re-pinned so physics can't drift it)
        double travelX, travelZ, heading;
        boolean sprinting;
        final BlockPos[] tunnel = new BlockPos[2];
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        boolean colPending;
        int steps, busy, k, treeStep, buildK;
        long forced = Long.MIN_VALUE;
        Block[] tree;
        Mob mob;
        int mobTicks, mobGap, bowDraw;
        boolean bowFight;
        FishingHook hook;
        int nextCatch;
        Animal cowA, cowB;
        Sheep sheep;
        final Map<ResourceLocation, Integer> won = new HashMap<>();
        final Map<ResourceLocation, Double> expected = new HashMap<>();
        final Map<ResourceLocation, Integer> firstMinute = new HashMap<>();
        final int[] fired = new int[TriggerType.values().length];
        final double[] amount = new double[TriggerType.values().length];
        final Map<Act, Integer> actCards = new TreeMap<>();
        int total, dups, newSkills;

        S(int idx, int seed) { this.idx = idx; this.seed = seed; this.rng = new Random(seed); }
    }

    static MinecraftServer server;
    static ServerLevel level;
    static BlockPos spawn;
    static final List<S> sims = new ArrayList<>();
    static final Map<UUID, S> BY_ID = new HashMap<>();
    static int tick = -1;
    static Act act;

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent e) {
        if (!ON || !e.getServer().isDedicatedServer()) return;
        server = e.getServer();
        level = server.overworld();
        var rules = level.getGameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_MOBGRIEFING).set(false, server);
        rules.getRule(GameRules.RULE_DOFIRETICK).set(false, server);
        level.setDayTime(6000);
        spawn = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, level.getSharedSpawnPos());
        for (int i = 0; i < SESSIONS; i++) {
            S s = new S(i, SEED + i);
            // a sky platform: hills, overhangs and shore water near spawn shaded crops and flooded the farm
            s.home = new BlockPos(spawn.getX() + i * 64, 150, spawn.getZ());
            buildArena(s.home);
            s.p = login(s);
            s.travelX = s.home.getX() + 0.5;
            s.travelZ = s.home.getZ() + 0.5;
            s.heading = i * 2 * Math.PI / SESSIONS;
            // mines near base (300-600 blocks out), so they never count toward the far-from-spawn milestones
            s.tunnel[0] = new BlockPos(spawn.getX() + 200, 11, spawn.getZ() + 300 + i * 24);
            s.tunnel[1] = new BlockPos(spawn.getX() + 200, -54, spawn.getZ() + 312 + i * 24);
            sims.add(s);
            BY_ID.put(s.p.getUUID(), s);
        }
        Triggers.SPIES.add(PlaySim::spy);
        server.tickRateManager().requestGameToSprint(MINUTES * 1200 + 200);
        tick = 0;
        LOG.info("[habits-sim] started: {} sessions x {} min, seeds {}..{}, world seed {}, drop rate x{}",
                SESSIONS, MINUTES, SEED, SEED + SESSIONS - 1, level.getSeed(), CoreInit.DROP_RATE.get());
    }

    static SimPlayer login(S s) {
        UUID id = UUID.nameUUIDFromBytes(("habits-sim-" + s.seed).getBytes());
        SimPlayer p = new SimPlayer(level, new GameProfile(id, "sim" + s.idx));
        try { // seed the player's RNG: every card roll and variant roll draws from it
            Field f = net.minecraft.world.entity.Entity.class.getDeclaredField("random");
            f.setAccessible(true);
            f.set(p, net.minecraft.util.RandomSource.create(s.seed));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        p.setGameMode(GameType.SURVIVAL);
        s.at = Vec3.atBottomCenterOf(s.home);
        p.moveTo(s.at.x, s.at.y, s.at.z, 0, 0);
        PerfHarness.allow(id, true);
        PerfHarness.giveChannel(p);
        level.addNewPlayer(p);
        playerList().add(p);
        EventHooks.firePlayerLoggedIn(p);
        return p;
    }

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

    // Arena layout around home h (all at floor level h.y): tree (6,0,0); chest/table/furnace/enchanter (0..3,0,-3);
    // build plot x -12..-5, z -4..3; farm x -4..4, z 6..14 (water at 0,10); pool x 8..12, z 6..10; animals x -8.
    static void buildArena(BlockPos h) {
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) {
            for (int y = -4; y <= -1; y++) level.setBlock(h.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
            for (int y = 0; y < 8; y++) level.setBlock(h.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        for (int cx = (h.getX() - 20) >> 4; cx <= (h.getX() + 20) >> 4; cx++)
            for (int cz = (h.getZ() - 20) >> 4; cz <= (h.getZ() + 20) >> 4; cz++) level.setChunkForced(cx, cz, true);
        level.setBlock(h.offset(6, -1, 0), Blocks.DIRT.defaultBlockState(), 2);
        level.setBlock(h.offset(0, 0, -3), Blocks.CHEST.defaultBlockState(), 2);
        level.setBlock(h.offset(1, 0, -3), Blocks.CRAFTING_TABLE.defaultBlockState(), 2);
        level.setBlock(h.offset(2, 0, -3), Blocks.FURNACE.defaultBlockState(), 2);
        level.setBlock(h.offset(3, 0, -3), Blocks.ENCHANTING_TABLE.defaultBlockState(), 2);
        for (int x = -4; x <= 4; x++) for (int z = 6; z <= 14; z++) {
            boolean water = x == 0 && z == 10;
            level.setBlock(h.offset(x, -1, z), water ? Blocks.WATER.defaultBlockState() : Blocks.FARMLAND.defaultBlockState(), 2);
            if (!water) level.setBlock(h.offset(x, 0, z), crop(x, z).defaultBlockState(), 2);
        }
        for (int x = 8; x <= 12; x++) for (int z = 6; z <= 10; z++) for (int y = -3; y <= -1; y++)
            level.setBlock(h.offset(x, y, z), Blocks.WATER.defaultBlockState(), 2);
    }

    static Block crop(int x, int z) {
        int r = Math.floorMod(x * 7 + z * 3, 4);
        return r < 2 ? Blocks.WHEAT : r == 2 ? Blocks.CARROTS : Blocks.POTATOES;
    }

    // ---------------------------------------------------------------- counting

    /** Every trigger that passed the gate: count it and add each matching skill's win odds (core's own formula). */
    static void spy(ServerPlayer pl, TriggerType type, TriggerContext ctx) {
        S s = BY_ID.get(pl.getUUID());
        if (s == null) return;
        s.fired[type.ordinal()]++;
        s.amount[type.ordinal()] += ctx.amount();
        double mult = CoreInit.DROP_RATE.get();
        var access = pl.registryAccess();
        for (SkillDef d : SkillRegistry.candidates(type, ctx.target())) {
            TriggerSpec spec = d.trigger();
            if (ctx.amount() < spec.min()) continue;
            boolean match = (spec.match().isEmpty() || spec.match().get().test(ctx.target(), type.targetKind, access))
                    && (spec.item().isEmpty() || spec.item().get().test(ctx.item(), com.habits.core.TargetKind.ITEM, access))
                    && (spec.entity().isEmpty() || spec.entity().get().test(ctx.entity(), com.habits.core.TargetKind.ENTITY, access));
            if (!match) continue;
            double c = Math.min(1, spec.chance() * mult);
            s.expected.merge(d.id(), type.perUnit ? 1 - Math.pow(1 - c, ctx.amount()) : c, Double::sum);
        }
    }

    /** Won cards go straight into the inventory: take them out and count them. */
    static void collect(S s) {
        var inv = s.p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            CardData d = CardItems.dataOf(st);
            if (d == null) continue;
            for (int n = 0; n < st.getCount(); n++) {
                s.total++;
                if (s.won.merge(d.skill(), 1, Integer::sum) == 1) { s.newSkills++; s.firstMinute.put(d.skill(), tick / 1200); }
                else s.dups++;
                s.actCards.merge(act, 1, Integer::sum);
            }
            inv.setItem(i, ItemStack.EMPTY);
        }
        // a card can also land at the feet when the inventory is full
        for (var it : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, s.p.getBoundingBox().inflate(2))) {
            if (CardItems.dataOf(it.getItem()) == null) continue;
            inv.add(it.getItem().copy());
            it.discard();
        }
    }

    // ---------------------------------------------------------------- the session

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Pre e) {
        if (tick < 0) return;
        if (tick >= MINUTES * 1200) { finish(); return; }
        int inCycle = tick % CYCLE_TICKS, cycle = tick / CYCLE_TICKS, sec = inCycle / 20;
        Act now = Act.TRAVEL;
        for (int acc = 0; ; ) {
            if (sec < (acc += now.secs)) break;
            now = Act.values()[now.ordinal() + 1];
        }
        int actTick = inCycle - startOf(now) * 20;
        if (now != act) {
            if (act != null) for (S s : sims) leave(s, act);
            act = now;
            level.setDayTime(now == Act.FIGHT ? 18000 : 6000); // night fights; sessions run in lockstep
            for (S s : sims) enter(s, now, cycle);
        }
        for (S s : sims) {
            SimPlayer p = s.p;
            p.doTick();
            if (p.invulnerableTime > 0) p.invulnerableTime--; // ServerPlayer.tick does this, and a FakePlayer skips it
            if (p.getHealth() < 10) p.setHealth(p.getMaxHealth()); // stands in for eating, regen and retreating
            if (p.getFoodData().getFoodLevel() < 8) p.getFoodData().setFoodLevel(18);
            p.clearFire();
            if (now != Act.IDLE) p.resetLastActionTime(); // a client resets this on every input packet
            if (now != Act.TRAVEL) pin(s); // a server player never computes onGround itself (the client reports it)
            switch (now) {
                case TRAVEL -> travel(s, actTick);
                case MINE -> mine(s);
                case CHOP -> chop(s);
                case FIGHT -> fight(s);
                case BUILD -> build(s, actTick);
                case FARM -> farm(s, actTick);
                case FISH -> fish(s);
                case CHORES -> chores(s, actTick, cycle);
                case IDLE -> {}
            }
            if (now != Act.TRAVEL) pin(s);
            collect(s);
            if (tick % 200 == 199) clearJunk(s);
        }
        tick++;
    }

    static int startOf(Act a) {
        int acc = 0;
        for (Act b : Act.values()) { if (b == a) return acc; acc += b.secs; }
        return acc;
    }

    static void pin(S s) {
        s.p.moveTo(s.at.x, s.at.y, s.at.z, s.p.getYRot(), s.p.getXRot());
        s.p.setDeltaMovement(Vec3.ZERO);
        s.p.setOnGround(true);
        s.p.fallDistance = 0;
    }

    static void enter(S s, Act a, int cycle) {
        s.busy = 0;
        s.queue.clear();
        switch (a) {
            case TRAVEL -> eat(s);
            case MINE -> {
                s.colPending = false;
                BlockPos c = s.tunnel[cycle % 2];
                force(s, c);
                level.setBlock(c, Blocks.AIR.defaultBlockState(), 2); // standing room, not stuck in rock
                level.setBlock(c.above(), Blocks.AIR.defaultBlockState(), 2);
                s.at = Vec3.atBottomCenterOf(c);
            }
            case CHOP -> { s.treeStep = 0; s.at = Vec3.atBottomCenterOf(s.home.offset(4, 0, 0)); growTree(s); }
            case FIGHT -> { s.mob = null; s.mobGap = 40; s.at = Vec3.atBottomCenterOf(s.home.offset(0, 0, -12)); }
            case BUILD -> { s.at = Vec3.atBottomCenterOf(s.home); eat(s); }
            case FARM -> {
                s.k = 0;
                s.at = Vec3.atBottomCenterOf(s.home.offset(0, 0, 5));
                for (int x = -4; x <= 4; x++) for (int z = 6; z <= 14; z++) {
                    if (x == 0 && z == 10) continue;
                    BlockPos c = s.home.offset(x, 0, z);
                    level.setBlock(c.below(), Blocks.FARMLAND.defaultBlockState(), 2);
                    level.setBlock(c, ((CropBlock) crop(x, z)).getStateForAge(7), 2); // grown since the last visit
                }
                if (s.cowA != null) { s.cowA.discard(); s.cowB.discard(); s.sheep.discard(); }
                level.getEntitiesOfClass(Animal.class, new AABB(s.home).inflate(20, 4, 20)).forEach(en -> en.discard());
                s.cowA = animal(EntityType.COW, s.home.offset(-8, 0, 9));
                s.cowB = animal(EntityType.COW, s.home.offset(-8, 0, 11));
                s.sheep = animal(EntityType.SHEEP, s.home.offset(-8, 0, 14));
            }
            case FISH -> { s.at = Vec3.atBottomCenterOf(s.home.offset(7, 0, 8)); s.nextCatch = 100 + s.rng.nextInt(500); }
            default -> s.at = Vec3.atBottomCenterOf(s.home);
        }
        if (a != Act.TRAVEL) pin(s);
    }

    static void leave(S s, Act a) {
        s.p.setSprinting(false);
        if (a == Act.MINE && s.forced != Long.MIN_VALUE) {
            level.setChunkForced((int) (s.forced >> 32), (int) s.forced, false);
            s.forced = Long.MIN_VALUE;
        }
        if (a == Act.FIGHT && s.mob != null) { s.mob.discard(); s.mob = null; }
        if (a == Act.FISH && s.hook != null) { s.hook.discard(); s.hook = null; }
        if (s.p.containerMenu != s.p.inventoryMenu) s.p.closeContainer();
    }

    static void walk(S s, int cm) {
        if (cm > 0) s.p.awardStat(s.sprinting ? Stats.SPRINT_ONE_CM : Stats.WALK_ONE_CM, cm);
    }

    /** A won card lands in the selected slot when the hand is empty: count it before the hand is overwritten. */
    static void hand(S s, ItemStack stack) {
        collect(s);
        s.p.setItemInHand(InteractionHand.MAIN_HAND, stack);
    }

    static void hold(S s, Item item) {
        hand(s, new ItemStack(item, item.getDefaultMaxStackSize()));
    }

    static void clearJunk(S s) {
        s.p.getInventory().clearContent();
        // a real inventory is never bare for long; an empty one fires minimalist_10min every 10 minutes
        for (int i = 9; i < 18; i++) s.p.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        s.p.containerMenu.broadcastChanges();
    }

    /** Right-click a block face like a player looking at it. */
    static void click(S s, BlockPos pos, Direction face, ItemStack stack) {
        hand(s, stack);
        s.p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        s.p.gameMode.useItemOn(s.p, level, stack, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
    }

    static boolean breakBlock(S s, BlockPos pos) {
        s.p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
        return s.p.gameMode.destroyBlock(pos);
    }

    static void eat(S s) {
        Item food = pick(s, new Item[]{Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.APPLE, Items.BAKED_POTATO, Items.CARROT},
                new int[]{35, 25, 15, 10, 10, 5});
        ItemStack st = new ItemStack(food);
        hand(s, st);
        ItemStack copy = st.copy();
        EventHooks.onItemUseFinish(s.p, copy, 0, st.finishUsingItem(level, s.p));
    }

    static <T> T pick(S s, T[] items, int[] weights) {
        int sum = 0;
        for (int w : weights) sum += w;
        int r = s.rng.nextInt(sum);
        for (int i = 0; i < items.length; i++) if ((r -= weights[i]) < 0) return items[i];
        return items[items.length - 1];
    }

    // ---- TRAVEL: walk/sprint across the real world surface (biomes and structures come from the real world gen)
    static void travel(S s, int t) {
        SimPlayer p = s.p;
        if (t % 100 == 0) { s.sprinting = s.rng.nextInt(100) < 60; s.heading += s.rng.nextGaussian() * 0.4; }
        p.setSprinting(s.sprinting);
        double speed = s.sprinting ? 0.2806 : 0.2158; // blocks per tick: vanilla sprint 5.61 m/s, walk 4.32 m/s
        s.travelX += Math.cos(s.heading) * speed;
        s.travelZ += Math.sin(s.heading) * speed;
        int x = (int) Math.floor(s.travelX), z = (int) Math.floor(s.travelZ);
        // the real surface, leaves walk-through; getChunk generates the chunk (Level.getHeight answers the world floor
        // for an unloaded one)
        int y = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        // a teleport skips collision: lift the player clear of cliff faces (vanilla would block the step instead)
        for (int k = 0; k < 8 && buried(EntityType.PLAYER.getDimensions().makeBoundingBox(s.travelX, y, s.travelZ)); k++) y++;
        boolean water = !level.getFluidState(new BlockPos(x, y - 1, z)).isEmpty();
        p.moveTo(s.travelX, y, s.travelZ, (float) Math.toDegrees(s.heading) - 90, 0);
        p.setDeltaMovement(Vec3.ZERO);
        p.setOnGround(!water);
        p.fallDistance = 0;
        p.awardStat(water ? Stats.SWIM_ONE_CM : s.sprinting ? Stats.SPRINT_ONE_CM : Stats.WALK_ONE_CM, (int) Math.round(speed * 100));
        if (!water && t % (s.sprinting ? 15 : 60) == 0) p.awardStat(Stats.JUMP); // sprint-jumping
        if (t == 1000) { // one 5-block drop per trip
            p.setOnGround(false);
            p.fallDistance = 5;
            p.causeFallDamage(5, 1, level.damageSources().fall());
            p.fallDistance = 0;
        }
        if (t == Act.TRAVEL.secs * 20 - 1) { // done exploring: back home
            s.sprinting = false;
            p.setSprinting(false);
        }
    }

    /** Any suffocating block (the in_wall damage kind) inside this box. */
    static boolean buried(AABB box) {
        for (BlockPos b : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ)))
            if (level.getBlockState(b).isSuffocating(level, b)) return true;
        return false;
    }

    // ---- MINE: a 2x1 tunnel through real terrain (y 11 on even cycles, y -54 on odd), plus every exposed ore vein
    static void mine(S s) {
        if (s.busy > 0) { s.busy--; return; }
        int lane = s.at.y > 0 ? 0 : 1;
        BlockPos feet = BlockPos.containing(s.at);
        for (BlockPos b : new BlockPos[]{feet.above(), feet}) // gravel or sand fell in: dig out first, like a player would
            if (level.getBlockState(b).isSuffocating(level, b) && !b.equals(s.queue.peekFirst())) s.queue.addFirst(b.immutable());
        if (s.queue.isEmpty()) {
            if (s.colPending) { // step into the column just cleared and look around for ore
                BlockPos c = s.tunnel[lane];
                s.at = Vec3.atBottomCenterOf(c);
                walk(s, 100);
                force(s, c);
                for (BlockPos b : new BlockPos[]{c, c.above()}) for (Direction d : Direction.values()) addOre(s, b.relative(d));
                if (++s.steps % 10 == 0) click(s, c.west().below(), Direction.UP, new ItemStack(Items.TORCH, 16));
                s.colPending = false;
                return;
            }
            BlockPos c = s.tunnel[lane] = s.tunnel[lane].east();
            s.queue.add(c);
            s.queue.add(c.above());
            s.colPending = true;
        }
        BlockPos pos = s.queue.poll();
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) return;
        if (!st.getFluidState().isEmpty()) { // drain it and wall off the neighbours it would flow in from
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            BlockPos c = s.tunnel[lane];
            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                if (!n.equals(c) && !n.equals(c.above()) && !n.equals(c.west()) && !n.equals(c.west().above())
                        && !level.getFluidState(n).isEmpty()) level.setBlock(n, Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
            return;
        }
        boolean ore = st.is(Tags.Blocks.ORES);
        if (ore && s.p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 4.5) return;
        hand(s, new ItemStack(st.is(BlockTags.MINEABLE_WITH_SHOVEL) ? Items.IRON_SHOVEL : Items.IRON_PICKAXE));
        float hard = st.getDestroySpeed(level, pos);
        if (hard < 0) return; // bedrock
        breakBlock(s, pos);
        s.busy = (int) Math.ceil(hard * 30 / 6) + 5; // iron tool speed 6, plus aim and reaction
        if (ore) for (Direction d : Direction.values()) addOre(s, pos.relative(d)); // follow the vein
    }

    static void addOre(S s, BlockPos b) {
        if (level.getBlockState(b).is(Tags.Blocks.ORES) && !s.queue.contains(b)) s.queue.add(b);
    }

    static void force(S s, BlockPos b) {
        long key = ((long) (b.getX() >> 4) << 32) | ((b.getZ() >> 4) & 0xffffffffL);
        if (key == s.forced) return;
        if (s.forced != Long.MIN_VALUE) level.setChunkForced((int) (s.forced >> 32), (int) s.forced, false);
        level.setChunkForced(b.getX() >> 4, b.getZ() >> 4, true); // drops and orbs tick so they can be picked up
        s.forced = key;
    }

    // ---- CHOP: fell a 5-log tree (bottom up), knock 2 leaves, replant, walk to the next
    static final Block[][] TREES = {{Blocks.OAK_LOG, Blocks.OAK_LEAVES, Blocks.OAK_SAPLING},
            {Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, Blocks.BIRCH_SAPLING}, {Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_SAPLING}};
    static void growTree(S s) {
        s.tree = TREES[s.rng.nextInt(TREES.length)];
        BlockPos b = s.home.offset(6, 0, 0);
        for (int y = 0; y < 5; y++) level.setBlock(b.above(y), s.tree[0].defaultBlockState(), 2);
        for (int y = 3; y <= 5; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            if ((x != 0 || z != 0 || y == 5)) level.setBlock(b.offset(x, y, z), s.tree[1].defaultBlockState()
                    .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 2);
    }

    static void chop(S s) {
        if (s.busy > 0) { s.busy--; return; }
        BlockPos b = s.home.offset(6, 0, 0);
        int k = s.treeStep++;
        if (k < 5) { hold(s, Items.IRON_AXE); breakBlock(s, b.above(k)); s.busy = 15; }
        else if (k < 7) { hand(s, ItemStack.EMPTY); breakBlock(s, b.offset(1, 3 + k - 5, 0)); s.busy = 6; }
        else if (k == 7) {
            level.setBlock(b, Blocks.AIR.defaultBlockState(), 2);
            click(s, b.below(), Direction.UP, new ItemStack(s.tree[2].asItem(), 8));
            s.busy = 20;
        } else { // next tree: clear what is left, regrow, walk over
            for (int y = 0; y <= 5; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                level.setBlock(b.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(b.below(), Blocks.DIRT.defaultBlockState(), 2);
            growTree(s);
            walk(s, 600);
            s.treeStep = 0;
            s.busy = 40;
        }
    }

    // ---- FIGHT: night mobs one at a time; melee (some crits and sprint hits) or bow; they hit back
    static void fight(S s) {
        SimPlayer p = s.p;
        if (s.mob == null || !s.mob.isAlive() || s.mobTicks > 600) {
            if (s.mob != null) { if (s.mob.isAlive()) s.mob.discard(); s.mob = null; s.mobGap = 160 + s.rng.nextInt(120); }
            if (s.mobGap-- > 0) { if (s.mobGap % 20 == 0) walk(s, 150); return; }
            EntityType<? extends Mob> type = pick(s, new EntityType[]{EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER},
                    new int[]{40, 25, 20, 15});
            Mob m = type.create(level);
            m.moveTo(s.at.x + 4, s.at.y, s.at.z, 180, 0);
            m.setNoAi(true); // scripted attacks below; AI would also wander into the neighbour's arena
            m.setPersistenceRequired();
            if (m instanceof AbstractSkeleton) m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            level.addFreshEntity(m);
            s.mob = m;
            s.mobTicks = 0;
            s.bowFight = s.rng.nextInt(100) < (m instanceof AbstractSkeleton ? 50 : 15);
            s.bowDraw = 0;
        }
        Mob m = s.mob;
        int t = s.mobTicks++;
        if (s.bowFight) {
            p.lookAt(EntityAnchorArgument.Anchor.EYES, m.getEyePosition());
            if (++s.bowDraw >= 25) {
                ItemStack bow = new ItemStack(Items.BOW);
                hand(s, bow);
                p.getInventory().add(new ItemStack(Items.ARROW, 8));
                ((BowItem) Items.BOW).releaseUsing(bow, level, p, bow.getUseDuration(p) - 20);
                s.bowDraw = 0;
            }
        } else {
            if (m.distanceTo(p) > 2.5) { // close in
                m.moveTo(s.at.x + 2, m.getY(), s.at.z, m.getYRot(), 0);
                walk(s, 200);
            }
            if (t % 13 == 12) {
                hold(s, Items.IRON_SWORD);
                p.lookAt(EntityAnchorArgument.Anchor.EYES, m.getEyePosition());
                int r = s.rng.nextInt(100);
                if (r < 30) { p.setOnGround(false); p.fallDistance = 0.6f; p.awardStat(Stats.JUMP); } // jump crit
                else if (r < 50) { p.setSprinting(true); s.sprinting = true; walk(s, 300); s.sprinting = false; }
                p.attack(m);
                p.setSprinting(false);
                p.setOnGround(true);
                p.fallDistance = 0;
            }
        }
        if (!m.isAlive()) return;
        if (m instanceof AbstractSkeleton sk) { if (t % 40 == 39) sk.performRangedAttack(p, 1); }
        else if (m.getType() == EntityType.CREEPER) {
            if (t == 25 && s.rng.nextInt(6) == 0) { // one creeper in six gets its blast off
                level.explode(m, m.getX(), m.getY(), m.getZ(), 3, Level.ExplosionInteraction.MOB);
                m.discard();
            }
        } else if (t % 20 == 10 && m.distanceTo(p) < 3) m.doHurtTarget(p); // vanilla melee cooldown is 20 ticks
    }

    // ---- BUILD: place blocks, open chests, craft, take smelted items, use the workstations
    static final Item[] BLOCKS = {Items.COBBLESTONE, Items.OAK_PLANKS, Items.STONE_BRICKS, Items.OAK_STAIRS, Items.OAK_SLAB,
            Items.OAK_FENCE, Items.GLASS, Items.TORCH, Items.OAK_DOOR};
    static final int[] BLOCK_W = {38, 28, 10, 6, 6, 3, 4, 3, 2};
    static final Item[] CRAFTS = {Items.OAK_PLANKS, Items.STICK, Items.TORCH, Items.CHEST, Items.IRON_PICKAXE, Items.BREAD,
            Items.FURNACE, Items.IRON_SWORD, Items.COBBLESTONE_STAIRS, Items.LADDER};
    static final int[] CRAFT_W = {28, 20, 18, 5, 4, 6, 4, 3, 7, 5};
    static final Item[] SMELTS = {Items.IRON_INGOT, Items.COOKED_BEEF, Items.GLASS, Items.CHARCOAL, Items.STONE, Items.COPPER_INGOT};
    static final int[] SMELT_W = {40, 20, 10, 10, 10, 10};

    static void build(S s, int t) {
        if (t % 20 == 0) {
            s.p.setShiftKeyDown(s.rng.nextInt(10) == 0);
            s.p.awardStat(s.p.isShiftKeyDown() ? Stats.CROUCH_ONE_CM : Stats.WALK_ONE_CM, s.p.isShiftKeyDown() ? 130 : 50);
        }
        if (t % 30 != 0) return;
        int r = s.rng.nextInt(100);
        BlockPos h = s.home;
        if (r < 70) {
            int k = s.buildK++ % 64;
            BlockPos pos = h.offset(-12 + k % 16, k / 16, -6);
            if (k == 0) for (int i = 0; i < 64; i++) level.setBlock(h.offset(-12 + i % 16, i / 16, -6), Blocks.AIR.defaultBlockState(), 2);
            s.at = Vec3.atBottomCenterOf(h.offset(-12 + k % 16, 0, -8));
            pin(s);
            if (pos.getY() > h.getY() && level.getBlockState(pos.below()).isAir()) level.setBlock(pos.below(), Blocks.COBBLESTONE.defaultBlockState(), 2);
            Item it = pick(s, BLOCKS, BLOCK_W);
            s.p.setShiftKeyDown(false);
            click(s, pos.below(), Direction.UP, new ItemStack(it, 16));
        } else if (r < 80) {
            s.at = Vec3.atBottomCenterOf(h);
            pin(s);
            s.p.setShiftKeyDown(false);
            click(s, h.offset(0, 0, -3), Direction.UP, ItemStack.EMPTY);
            s.p.closeContainer();
        } else if (r < 89) {
            Item it = pick(s, CRAFTS, CRAFT_W);
            int n = it == Items.OAK_PLANKS || it == Items.STICK || it == Items.TORCH || it == Items.COBBLESTONE_STAIRS ? 4 : it == Items.LADDER ? 3 : 1;
            EventHooks.firePlayerCraftingEvent(s.p, new ItemStack(it, n), new TransientCraftingContainer(s.p.inventoryMenu, 3, 3));
        } else if (r < 96) {
            EventHooks.firePlayerSmeltedEvent(s.p, new ItemStack(pick(s, SMELTS, SMELT_W), 1 + s.rng.nextInt(8)));
        } else {
            s.at = Vec3.atBottomCenterOf(h);
            pin(s);
            s.p.setShiftKeyDown(false);
            click(s, h.offset(1 + s.rng.nextInt(2), 0, -3), Direction.UP, ItemStack.EMPTY); // crafting table or furnace
            s.p.closeContainer();
        }
    }

    // ---- FARM: till a few blocks, harvest + replant the plot by hand, bonemeal, breed two cows, shear a sheep
    static void farm(S s, int t) {
        if (t % 20 == 0) walk(s, 100);
        if (t % 10 != 0) return;
        int k = s.k++;
        BlockPos h = s.home;
        if (k < 6) { // extend the farm
            BlockPos d = h.offset(-6, -1, 6 + k);
            level.setBlock(d, Blocks.DIRT.defaultBlockState(), 2);
            level.setBlock(d.above(), Blocks.AIR.defaultBlockState(), 2);
            click(s, d, Direction.UP, new ItemStack(Items.IRON_HOE));
            return;
        }
        int plot = k - 6;
        if (plot < 80) {
            int i = plot >= 40 ? plot + 1 : plot; // skip the water block
            int x = -4 + i % 9, z = 6 + i / 9;
            BlockPos c = h.offset(x, 0, z);
            s.at = Vec3.atBottomCenterOf(c.north()); // walk along the row
            pin(s);
            Block cr = crop(x, z);
            hand(s, ItemStack.EMPTY);
            breakBlock(s, c);
            level.setBlock(c.below(), Blocks.FARMLAND.defaultBlockState(), 2);
            Item seed = cr == Blocks.WHEAT ? Items.WHEAT_SEEDS : cr == Blocks.CARROTS ? Items.CARROT : Items.POTATO;
            click(s, c.below(), Direction.UP, new ItemStack(seed, 16));
            if (plot % 27 == 0) click(s, c, Direction.UP, new ItemStack(Items.BONE_MEAL, 8));
            return;
        }
        if (plot == 80) { // breed
            for (Animal a : new Animal[]{s.cowA, s.cowB}) {
                hand(s, new ItemStack(Items.WHEAT, 8));
                s.p.interactOn(a, InteractionHand.MAIN_HAND);
            }
            if (s.cowA.isInLove() && s.cowB.isInLove()) s.cowA.spawnChildFromBreeding(level, s.cowB);
        } else if (plot == 81) {
            hand(s, new ItemStack(Items.SHEARS));
            s.p.interactOn(s.sheep, InteractionHand.MAIN_HAND);
        }
    }

    static <T extends Animal> T animal(EntityType<T> type, BlockPos at) {
        T a = type.create(level);
        a.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        a.setNoAi(true);
        level.addFreshEntity(a);
        return a;
    }

    // ---- FISH: a real hook in the pool, reeled through FishingHook.retrieve (vanilla loot table, open water)
    static Field openWater;

    static void fish(S s) {
        SimPlayer p = s.p;
        ItemStack rod = p.getMainHandItem().is(Items.FISHING_ROD) ? p.getMainHandItem() : new ItemStack(Items.FISHING_ROD);
        hand(s, rod);
        if (s.hook == null || s.hook.isRemoved()) {
            s.hook = new FishingHook(p, level, 0, 0);
            level.addFreshEntity(s.hook);
        }
        s.hook.moveTo(s.home.getX() + 10.5, s.home.getY() - 0.9, s.home.getZ() + 8.5);
        s.hook.setDeltaMovement(Vec3.ZERO);
        if (--s.nextCatch > 0) return;
        try {
            if (openWater == null) { openWater = FishingHook.class.getDeclaredField("openWater"); openWater.setAccessible(true); }
            openWater.setBoolean(s.hook, true);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        s.hook.nibble = 20;
        s.hook.retrieve(rod);
        s.hook = null;
        s.nextCatch = 100 + s.rng.nextInt(500); // 5-30 s bite wait, no Lure
        p.awardStat(Stats.ITEM_USED.get(Items.FISHING_ROD), 2); // cast + reel
    }

    // ---- CHORES: enchant (cycles 1 and 4), trade with a villager (cycles 2 and 5), sleep (cycles 0, 2, 4)
    static void chores(S s, int t, int cycle) {
        SimPlayer p = s.p;
        if (t == 20 && cycle % 3 == 1) {
            BlockPos table = s.home.offset(3, 0, -3);
            p.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inv, pl) -> new EnchantmentMenu(id, inv, ContainerLevelAccess.create(level, table)),
                    net.minecraft.network.chat.Component.empty()));
            if (p.containerMenu instanceof EnchantmentMenu m) {
                p.experienceLevel = 30;
                m.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 3));
                m.getSlot(0).set(new ItemStack(s.rng.nextBoolean() ? Items.IRON_PICKAXE : Items.BOOK));
                for (int b = 2; b >= 0; b--) if (m.costs[b] > 0) { m.clickMenuButton(p, b); break; }
            }
            p.closeContainer();
        }
        if (t == 60 && cycle % 3 == 2) {
            Villager v = EntityType.VILLAGER.create(level);
            for (int i = 0; i < 3; i++) {
                p.awardStat(Stats.TALKED_TO_VILLAGER);
                var offer = new MerchantOffer(new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD), 12, 2, 0.05f);
                NeoForge.EVENT_BUS.post(new TradeWithVillagerEvent(p, offer, v));
            }
            v.discard();
        }
        if (t == 100 && cycle % 2 == 0) {
            p.startSleeping(s.home.offset(-2, 0, 0));
            p.stopSleepInBed(false, true);
            pin(s);
        }
    }

    // ---------------------------------------------------------------- report

    static void finish() {
        tick = -1;
        List<String> out = new ArrayList<>();
        out.add(String.format("habits play-session sim: %d sessions x %d min, player RNG seeds %d..%d, world seed %d, drop rate x%.2f, %d skills",
                SESSIONS, MINUTES, SEED, SEED + SESSIONS - 1, level.getSeed(), CoreInit.DROP_RATE.get(), SkillRegistry.all().size()));
        StringBuilder mix = new StringBuilder("mix per 10-minute cycle (s):");
        for (Act a : Act.values()) mix.append(' ').append(a.name().toLowerCase()).append('=').append(a.secs);
        out.add(mix.toString());
        out.add("");
        for (S s : sims) {
            out.add(String.format("session seed=%d: %d cards, %d distinct skills, %d duplicates; by rarity %s; by domain %s; by activity %s",
                    s.seed, s.total, s.newSkills, s.dups, byRarity(s.won), byCategory(s.won), s.actCards));
        }
        out.add("");
        // per skill: total won across sessions, mean per session, expected per session (sum of odds), first minute
        Map<ResourceLocation, int[]> wonAll = new TreeMap<>();
        Map<ResourceLocation, Double> expAll = new TreeMap<>();
        for (S s : sims) {
            s.won.forEach((id, n) -> wonAll.computeIfAbsent(id, k -> new int[SESSIONS])[s.idx] = n);
            s.expected.forEach((id, x) -> expAll.merge(id, x / SESSIONS, Double::sum));
        }
        List<SkillDef> defs = new ArrayList<>(SkillRegistry.all());
        defs.sort(Comparator.comparingDouble((SkillDef d) -> -expAll.getOrDefault(d.id(), 0.0)).thenComparing(d -> d.id().toString()));
        out.add("skill | rarity | domain | trigger | chance | won per session (each seed) | won mean | expected mean");
        for (SkillDef d : defs) {
            int[] w = wonAll.getOrDefault(d.id(), new int[SESSIONS]);
            double exp = expAll.getOrDefault(d.id(), 0.0);
            int sum = 0;
            for (int n : w) sum += n;
            if (sum == 0 && exp < 0.0005) continue;
            StringBuilder each = new StringBuilder();
            for (int n : w) each.append(each.isEmpty() ? "" : " ").append(n);
            out.add(String.format("%s | %s | %s | %s | %s | %s | %.2f | %.3f", d.id().getPath(), d.rarity().name().toLowerCase(),
                    d.category().name().toLowerCase(), d.trigger().type().getSerializedName(), d.trigger().chance(), each,
                    (double) sum / SESSIONS, exp));
        }
        out.add("");
        // coverage: skills whose trigger never fired with a match (expected ~0), by domain and rarity
        Map<String, int[]> cover = new TreeMap<>();
        for (SkillDef d : defs) {
            int[] c = cover.computeIfAbsent(d.category().name().toLowerCase(), k -> new int[3]);
            c[0]++;
            if (expAll.getOrDefault(d.id(), 0.0) >= 0.0005) c[1]++;
            if (wonAll.containsKey(d.id())) c[2]++;
        }
        out.add("domain | skills | reachable in this mix (expected > 0) | won at least once in any session");
        cover.forEach((k, c) -> out.add(String.format("%s | %d | %d | %d", k, c[0], c[1], c[2])));
        Map<String, int[]> rc = new TreeMap<>();
        for (SkillDef d : defs) {
            int[] c = rc.computeIfAbsent(d.rarity().ordinal() + " " + d.rarity().name().toLowerCase(), k -> new int[3]);
            c[0]++;
            if (expAll.getOrDefault(d.id(), 0.0) >= 0.0005) c[1]++;
            if (wonAll.containsKey(d.id())) c[2]++;
        }
        out.add("rarity | skills | reachable | won");
        rc.forEach((k, c) -> out.add(String.format("%s | %d | %d | %d", k.substring(2), c[0], c[1], c[2])));
        out.add("");
        out.add("triggers per session (mean events, mean amount):");
        for (TriggerType t : TriggerType.values()) {
            double ev = 0, am = 0;
            for (S s : sims) { ev += s.fired[t.ordinal()]; am += s.amount[t.ordinal()]; }
            if (ev > 0) out.add(String.format("  %s %.1f events, amount %.1f", t.getSerializedName(), ev / SESSIONS, am / SESSIONS));
        }
        out.add("");
        out.add("first minute each skill was won (seed: skill@min ...):");
        for (S s : sims) {
            StringBuilder b = new StringBuilder("  " + s.seed + ":");
            s.firstMinute.entrySet().stream().sorted(Map.Entry.comparingByValue())
                    .forEach(en -> b.append(' ').append(en.getKey().getPath()).append('@').append(en.getValue()));
            out.add(b.toString());
        }
        try {
            Files.createDirectories(Path.of("sim"));
            Files.write(Path.of("sim/session.txt"), out);
        } catch (IOException ex) {
            LOG.error("[habits-sim] write failed", ex);
        }
        out.forEach(l -> LOG.info("[habits-sim] {}", l));
        server.halt(false);
    }

    static Map<String, Integer> byRarity(Map<ResourceLocation, Integer> won) {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (Rarity r : Rarity.values()) m.put(r.name().toLowerCase(), 0);
        won.forEach((id, n) -> { SkillDef d = SkillRegistry.get(id); if (d != null) m.merge(d.rarity().name().toLowerCase(), n, Integer::sum); });
        return m;
    }

    static Map<String, Integer> byCategory(Map<ResourceLocation, Integer> won) {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (Category c : Category.values()) m.put(c.name().toLowerCase(), 0);
        won.forEach((id, n) -> { SkillDef d = SkillRegistry.get(id); if (d != null) m.merge(d.category().name().toLowerCase(), n, Integer::sum); });
        return m;
    }
}
