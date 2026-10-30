package com.habits.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * Which events count for a skill, and the drop chance. JSON:
 * <pre>{"type": "damage_dealt", "match": "#minecraft:is_projectile", "item": "minecraft:crossbow", "entity": "#minecraft:skeletons",
 *  "min": 0, "chance": 0.004}</pre>
 * Every matcher is an {@link IdMatch} and optional; an absent matcher accepts anything. All present matchers must pass:
 * {@code match} against {@link TriggerContext#target()} (in the type's {@link TargetKind}), {@code item} against
 * {@link TriggerContext#item()}, {@code entity} against {@link TriggerContext#entity()}; amount must be at least {@code min}.
 *
 * @param chance 0..1 per matching event, or per unit of amount for {@link TriggerType#perUnit} triggers
 */
public record TriggerSpec(TriggerType type, Optional<IdMatch> match, Optional<IdMatch> item, Optional<IdMatch> entity,
                          double min, double chance) {
    public static final Codec<TriggerSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            TriggerType.CODEC.fieldOf("type").forGetter(TriggerSpec::type),
            IdMatch.CODEC.optionalFieldOf("match").forGetter(TriggerSpec::match),
            IdMatch.CODEC.optionalFieldOf("item").forGetter(TriggerSpec::item),
            IdMatch.CODEC.optionalFieldOf("entity").forGetter(TriggerSpec::entity),
            Codec.DOUBLE.optionalFieldOf("min", 0.0).forGetter(TriggerSpec::min),
            Codec.doubleRange(0, 1).fieldOf("chance").forGetter(TriggerSpec::chance)
    ).apply(i, TriggerSpec::new));
}
