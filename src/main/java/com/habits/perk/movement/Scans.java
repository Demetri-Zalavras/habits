package com.habits.perk.movement;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Every 5 s per player (staggered): compass bar markers, through-wall outlines and glows. Only loaded chunks are read
 * ({@code getChunkNow}); nothing here can load or generate a chunk. Results are capped, and a marker list is only sent
 * when it changed.
 */
final class Scans {
    private Scans() {}

    static final Set<ResourceKey<Structure>> MINESHAFTS = Set.of(BuiltinStructures.MINESHAFT, BuiltinStructures.MINESHAFT_MESA);
    static final Set<ResourceKey<Structure>> WRECKS = Set.of(BuiltinStructures.SHIPWRECK, BuiltinStructures.SHIPWRECK_BEACHED,
            BuiltinStructures.OCEAN_RUIN_COLD, BuiltinStructures.OCEAN_RUIN_WARM);
    static final Set<ResourceKey<Structure>> TREASURE = Set.of(BuiltinStructures.BURIED_TREASURE);
    static final Set<ResourceKey<Structure>> MANSION = Set.of(BuiltinStructures.WOODLAND_MANSION);
    static final int MAX_MARKERS = 48, MAX_BLOCKS = 64;

    static void run(ServerPlayer p, MoveState s) {
        markers(p, s);
        highlights(p, s);
        if (s.has(Mech.FOSSIL_FINDER)) {
            List<Integer> ids = new ArrayList<>();
            for (AbstractSkeleton sk : p.level().getEntitiesOfClass(AbstractSkeleton.class, p.getBoundingBox().inflate(s.v(Mech.FOSSIL_FINDER)))) {
                if (ids.size() >= 128) break;
                ids.add(sk.getId());
            }
            if (!ids.isEmpty()) PacketDistributor.sendToPlayer(p, new MovePayloads.Glow(ids, 120));
        }
    }

    // ------------------------------------------------------------------------------------------- compass bar

    static void markers(ServerPlayer p, MoveState s) {
        MovePayloads.Markers m = compose(p, s);
        int hash = 7;
        for (int i = 0; i < m.kinds().length; i++) hash = hash * 31 + m.kinds()[i] * 17 + Long.hashCode(m.xyz()[i]);
        if (hash == 0) hash = 2;
        if (hash != s.markerHash) {
            s.markerHash = hash;
            PacketDistributor.sendToPlayer(p, m);
        }
    }

    /** Every marker this player should see right now (capped). Empty when no marker skill is active. */
    static MovePayloads.Markers compose(ServerPlayer p, MoveState s) {
        boolean path = s.has(Mech.PATHFINDER), home = s.has(Mech.OVERWORLDER);
        boolean riding = s.has(Mech.MOUNTED_EXPLORER) && p.isPassenger();
        boolean treasure = s.has(Mech.X_MARKS_THE_SPOT) && (p.getMainHandItem().is(Items.MAP) || p.getMainHandItem().is(Items.FILLED_MAP)
                || p.getOffhandItem().is(Items.MAP) || p.getOffhandItem().is(Items.FILLED_MAP));
        if (!path && !home && !riding && !treasure && !s.has(Mech.MESA_RUNNER) && !s.has(Mech.OUTPOST_SCOUT) && !s.has(Mech.SHIPWRECKED)
                && !s.has(Mech.SPYGLASS_SCOUT)) return new MovePayloads.Markers(new byte[0], new long[0]);
        ServerLevel level = p.serverLevel();
        List<Byte> kinds = new ArrayList<>();
        List<Long> xyz = new ArrayList<>();
        BlockPos bed = p.getRespawnPosition() != null && p.getRespawnDimension() == level.dimension() ? p.getRespawnPosition() : null;
        var death = p.getLastDeathLocation().filter(g -> g.dimension() == level.dimension()).map(GlobalPos::pos).orElse(null);
        if (path && s.lv(Mech.PATHFINDER) >= s.p(Mech.PATHFINDER, "bed_level") && bed != null) add(kinds, xyz, MovePayloads.M_BED, bed);
        if (path && s.lv(Mech.PATHFINDER) >= s.p(Mech.PATHFINDER, "death_level") && death != null) add(kinds, xyz, MovePayloads.M_DEATH, death);
        if (home) {
            BlockPos h = bed != null ? bed : level.dimension() == Level.OVERWORLD ? level.getSharedSpawnPos() : null;
            if (h != null) add(kinds, xyz, MovePayloads.M_HOME, h);
            if (death != null && s.lv(Mech.OVERWORLDER) >= s.p(Mech.OVERWORLDER, "death_level")) add(kinds, xyz, MovePayloads.M_DEATH, death);
        }
        if (path) for (MoveData.Place w : p.getData(MoveData.TYPE).waypoints)
            if (w.pos().dimension() == level.dimension()) add(kinds, xyz, MovePayloads.M_WAYPOINT, w.pos().pos());
        long now = level.getGameTime();
        s.spyMarks.removeIf(m -> m[1] < now);
        for (long[] m : s.spyMarks) add(kinds, xyz, MovePayloads.M_SPY, BlockPos.of(m[0]));
        if (s.has(Mech.MESA_RUNNER))
            for (BlockPos b : blocks(level, p.blockPosition(), (int) s.v(Mech.MESA_RUNNER), st -> st.is(Tags.Blocks.ORES_GOLD), 8))
                add(kinds, xyz, MovePayloads.M_ORE, b);
        if (riding) {
            int r = (int) s.v(Mech.MOUNTED_EXPLORER);
            // ponytail: ore pulse capped at 32 blocks; the full radius is used for structures, which are cheap to find
            for (BlockPos b : blocks(level, p.blockPosition(), Math.min(32, r), st -> st.is(Tags.Blocks.ORES), 8)) add(kinds, xyz, MovePayloads.M_ORE, b);
            for (BlockPos b : structures(level, p.blockPosition(), r, null)) add(kinds, xyz, MovePayloads.M_STRUCTURE, b);
        }
        if (s.has(Mech.SHIPWRECKED))
            for (BlockPos b : structures(level, p.blockPosition(), (int) s.v(Mech.SHIPWRECKED), WRECKS)) add(kinds, xyz, MovePayloads.M_WRECK, b);
        if (treasure)
            for (BlockPos b : structures(level, p.blockPosition(), (int) s.v(Mech.X_MARKS_THE_SPOT), TREASURE)) add(kinds, xyz, MovePayloads.M_TREASURE, b);
        if (s.has(Mech.OUTPOST_SCOUT)) {
            for (Raider r : level.getEntitiesOfClass(Raider.class, p.getBoundingBox().inflate(s.v(Mech.OUTPOST_SCOUT), 64, s.v(Mech.OUTPOST_SCOUT)),
                    r -> r.isAlive() && !r.hasActiveRaid())) {
                if (kinds.size() >= MAX_MARKERS) break;
                add(kinds, xyz, MovePayloads.M_RAIDER, r.blockPosition());
            }
        }
        byte[] k = new byte[Math.min(kinds.size(), MAX_MARKERS)];
        long[] x = new long[k.length];
        for (int i = 0; i < k.length; i++) {
            k[i] = kinds.get(i);
            x[i] = xyz.get(i);
        }
        return new MovePayloads.Markers(k, x);
    }

    static void add(List<Byte> kinds, List<Long> xyz, byte kind, BlockPos pos) {
        if (kinds.size() >= MAX_MARKERS) return;
        kinds.add(kind);
        xyz.add(pos.asLong());
    }

    // ------------------------------------------------------------------------------------------- outlines

    static void highlights(ServerPlayer p, MoveState s) {
        ServerLevel level = p.serverLevel();
        BlockPos at = p.blockPosition();
        if (s.has(Mech.PEARL_DIVER) && p.isInWater())
            sendBlocks(p, MovePayloads.H_LOOT, blockEntities(level, at, (int) s.v(Mech.PEARL_DIVER),
                    be -> be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof BrushableBlockEntity));
        if (s.has(Mech.RUIN_READER))
            sendBlocks(p, MovePayloads.H_SUSPICIOUS, blockEntities(level, at, (int) s.v(Mech.RUIN_READER), be -> be instanceof BrushableBlockEntity));
        if (s.has(Mech.TOMB_RAIDER))
            sendBlocks(p, MovePayloads.H_TRAP, blocks(level, at, (int) s.v(Mech.TOMB_RAIDER), Scans::trap, MAX_BLOCKS));
        if (s.has(Mech.MANSION_RAIDER)) sendBlocks(p, MovePayloads.H_SECRET, secretRooms(level, at, (int) s.v(Mech.MANSION_RAIDER)));
    }

    static boolean trap(BlockState b) {
        return b.is(Blocks.TRIPWIRE) || b.is(Blocks.TRIPWIRE_HOOK) || b.is(BlockTags.PRESSURE_PLATES) || b.is(Blocks.TNT);
    }

    static void sendBlocks(ServerPlayer p, byte kind, List<BlockPos> blocks) {
        if (!blocks.isEmpty()) PacketDistributor.sendToPlayer(p, new MovePayloads.Highlights(kind, blocks, 120));
    }

    // ------------------------------------------------------------------------------------------- loaded-chunk readers

    /** Matching blocks within r, skipping whole sections whose palette can't contain a match. Loaded chunks only. */
    static List<BlockPos> blocks(ServerLevel level, BlockPos c, int r, Predicate<BlockState> match, int cap) {
        List<BlockPos> out = new ArrayList<>();
        int minY = Math.max(level.getMinBuildHeight(), c.getY() - r), maxY = Math.min(level.getMaxBuildHeight() - 1, c.getY() + r);
        long r2 = (long) r * r;
        for (int cx = (c.getX() - r) >> 4; cx <= (c.getX() + r) >> 4; cx++)
            for (int cz = (c.getZ() - r) >> 4; cz <= (c.getZ() + r) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (int sy = SectionPos.blockToSectionCoord(minY); sy <= SectionPos.blockToSectionCoord(maxY); sy++) {
                    LevelChunkSection sec = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                    if (sec.hasOnlyAir() || !sec.getStates().maybeHas(match)) continue;
                    for (int i = 0; i < 4096; i++) {
                        int lx = i & 15, lz = (i >> 4) & 15, ly = i >> 8;
                        BlockState st = sec.getBlockState(lx, ly, lz);
                        if (!match.test(st)) continue;
                        int x = (cx << 4) + lx, y = (sy << 4) + ly, z = (cz << 4) + lz;
                        long dx = x - c.getX(), dy = y - c.getY(), dz = z - c.getZ();
                        if (dx * dx + dy * dy + dz * dz > r2) continue;
                        out.add(new BlockPos(x, y, z));
                        if (out.size() >= cap) return out;
                    }
                }
            }
        return out;
    }

    static List<BlockPos> blockEntities(ServerLevel level, BlockPos c, int r, Predicate<BlockEntity> match) {
        List<BlockPos> out = new ArrayList<>();
        long r2 = (long) r * r;
        for (int cx = (c.getX() - r) >> 4; cx <= (c.getX() + r) >> 4; cx++)
            for (int cz = (c.getZ() - r) >> 4; cz <= (c.getZ() + r) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                    if (e.getKey().distSqr(c) > r2 || !match.test(e.getValue())) continue;
                    out.add(e.getKey());
                    if (out.size() >= MAX_BLOCKS) return out;
                }
            }
        return out;
    }

    /** Structure starts (centre of their box) in loaded chunks within r; {@code only} null = any structure. */
    static List<BlockPos> structures(ServerLevel level, BlockPos c, int r, Set<ResourceKey<Structure>> only) {
        List<BlockPos> out = new ArrayList<>();
        var reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        int cr = Math.min(r >> 4, level.getServer().getPlayerList().getViewDistance() + 1);
        int pcx = c.getX() >> 4, pcz = c.getZ() >> 4;
        long r2 = (long) r * r;
        for (int cx = pcx - cr; cx <= pcx + cr; cx++)
            for (int cz = pcz - cr; cz <= pcz + cr; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (Map.Entry<Structure, StructureStart> e : chunk.getAllStarts().entrySet()) {
                    if (!e.getValue().isValid()) continue;
                    if (only != null && reg.getResourceKey(e.getKey()).filter(only::contains).isEmpty()) continue;
                    BlockPos centre = e.getValue().getBoundingBox().getCenter();
                    if (centre.distSqr(c) > r2) continue;
                    out.add(centre);
                    if (out.size() >= 16) return out;
                }
            }
        return out;
    }

    /** True when {@code pos} is inside a piece of one of these structures. Reads only loaded chunks. */
    static boolean insideAny(ServerLevel level, BlockPos pos, Set<ResourceKey<Structure>> keys) {
        LevelChunk here = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (here == null) return false;
        var reg = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (ResourceKey<Structure> key : keys) {
            Structure st = reg.get(key);
            if (st == null) continue;
            LongSet refs = here.getReferencesForStructure(st);
            for (long ref : refs) {
                LevelChunk owner = level.getChunkSource().getChunkNow(net.minecraft.world.level.ChunkPos.getX(ref), net.minecraft.world.level.ChunkPos.getZ(ref));
                if (owner == null) continue;
                StructureStart start = owner.getStartForStructure(st);
                if (start == null || !start.isValid() || !start.getBoundingBox().isInside(pos)) continue;
                for (StructurePiece piece : start.getPieces()) if (piece.getBoundingBox().isInside(pos)) return true;
            }
        }
        return false;
    }

    /** Mansion secret rooms (the "1x1_as", "1x2_s*" and "2x2_s*" templates) as min/max corner pairs. */
    static List<BlockPos> secretRooms(ServerLevel level, BlockPos c, int r) {
        List<BlockPos> out = new ArrayList<>();
        Structure mansion = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(BuiltinStructures.WOODLAND_MANSION);
        if (mansion == null) return out;
        LevelChunk here = level.getChunkSource().getChunkNow(c.getX() >> 4, c.getZ() >> 4);
        if (here == null) return out;
        for (long ref : here.getReferencesForStructure(mansion)) {
            LevelChunk owner = level.getChunkSource().getChunkNow(net.minecraft.world.level.ChunkPos.getX(ref), net.minecraft.world.level.ChunkPos.getZ(ref));
            if (owner == null) continue;
            StructureStart start = owner.getStartForStructure(mansion);
            if (start == null || !start.isValid()) continue;
            for (StructurePiece piece : start.getPieces()) {
                if (!(piece instanceof TemplateStructurePiece tp) || !secret(tp.templateName)) continue;
                BoundingBox bb = piece.getBoundingBox();
                if (bb.getCenter().distSqr(c) > (long) r * r) continue;
                out.add(new BlockPos(bb.minX(), bb.minY(), bb.minZ()));
                out.add(new BlockPos(bb.maxX(), bb.maxY(), bb.maxZ()));
                if (out.size() >= MAX_BLOCKS) return out;
            }
        }
        return out;
    }

    static boolean secret(String template) {
        int slash = template.lastIndexOf('/');
        String n = template.substring(slash + 1);
        return n.startsWith("1x1_as") || n.startsWith("1x2_s") || n.startsWith("2x2_s");
    }
}
