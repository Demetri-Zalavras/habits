package com.habits.perk.building.client;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;

/** Quick Hands: a shorter right-click repeat delay while placing blocks (client only; the delay is the client's). */
public final class QuickHands {
    private QuickHands() {}

    /** The delay to use after a right-click, or {@code current} when the perk does not apply. */
    public static int delay(Minecraft mc, int current) {
        var p = mc.player;
        if (p == null || p.isSpectator() || !(p.getMainHandItem().getItem() instanceof BlockItem)) return current;
        HabitsApi.PerkHit h = HabitsApi.mechanicHit(p, Habits.id("building/quick_hands"));
        if (h == null) return current;
        double v = Math.max(0, h.value());
        int d = (int) v + (p.getRandom().nextDouble() < v - (int) v ? 1 : 0); // 2.5 ticks: 2 or 3 at even odds
        return Math.min(current, Math.max(1, d));
    }
}
