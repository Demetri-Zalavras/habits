package com.habits.perk;

import com.habits.core.HabitsApi;
import com.habits.core.HabitsApi.PerkHit;
import com.habits.core.IdMatch;
import com.habits.core.PerkType;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Shared reads for the stat {@link PerkType}s: sum the values of active skills whose condition holds and whose filter
 * params accept the event. */
public final class Stats {
    private Stats() {}

    private static final Map<String, Optional<IdMatch>> PARSED = new ConcurrentHashMap<>();

    /** Sum of every active skill of {@code type} whose condition holds and that passes {@code filter}. */
    public static double sum(Player p, PerkType type, Predicate<PerkHit> filter) {
        double v = 0;
        for (PerkHit h : HabitsApi.perkValues(p, type)) {
            if (Conditions.test(p, h.skill().perk().params().get("condition")) && filter.test(h)) v += h.value();
        }
        return v;
    }

    public static double sum(Player p, PerkType type) {
        return sum(p, type, h -> true);
    }

    /** True if the hit has no {@code key} param, or its IdMatch accepts {@code id} (tags from {@code registry}). */
    public static <T> boolean accepts(PerkHit h, String key, @Nullable ResourceLocation id, ResourceKey<? extends Registry<T>> registry, Player p) {
        String s = h.skill().perk().params().get(key);
        if (s == null) return true;
        Optional<IdMatch> m = PARSED.computeIfAbsent(s, k -> Optional.ofNullable(IdMatch.parse(k)));
        return m.isPresent() && m.get().test(id, registry, p.level().registryAccess());
    }

    public static boolean has(PerkHit h, String key) {
        return h.skill().perk().params().containsKey(key);
    }

    /** {@code x} rounded up or down at random so the expected value is exact (2.3 -> 3 with 30%). */
    public static int roundRandom(double x, RandomSource r) {
        int f = (int) Math.floor(x);
        return f + (r.nextDouble() < x - f ? 1 : 0);
    }
}
