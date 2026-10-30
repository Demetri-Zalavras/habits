package com.habits.client.fx;

import com.habits.Habits;
import com.habits.core.ClientHooks;
import com.habits.core.Payloads;
import com.habits.core.Rarity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Entry point of package client.fx (card-add flip HUD, card item models, slot level, sounds). Owner: FX. Client dist only. */
public final class FxClientInit {
    private FxClientInit() {}

    static ModConfigSpec.BooleanValue FLIP, FEED;
    static ModConfigSpec.IntValue FEED_ROWS;
    static ModConfigSpec.DoubleValue FEED_SECONDS;
    static ModConfigSpec.ConfigValue<List<? extends Double>> CHIME_PITCH, HOLD_MS, INTENSITY, PARTICLES, SPEED, TURNS;
    static ModConfigSpec.DoubleValue CARD_HEIGHT, DIM, QUEUED_HOLD_MS;

    public static void init(IEventBus modBus) {
        modBus.addListener(ModelEvent.RegisterAdditional.class, CardModels::register);
        modBus.addListener(ModelEvent.ModifyBakingResult.class, CardModels::bake);
        modBus.addListener(RegisterItemDecorationsEvent.class, CardLevelDecorator::register);
        modBus.addListener(RegisterGuiLayersEvent.class, e -> e.registerAboveAll(Habits.id("gain_feed"), GainFeed::render));
        modBus.addListener(RegisterGuiLayersEvent.class, e -> e.registerAboveAll(Habits.id("card_flip"), (g, dt) -> {
            long t0 = FxShots.Perf.on ? System.nanoTime() : 0;
            FlipHud.render(g, dt);
            if (t0 != 0) FxShots.Perf.fxNs += System.nanoTime() - t0;
        }));
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, e -> FxShots.Perf.frame());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> { FlipHud.clear(); GainFeed.clear(); });
        // only a left click or Esc skips the flip; movement, right-click (adding the next card) and other keys never do
        NeoForge.EVENT_BUS.addListener(InputEvent.MouseButton.Pre.class, e -> {
            if (e.getAction() == GLFW.GLFW_PRESS && e.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                    && Minecraft.getInstance().screen == null) FlipHud.skip(); // not consumed: the swing still happens
        });
        // Esc reaches mods only as the pause menu opening (vanilla handles the key first): a skipping Esc cancels it
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class, e -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(e.getNewScreen() instanceof PauseScreen) || e.getCurrentScreen() != null
                    || !InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_ESCAPE) || !FlipHud.skip()) return;
            e.setCanceled(true);
            mc.tell(mc.getSoundManager()::resume); // pauseGame pauses all sounds right after this cancelled open
        });
        ClientHooks.register(new ClientHooks() {
            @Override
            public void cardAdded(Payloads.CardAdded e) {
                FxShots.REAL_SEEN++;
                if (!e.wasNew()) return; // only a brand-new skill flips; core's action bar covers the rest
                FlipHud.enqueue(e.def(), new FlipHud.Added(e.skill(), e.level(), e.progress(), e.needed(), e.wasNew(), e.leveledUp()));
            }

            @Override
            public void cardGained(Payloads.CardAdded e) {
                GainFeed.card(e);
            }

            @Override
            public void xpGained(Payloads.XpGained e) {
                GainFeed.xp(e);
            }

            @Override
            public void sigilGained(Payloads.FeedSigil e) {
                GainFeed.sigil(e.icon(), e.name(), e.rarity(), e.count());
            }

            @Override
            public void skillsReloaded() {
                CardModels.clear();
            }
        });
        FxShots.register();
        FeedShots.register();
    }

    public static void defineConfig(ModConfigSpec.Builder b) {
        b.comment("Per-rarity lists are Common, Uncommon, Rare, Epic, Legendary.");
        CHIME_PITCH = list(b, "chime_pitch", "Reveal sound pitch", 0.7, 0.84, 1.0, 1.19, 1.41);
        FLIP = b.comment("Card flip on screen when a card unlocks a new skill in your book").define("flip", true);
        // keys renamed from hold_ms / speed (2026-10-08) so saved configs pick up the slower flip instead of the old values
        HOLD_MS = list(b, "read_ms", "How long the revealed card stays up to be read, in ms", 5000.0, 5500.0, 6000.0, 6500.0, 7000.0);
        QUEUED_HOLD_MS = b.comment("Read time in ms while more new cards wait to flip, so a stack of unlocks stays short")
                .defineInRange("queued_read_ms", 2500.0, 0, 60000);
        SPEED = list(b, "time_scale", "Rise, spin and fly-out time multiplier (1 = face up at 0.8 s, fly-out 0.4 s)", 1.2, 1.5, 1.8, 2.0, 2.0);
        TURNS = list(b, "turns", "Spin turns before the card lands face up (must end in .5)", 0.5, 0.5, 1.5, 1.5, 1.5);
        INTENSITY = list(b, "intensity", "Size of the glow, ring and burst", 0.55, 0.68, 0.8, 1.0, 1.3);
        PARTICLES = list(b, "particles", "Burst particle count", 0.0, 16.0, 22.0, 28.0, 40.0);
        CARD_HEIGHT = b.comment("Flip card height as a share of the screen height").defineInRange("card_height", 0.58, 0.2, 0.9);
        DIM = b.comment("How dark the screen edges get during the flip").defineInRange("dim", 0.75, 0, 1);
        FEED = b.comment("Show cards and sigils you gain as rows on the left of the screen").define("feed", true);
        FEED_ROWS = b.comment("Most feed rows shown at once").defineInRange("feed_rows", 4, 1, 8);
        FEED_SECONDS = b.comment("Seconds a feed row stays before it fades").defineInRange("feed_seconds", 5.0, 1, 60);
    }

    private static ModConfigSpec.ConfigValue<List<? extends Double>> list(ModConfigSpec.Builder b, String key, String comment, Double... def) {
        return b.comment(comment).defineList(key, List.of(def), () -> 0.0, o -> o instanceof Double);
    }

    static double perRarity(ModConfigSpec.ConfigValue<List<? extends Double>> v, Rarity r) {
        List<? extends Double> l = v.get();
        return l.isEmpty() ? v.getDefault().get(r.ordinal()) : l.get(Math.min(r.ordinal(), l.size() - 1));
    }
}
