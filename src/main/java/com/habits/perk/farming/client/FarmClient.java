package com.habits.perk.farming.client;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.perk.farming.FarmHooks;
import com.habits.perk.farming.FarmNet;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Tells the server about sneak + right-click on air with an empty hand, only when a perk that uses it is active. */
@EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT)
public final class FarmClient {
    private FarmClient() {}

    @SubscribeEvent
    public static void onEmpty(PlayerInteractEvent.RightClickEmpty e) {
        var p = e.getEntity();
        if (e.getHand() != InteractionHand.MAIN_HAND || !p.isShiftKeyDown() || !p.getMainHandItem().isEmpty()) return;
        if (HabitsApi.mechanicHit(p, Habits.id("whistle")) == null && HabitsApi.mechanicHit(p, Habits.id("frog_tongue")) == null) return;
        PacketDistributor.sendToServer(FarmNet.EmptyUse.INSTANCE);
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        FarmHooks.clearLookout();
    }
}
