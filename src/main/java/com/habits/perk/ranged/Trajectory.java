package com.habits.perk.ranged;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Arrow flight with vanilla's numbers (move by velocity, then drag 0.99 or 0.6 in water, then gravity 0.05), shared by
 * the Bullseye preview and its GameTest so the preview can't drift from the real arrow.
 */
public final class Trajectory {
    private Trajectory() {}

    public static final double GRAVITY = 0.05, DRAG = 0.99, WATER_DRAG = 0.6;
    public static final int MAX_STEPS = 120;

    public interface Dot {
        void at(Vec3 pos);
    }

    /** Where the flight ended: {@code end} is the impact point (or where the preview length ran out). */
    public record Result(Vec3 end, double length, @Nullable Entity mob, boolean hitBlock) {}

    /** Walk the path from {@code pos} with velocity {@code v} up to {@code maxLen} blocks, calling {@code dot} every {@code spacing} blocks. */
    public static Result simulate(Level level, @Nullable Entity shooter, Vec3 pos, Vec3 v, double maxLen, double spacing, int maxDots, @Nullable Dot dot) {
        double flown = 0, nextDot = spacing;
        int dots = 0;
        for (int i = 0; i < MAX_STEPS && flown < maxLen; i++) {
            Vec3 next = pos.add(v);
            HitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                    shooter == null ? net.minecraft.world.phys.shapes.CollisionContext.empty() : net.minecraft.world.phys.shapes.CollisionContext.of(shooter)));
            boolean block = hit.getType() != HitResult.Type.MISS;
            if (block) next = hit.getLocation();
            Entity mob = firstMob(level, shooter, pos, next);
            double seg = next.distanceTo(pos);
            if (seg > maxLen - flown) { // the preview runs out mid-step
                next = pos.add(next.subtract(pos).scale((maxLen - flown) / seg));
                seg = maxLen - flown;
                block = false;
                mob = null;
            }
            while (dot != null && nextDot <= flown + seg && dots < maxDots) {
                dot.at(pos.add(next.subtract(pos).scale((nextDot - flown) / Math.max(seg, 1e-6))));
                dots++;
                nextDot += spacing;
            }
            flown += seg;
            if (mob != null) return new Result(mob.getBoundingBox().getCenter(), flown, mob, false);
            if (block || flown >= maxLen) return new Result(next, flown, null, block);
            pos = next;
            boolean wet = !level.getFluidState(BlockPos.containing(pos)).isEmpty();
            v = v.scale(wet ? WATER_DRAG : DRAG).subtract(0, GRAVITY, 0);
        }
        return new Result(pos, flown, null, false);
    }

    @Nullable
    private static Entity firstMob(Level level, @Nullable Entity shooter, Vec3 a, Vec3 b) {
        for (Entity e : level.getEntities(shooter, new AABB(a, b).inflate(0.3), x -> x instanceof LivingEntity && x.isAlive() && x.isPickable()))
            if (e.getBoundingBox().inflate(0.3).clip(a, b).isPresent()) return e;
        return null;
    }
}
