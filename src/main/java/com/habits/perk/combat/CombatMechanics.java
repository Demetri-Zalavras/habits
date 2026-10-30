package com.habits.perk.combat;

import com.habits.core.PerkMechanic;
import com.habits.core.PerkMechanics;
import com.habits.trigger.Triggers;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Melee perk mechanics (owner: the melee domain agent). One mechanic per melee skill, ids {@code habits:melee/<skill>}
 * (see {@link Mech} and docs/mechanics/melee.md). All behavior lives in plain game-event listeners ({@link Hooks}).
 */
public final class CombatMechanics {
    private CombatMechanics() {}

    public static void init(IEventBus modBus) {
        for (Mech m : Mech.values()) {
            String meaning = m.meaning;
            PerkMechanics.register(m.id, () -> meaning);
        }
        NeoForge.EVENT_BUS.register(Hooks.class);
        NeoForge.EVENT_BUS.register(Mythic.class);
        Mythic.ATTACHMENTS.register(modBus);
        Triggers.SPIES.add(Hooks::onSwingAir);
    }
}
