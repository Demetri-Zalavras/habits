package com.habits.perk.survival;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Survival perk mechanics (one per survival skill, ids {@code habits:survival/<skill>}; table in docs/mechanics/survival.md).
 * Called once from PerkInit.
 */
public final class SurvivalMechanics {
    private SurvivalMechanics() {}

    public static void init(IEventBus modBus) {
        Sk.registerAll();
        ClassGuard.init();
        modBus.addListener(SurvivalNet::register);
        var bus = NeoForge.EVENT_BUS;
        bus.register(Defense.class);
        bus.register(Effects.class);
        bus.register(Food.class);
        bus.register(Life.class);
        bus.register(Ticks.class);
        bus.register(Guard.class);
        bus.register(ClassGuard.class);
        bus.register(TempBlocks.class);
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> { Sk.forget(e.getEntity()); Life.forget(e.getEntity()); });
        bus.addListener((ServerStoppedEvent e) -> { Sk.clearServer(); Life.clearServer(); });
    }
}
