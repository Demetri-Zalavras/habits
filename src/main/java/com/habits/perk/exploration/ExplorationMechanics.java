package com.habits.perk.exploration;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Entry point of package perk.exploration (Exploration specialization mechanics). Owner: PERKS-EXPLORATION. Called once
 * from PerkInit. Mechanic id = skill id ({@code habits:exploration/<name>}); the table is in docs/mechanics/exploration.md. */
public final class ExplorationMechanics {
    private ExplorationMechanics() {}

    public static void init(IEventBus modBus) {
        Zones.init();
        Finds.init();
        Travel.init();
        NeoForge.EVENT_BUS.register(Zones.class);
        NeoForge.EVENT_BUS.register(Finds.class);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> X.forget(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            X.forgetAll();
            Zones.TEST_INSIDE.clear();
        });
    }
}
