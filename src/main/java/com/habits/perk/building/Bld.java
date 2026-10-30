package com.habits.perk.building;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.perk.mining.Work;
import com.habits.trigger.TriggerEvents;
import com.habits.trigger.Triggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Shared reads and world work for the building mechanics. Every place, change and break goes through the same events a
 * hand action would (claims and protection mods can cancel) and sets {@link Work#busy} so no other perk chains off it
 * and no card rolls for the place itself. */
public final class Bld {
    private Bld() {}

    /** GameTest fake players allowed through {@link #allowed}. */
    static final Set<UUID> TEST_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final List<Map<UUID, ?>> PLAYER_MAPS = new ArrayList<>();

    static <V> Map<UUID, V> playerMap() {
        Map<UUID, V> m = new ConcurrentHashMap<>();
        PLAYER_MAPS.add(m);
        return m;
    }

    static boolean allowed(@Nullable Player p) {
        return p != null && p.isAlive() && !p.isSpectator()
                && (!(p instanceof FakePlayer) || TEST_PLAYERS.contains(p.getUUID()) || Triggers.TEST_PLAYERS.contains(p.getUUID()));
    }

    static ResourceLocation id(String name) {
        return Habits.id("building/" + name);
    }

    /** The best active hit for {@code habits:building/<name>}, or null (also null when not {@link #allowed}). */
    @Nullable
    static HabitsApi.PerkHit hit(@Nullable Player p, String name) {
        return allowed(p) ? HabitsApi.mechanicHit(p, id(name)) : null;
    }

    /** A level-scaled param {@code "a..b"} (Lv1 = a, Lv10 = b, linear), or a plain number; {@code a..b} is the default. */
    static double lerp(HabitsApi.PerkHit hit, String key, double a, double b) {
        String s = hit.skill().perk().params().get(key);
        if (s != null) {
            try {
                int i = s.indexOf("..");
                if (i < 0) return Double.parseDouble(s.trim());
                a = Double.parseDouble(s.substring(0, i).trim());
                b = Double.parseDouble(s.substring(i + 2).trim());
            } catch (NumberFormatException ignored) {
                // bad JSON param: defaults
            }
        }
        return a + (b - a) * (Math.min(10, Math.max(1, hit.level())) - 1) / 9.0;
    }

    static String param(HabitsApi.PerkHit hit, String key, String fallback) {
        return hit.skill().perk().param(key, fallback);
    }

    /** True with {@code percent}% odds. */
    static boolean roll(Player p, double percent) {
        return p.getRandom().nextDouble() * 100 < percent;
    }

    static void fire(Player p, String event, @Nullable BlockPos pos) {
        Triggers.fire(p, TriggerType.CUSTOM, pos == null ? TriggerContext.of(id(event)) : TriggerContext.of(id(event)).withPos(pos));
    }

    static void say(Player p, String key, Object... args) {
        p.displayClientMessage(Component.translatable("habits.building." + key, args), true);
    }

    // ---------------------------------------------------------------- items

    static boolean has(Player p, Item item, int n) {
        return p.getAbilities().instabuild || p.getInventory().countItem(item) >= n;
    }

    /** Take one item, hands first (creative: free). */
    static boolean take(Player p, Item item) {
        if (p.getAbilities().instabuild) return true;
        for (ItemStack s : new ItemStack[]{p.getMainHandItem(), p.getOffhandItem()})
            if (s.is(item)) { s.shrink(1); return true; }
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) { s.shrink(1); return true; }
        }
        return false;
    }

    static void give(Player p, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!p.getInventory().add(stack) && !stack.isEmpty()) p.drop(stack, false);
    }

    // ---------------------------------------------------------------- world work

    /** Place a state as the player if the spot is replaceable (no fluid source is overwritten unless {@code fluidOk}). */
    static boolean placeAs(Player p, BlockPos pos, BlockState state) {
        if (!(p.level() instanceof ServerLevel level) || !level.isLoaded(pos)) return false;
        BlockState at = level.getBlockState(pos);
        if (!at.canBeReplaced() || !state.canSurvive(level, pos) || !level.isUnobstructed(state, pos, CollisionContext.empty())) return false;
        return set(p, level, pos, Block.updateFromNeighbourShapes(state, level, pos), 3);
    }

    /** Change a block in place as the player (fires the place event, reverts if cancelled). */
    static boolean changeAs(Player p, BlockPos pos, BlockState state) {
        return p.level() instanceof ServerLevel level && level.isLoaded(pos) && set(p, level, pos, state, 3);
    }

    static boolean set(Player p, ServerLevel level, BlockPos pos, BlockState state, int flags) {
        boolean was = Work.busy;
        Work.busy = true;
        try {
            BlockSnapshot snap = BlockSnapshot.create(level.dimension(), level, pos);
            if (!level.setBlock(pos, state, flags)) return false;
            if (EventHooks.onBlockPlace(p, snap, Direction.UP)) {
                snap.restore();
                return false;
            }
            return true;
        } finally {
            Work.busy = was;
        }
    }

    /** Break as the player (BreakEvent, durability, enchanted drops); rolls cards like a hand break. */
    static boolean breakAs(ServerPlayer p, BlockPos pos) {
        BlockState s = p.level().getBlockState(pos);
        ResourceLocation tool = TriggerEvents.itemOrNull(p.getMainHandItem());
        boolean was = Work.busy, broke;
        Work.busy = true;
        try {
            broke = p.gameMode.destroyBlock(pos);
        } finally {
            Work.busy = was;
        }
        if (broke) TriggerEvents.rollBreak(p, s, pos, tool);
        return broke;
    }

    /** Breadth-first walk from {@code start} over {@code dirs} through positions passing {@code ok}, nearest first.
     * Returns at most {@code max + 1} positions (start included when it passes), so {@code size() > max} means "too big". */
    static List<BlockPos> flood(Level level, BlockPos start, int max, Direction[] dirs, Predicate<BlockPos> ok) {
        List<BlockPos> out = new ArrayList<>();
        if (!ok.test(start)) return out;
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty() && out.size() <= max) {
            BlockPos c = q.poll();
            out.add(c);
            for (Direction d : dirs) {
                BlockPos n = c.relative(d);
                if (seen.add(n) && level.isLoaded(n) && ok.test(n)) q.add(n);
            }
        }
        return out;
    }

    static final Direction[] FLAT = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    // ---------------------------------------------------------------- jobs (spread over ticks)

    private static final Map<UUID, ArrayDeque<Runnable>> JOBS = playerMap();
    private static final Map<UUID, Integer> RATE = playerMap();

    /** Queue steps for a player, {@code perTick} per server tick. */
    static void queue(Player p, List<Runnable> steps, int perTick) {
        JOBS.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>()).addAll(steps);
        RATE.put(p.getUUID(), Math.max(1, perTick));
    }

    /** Run every queued step now (tests: fake players and their jobs never wait for a tick). */
    static void drain(Player p) {
        ArrayDeque<Runnable> q = JOBS.remove(p.getUUID());
        if (q != null) while (!q.isEmpty()) q.poll().run();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (JOBS.isEmpty()) return;
        for (var it = JOBS.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            int n = RATE.getOrDefault(en.getKey(), 8);
            ArrayDeque<Runnable> q = en.getValue();
            for (int i = 0; i < n && !q.isEmpty(); i++) q.poll().run();
            if (q.isEmpty()) it.remove();
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        UUID id = e.getEntity().getUUID();
        for (Map<UUID, ?> m : PLAYER_MAPS) m.remove(id);
        Moving.logout(e.getEntity());
    }

    @SubscribeEvent
    public static void onStop(ServerStoppingEvent e) {
        for (Map<UUID, ?> m : PLAYER_MAPS) m.clear();
    }
}
