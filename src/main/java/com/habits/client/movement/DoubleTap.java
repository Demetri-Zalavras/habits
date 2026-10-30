package com.habits.client.movement;

/** Double-tap detector fed with key-press edges on a tick clock. A hit consumes both taps, so a triple tap fires once. */
final class DoubleTap {
    private final int window;
    private long last = Long.MIN_VALUE / 2;

    DoubleTap(int window) {
        this.window = window;
    }

    /** Call on each press edge; true when it lands within {@code window} ticks of the previous unconsumed press. */
    boolean press(long tick) {
        boolean hit = tick - last <= window;
        last = hit ? Long.MIN_VALUE / 2 : tick;
        return hit;
    }
}
