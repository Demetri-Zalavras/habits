package com.habits;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/** Mod entry. Owner: core. Every package wires itself from its own init; nobody else edits this file (CONTRACT.md). */
@Mod(Habits.MODID)
public final class Habits {
    public static final String MODID = "habits";

    /** Interned: perk code calls this per tick and per event, so a repeat path costs a map read, not a new id. Bounded by
     * the distinct paths the code uses. */
    private static final java.util.Map<String, ResourceLocation> IDS = new java.util.concurrent.ConcurrentHashMap<>();

    public static ResourceLocation id(String path) {
        ResourceLocation r = IDS.get(path);
        return r != null ? r : IDS.computeIfAbsent(path, p -> ResourceLocation.fromNamespaceAndPath(MODID, p));
    }

    public Habits(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        com.habits.core.CoreInit.init(modBus);
        com.habits.trigger.TriggerInit.init(modBus);
        com.habits.perk.PerkInit.init(modBus);
        com.habits.progression.ProgressionInit.init(modBus);
        com.habits.danger.DangerInit.init(modBus); // danger:
    }
}
