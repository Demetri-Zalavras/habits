package com.habits.client.book;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** The big number + unit the card pulls out of a perk line template. */
class CardReadoutTest {
    @Test
    void percentWithSignTakesTheWordsBefore() {
        assertArrayEquals(new String[]{"+12%", "Mining speed"}, CardFace.valueAndUnit("Mining speed +%s%%", "12"));
    }

    @Test
    void unitIsTheFirstNounAfterTheNumber() {
        assertArrayEquals(new String[]{"3", "blocks"}, CardFace.valueAndUnit("Bend arrows within %s blocks", "3"));
        assertArrayEquals(new String[]{"19", "ore"}, CardFace.valueAndUnit("Breaks up to %s more connected ore", "19"));
        assertArrayEquals(new String[]{"24", "logs"}, CardFace.valueAndUnit("Fells up to %s logs. Sneak to chop one", "24"));
        assertArrayEquals(new String[]{"47%", "chance"}, CardFace.valueAndUnit("%s%% chance kills drop gold", "47"));
    }

    @Test
    void multiplierKeepsItsX() {
        assertArrayEquals(new String[]{"3x", "damage"}, CardFace.valueAndUnit("Backstabs deal %sx damage", "3"));
        assertArrayEquals(new String[]{"2", "xp"}, CardFace.valueAndUnit("Gain %s xp", "2"));
    }

    @Test
    void noPlaceholderShowsTheValueAlone() {
        assertArrayEquals(new String[]{"5", ""}, CardFace.valueAndUnit("Shows your coordinates", "5"));
    }
}
