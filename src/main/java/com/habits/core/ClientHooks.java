package com.habits.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Core -> client packages, without core importing client code. BOOK-UI and FX each implement this (only the methods
 * they care about) and call {@link #register} from their client init. Core calls {@link #each} on the client thread.
 * This file is common-safe: no client classes.
 */
public interface ClientHooks {
    /** A card was just added to the collection by right-click. FX plays the HUD flip; queue, never block. */
    default void cardAdded(Payloads.CardAdded e) {}

    /** A won card added itself (auto-add; fields as in {@link #cardAdded}). FX shows a feed row, never a flip. */
    default void cardGained(Payloads.CardAdded e) {}

    /** A sigil gain for the feed ({@link HabitsApi#feedSigil}). */
    default void sigilGained(Payloads.FeedSigil e) {}

    /** XP earned for a skill (batched). */
    default void xpGained(Payloads.XpGained e) {}

    /** The book was right-clicked. BOOK-UI opens the binder screen. */
    default void openBinder() {}

    /** A Wild Card was right-clicked: open the skill picker (core's own client hook, registered in HabitsClient). */
    default void openWildCard() {}

    /** The server wants a specialization pick (first join, or a Scroll of Unlearning was read). */
    default void openSpecPick() {}

    /** A class book was right-clicked: open its confirm screen. */
    default void openClassBook(Payloads.OpenClassBook p) {}

    /** Skill definitions were (re)synced from the server; drop any per-skill caches. */
    default void skillsReloaded() {}

    List<ClientHooks> ALL = new CopyOnWriteArrayList<>();

    static void register(ClientHooks hooks) {
        ALL.add(hooks);
    }

    static void each(Consumer<ClientHooks> call) {
        ALL.forEach(call);
    }
}
