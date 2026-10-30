package com.habits.client.fx;

import com.habits.Habits;
import net.minecraft.sounds.SoundEvent;

/** Client-only sound events (defined in sounds part fx.json, never registered: nothing to sync). */
final class FxSounds {
    static final SoundEvent FLIP = SoundEvent.createVariableRangeEvent(Habits.id("fx.flip"));
    static final SoundEvent REVEAL = SoundEvent.createVariableRangeEvent(Habits.id("fx.reveal"));
    static final SoundEvent REVEAL_BIG = SoundEvent.createVariableRangeEvent(Habits.id("fx.reveal_big"));

    private FxSounds() {}
}
