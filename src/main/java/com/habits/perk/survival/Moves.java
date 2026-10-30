package com.habits.perk.survival;

import com.habits.core.HabitsApi;
import com.habits.perk.survival.client.SurvivalClient;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Movement perks. Player movement is decided by the client, so these run on the local player. Cobweb, berry bush and
 * powder snow movement are mixins (see SurvivalHooks), which run on both sides.
 */
final class Moves {
    private Moves() {}

    /** Local player only. */
    static void client(Player p) {
        Vec3 v = p.getDeltaMovement();
        HabitsApi.PerkHit h;
        boolean jump = SurvivalClient.jumpDown();
        if (p.hasEffect(MobEffects.LEVITATION) && (h = Sk.BULLET_SURFER.hit(p)) != null) {
            double steer = Sk.p(h, "steer");
            if (p.isShiftKeyDown()) p.setDeltaMovement(v.x, v.y - steer, v.z);
            else if (jump) p.setDeltaMovement(v.x, v.y + steer * 0.5, v.z);
            v = p.getDeltaMovement();
        }
        if (p.hasEffect(MobEffects.SLOW_FALLING) && !p.onGround() && v.y < 0 && !p.isFallFlying() && !p.isInWater()
                && (h = Sk.FEATHER_DRIFT.hit(p)) != null) {
            Vec3 look = p.getLookAngle();
            Vec3 flat = new Vec3(look.x, 0, look.z).normalize().scale(h.value());
            p.setDeltaMovement(v.x * 0.9 + flat.x * 0.1, v.y, v.z * 0.9 + flat.z * 0.1);
            v = p.getDeltaMovement();
        }
        if ((h = Sk.SPRING_HEELS.hit(p)) != null) SurvivalClient.doubleJump(p, h.value(), p.hasEffect(MobEffects.JUMP), jump);
        if (p.isInLava() && p.hasEffect(MobEffects.FIRE_RESISTANCE) && (h = Sk.SALAMANDER.hit(p)) != null) {
            // lava keeps half the speed each tick; this multiplier makes the steady speed up to twice vanilla
            double k = 1 + Sk.p(h, "swim") / 300;
            p.setDeltaMovement(v.x * k, v.y, v.z * k);
        }
        double clear = Sk.CLEAR_HEAD.v(p);
        if (clear > 0) SurvivalClient.calmNausea(p, clear);
    }
}
