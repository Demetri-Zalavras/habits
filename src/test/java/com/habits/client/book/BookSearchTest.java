package com.habits.client.book;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookSearchTest {
    private static final String TEXT = "Vein Miner\nBreaks up to 19 more connected ore\nmining ores\nMining\nRare";

    @Test
    void everyWordMustAppearInAnyOrderAndCase() {
        assertTrue(BookSearch.matches(TEXT, "vein"));
        assertTrue(BookSearch.matches(TEXT, "ORE rare"));
        assertTrue(BookSearch.matches(TEXT, "  connected   miner "));
        assertFalse(BookSearch.matches(TEXT, "ore legendary"));
    }

    @Test
    void partWordsMatch() {
        assertTrue(BookSearch.matches(TEXT, "conn"));
    }

    @Test
    void blankQueryMatchesEverything() {
        assertTrue(BookSearch.matches(TEXT, "   "));
    }
}
