package com.habits.perk.crafting;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Entry point of package perk.crafting (Crafting specialization mechanics). Owner: PERKS-CRAFTING. Called once from
 * PerkInit. Every mechanic id equals its skill id ({@code habits:crafting/<name>}); table in docs/mechanics/crafting.md.
 */
public final class CraftingMechanics {
    private CraftingMechanics() {}

    public static void init(IEventBus modBus) {
        Bench.init();
        Forge.init();
        Anvil.init();
        Care.init();
        Alchemy.init();
        Market.init();
        Smith.init();
        Enhance.init();
        modBus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTab() == com.habits.core.CoreRegistries.TAB.get()) e.accept(Enhance.STONE.get());
        });
        for (Class<?> c : new Class<?>[]{Bench.class, Forge.class, Anvil.class, Care.class, Alchemy.class, Market.class, Smith.class, Rating.class, Brew.class, Enhance.class}) {
            NeoForge.EVENT_BUS.register(c);
        }
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> C.forget(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> C.forgetAll());
    }
}
