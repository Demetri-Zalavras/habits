package com.habits.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mechanic id -> implementation. Register during mod construction (your subpackage's {@code init}), before datapacks
 * load. Skills naming an unregistered id still load (with a warning) and simply do nothing until it exists.
 */
public final class PerkMechanics {
    private static final Map<ResourceLocation, PerkMechanic> ALL = new LinkedHashMap<>();

    private PerkMechanics() {}

    /** Register a mechanic. A duplicate id throws (two agents claimed the same id). */
    public static synchronized void register(ResourceLocation id, PerkMechanic mechanic) {
        if (ALL.putIfAbsent(id, mechanic) != null) throw new IllegalStateException("perk mechanic registered twice: " + id);
    }

    @Nullable
    public static PerkMechanic get(ResourceLocation id) {
        return ALL.get(id);
    }

    public static Map<ResourceLocation, PerkMechanic> all() {
        return Collections.unmodifiableMap(ALL);
    }

    /** Dispatch {@link PerkMechanic#tick} for the player's active mechanic skills (best hit per mechanic). */
    static void tick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (!p.isAlive() || p.isSpectator()) return;
        HabitsApi.Index ix = HabitsApi.index(p);
        for (int i = 0; i < ix.tickers.length; i++) ix.tickers[i].tick(p, ix.tickHits[i]);
    }
}
