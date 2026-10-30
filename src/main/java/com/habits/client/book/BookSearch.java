package com.habits.client.book;

import java.util.Locale;

/** Book search matching: every word of the query must appear somewhere in the card's text (any order, any case). */
final class BookSearch {
    private BookSearch() {}

    static final int MAX_LEN = 32;

    static boolean matches(String text, String query) {
        String hay = text.toLowerCase(Locale.ROOT);
        for (String w : query.toLowerCase(Locale.ROOT).trim().split("\s+"))
            if (!w.isEmpty() && !hay.contains(w)) return false;
        return true;
    }
}
