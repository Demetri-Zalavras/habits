package com.habits.perk;

import com.habits.Habits;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Run perk follow-ups at the end of a server tick. Not {@code server.tell(TickTask)}: a server that is behind only runs
 * a TickTask once tickCount > tick + 3, so follow-ups ran
 * seconds late under load. Tasks run on the server thread; adding is thread-safe (chat events). */
@EventBusSubscriber(modid = Habits.MODID)
public final class Later {
    private Later() {}

    private record Task(int ticks, Runnable r) {}

    private static List<Task> tasks = new ArrayList<>();

    /** {@code ticks} 0 = at the end of this tick, 1 = end of the next tick, ... */
    public static synchronized void run(@Nullable MinecraftServer server, int ticks, Runnable r) {
        if (server != null) tasks.add(new Task(ticks, r));
    }

    public static void run(ServerLevel level, int ticks, Runnable r) {
        run(level.getServer(), ticks, r);
    }

    @SubscribeEvent
    static void onTick(ServerTickEvent.Post e) {
        List<Task> now;
        synchronized (Later.class) {
            if (tasks.isEmpty()) return;
            now = tasks;
            tasks = new ArrayList<>();
        }
        for (Task t : now) {
            if (t.ticks > 0) run(e.getServer(), t.ticks - 1, t.r);
            else t.r.run();
        }
    }

    @SubscribeEvent
    static synchronized void onStopped(ServerStoppedEvent e) {
        tasks = new ArrayList<>();
    }
}
