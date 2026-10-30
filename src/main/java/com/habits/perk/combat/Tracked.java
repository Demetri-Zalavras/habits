package com.habits.perk.combat;

import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Short-lived per-mob effects (daze, bleed, slam, homing...) and end-of-tick jobs. Server thread only. Capped, dropped when
 * the mob dies or unloads, cleared on server stop. Only tagged mobs tick, never a world scan. */
final class Tracked {
    private Tracked() {}

    /** Return false when done. */
    interface Task {
        boolean tick(LivingEntity e);
    }

    /** A task that runs {@link #run} for {@code ticks} ticks, then {@link #end}. */
    abstract static class Timed implements Task {
        int ticks;

        Timed(int ticks) {
            this.ticks = ticks;
        }

        @Override
        public final boolean tick(LivingEntity e) {
            if (ticks-- <= 0) {
                end(e);
                return false;
            }
            run(e);
            return true;
        }

        void run(LivingEntity e) {}

        void end(LivingEntity e) {}
    }

    static final int MAX_MOBS = 256;
    private static final Map<LivingEntity, Map<String, Task>> TASKS = new IdentityHashMap<>();
    private static List<Runnable> later = new ArrayList<>();

    static void put(LivingEntity e, String key, Task t) {
        Map<String, Task> m = TASKS.get(e);
        if (m == null) {
            if (TASKS.size() >= MAX_MOBS) return;
            TASKS.put(e, m = new java.util.HashMap<>(4));
        }
        m.put(key, t);
    }

    @Nullable
    static Task get(LivingEntity e, String key) {
        Map<String, Task> m = TASKS.get(e);
        return m == null ? null : m.get(key);
    }

    static boolean has(LivingEntity e, String key) {
        return get(e, key) != null;
    }

    static int size() {
        return TASKS.size();
    }

    /** Run at the end of this server tick, after the current attack finished (vanilla resets motion and fall distance
     * after the damage events). */
    static void later(Runnable r) {
        if (later.size() < 512) later.add(r);
    }

    private record Delayed(int[] ticks, Runnable r) {}

    private static final List<Delayed> DELAYED = new ArrayList<>();

    /** Run {@code delay} ticks from now: spreads multi-entity work (fang lines) over several ticks. */
    static void laterIn(int delay, Runnable r) {
        if (delay <= 0) later(r);
        else if (DELAYED.size() < 512) DELAYED.add(new Delayed(new int[]{delay}, r));
    }

    static void tick() {
        if (!DELAYED.isEmpty()) DELAYED.removeIf(d -> {
            if (--d.ticks[0] > 0) return false;
            later.add(d.r);
            return true;
        });
        List<Runnable> now = later;
        later = new ArrayList<>();
        for (Runnable r : now) r.run();
        if (TASKS.isEmpty()) return;
        for (var en : new ArrayList<>(TASKS.entrySet())) {
            LivingEntity e = en.getKey();
            Map<String, Task> m = en.getValue();
            if (e.isRemoved() || !e.isAlive()) {
                TASKS.remove(e);
                continue;
            }
            for (var t : new ArrayList<>(m.entrySet())) {
                // a task may replace itself or add another key while ticking; only remove it if it is still the one there
                if (!t.getValue().tick(e)) m.remove(t.getKey(), t.getValue());
            }
            if (m.isEmpty()) TASKS.remove(e);
        }
    }

    static void clear() {
        TASKS.clear();
        DELAYED.clear();
        later = new ArrayList<>();
    }
}
