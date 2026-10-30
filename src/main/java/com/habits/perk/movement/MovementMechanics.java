package com.habits.perk.movement;

import com.habits.core.PerkMechanics;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Movement and exploration perk mechanics (docs/mechanics/movement-exploration.md). Called once from PerkInit.
 * Mechanic ids are {@code habits:movement/<skill>}, one per skill ({@link Mech}). Server logic: {@link ServerTick},
 * {@link WorldEvents}, {@link Actions}; local-player physics: {@link Physics}; client input, compass bar and outlines
 * live in {@code com.habits.client.movement} and wire themselves with {@code @EventBusSubscriber(value = Dist.CLIENT)}.
 */
public final class MovementMechanics {
    private MovementMechanics() {}

    public static void init(IEventBus modBus) {
        for (Mech m : Mech.ALL) {
            String meaning = m.meaning;
            PerkMechanics.register(m.id, () -> meaning);
        }
        MoveData.ATTACHMENTS.register(modBus);
        modBus.addListener(MovePayloads::register);
        NeoForge.EVENT_BUS.register(WorldEvents.class);
        NeoForge.EVENT_BUS.register(SpecEvents.class);
        com.habits.danger.DangerApi.setHooks(new FrontierLord());
    }
}
