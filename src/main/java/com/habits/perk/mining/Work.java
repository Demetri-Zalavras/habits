package com.habits.perk.mining;

import com.habits.trigger.TriggerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Multi-block work done AS the player, spread over ticks. Every break goes through
 * {@code ServerPlayerGameMode.destroyBlock}: it fires BreakEvent (claims and protection mods can cancel), costs tool
 * durability, and drops with the tool's enchants. Every place fires EntityPlaceEvent and is undone if cancelled.
 */
public final class Work {
    private Work() {}

    /** True while we break or place for a player, so our own listeners don't chain off our work. */
    public static boolean busy;

    record Job(ServerLevel level, ArrayDeque<BlockPos> todo, int perTick, Item tool, Predicate<BlockState> ok) {}

    private static final Map<UUID, ArrayDeque<Job>> JOBS = new HashMap<>();

    /** Queue blocks to break as {@code p}, {@code perTick} per server tick. Stops if the held tool changes or breaks. */
    static void queueBreak(ServerPlayer p, Collection<BlockPos> blocks, int perTick, Predicate<BlockState> ok) {
        if (blocks.isEmpty()) return;
        JOBS.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>())
                .add(new Job(p.serverLevel(), new ArrayDeque<>(blocks), Math.max(1, perTick), p.getMainHandItem().getItem(), ok));
    }

    /** Run every queued job for this player now (tests: fake players never tick). */
    static void drain(ServerPlayer p) {
        while (JOBS.containsKey(p.getUUID())) step(p);
    }

    /** One server tick of work for this player (tests time it). */
    static void stepOnce(ServerPlayer p) {
        step(p);
    }

    static int pending(Player p) {
        ArrayDeque<Job> q = JOBS.get(p.getUUID());
        if (q == null) return 0;
        int n = 0;
        for (Job j : q) n += j.todo.size();
        return n;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (JOBS.isEmpty()) return;
        for (UUID id : java.util.List.copyOf(JOBS.keySet())) {
            ServerPlayer p = e.getServer().getPlayerList().getPlayer(id);
            if (p == null) {
                // fake players are not in the player list; tests drain by hand
                ArrayDeque<Job> q = JOBS.get(id);
                if (q != null && !q.isEmpty() && q.peek().level.getPlayerByUUID(id) instanceof ServerPlayer sp) step(sp);
                else JOBS.remove(id);
                continue;
            }
            step(p);
        }
    }

    private static void step(ServerPlayer p) {
        ArrayDeque<Job> q = JOBS.get(p.getUUID());
        if (q == null) return;
        Job j = q.peek();
        if (j == null || !MiningMechanics.allowed(p) || p.level() != j.level) {
            JOBS.remove(p.getUUID());
            return;
        }
        int n = j.perTick;
        while (n > 0 && !j.todo.isEmpty()) {
            if (j.tool != p.getMainHandItem().getItem()) { j.todo.clear(); break; }
            BlockPos pos = j.todo.poll();
            BlockState s = j.level.getBlockState(pos);
            if (s.isAir() || !j.ok.test(s) || pos.distToCenterSqr(p.position()) > 24 * 24) continue;
            breakAs(p, pos);
            n--;
        }
        if (j.todo.isEmpty()) {
            q.poll();
            if (q.isEmpty()) JOBS.remove(p.getUUID());
        }
    }

    /** Break one block as the player right now (BreakEvent, durability, enchanted drops). */
    static boolean breakAs(ServerPlayer p, BlockPos pos) {
        BlockState s = p.level().getBlockState(pos);
        ResourceLocation tool = TriggerEvents.itemOrNull(p.getMainHandItem());
        boolean was = busy, broke;
        busy = true;
        try {
            broke = p.gameMode.destroyBlock(pos);
        } finally {
            busy = was;
        }
        // every extra block a perk breaks rolls cards like a hand-broken one (user call, 2026-10-08)
        if (broke) TriggerEvents.rollBreak(p, s, pos, tool);
        return broke;
    }

    /** Place {@code state} at {@code pos} as the player if the spot is replaceable; fires EntityPlaceEvent and undoes
     * the place if anything cancels it. Does not take items: call {@link #take} first. */
    static boolean placeAs(Player p, BlockPos pos, BlockState state, Direction face) {
        if (!(p.level() instanceof ServerLevel level) || !level.isLoaded(pos) || !level.getBlockState(pos).canBeReplaced()
                || !state.canSurvive(level, pos) || !level.isUnobstructed(state, pos, net.minecraft.world.phys.shapes.CollisionContext.empty())) return false;
        boolean was = busy;
        busy = true;
        try {
            BlockSnapshot snap = BlockSnapshot.create(level.dimension(), level, pos);
            if (!level.setBlock(pos, state, 3)) return false;
            if (EventHooks.onBlockPlace(p, snap, face)) {
                snap.restore();
                return false;
            }
            return true;
        } finally {
            busy = was;
        }
    }

    /** Change a block in place as the player (rotate, wax, light, harden): fires EntityPlaceEvent and reverts if
     * anything cancels it. */
    static boolean changeAs(Player p, BlockPos pos, BlockState state) {
        if (!(p.level() instanceof ServerLevel level) || !level.isLoaded(pos)) return false;
        boolean was = busy;
        busy = true;
        try {
            BlockSnapshot snap = BlockSnapshot.create(level.dimension(), level, pos);
            if (!level.setBlock(pos, state, 3)) return false;
            if (EventHooks.onBlockPlace(p, snap, Direction.UP)) {
                snap.restore();
                return false;
            }
            return true;
        } finally {
            busy = was;
        }
    }

    /** Remove a block as the player without drops, after a BreakEvent check (undo, swapper, scoop). */
    static boolean removeAs(ServerPlayer p, BlockPos pos) {
        ServerLevel level = p.serverLevel();
        if (!level.isLoaded(pos)) return false;
        boolean was = busy;
        busy = true;
        try {
            var ev = net.neoforged.neoforge.common.CommonHooks.fireBlockBreak(level, p.gameMode.getGameModeForPlayer(), p, pos, level.getBlockState(pos));
            return !ev.isCanceled() && level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        } finally {
            busy = was;
        }
    }

    /** Items one block of this state took to build: a double slab is two slabs, four candles four candles. */
    static int cost(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) return s.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
        for (var prop : List.of(BlockStateProperties.LAYERS, BlockStateProperties.CANDLES, BlockStateProperties.PICKLES,
                BlockStateProperties.EGGS, BlockStateProperties.FLOWER_AMOUNT))
            if (s.hasProperty(prop)) return s.getValue(prop);
        return 1;
    }

    /** True if the player has {@code n} of {@code item} (creative always). */
    static boolean has(Player p, Item item, int n) {
        return p.getAbilities().instabuild || p.getInventory().countItem(item) >= n;
    }

    /** Take one {@code item} from the inventory (creative: free). False if none. */
    static boolean take(Player p, Item item) {
        if (p.getAbilities().instabuild) return true;
        var inv = p.getInventory();
        // hands first, so the stack being used empties before the backpack
        ItemStack main = p.getMainHandItem();
        if (main.is(item)) { main.shrink(1); return true; }
        ItemStack off = p.getOffhandItem();
        if (off.is(item)) { off.shrink(1); return true; }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) { s.shrink(1); return true; }
        }
        return false;
    }

    /** Give an item, dropping what does not fit at the player's feet. */
    static void give(Player p, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!p.getInventory().add(stack) && !stack.isEmpty()) p.drop(stack, false);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        UUID id = e.getEntity().getUUID();
        JOBS.remove(id);
        for (var c : PLAYER_CLEANUP) c.accept(id);
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent e) {
        JOBS.clear();
        for (Runnable r : STOP_CLEANUP) r.run();
    }

    /** Per-player static state registers its remover here (logout) and its clear here (server stop). */
    static final java.util.List<java.util.function.Consumer<UUID>> PLAYER_CLEANUP = new java.util.ArrayList<>();
    static final java.util.List<Runnable> STOP_CLEANUP = new java.util.ArrayList<>();

    static <V> Map<UUID, V> playerMap() {
        Map<UUID, V> m = new HashMap<>();
        PLAYER_CLEANUP.add(m::remove);
        STOP_CLEANUP.add(m::clear);
        return m;
    }
}
