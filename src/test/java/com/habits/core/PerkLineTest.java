package com.habits.core;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SkillDef.perkLine picks the {@code .one} lang key when the shown value is exactly "1" and that key exists. */
class PerkLineTest {
    private final Language before = Language.getInstance();

    @AfterEach
    void restore() {
        Language.inject(before);
    }

    private static void lang(Map<String, String> keys) {
        Language.inject(new Language() {
            @Override public String getOrDefault(String key, String fallback) { return keys.getOrDefault(key, fallback); }
            @Override public boolean has(String key) { return keys.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
        });
    }

    private static SkillDef skill(List<Double> values) {
        PerkSpec perk = new PerkSpec(Optional.empty(), Optional.of(ResourceLocation.fromNamespaceAndPath("habits", "air_place")),
                Map.of(), 0, 0, Optional.of(values));
        TriggerSpec t = new TriggerSpec(TriggerType.CUSTOM, Optional.empty(), Optional.empty(), Optional.empty(), 0, 0);
        ResourceLocation stone = ResourceLocation.withDefaultNamespace("stone");
        return new SkillDef(ResourceLocation.fromNamespaceAndPath("habits", "air_place"), Rarity.COMMON, Category.BUILDING, t,
                stone, stone, perk, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static String key(SkillDef d, int level) {
        return ((TranslatableContents) d.perkLine(level).getContents()).getKey();
    }

    @Test
    void singularOnlyForOneAndOnlyWhenTheKeyExists() {
        SkillDef d = skill(List.of(1.0, 1.5, 2.0, 2.0, 3.0, 3.0, 4.0, 4.0, 5.0, 6.0));
        lang(Map.of("mechanic.habits.air_place", "%s blocks", "mechanic.habits.air_place.one", "%s block"));
        assertEquals("mechanic.habits.air_place.one", key(d, 1), "1 -> singular");
        assertEquals("mechanic.habits.air_place.one", key(d, 0), "unowned shows Lv1's value");
        assertEquals("mechanic.habits.air_place", key(d, 2), "1.5 -> plural");
        assertEquals("mechanic.habits.air_place", key(d, 10), "6 -> plural");
        lang(Map.of("mechanic.habits.air_place", "%s blocks"));
        assertEquals("mechanic.habits.air_place", key(d, 1), "no .one key -> plural");
    }
}
