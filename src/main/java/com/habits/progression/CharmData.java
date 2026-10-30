package com.habits.progression;

import com.habits.core.Category;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The {@code habits:charm} component, and what an equipped charm slot holds: a category, a rarity and 1..4 rolled
 * lines. Line values are whole percents for PERKS and DROPS, and stat points for STAT (see {@link #statAmount}). */
public record CharmData(Category category, Rarity rarity, List<Line> lines) {
    public enum LineKind implements StringRepresentable {
        /** That category's perks +%. */
        PERKS,
        /** That category's card drop rate +%. */
        DROPS,
        /** The category's vanilla stat (at most one per charm). */
        STAT;

        public static final Codec<LineKind> CODEC = StringRepresentable.fromEnum(LineKind::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Line(LineKind kind, double value) {
        public static final Codec<Line> CODEC = RecordCodecBuilder.create(i -> i.group(
                LineKind.CODEC.fieldOf("kind").forGetter(Line::kind),
                Codec.DOUBLE.fieldOf("value").forGetter(Line::value)
        ).apply(i, Line::new));
        static final StreamCodec<ByteBuf, Line> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.map(i -> LineKind.values()[Math.floorMod(i, 3)], Enum::ordinal), Line::kind,
                ByteBufCodecs.DOUBLE, Line::value, Line::new);
    }

    public static final Codec<CharmData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Category.CODEC.fieldOf("category").forGetter(CharmData::category),
            Rarity.CODEC.fieldOf("rarity").forGetter(CharmData::rarity),
            Line.CODEC.listOf().fieldOf("lines").forGetter(CharmData::lines)
    ).apply(i, CharmData::new));

    public static final StreamCodec<ByteBuf, CharmData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(i -> Category.values()[Math.floorMod(i, Category.values().length)], Enum::ordinal), CharmData::category,
            SigilData.RARITY_STREAM, CharmData::rarity,
            Line.STREAM_CODEC.apply(ByteBufCodecs.list()), CharmData::lines,
            CharmData::new);

    public CharmData {
        lines = List.copyOf(lines);
    }

    /** A fresh charm: line count and value ranges from config by rarity. */
    public static CharmData roll(Category category, Rarity rarity, RandomSource r) {
        int n = Math.max(1, (int) ProgressionInit.at(ProgressionInit.CHARM_LINES, rarity));
        List<Line> lines = new ArrayList<>(n);
        // distinct kinds first (perks, drops, stat in random order); a 4th line repeats perks or drops
        List<LineKind> order = new ArrayList<>(List.of(LineKind.values()));
        for (int i = order.size() - 1; i > 0; i--) order.set(i, order.set(r.nextInt(i + 1), order.get(i)));
        for (int i = 0; i < n; i++) lines.add(lineOf(i < order.size() ? order.get(i) : LineKind.values()[r.nextInt(2)], rarity, r));
        return new CharmData(category, rarity, lines);
    }

    /** A new line for slot {@code skip} (or a new one when -1): any kind, but never a second STAT line. */
    static Line rollLine(Rarity rarity, List<Line> others, int skip, RandomSource r) {
        boolean hasStat = false;
        for (int i = 0; i < others.size(); i++) if (i != skip && others.get(i).kind == LineKind.STAT) hasStat = true;
        return lineOf(LineKind.values()[r.nextInt(hasStat ? 2 : 3)], rarity, r);
    }

    static Line lineOf(LineKind kind, Rarity rarity, RandomSource r) {
        var min = switch (kind) { case PERKS -> ProgressionInit.CHARM_PERK_MIN; case DROPS -> ProgressionInit.CHARM_DROP_MIN; case STAT -> ProgressionInit.CHARM_STAT_MIN; };
        var max = switch (kind) { case PERKS -> ProgressionInit.CHARM_PERK_MAX; case DROPS -> ProgressionInit.CHARM_DROP_MAX; case STAT -> ProgressionInit.CHARM_STAT_MAX; };
        double lo = ProgressionInit.at(min, rarity), hi = Math.max(lo, ProgressionInit.at(max, rarity));
        return new Line(kind, Math.round(lo + r.nextDouble() * (hi - lo)));
    }

    /** This charm with line {@code index} rolled again; the other lines stay the same. */
    public CharmData reroll(int index, RandomSource r) {
        List<Line> next = new ArrayList<>(lines);
        next.set(index, rollLine(rarity, lines, index, r));
        return new CharmData(category, rarity, next);
    }

    /** Summed percent of one kind of line. */
    public double sum(LineKind kind) {
        double s = 0;
        for (Line l : lines) if (l.kind == kind) s += l.value;
        return s;
    }

    // ------------------------------------------------------------------ vanilla stat per category

    /** The vanilla stat a category's STAT line raises. */
    public static Holder<Attribute> statAttribute(Category c) {
        return switch (c) {
            case WARRIOR -> Attributes.ATTACK_DAMAGE;
            case RANGER, MOVEMENT -> Attributes.MOVEMENT_SPEED;
            case GUARDIAN -> Attributes.MAX_HEALTH;
            case MINING -> Attributes.BLOCK_BREAK_SPEED;
            case FARMING, NATURE -> Attributes.LUCK;
            case CRAFTING, BUILDING -> Attributes.BLOCK_INTERACTION_RANGE;
            case EXPLORATION -> Attributes.MOVEMENT_EFFICIENCY;
        };
    }

    /** Percent stats scale the player's base value; the others add flat points. */
    public static AttributeModifier.Operation statOperation(Category c) {
        return switch (c) {
            case WARRIOR, RANGER, MINING, MOVEMENT -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            default -> AttributeModifier.Operation.ADD_VALUE;
        };
    }

    /** Modifier amount of a STAT line value: value times the category's config scale. */
    public static double statAmount(Category c, double value) {
        return value * ProgressionInit.statScale(c);
    }

    // ------------------------------------------------------------------ text

    public Component name() {
        return Component.translatable("item.habits.charm.named", rarity.displayName(), category.displayName())
                .withStyle(s -> s.withColor(rarity.color).withItalic(false));
    }

    /** One plain tooltip line. */
    public net.minecraft.network.chat.MutableComponent lineText(Line l) {
        return switch (l.kind) {
            case PERKS -> Component.translatable("charm.habits.line.perks", category.displayName(), SkillDef.format(l.value));
            case DROPS -> Component.translatable("charm.habits.line.drops", category.displayName(), SkillDef.format(l.value));
            case STAT -> {
                double amount = statAmount(category, l.value);
                Component stat = Component.translatable(statAttribute(category).value().getDescriptionId());
                yield statOperation(category) == AttributeModifier.Operation.ADD_MULTIPLIED_BASE
                        ? Component.translatable("charm.habits.line.stat_percent", stat, SkillDef.format(Math.round(amount * 1000) / 10.0))
                        : Component.translatable("charm.habits.line.stat_flat", stat, SkillDef.format(Math.round(amount * 100) / 100.0));
            }
        };
    }
}
