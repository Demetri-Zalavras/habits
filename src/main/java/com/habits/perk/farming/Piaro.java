package com.habits.perk.farming;

import com.habits.core.Category;
import com.habits.core.Specs;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HoeItem;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Piaro's Way (Overgeared's legendary farmer turned sword saint): a player who picked Farming counts every ripe crop
 * they harvest, by hand or through skills, and hoes hit harder with that lifetime count, +1 damage per piaro_per harvests up to piaro_cap (FarmCfg).
 * The count lives in the player's persisted NBT, so it survives death.
 */
final class Piaro {
    private Piaro() {}

    static final String KEY = "habits_piaro_harvest";

    static boolean farmer(Player p) {
        return Specs.of(p).picks().contains(Category.FARMING);
    }

    static CompoundTag store(Player p) {
        CompoundTag root = p.getPersistentData();
        if (!root.contains(Player.PERSISTED_NBT_TAG)) root.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }

    static int harvested(Player p) {
        return store(p).getInt(KEY);
    }

    static double bonus(int harvested) {
        return Math.min(FarmCfg.PIARO_CAP.get(), harvested / (double) FarmCfg.PIARO_PER.get());
    }

    /** Counts one harvest; every full piaro_per tells the player the new bonus. */
    static void count(Player p) {
        CompoundTag t = store(p);
        int n = t.getInt(KEY) + 1;
        t.putInt(KEY, n);
        int per = FarmCfg.PIARO_PER.get();
        if (n % per == 0 && n / per <= FarmCfg.PIARO_CAP.get())
            p.displayClientMessage(Component.translatable("habits.farming.piaro", n, String.format("%.0f", bonus(n)))
                    .withStyle(st -> st.withColor(0x8BC34A)), true);
    }

    /** Every harvest trigger counts: hand breaks, area perks (Combine, Scythe...), right-click replant, berries. */
    static void init() {
        Triggers.SPIES.add((p, type, ctx) -> {
            if (type == TriggerType.HARVEST_CROP && !p.isCreative() && farmer(p)) count(p);
        });
    }

    @SubscribeEvent(priority = EventPriority.HIGH) // flat damage before the % perks scale it
    static void onHurt(LivingIncomingDamageEvent e) {
        if (!(e.getSource().getEntity() instanceof Player a) || e.getSource().getDirectEntity() != a || a.level().isClientSide) return;
        if (!(a.getMainHandItem().getItem() instanceof HoeItem) || !farmer(a)) return;
        double b = bonus(harvested(a));
        if (b > 0) e.setAmount(e.getAmount() + (float) b);
    }
}
