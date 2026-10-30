package com.habits.perk.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Portal Restorer: lighting fire inside a broken nether portal frame shows its gaps and, when few enough, fills them
 * with obsidian (crying obsidian can't hold a portal, so it is swapped too). Capped per use and on a cooldown so it is
 * not an obsidian farm.
 */
final class Portals {
    private Portals() {}

    static final int MAX = 23;

    static boolean restore(ServerPlayer p, MoveState s, BlockPos fire) {
        ServerLevel level = p.serverLevel();
        if (!level.getBlockState(fire).isAir()) return false;
        for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            List<BlockPos> gaps = gaps(level, fire, axis);
            if (gaps == null || gaps.isEmpty()) continue;
            int cap = (int) s.v(Mech.PORTAL_RESTORER);
            MoveData d = p.getData(MoveData.TYPE);
            if (cap > 0 && gaps.size() <= cap && level.getGameTime() >= d.restoreReadyAt) {
                for (BlockPos g : gaps) level.setBlockAndUpdate(g, Blocks.OBSIDIAN.defaultBlockState());
                d.restoreReadyAt = level.getGameTime() + Math.round(s.p(Mech.PORTAL_RESTORER, "cooldown") * 60 * 20);
                return true;
            }
            PacketDistributor.sendToPlayer(p, new MovePayloads.Highlights(MovePayloads.H_PORTAL_GAP, gaps, 200));
            Mv.say(p, gaps.size() == 1 ? "habits.movement.portal_gaps.one" : "habits.movement.portal_gaps", gaps.size());
            return false;
        }
        return false;
    }

    static boolean frame(BlockState b) {
        return b.is(Blocks.OBSIDIAN) || b.is(Blocks.CRYING_OBSIDIAN);
    }

    /** Positions in the frame around {@code fire} that need obsidian, or null when this is not a portal frame. */
    static List<BlockPos> gaps(ServerLevel level, BlockPos fire, Direction.Axis axis) {
        int ux = axis == Direction.Axis.X ? 1 : 0, uz = 1 - ux;
        int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, minV = Integer.MAX_VALUE, maxV = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int u = -11; u <= 11; u++)
            for (int v = -11; v <= 11; v++) {
                m.set(fire.getX() + u * ux, fire.getY() + v, fire.getZ() + u * uz);
                if (!frame(level.getBlockState(m))) continue;
                minU = Math.min(minU, u); maxU = Math.max(maxU, u); minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            }
        if (minU == Integer.MAX_VALUE || minU >= 0 || maxU <= 0 || minV >= 0 || maxV <= 0) return null;
        int w = maxU - minU + 1, h = maxV - minV + 1;
        if (w < 4 || h < 5 || w > MAX || h > MAX) return null;
        List<BlockPos> gaps = new ArrayList<>();
        for (int u = minU; u <= maxU; u++)
            for (int v = minV; v <= maxV; v++) {
                boolean edgeU = u == minU || u == maxU, edgeV = v == minV || v == maxV;
                BlockPos at = new BlockPos(fire.getX() + u * ux, fire.getY() + v, fire.getZ() + u * uz);
                BlockState b = level.getBlockState(at);
                if (edgeU && edgeV) continue; // corners don't matter
                if (edgeU || edgeV) {
                    if (b.is(Blocks.OBSIDIAN)) continue;
                    if (b.isAir() || b.is(Blocks.CRYING_OBSIDIAN) || b.canBeReplaced()) gaps.add(at);
                    else return null;
                } else if (!b.isAir() && !b.is(Blocks.FIRE)) return null; // inside must be open
            }
        return gaps;
    }
}
