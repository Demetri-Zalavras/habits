package com.habits.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelingTest {
    @Test
    void cardsToMax() {
        SkillProgress p = SkillProgress.NONE;
        int cards = 0;
        while (Leveling.addCard(p) != null) { p = Leveling.addCard(p); cards++; }
        assertEquals(10, p.level());
        assertEquals(55, cards);
        assertEquals(55, Leveling.totalCards(10));
        assertNull(Leveling.addCard(p), "maxed card refused");
    }

    @Test
    void mythicalReachesLv10WithFifteenCards() {
        SkillProgress p = SkillProgress.NONE;
        int cards = 0;
        for (SkillProgress next; (next = Leveling.addCard(p, Rarity.MYTHICAL)) != null; p = next) cards++;
        assertEquals(10, p.level());
        assertEquals(15, cards);
        assertEquals(15, Leveling.totalCards(10, Rarity.MYTHICAL));
        assertEquals(55, Leveling.totalCards(10, Rarity.LEGENDARY), "other rarities keep 55");
        assertEquals(1, Leveling.cardsToNext(0, Rarity.MYTHICAL), "one card unlocks");
        assertEquals(2, Leveling.cardsToNext(9, Rarity.MYTHICAL));
        assertEquals(0, Leveling.cardsToNext(10, Rarity.MYTHICAL), "maxed");
        for (int l = 0; l < 10; l++) assertTrue(Leveling.cardsToNext(l, Rarity.MYTHICAL) <= Leveling.cardsToNext(l), "never slower, Lv" + l);
    }

    @Test
    void mythicalCurveFromConfigAndBadListsFallBack() {
        try {
            Leveling.setMythicalCurve(List.of(1, 1, 1, 1, 1, 1, 1, 1, 1, 1));
            assertEquals(10, Leveling.totalCards(10, Rarity.MYTHICAL));
            Leveling.setMythicalCurve(List.of(1, 2, 3)); // wrong size
            assertEquals(15, Leveling.totalCards(10, Rarity.MYTHICAL));
            Leveling.setMythicalCurve(List.of(1, 1, 1, 1, 1, 0, 2, 2, 2, 2)); // a free level
            assertEquals(15, Leveling.totalCards(10, Rarity.MYTHICAL));
        } finally {
            Leveling.setMythicalCurve(Leveling.MYTHICAL_DEFAULT);
        }
    }

    @Test
    void xpCarriesOverSeveralLevels() {
        SkillProgress p = Leveling.addXp(new SkillProgress(1, 0, true), Rarity.COMMON, 550); // 200 + 300 to Lv3, 50 left
        assertEquals(new SkillProgress(3, 50, true), p);
        assertEquals(new SkillProgress(10, 0, true), Leveling.addXp(p, Rarity.COMMON, 1_000_000), "capped at Lv10");
        assertNull(Leveling.addXp(p, Rarity.COMMON, 0), "nothing gained");
    }

    @Test
    void firstCardsUnlockThenNeedTwo() {
        SkillProgress p = Leveling.addCard(SkillProgress.NONE);
        assertEquals(new SkillProgress(1, 0, true), p);
        assertEquals(2 * Leveling.XP_PER_CARD, p.needed(Rarity.LEGENDARY));
        assertEquals(new SkillProgress(1, Leveling.XP_PER_CARD, true), Leveling.addCard(p));
        assertEquals(new SkillProgress(2, 0, true), Leveling.addCard(Leveling.addCard(p)));
    }

    @Test
    void perkValues() {
        PerkSpec linear = PerkSpec.stat(PerkType.MINING_SPEED, 6, 6);
        assertEquals(0, linear.valueAt(0));
        assertEquals(6, linear.valueAt(1));
        assertEquals(60, linear.valueAt(10));
        assertEquals(60, linear.valueAt(12), "clamped");
        PerkSpec explicit = new PerkSpec(Optional.of(PerkType.REACH), Optional.empty(), Map.of(), 0, 0, Optional.of(List.of(1., 2., 3., 4., 5., 6., 7., 8., 9., 99.)));
        assertEquals(99, explicit.valueAt(10));
    }
}
