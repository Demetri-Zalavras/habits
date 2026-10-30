package com.habits.trigger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;

/** Entry point of package trigger. Owner: TRIGGERS. Called once from Habits. Register your listeners here
 * (modBus for registration and setup events, {@code NeoForge.EVENT_BUS} for game events). */
public final class TriggerInit {
    private TriggerInit() {}

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(TriggerEvents.class);
    }

    /** Define this package's config values (already inside its own section). Store them in your own static fields. */
    public static void defineConfig(ModConfigSpec.Builder b) {
    }
}
