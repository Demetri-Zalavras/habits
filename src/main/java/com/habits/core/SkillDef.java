package com.habits.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One skill, loaded from {@code data/<ns>/habits_skills/<path>.json}; its id is {@code <ns>:<path>} (the loader fills
 * {@code id} from the file name, so files never contain it). Full schema and examples: CONTRACT.md.
 * <p>Player text: {@code name}, {@code found_by} and {@code perk_text} are optional in JSON. When absent, the lang keys
 * {@code skill.<ns>.<path>} and {@code skill.<ns>.<path>.found_by} are used, and the perk line falls back to
 * {@link PerkSpec#translationKey()} with the value. Always read text through the Component methods below.
 *
 * @param icon     item rendered big in the card art and on the inventory card
 * @param art      block whose texture is tiled behind the art window (default minecraft:stone)
 * @param perkText optional perk line override; {@code {v}} becomes the value ("Mining speed +{v}%")
 */
public record SkillDef(ResourceLocation id, Rarity rarity, Category category, TriggerSpec trigger,
                       ResourceLocation icon, ResourceLocation art, PerkSpec perk,
                       Optional<String> name, Optional<String> foundBy, Optional<String> perkText,
                       Optional<ResourceLocation> classId, boolean classPassive, Optional<TitleSpec> title, List<ForkStep> forks) {

    /** The pre-class/title/fork shape (tests, previews). */
    public SkillDef(ResourceLocation id, Rarity rarity, Category category, TriggerSpec trigger, ResourceLocation icon,
                    ResourceLocation art, PerkSpec perk, Optional<String> name, Optional<String> foundBy, Optional<String> perkText) {
        this(id, rarity, category, trigger, icon, art, perk, name, foundBy, perkText, Optional.empty(), false, Optional.empty(), List.of());
    }

    /** JSON {@code "title"}: earned (at Lv10, never levels) when the condition holds. One of: a vanilla stat
     * ({@code stat_type} + {@code stat}, e.g. minecraft:killed + minecraft:ender_dragon) of at least {@code min}; an
     * {@code advancement} done; total skill levels of at least {@code total_level}; at least {@code maxed} skills at Lv10. */
    public record TitleSpec(Optional<ResourceLocation> statType, Optional<ResourceLocation> stat, long min,
                            Optional<ResourceLocation> advancement, int totalLevel, int maxed) {
        public static final Codec<TitleSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.optionalFieldOf("stat_type").forGetter(TitleSpec::statType),
                ResourceLocation.CODEC.optionalFieldOf("stat").forGetter(TitleSpec::stat),
                Codec.LONG.optionalFieldOf("min", 1L).forGetter(TitleSpec::min),
                ResourceLocation.CODEC.optionalFieldOf("advancement").forGetter(TitleSpec::advancement),
                Codec.INT.optionalFieldOf("total_level", 0).forGetter(TitleSpec::totalLevel),
                Codec.INT.optionalFieldOf("maxed", 0).forGetter(TitleSpec::maxed)
        ).apply(i, TitleSpec::new));
    }

    /** JSON {@code "forks"}: at {@code level} the player picks one option (free to swap in the book; the first until
     * then). An option scales the perk values by {@code scale} and overrides {@code params}. Lang
     * {@code skill.<ns>.<path>.fork.<id>} (name) and {@code .desc}. */
    public record ForkStep(int level, List<ForkOption> options) {
        public static final Codec<ForkStep> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("level").forGetter(ForkStep::level),
                ForkOption.CODEC.listOf().fieldOf("options").forGetter(ForkStep::options)
        ).apply(i, ForkStep::new));
    }

    public record ForkOption(String id, double scale, java.util.Map<String, String> params) {
        public static final Codec<ForkOption> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(ForkOption::id),
                Codec.DOUBLE.optionalFieldOf("scale", 1.0).forGetter(ForkOption::scale),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("params", java.util.Map.of()).forGetter(ForkOption::params)
        ).apply(i, ForkOption::new));
    }

    /** A copy with another perk (sigil/charm/fork boosts), everything else kept. */
    public SkillDef withPerk(PerkSpec p) {
        return new SkillDef(id, rarity, category, trigger, icon, art, p, name, foundBy, perkText, classId, classPassive, title, forks);
    }

    /** Exclusive to a class ({@code "class"}), a class itself ({@code "class_passive"}), or a title: none of these show
     * in the category tabs or roll from the category's actions for everyone. */
    public boolean special() {
        return classId.isPresent() || classPassive || title.isPresent();
    }
    public static final Codec<SkillDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(SkillDef::id),
            Rarity.CODEC.fieldOf("rarity").forGetter(SkillDef::rarity),
            Category.CODEC.fieldOf("category").forGetter(SkillDef::category),
            TriggerSpec.CODEC.fieldOf("trigger").forGetter(SkillDef::trigger),
            ResourceLocation.CODEC.fieldOf("icon").forGetter(SkillDef::icon),
            ResourceLocation.CODEC.optionalFieldOf("art", ResourceLocation.withDefaultNamespace("stone")).forGetter(SkillDef::art),
            PerkSpec.CODEC.fieldOf("perk").forGetter(SkillDef::perk),
            Codec.STRING.optionalFieldOf("name").forGetter(SkillDef::name),
            Codec.STRING.optionalFieldOf("found_by").forGetter(SkillDef::foundBy),
            Codec.STRING.optionalFieldOf("perk_text").forGetter(SkillDef::perkText),
            ResourceLocation.CODEC.optionalFieldOf("class").forGetter(SkillDef::classId),
            Codec.BOOL.optionalFieldOf("class_passive", false).forGetter(SkillDef::classPassive),
            TitleSpec.CODEC.optionalFieldOf("title").forGetter(SkillDef::title),
            ForkStep.CODEC.listOf().optionalFieldOf("forks", List.of()).forGetter(SkillDef::forks)
    ).apply(i, SkillDef::new));

    /** Network form (registry sync payload). */
    public static final StreamCodec<ByteBuf, SkillDef> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

    /** {@code skill.<ns>.<path>} */
    public String translationKey() {
        return "skill." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }

    /** Skill name, plain (renderers add rarity color themselves). */
    public Component displayName() {
        return name.<Component>map(Component::literal).orElseGet(() -> Component.translatable(translationKey()));
    }

    /** What goes after "Found by: " ("mining stone"). */
    public Component foundByText() {
        return foundBy.<Component>map(Component::literal).orElseGet(() -> Component.translatable(translationKey() + ".found_by"));
    }

    /** Perk value at a level (see {@link PerkSpec#valueAt}). */
    public double perkValue(int level) {
        return perk.valueAt(level);
    }

    /** The perk line at {@code level} (level 0 shows the Lv1 value). */
    public Component perkLine(int level) {
        String v = format(perkValue(Math.max(1, level)));
        return perkText.<Component>map(t -> Component.literal(t.replace("{v}", v)))
                .orElseGet(() -> Component.translatable(perkKey(v), v));
    }

    /** The perk lang key, or its {@code .one} form when the value shows as "1" and that key exists ("1 block"). */
    String perkKey(String shownValue) {
        String key = perk.translationKey();
        return "1".equals(shownValue) && Language.getInstance().has(key + ".one") ? key + ".one" : key;
    }

    /** Whole numbers print without ".0", others with one decimal. */
    public static String format(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
