package com.habits.perk.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Sphere block scans spread over ticks: a few 16x16x16 sections per tick, and sections whose palette can't hold a
 * match are skipped without reading a block. Only loaded chunks are read. One scan per player per kind.
 */
public final class Scanner {
    private Scanner() {}

    /** Sections read per server tick, per scan. ~4096 states each, most skipped by the palette check. */
    static final int SECTIONS_PER_TICK = 6;
    /** Matches kept per scan, so a huge vein can't build a giant packet. */
    static final int MAX_HITS = 400;

    private record Job(ServerLevel level, BlockPos center, int r, int minY, int maxY, Predicate<BlockState> match,
                       ArrayDeque<SectionPos> todo, List<BlockPos> hits, Consumer<List<BlockPos>> done) {}

    private static final Map<UUID, Map<String, Job>> JOBS = Work.playerMap();

    static boolean running(ServerPlayer p, String kind) {
        Map<String, Job> m = JOBS.get(p.getUUID());
        return m != null && m.containsKey(kind);
    }

    /** Start (or restart) a scan of radius {@code r} around {@code center}, limited to y in [minY, maxY]. */
    static void start(ServerPlayer p, String kind, BlockPos center, int r, int minY, int maxY, Predicate<BlockState> match,
                      Consumer<List<BlockPos>> done) {
        ServerLevel level = p.serverLevel();
        minY = Math.max(minY, Math.max(level.getMinBuildHeight(), center.getY() - r));
        maxY = Math.min(maxY, Math.min(level.getMaxBuildHeight() - 1, center.getY() + r));
        ArrayDeque<SectionPos> todo = new ArrayDeque<>();
        if (minY <= maxY) {
            for (int sx = SectionPos.blockToSectionCoord(center.getX() - r); sx <= SectionPos.blockToSectionCoord(center.getX() + r); sx++)
                for (int sz = SectionPos.blockToSectionCoord(center.getZ() - r); sz <= SectionPos.blockToSectionCoord(center.getZ() + r); sz++)
                    for (int sy = SectionPos.blockToSectionCoord(minY); sy <= SectionPos.blockToSectionCoord(maxY); sy++)
                        todo.add(SectionPos.of(sx, sy, sz));
        }
        JOBS.computeIfAbsent(p.getUUID(), k -> new HashMap<>())
                .put(kind, new Job(level, center, r, minY, maxY, match, todo, new ArrayList<>(), done));
    }

    /** Finish every scan of this player right now (tests). */
    static void finish(ServerPlayer p) {
        while (JOBS.containsKey(p.getUUID())) step(p.getUUID());
    }

    /** One server tick of scanning for this player (tests time it). */
    static void stepOnce(ServerPlayer p) {
        step(p.getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        for (UUID id : List.copyOf(JOBS.keySet())) step(id);
    }

    private static void step(UUID id) {
        Map<String, Job> m = JOBS.get(id);
        if (m == null) return;
        for (String kind : List.copyOf(m.keySet())) {
            Job j = m.get(kind);
            for (int n = 0; n < SECTIONS_PER_TICK && !j.todo.isEmpty(); n++) scan(j, j.todo.poll());
            if (j.todo.isEmpty() || j.hits.size() >= MAX_HITS) {
                m.remove(kind);
                j.done.accept(j.hits);
            }
        }
        if (m.isEmpty()) JOBS.remove(id);
    }

    private static void scan(Job j, SectionPos sp) {
        LevelChunk chunk = j.level.getChunkSource().getChunkNow(sp.x(), sp.z());
        if (chunk == null) return;
        int idx = chunk.getSectionIndexFromSectionY(sp.y());
        if (idx < 0 || idx >= chunk.getSections().length) return;
        LevelChunkSection sec = chunk.getSection(idx);
        if (sec.hasOnlyAir() || !sec.maybeHas(j.match)) return;
        long r2 = (long) j.r * j.r;
        int bx = sp.minBlockX(), by = sp.minBlockY(), bz = sp.minBlockZ();
        for (int y = 0; y < 16; y++) {
            int wy = by + y;
            if (wy < j.minY || wy > j.maxY) continue;
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    if (!j.match.test(sec.getBlockState(x, y, z))) continue;
                    BlockPos pos = new BlockPos(bx + x, wy, bz + z);
                    if (pos.distSqr(j.center) <= r2 && j.hits.size() < MAX_HITS) j.hits.add(pos);
                }
        }
    }
}
