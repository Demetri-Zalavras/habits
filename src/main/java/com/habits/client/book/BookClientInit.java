package com.habits.client.book;

import com.habits.client.DevShots;
import com.habits.core.ClientHooks;
import com.habits.core.SkillRegistry;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Entry point of package client.book. Owner: BOOK-UI. Called once from HabitsClient (client dist only). Register your listeners here
 * (modBus for registration and setup events, {@code NeoForge.EVENT_BUS} for game events). */
public final class BookClientInit {
    private BookClientInit() {}

    static ModConfigSpec.DoubleValue FOIL_SECONDS;
    private static boolean warm;

    public static void init(IEventBus modBus) {
        ClientHooks.register(new ClientHooks() {
            @Override
            public void openBinder() {
                Minecraft.getInstance().setScreen(new BinderScreen());
            }

            @Override
            public void skillsReloaded() {
                CardRenderer.clearCache();
                if (Minecraft.getInstance().screen instanceof BinderScreen b) b.rebuild();
            }
        });
        DevShots.PLANS.put("book", BookShots::plan);
        DevShots.PLANS.put("booksearch", BookShots::search);
        DevShots.PLANS.put("bookprog", BookShots::progression);
        // the first face build of a session pays for font, shader and item-model warm-up (the one-off first-open hitch):
        // build one FULL face right after joining so the book opens warm
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> warm = true);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            if (!warm || Minecraft.getInstance().player == null) return;
            var def = SkillRegistry.all().stream().findFirst().orElse(null);
            if (def == null) return; // skills not synced yet: try next tick
            warm = false;
            CardRenderer.prepare(def, 1, true, CardRenderer.Size.FULL, CardFace.DEFAULT_W, Long.MAX_VALUE);
        });
        // cached card faces hold old textures and fonts after a resource reload (F3+T)
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent e) ->
                e.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) rm -> CardRenderer.clearCache()));
    }

    /** Define this package's config values (already inside its own section). Store them in your own static fields. */
    public static void defineConfig(ModConfigSpec.Builder b) {
        FOIL_SECONDS = b.comment("Seconds for one foil shine sweep across Epic and Legendary cards.")
                .defineInRange("foilSeconds", 3.2, 0.5, 30.0);
    }
}
