package com.habits.danger;

import com.habits.Habits;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tells a player on the action bar when they cross into a harder zone (with a low drum) or back into a safer one. */
public final class ZoneWatch {
    private ZoneWatch() {}

    static final SoundEvent DRUM = SoundEvent.createVariableRangeEvent(Habits.id("danger.rises"));
    /** Last zone rank seen per player; the first check after login only records it. Cleared on logout and server stop. */
    private static final Map<UUID, Integer> RANK = new ConcurrentHashMap<>();

    static void init(IEventBus bus) {
        bus.addListener(ZoneWatch::onTick);
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> RANK.remove(e.getEntity().getUUID()));
        bus.addListener((ServerStoppedEvent e) -> RANK.clear());
    }

    private static void onTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % DangerInit.CHECK_TICKS.get() != 0) return;
        List<ServerPlayer> players = e.getServer().getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) check(players.get(i));
    }

    static void check(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator()) return;
        Zones.Zone z = Zones.at(p.serverLevel(), p.getX(), p.getZ());
        Integer was = RANK.put(p.getUUID(), z.rank());
        if (was == null || was == z.rank()) return;
        if (z.rank() > was) {
            p.displayClientMessage(Component.translatable("danger.habits.rises", z.name()), true);
            p.playNotifySound(DRUM, SoundSource.AMBIENT, DangerInit.DRUM_VOLUME.get().floatValue(), DangerInit.DRUM_PITCH.get().floatValue());
        } else {
            p.displayClientMessage(Component.translatable("danger.habits.safer", z.name()), true);
        }
    }
}
