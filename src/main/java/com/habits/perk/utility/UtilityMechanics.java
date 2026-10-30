package com.habits.perk.utility;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Crafting, utility and odd perk mechanics (domain crafting-utility-odd). Called once from PerkInit. Every mechanic id
 * equals its skill id; the table is in docs/mechanics/crafting-utility-odd.md.
 */
public final class UtilityMechanics {
    private UtilityMechanics() {}

    public static void init(IEventBus modBus) {
        Crafting.init();
        Furnaces.init();
        Tables.init();
        Brewing.init();
        Music.init();
        Villagers.init();
        Xp.init();
        Odd.init();
        Mobs.init();
        Inventory.init(modBus);
        UtilityNet.init(modBus);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) com.habits.perk.utility.client.UtilityClient.init(modBus);
        for (Class<?> c : new Class<?>[]{Crafting.class, Furnaces.class, Tables.class, Brewing.class, Music.class, Villagers.class,
                Xp.class, Odd.class, Mobs.class, Inventory.class}) {
            NeoForge.EVENT_BUS.register(c);
        }
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> U.forget(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            U.forgetAll();
            Music.NOTE_CLICK.clear();
            Mobs.SCARED.clear();
            Odd.REGROWN.clear();
            Furnaces.clearCache();
        });
    }
}
