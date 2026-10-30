package com.habits.progression.client;

import com.habits.Habits;
import com.habits.core.Rarity;
import com.habits.progression.CharmData;
import com.habits.progression.ProgRegistries;
import com.habits.progression.SigilData;
import net.minecraft.client.renderer.item.ItemProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

/** Item looks: model overrides pick the sigil texture by type ({@code habits:kind} = ordinal / 10) and the charm texture
 * by category ((ordinal + 1) / 10, 0 = charm_base); layer 0 is tinted toward the rarity color. Epic and Legendary also
 * get the enchant glint (the items' isFoil). */
@EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ProgressionClient {
    private ProgressionClient() {}

    @SubscribeEvent
    static void setup(FMLClientSetupEvent e) {
        e.enqueueWork(() -> {
            ItemProperties.register(ProgRegistries.SIGIL.get(), Habits.id("kind"), (stack, level, entity, seed) -> {
                SigilData d = stack.get(ProgRegistries.SIGIL_DATA.get());
                return d == null ? 0 : d.type().ordinal() / 10f;
            });
            ItemProperties.register(ProgRegistries.CHARM.get(), Habits.id("kind"), (stack, level, entity, seed) -> {
                CharmData d = stack.get(ProgRegistries.CHARM_DATA.get());
                return d == null ? 0 : (d.category().ordinal() + 1) / 10f;
            });
        });
    }

    @SubscribeEvent
    static void colors(RegisterColorHandlersEvent.Item e) {
        e.register((stack, tint) -> {
            if (tint != 0) return -1;
            SigilData s = stack.get(ProgRegistries.SIGIL_DATA.get());
            CharmData c = stack.get(ProgRegistries.CHARM_DATA.get());
            Rarity r = s != null ? s.rarity() : c != null ? c.rarity() : null;
            return r == null ? -1 : 0xFF000000 | towardWhite(r.color);
        }, ProgRegistries.SIGIL.get(), ProgRegistries.CHARM.get());
    }

    /** Rarity color mixed 40% toward white, so the texture's own colors still read. */
    static int towardWhite(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r += (255 - r) * 2 / 5; g += (255 - g) * 2 / 5; b += (255 - b) * 2 / 5;
        return (r << 16) | (g << 8) | b;
    }
}
