package com.habits.perk;

import com.habits.core.PerkType;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/** Entry points called from the mixins in {@code perk.mixin} (kept out of the mixin package, which may hold mixins only). */
public final class StatHooks {
    private StatHooks() {}

    /** HUNGER_SAVE: exhaustion after the discount. */
    public static float exhaustion(Player p, float amount) {
        if (amount <= 0) return amount;
        double v = Stats.sum(p, PerkType.HUNGER_SAVE);
        return v == 0 ? amount : (float) (amount * (1 - Math.min(v, PerkInit.HUNGER_SAVE_CAP.get()) / 100));
    }

    /** DURABILITY_SAVE: each point of wear is skipped with the perk chance. */
    public static int durability(ItemStack stack, int amount, LivingEntity user) {
        if (amount <= 0 || !(user instanceof Player p)) return amount;
        double v = Stats.sum(p, PerkType.DURABILITY_SAVE, h -> Stats.accepts(h, "item", StatEvents.itemId(stack), Registries.ITEM, p));
        if (v <= 0) return amount;
        int kept = 0;
        for (int i = 0; i < amount; i++) if (p.getRandom().nextDouble() * 100 >= v) kept++;
        return kept;
    }

    /** ANVIL_DISCOUNT: level cost after the discount (deterministic, so client and server agree). */
    public static int anvilCost(Player p, int cost) {
        if (cost <= 1) return cost;
        double v = Math.min(100, Stats.sum(p, PerkType.ANVIL_DISCOUNT));
        return v <= 0 ? cost : Math.max(1, cost - (int) Math.round(cost * v / 100));
    }

    /** TRADE_DISCOUNT: lowers each offer's first cost, on top of reputation and Hero of the Village. */
    public static void tradeDiscount(MerchantOffers offers, Player p) {
        double v = Math.min(100, Stats.sum(p, PerkType.TRADE_DISCOUNT));
        if (v <= 0) return;
        for (MerchantOffer o : offers) {
            int off = (int) Math.floor(o.getBaseCostA().getCount() * v / 100);
            if (off > 0) o.addToSpecialPriceDiff(-off);
        }
    }

    /** LOOTING_BONUS: extra Looting levels wherever the game asks for the player's Looting level (mob loot). */
    public static int looting(Holder<Enchantment> enchantment, LivingEntity entity, int level) {
        if (!(entity instanceof Player p) || !enchantment.is(Enchantments.LOOTING)) return level;
        return level + (int) Math.round(Stats.sum(p, PerkType.LOOTING_BONUS));
    }
}
