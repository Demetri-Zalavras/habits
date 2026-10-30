package com.habits;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config (look and feel). Owner: core. Loaded only from {@link HabitsClient}; each client package defines its
 * own section in its init's {@code defineConfig(B)}. */
public final class ClientConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    static {
        B.push("book"); com.habits.client.book.BookClientInit.defineConfig(B); B.pop();
        B.push("fx"); com.habits.client.fx.FxClientInit.defineConfig(B); B.pop();
    }

    public static final ModConfigSpec SPEC = B.build();

    private ClientConfig() {}
}
