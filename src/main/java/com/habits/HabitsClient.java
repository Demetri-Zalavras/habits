package com.habits;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import com.habits.core.Payloads;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-only entry. Owner: core. Calls the client package inits; nobody else edits this file. */
@Mod(value = Habits.MODID, dist = Dist.CLIENT)
public final class HabitsClient {
    public HabitsClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        com.habits.client.book.BookClientInit.init(modBus);
        com.habits.client.fx.FxClientInit.init(modBus);
        com.habits.core.ClientHooks.register(new com.habits.core.ClientHooks() {
            @Override public void openWildCard() { net.minecraft.client.Minecraft.getInstance().setScreen(new com.habits.client.WildCardScreen()); }
            @Override public void openClassBook(Payloads.OpenClassBook p) { net.minecraft.client.Minecraft.getInstance().setScreen(new com.habits.client.ClassBookScreen(p)); }
            @Override public void openSpecPick() { com.habits.client.SpecScreen.request(); }
        });
        NeoForge.EVENT_BUS.addListener(com.habits.client.SpecScreen::onTick);
        // titles: nameplates read the synced shown title; display names are cached, so refresh them now and then
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> {
            var level = net.minecraft.client.Minecraft.getInstance().level;
            if (level != null && level.getGameTime() % 20 == 0) level.players().forEach(net.minecraft.world.entity.player.Player::refreshDisplayName);
        });
        // vanilla never tells the server about a swing at nothing; core rate-limits and fires CUSTOM habits:swing_air
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.LeftClickEmpty e) -> PacketDistributor.sendToServer(Payloads.SwingAir.INSTANCE));
    }
}
