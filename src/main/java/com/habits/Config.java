package com.habits;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Common config (server rules, shipped to clients for display). Owner: core. Each package defines its own section
 * in its init's {@code defineConfig(B)}, already wired below; edit your own section, never this file. */
public final class Config {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    static {
        B.push("core"); com.habits.core.CoreInit.defineConfig(B); B.pop();
        B.push("triggers"); com.habits.trigger.TriggerInit.defineConfig(B); B.pop();
        B.push("perks"); com.habits.perk.PerkInit.defineConfig(B); B.pop();
        B.push("progression"); com.habits.progression.ProgressionInit.defineConfig(B); B.pop();
        B.push("danger"); com.habits.danger.DangerInit.defineConfig(B); B.pop(); // danger:
    }

    public static final ModConfigSpec SPEC = B.build();

    private Config() {}
}
