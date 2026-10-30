package com.habits.perk.utility;

import com.habits.core.HabitsApi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.math.Fraction;
import org.jetbrains.annotations.Nullable;

/**
 * Entry points for the mixins in {@code perk.utility.mixin} (a mixin package may hold mixins only). Both sides: the
 * collection is synced to its own player, so client and server compute the same anvil cap and bundle size.
 */
public final class UtilityHooks {
    private UtilityHooks() {}

    /** Vanilla's "Too Expensive!" limit. */
    public static final int VANILLA_CAP = 40;

    @Nullable
    private static HabitsApi.PerkHit hit(@Nullable Player p, net.minecraft.resources.ResourceLocation mech) {
        return p == null || !p.isAlive() || p.isSpectator() ? null : HabitsApi.mechanicHit(p, mech);
    }

    // ------------------------------------------------------------------ lucky_charm (HabitsApi.onTrigger)

    /** The drop-rate multiplier core rolls every candidate skill with, raised by lucky_charm. */
    public static double dropRate(ServerPlayer p, double mult) {
        HabitsApi.PerkHit h = hit(p, Odd.LUCKY);
        return h == null ? mult : mult * (1 + h.value() / 100);
    }

    // ------------------------------------------------------------------ penny_pincher (AnvilMenu, AnvilScreen)

    /** Level cost after the discount, before the "Too Expensive!" check. */
    public static int anvilCost(Player p, int cost) {
        HabitsApi.PerkHit h = cost > 1 ? hit(p, Tables.PENNY) : null;
        return h == null ? cost : Math.max(1, (int) Math.round(cost * (1 - h.value() / 100)));
    }

    /** "Too Expensive!" limit for this player: penny_pincher raises it, overforge (Lv10) and penny_pincher (Lv10) lift it.
     * A stack in the left slot keeps vanilla's 40 (vanilla blocks enchanting a whole stack that way, and a raised cap
     * would let 64 items take one book). */
    public static int anvilCap(Player p, ItemStack left) {
        if (left.getCount() > 1) return VANILLA_CAP;
        HabitsApi.PerkHit o = hit(p, Tables.OVERFORGE);
        if (o != null && o.level() >= U.param(o, "no_cap_from", 10, 10)) return Integer.MAX_VALUE;
        HabitsApi.PerkHit h = hit(p, Tables.PENNY);
        if (h == null) return VANILLA_CAP;
        if (h.level() >= U.param(h, "no_cap_from", 10, 10)) return Integer.MAX_VALUE;
        return (int) U.param(h, "cap", VANILLA_CAP, VANILLA_CAP);
    }

    // ------------------------------------------------------------------ client reads (perk.utility.client)

    /** bookworm, client: the open table's full seed from the server ({@link UtilityNet.TableSeed}). */
    public static volatile int tableSeed;

    /** bookworm: how many enchants each offer's tooltip lists (0 without the skill, so vanilla's single hint). */
    public static int bookwormHints(@Nullable Player p) {
        HabitsApi.PerkHit h = hit(p, Tables.BOOKWORM);
        if (h == null) return 0;
        return h.level() >= U.param(h, "all_hints_from", 3, 3) ? Integer.MAX_VALUE : (int) U.param(h, "hints", 2, 2);
    }

    /** rummager: chest slots light up when they hold something you carry (every tier). */
    public static boolean mendingTouch(@Nullable Player p) {
        return hit(p, Xp.MENDING_TOUCH) != null;
    }

    public static boolean rummager(@Nullable Player p) {
        return hit(p, Inventory.RUMMAGER) != null;
    }

    // ------------------------------------------------------------------ bundle_up (BundleItem, BundleContents.Mutable)

    /** Capacity for the bundle click in progress on this thread (client and integrated server run on two threads). */
    private static final ThreadLocal<Fraction> BUNDLE_CAP = new ThreadLocal<>();

    public static void bundleBegin(Player p) {
        HabitsApi.PerkHit h = hit(p, Inventory.BUNDLE);
        BUNDLE_CAP.set(h == null || h.value() <= 1 ? null : Fraction.getFraction(h.value()));
    }

    public static void bundleEnd() {
        BUNDLE_CAP.remove();
    }

    /** Replaces the {@code Fraction.ONE} a bundle measures its free room against. */
    public static Fraction bundleCap(Fraction one) {
        Fraction f = BUNDLE_CAP.get();
        return f == null ? one : f;
    }
}
