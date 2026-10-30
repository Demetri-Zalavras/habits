package com.habits.trigger;

import com.habits.core.HabitsApi;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.perk.farming.Farm;
import com.habits.perk.mining.Work;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The single gate every trigger goes through: server players only, no fakes, alive (except DIE), not spectating. */
public final class Triggers {
    private Triggers() {}

    /** Test hook: sees every trigger that passes the gate, right before core gets it. */
    public interface Spy {
        void fired(ServerPlayer player, TriggerType type, TriggerContext ctx);
    }

    /** Copy-on-write: spies may add or remove themselves while being called, and the per-trigger walk (combat keeps a
     * spy registered for good) must not copy the list each time. */
    public static final List<Spy> SPIES = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Fake players allowed through the gate (GameTests only). */
    public static final Set<UUID> TEST_PLAYERS = new HashSet<>();

    public static void fire(Player player, TriggerType type, TriggerContext ctx) {
        // the mod's own multi-block work (vein mining, felling, area harvest, an arrow's permission check) is not the player's action
        if (Work.busy || Farm.busy) return;
        if (!allowed(player) || (!player.isAlive() && type != TriggerType.DIE)) return;
        ServerPlayer sp = (ServerPlayer) player;
        for (Spy s : SPIES) s.fired(sp, type, ctx);
        int won = HabitsApi.onTrigger(sp, type, ctx);
        // lucky_charm's "finding cards": one event per card won, never for a card won from this event itself
        if (!CARD_WON.equals(ctx.target())) for (int i = 0; i < won; i++) fire(sp, TriggerType.CUSTOM, TriggerContext.of(CARD_WON));
    }

    /** Fired once per card won from any other trigger. */
    public static final net.minecraft.resources.ResourceLocation CARD_WON = com.habits.Habits.id("card_dropped");

    /**
     * How much a {@link net.neoforged.neoforge.event.StatAwardEvent} actually adds. The event fires from
     * {@code StatsCounter.setValue} with the stat's NEW TOTAL, before it is stored, so the increment is that total
     * minus the value still in the counter. Reading {@code getValue()} directly rolls the whole lifetime total on
     * every step (cards dropped every few blocks).
     */
    public static int statDelta(net.neoforged.neoforge.event.StatAwardEvent e) {
        int old = e.getEntity() instanceof ServerPlayer sp ? sp.getStats().getValue(e.getStat()) : 0;
        return e.getValue() - old;
    }

    static boolean allowed(Player player) {
        return player instanceof ServerPlayer sp && !sp.isSpectator()
                && (!sp.isFakePlayer() || TEST_PLAYERS.contains(sp.getUUID()));
    }
}
