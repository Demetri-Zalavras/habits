package com.habits.perk.movement;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Movement class passives (docs/mechanics/movement-exploration.md "Class passives"). Freerunner and Stormstrider are
 * player attributes refreshed with the rest every 5 ticks; Outrider buffs the mount in {@link Mounts} and cuts its
 * damage in {@link WorldEvents}. */
final class ClassMoves {
    private ClassMoves() {}

    static void attributes(ServerPlayer p, MoveState s) {
        boolean foot = !p.isPassenger();
        boolean run = s.has(Mech.FREERUNNER) && foot && p.isSprinting();
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.FREERUNNER, run ? s.v(Mech.FREERUNNER) : 0);
        Mv.attrPct(p, Attributes.JUMP_STRENGTH, Mech.FREERUNNER, run ? s.p(Mech.FREERUNNER, "jump") : 0);
        boolean storm = s.has(Mech.STORMSTRIDER) && foot;
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.STORMSTRIDER, storm ? s.v(Mech.STORMSTRIDER) : 0);
        Mv.attrAdd(p, Attributes.STEP_HEIGHT, Mech.STORMSTRIDER, storm ? s.p(Mech.STORMSTRIDER, "step") : 0);
        Mv.attrAdd(p, Attributes.SAFE_FALL_DISTANCE, Mech.STORMSTRIDER, storm ? s.p(Mech.STORMSTRIDER, "safe_fall") : 0);
    }

    static void clear(ServerPlayer p) {
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.FREERUNNER, 0);
        Mv.attrPct(p, Attributes.JUMP_STRENGTH, Mech.FREERUNNER, 0);
        Mv.attrPct(p, Attributes.MOVEMENT_SPEED, Mech.STORMSTRIDER, 0);
        Mv.attrAdd(p, Attributes.STEP_HEIGHT, Mech.STORMSTRIDER, 0);
        Mv.attrAdd(p, Attributes.SAFE_FALL_DISTANCE, Mech.STORMSTRIDER, 0);
    }

    /** Damage multiplier for the Outrider's mount ({@code guard} % less, capped at 90). */
    static double mountGuard(MoveState s) {
        return 1 - Math.min(90, s.p(Mech.OUTRIDER, "guard")) / 100;
    }
}
