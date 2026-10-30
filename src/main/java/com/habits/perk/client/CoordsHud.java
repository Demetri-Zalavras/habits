package com.habits.perk.client;

import com.habits.Habits;
import com.habits.core.PerkType;
import com.habits.perk.Stats;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** SHOW_COORDS: block coordinates and facing, top-left, while the perk is on and the F3 screen is closed. */
@EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT)
public final class CoordsHud {
    private CoordsHud() {}

    @SubscribeEvent
    static void register(RegisterGuiLayersEvent e) {
        e.registerAbove(VanillaGuiLayers.CHAT, Habits.id("stat_coords"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            var p = mc.player;
            if (p == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen() || Stats.sum(p, PerkType.SHOW_COORDS) <= 0) return;
            Component text = Component.translatable("habits.hud.coords", p.getBlockX(), p.getBlockY(), p.getBlockZ(),
                    Component.translatable("habits.hud.facing." + p.getDirection().getName()));
            g.drawString(mc.font, text, 4, 4, 0xFFFFFF, true);
        });
    }
}
