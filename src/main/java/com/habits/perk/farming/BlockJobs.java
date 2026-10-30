package com.habits.perk.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.function.Predicate;

/**
 * Block-heavy work (tree felling, area harvest, midnight growth) spread over server ticks. Each player gets at most
 * {@link #BUDGET} block operations per tick across all their jobs; a job never touches an unloaded chunk.
 */
final class BlockJobs {
    private BlockJobs() {}

    /** Block operations per player per tick, shared by all their jobs. */
    static final int BUDGET = 8;

    interface Job {
        /** Do at most {@code budget} block operations. Returns how many it used, or -1 when finished. */
        int step(ServerPlayer p, int budget);
    }

    static final Map<ServerPlayer, Queue<Job>> JOBS = new HashMap<>();

    static void add(ServerPlayer p, Job j) {
        JOBS.computeIfAbsent(p, k -> new ArrayDeque<>()).add(j);
    }

    static boolean idle(ServerPlayer p) {
        Queue<Job> q = JOBS.get(p);
        return q == null || q.isEmpty();
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post e) {
        if (JOBS.isEmpty()) return;
        for (Iterator<Map.Entry<ServerPlayer, Queue<Job>>> it = JOBS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<ServerPlayer, Queue<Job>> en = it.next();
            ServerPlayer p = en.getKey();
            if (p.isRemoved() || !Farm.ok(p)) {
                it.remove();
                continue;
            }
            runTick(p, en.getValue());
            if (en.getValue().isEmpty()) it.remove();
        }
    }

    /** One tick of work for one player. Tests call it through {@link #drain}. */
    static int runTick(ServerPlayer p, Queue<Job> q) {
        // each job steps at most once a tick (its own rate), all jobs share the player's budget
        int left = BUDGET;
        for (java.util.Iterator<Job> it = q.iterator(); it.hasNext() && left > 0; ) {
            int used = it.next().step(p, left);
            if (used < 0) it.remove();
            else left -= Math.max(1, used);
        }
        return BUDGET - left;
    }

    /** Run a player's jobs to the end (tests). Returns the number of ticks it took. */
    static int drain(ServerPlayer p) {
        Queue<Job> q = JOBS.get(p);
        int ticks = 0;
        while (q != null && !q.isEmpty() && ticks < 10_000) {
            runTick(p, q);
            ticks++;
        }
        JOBS.remove(p);
        return ticks;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        JOBS.remove(e.getEntity());
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent e) {
        JOBS.clear();
    }

    /**
     * Break a list of positions as the player, {@code perTick} at most. Skips spots that no longer match {@code valid}.
     * With a {@code tool}, stops as soon as the player no longer holds that kind of tool (it broke or they switched).
     */
    static Job breaking(Queue<BlockPos> todo, int perTick, Predicate<BlockState> valid, @Nullable ItemStack tool,
                        @Nullable Runnable onDone) {
        return (p, budget) -> {
            int n = 0, max = Math.min(perTick, budget);
            while (n < max && !todo.isEmpty()) {
                // same kind of tool, not the same object: mods and inventory syncs may replace the held stack with a copy
                ItemStack held = p.getMainHandItem();
                if (tool != null && (held.isEmpty() || !held.is(tool.getItem()))) { todo.clear(); break; }
                BlockPos pos = todo.poll();
                n++;
                if (!Farm.loaded(p.level(), pos) || !valid.test(p.level().getBlockState(pos))) continue;
                Farm.breakAs(p, pos);
            }
            if (!todo.isEmpty()) return n;
            if (onDone != null) onDone.run();
            return -1;
        };
    }

    /** Cost units: a block read is 1, a break or removal is READS_PER_OP (one budget op). */
    static final int READS_PER_OP = 32;

    /**
     * Visit positions from an iterator. Each look costs 1 unit; {@code act} returns the extra units it spent (0 for a
     * look, a few for a quiet state change, READS_PER_OP for a removal with drops). Spends at most
     * {@code perTick * READS_PER_OP} units a tick and skips unloaded positions.
     */
    static Job visiting(Iterator<BlockPos> it, int perTick, java.util.function.ToIntFunction<BlockPos> act) {
        return (p, budget) -> {
            int units = 0, max = Math.min(perTick, budget) * READS_PER_OP;
            while (units < max && it.hasNext()) {
                BlockPos pos = it.next().immutable();
                units++;
                if (Farm.loaded(p.level(), pos)) units += act.applyAsInt(pos);
            }
            return it.hasNext() ? Math.max(1, units / READS_PER_OP) : -1;
        };
    }
}
