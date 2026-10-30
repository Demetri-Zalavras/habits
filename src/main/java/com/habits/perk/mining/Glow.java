package com.habits.perk.mining;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Through-wall reveals. The server scans once (bounded, spread over ticks by {@link Scanner}), sends the positions to
 * that player only, and the client draws them for a fixed time. Background reveals rescan only when the player moved
 * a quarter of the radius or 10 seconds passed.
 */
final class Glow {
    private Glow() {}

    static final int OUTLINE = 0, SPARKLE = 1, TRAIL = 2;

    /** kind -> {game time of last scan, packed pos of last scan}. */
    private static final Map<UUID, Map<String, long[]>> LAST = Work.playerMap();

    /** True when a background scan of {@code kind} is due (moved r/4 or 10s passed), and records it. */
    static boolean due(ServerPlayer p, String kind, int r, int minInterval) {
        long now = p.level().getGameTime();
        long[] l = LAST.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).get(kind);
        if (l != null) {
            if (now - l[0] < minInterval) return false;
            double moved = Math.sqrt(BlockPos.of(l[1]).distSqr(p.blockPosition()));
            if (moved < Math.max(2, r / 4.0) && now - l[0] < 200) return false;
        }
        if (Scanner.running(p, kind)) return false;
        LAST.get(p.getUUID()).put(kind, new long[]{now, p.blockPosition().asLong()});
        return true;
    }

    static int oreColor(BlockState s) {
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (id.contains("coal")) return 0x8A8A8A;
        if (id.contains("iron")) return 0xD8AF93;
        if (id.contains("copper")) return 0xE0773B;
        if (id.contains("gold")) return 0xFCEE4B;
        if (id.contains("redstone")) return 0xFF2020;
        if (id.contains("lapis")) return 0x3060FF;
        if (id.contains("diamond")) return 0x4AEDD9;
        if (id.contains("emerald")) return 0x17DD62;
        if (id.contains("quartz")) return 0xEEE6DE;
        if (id.contains("debris")) return 0xB0703C;
        return 0xFFFFFF;
    }

    private static List<Integer> colors(ServerLevel level, List<BlockPos> pos) {
        List<Integer> c = new ArrayList<>(pos.size());
        for (BlockPos b : pos) c.add(oreColor(level.getBlockState(b)));
        return c;
    }

    // ---------------------------------------------------------------- ore pulse (on redstone ore break)

    static void orePulse(ServerPlayer p, HabitsApi.PerkHit h) {
        long now = p.level().getGameTime();
        if (now < p.getPersistentData().getLong("habits_pulse_ready") || Scanner.running(p, "ore_pulse")) return;
        p.getPersistentData().putLong("habits_pulse_ready", now + 40);
        int r = (int) h.value();
        int ticks = (int) (MiningMechanics.lerp(h, "glow_s", 3, 12) * 20);
        ServerLevel level = p.serverLevel();
        Scanner.start(p, "ore_pulse", p.blockPosition(), r, Integer.MIN_VALUE, Integer.MAX_VALUE, s -> s.is(Tags.Blocks.ORES),
                hits -> MiningNet.marks(p, hits, colors(level, hits), ticks, OUTLINE));
    }

    // ---------------------------------------------------------------- background reveals (tick mechanics)

    static void debris(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 20 != 0 || p.level().dimension() != Level.NETHER) return;
        int r = (int) h.value();
        if (!due(p, "debris_diver", r, 60)) return;
        int minY = (int) MiningMechanics.lerp(h, "min_y", 8, 8), maxY = (int) MiningMechanics.lerp(h, "max_y", 22, 22);
        Scanner.start(p, "debris_diver", p.blockPosition(), r, minY, maxY, s -> s.is(Blocks.ANCIENT_DEBRIS),
                hits -> MiningNet.marks(p, hits, colors(p.serverLevel(), hits), 220, OUTLINE));
    }

    static void bugs(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 20 != 0) return;
        int r = (int) h.value();
        if (!due(p, "bug_hunter", r, 60)) return;
        Scanner.start(p, "bug_hunter", p.blockPosition(), r, Integer.MIN_VALUE, Integer.MAX_VALUE,
                s -> s.getBlock() instanceof InfestedBlock,
                hits -> MiningNet.marks(p, hits, hits.stream().map(b -> 0xB0B0B0).toList(), 220, SPARKLE));
    }

    static void dowsing(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 40 != 0) return;
        int r = (int) h.value();
        if (!due(p, "diamond_dowsing", r, 40)) return;
        BlockPos at = p.blockPosition();
        Scanner.start(p, "diamond_dowsing", at, r, Integer.MIN_VALUE, Integer.MAX_VALUE, s -> s.is(BlockTags.DIAMOND_ORES), hits -> {
            hits.stream().min(Comparator.comparingDouble(b -> b.distSqr(at)))
                    .ifPresent(b -> MiningNet.marks(p, List.of(b), List.of(0x4AEDD9), 220, TRAIL));
        });
    }

    /** Treasure sense reads block entity maps of loaded chunks: no block scan at all. */
    static void treasure(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 20 != 0) return;
        int r = (int) h.value();
        if (!due(p, "treasure_sense", r, 60)) return;
        List<BlockPos> hits = treasureNear(p.serverLevel(), p.blockPosition(), r);
        MiningNet.marks(p, hits, hits.stream().map(b -> 0xFFB52E).toList(), 220, OUTLINE);
    }

    static List<BlockPos> treasureNear(ServerLevel level, BlockPos at, int r) {
        List<BlockPos> hits = new ArrayList<>();
        int cr = (r >> 4) + 1;
        for (int cx = (at.getX() >> 4) - cr; cx <= (at.getX() >> 4) + cr; cx++)
            for (int cz = (at.getZ() >> 4) - cr; cz <= (at.getZ() >> 4) + cr; cz++) {
                var chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockState s = be.getBlockState();
                    boolean treasure = be instanceof RandomizableContainerBlockEntity || s.is(Blocks.SPAWNER) || s.is(Blocks.TRIAL_SPAWNER)
                            || s.is(Blocks.CHEST) || s.is(Blocks.BARREL);
                    if (treasure && be.getBlockPos().distSqr(at) <= (long) r * r && hits.size() < Scanner.MAX_HITS) hits.add(be.getBlockPos());
                }
            }
        return hits;
    }
}
