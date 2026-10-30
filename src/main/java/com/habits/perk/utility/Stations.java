package com.habits.perk.utility;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Blocks a player used or placed (furnaces, brewing stands, campfires, crafters), so block perks touch only those few
 * blocks instead of scanning the world. Capped per player, oldest dropped; cleared on logout.
 */
final class Stations {
    private Stations() {}

    static final int CAP = 16;
    private static final Map<UUID, LinkedHashSet<GlobalPos>> USED = U.playerMap();
    private static final Map<UUID, LinkedHashSet<GlobalPos>> PLACED = U.playerMap();

    static void used(Player p, BlockPos pos) {
        add(USED, p, pos);
    }

    static void placed(Player p, BlockPos pos) {
        add(PLACED, p, pos);
    }

    private static void add(Map<UUID, LinkedHashSet<GlobalPos>> map, Player p, BlockPos pos) {
        LinkedHashSet<GlobalPos> set = map.computeIfAbsent(p.getUUID(), k -> new LinkedHashSet<>());
        GlobalPos g = GlobalPos.of(p.level().dimension(), pos.immutable());
        set.remove(g);
        set.add(g);
        while (set.size() > CAP) set.remove(set.iterator().next());
    }

    static boolean hasPlaced(Player p, GlobalPos g) {
        LinkedHashSet<GlobalPos> set = PLACED.get(p.getUUID());
        return set != null && set.contains(g);
    }

    /** Loaded block entities of class {@code type} the player used, in the player's level (never loads chunks). */
    static <T extends BlockEntity> List<T> used(Player p, Class<T> type) {
        return find(USED, p, type);
    }

    static <T extends BlockEntity> List<T> placed(Player p, Class<T> type) {
        return find(PLACED, p, type);
    }

    private static <T extends BlockEntity> List<T> find(Map<UUID, LinkedHashSet<GlobalPos>> map, Player p, Class<T> type) {
        LinkedHashSet<GlobalPos> set = map.get(p.getUUID());
        if (set == null || set.isEmpty()) return List.of();
        List<T> out = new ArrayList<>();
        Level level = p.level();
        for (GlobalPos g : set) {
            T be = at(level, g, type);
            if (be != null) out.add(be);
        }
        return out;
    }

    @Nullable
    private static <T extends BlockEntity> T at(Level level, GlobalPos g, Class<T> type) {
        if (g.dimension() != level.dimension() || !level.isLoaded(g.pos())) return null;
        BlockEntity be = level.getBlockEntity(g.pos());
        return type.isInstance(be) ? type.cast(be) : null;
    }
}
