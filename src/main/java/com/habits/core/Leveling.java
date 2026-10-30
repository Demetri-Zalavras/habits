package com.habits.core;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Pure leveling math. Progress is XP ({@link #XP_PER_CARD} per old card). Design: Lv n -> n+1 needs n+1 cards, cap 10, 55 cards total. Mythical skills use their own,
 * shorter curve (config {@code core.mythicalCardsPerLevel}, default 15 cards total, user 2026-10-13). */
public final class Leveling {
    public static final int MAX_LEVEL = 10;

    /** Mythical cards needed from level i to i+1 (index 0 = the unlock). Sums to 15. */
    public static final List<Integer> MYTHICAL_DEFAULT = List.of(1, 1, 1, 1, 1, 2, 2, 2, 2, 2);
    private static volatile int[] mythical = toArray(MYTHICAL_DEFAULT);

    private Leveling() {}

    /** Config load/reload: a valid list (10 numbers, each 1 or more) replaces the Mythical curve, anything else
     * restores the default. */
    public static void setMythicalCurve(@Nullable List<? extends Number> steps) {
        mythical = toArray(validCurve(steps) ? steps : MYTHICAL_DEFAULT);
    }

    public static boolean validCurve(@Nullable Object o) {
        return o instanceof List<?> l && l.size() == MAX_LEVEL && l.stream().allMatch(e -> e instanceof Number n && n.intValue() >= 1);
    }

    private static int[] toArray(List<? extends Number> l) {
        return l.stream().mapToInt(Number::intValue).toArray();
    }

    /** Cards needed to go from {@code level} to the next on the normal (non-Mythical) curve; 1 to unlock; 0 at max. */
    public static int cardsToNext(int level) {
        return cardsToNext(level, Rarity.COMMON);
    }

    /** Cards needed to go from {@code level} to the next for a skill of this rarity; 0 at max. */
    public static int cardsToNext(int level, Rarity rarity) {
        if (level >= MAX_LEVEL) return 0;
        int l = Math.max(0, level);
        return rarity == Rarity.MYTHICAL ? mythical[l] : l + 1;
    }

    /** Total cards spent to reach {@code level} from nothing on the normal curve (Lv10 = 55). */
    public static int totalCards(int level) {
        return totalCards(level, Rarity.COMMON);
    }

    /** Total cards spent to reach {@code level} from nothing (Lv10: 55, Mythical 15 by default). */
    public static int totalCards(int level, Rarity rarity) {
        int n = 0;
        for (int l = 0; l < Math.min(level, MAX_LEVEL); l++) n += cardsToNext(l, rarity);
        return n;
    }

    /** {@link #addCard(SkillProgress, Rarity)} on the normal curve. */
    @Nullable
    public static SkillProgress addCard(SkillProgress p) {
        return addCard(p, Rarity.COMMON);
    }

    /** XP worth one card (the old unit): {@link SkillProgress#progress} is XP, and a level needs its cards times this. */
    public static final int XP_PER_CARD = 100;

    /** XP needed to go from {@code level} to the next for a skill of this rarity; 0 at max. */
    public static int xpToNext(int level, Rarity rarity) {
        return cardsToNext(level, rarity) * XP_PER_CARD;
    }

    /** Progress after adding one card's worth of XP, or null when already maxed (the card is refused). */
    @Nullable
    public static SkillProgress addCard(SkillProgress p, Rarity rarity) {
        return addXp(p, rarity, XP_PER_CARD);
    }

    /** Progress after gaining {@code xp} (several level-ups when it is a lot; leftover XP is kept, none past Lv10), or
     * null when already maxed or {@code xp} is not positive. */
    @Nullable
    public static SkillProgress addXp(SkillProgress p, Rarity rarity, int xp) {
        if (p.level() >= MAX_LEVEL || xp <= 0) return null;
        int level = p.level(), progress = p.progress() + xp;
        while (level < MAX_LEVEL && progress >= xpToNext(level, rarity)) {
            progress -= xpToNext(level, rarity);
            level++;
        }
        return new SkillProgress(level, level >= MAX_LEVEL ? 0 : progress, p.enabled());
    }
}
