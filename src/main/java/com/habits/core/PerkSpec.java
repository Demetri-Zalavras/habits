package com.habits.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A skill's perk: either a stat-style {@link PerkType} ({@code "type"}) or a custom mechanic ({@code "mechanic"}, an id
 * registered in {@link PerkMechanics}). Exactly one of the two. JSON:
 * <pre>{"type": "mining_speed", "params": {"block": "#minecraft:mineable/pickaxe"}, "base": 4, "per_level": 4}
 *{"mechanic": "habits:homing_arrows", "params": {}, "values": [1, 1.5, 2, 2.5, 3, 3.5, 4, 5, 6, 8]}</pre>
 * {@code values} (exactly 10, Lv1..Lv10) wins over base/per_level. {@code params} is optional, string to string.
 */
public record PerkSpec(Optional<PerkType> type, Optional<ResourceLocation> mechanic, Map<String, String> params,
                       double base, double perLevel, Optional<List<Double>> values) {
    public static final Codec<PerkSpec> CODEC = RecordCodecBuilder.<PerkSpec>create(i -> i.group(
            PerkType.CODEC.optionalFieldOf("type").forGetter(PerkSpec::type),
            ResourceLocation.CODEC.optionalFieldOf("mechanic").forGetter(PerkSpec::mechanic),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("params", Map.of()).forGetter(PerkSpec::params),
            Codec.DOUBLE.optionalFieldOf("base", 0.0).forGetter(PerkSpec::base),
            Codec.DOUBLE.optionalFieldOf("per_level", 0.0).forGetter(PerkSpec::perLevel),
            Codec.DOUBLE.listOf().optionalFieldOf("values").forGetter(PerkSpec::values)
    ).apply(i, PerkSpec::new)).validate(PerkSpec::check);

    private static DataResult<PerkSpec> check(PerkSpec p) {
        if (p.type.isPresent() == p.mechanic.isPresent()) return DataResult.error(() -> "perk needs exactly one of \"type\" or \"mechanic\"");
        if (p.values.isPresent() && p.values.get().size() != Leveling.MAX_LEVEL)
            return DataResult.error(() -> "perk values must have exactly " + Leveling.MAX_LEVEL + " entries");
        return DataResult.success(p);
    }

    public static PerkSpec stat(PerkType type, double base, double perLevel) {
        return new PerkSpec(Optional.of(type), Optional.empty(), Map.of(), base, perLevel, Optional.empty());
    }

    public boolean is(PerkType t) {
        return type.isPresent() && type.get() == t;
    }

    public boolean is(ResourceLocation mechanicId) {
        return mechanic.isPresent() && mechanic.get().equals(mechanicId);
    }

    /** Perk value at {@code level} (1..10); 0 at level 0 or below. Above 10 is clamped to 10. */
    public double valueAt(int level) {
        if (level <= 0) return 0;
        int l = Math.min(level, Leveling.MAX_LEVEL);
        return values.isPresent() ? values.get().get(l - 1) : base + perLevel * (l - 1); // no lambda: runs per perk read
    }

    /** A param, or {@code fallback} when absent. */
    public String param(String key, String fallback) {
        return params.getOrDefault(key, fallback);
    }

    /** Lang key of the default perk line, with one {@code %s} for the value: {@code perk.habits.<type>} or
     * {@code mechanic.<ns>.<path>}. */
    public String translationKey() {
        return type.map(PerkType::translationKey)
                .orElseGet(() -> "mechanic." + mechanic.get().getNamespace() + "." + mechanic.get().getPath().replace('/', '.'));
    }
}
