package com.habits.perk.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Blocks players placed that keep working without them: scarecrow pumpkins, ancient-gardener flowers, death-bloom roses.
 * Chunk-keyed so a lookup touches only the few chunks in range; entries whose block is gone are dropped lazily.
 */
final class FarmSaved extends SavedData {
    static final byte SCARECROW = 0, GARDEN = 1, ROSE = 2, SPRINKLER = 3, RICH = 4;

    /** a/b are per-kind numbers frozen at placement (radius, boost, effect strength...). */
    record Entry(BlockPos pos, byte kind, float a, float b) {}

    final Map<Long, List<Entry>> byChunk = new HashMap<>();
    int size;

    static FarmSaved get(ServerLevel l) {
        return l.getDataStorage().computeIfAbsent(new Factory<>(FarmSaved::new, FarmSaved::load), "habits_farming");
    }

    void add(BlockPos pos, byte kind, float a, float b) {
        remove(pos);
        byChunk.computeIfAbsent(ChunkPos.asLong(pos), k -> new ArrayList<>()).add(new Entry(pos.immutable(), kind, a, b));
        size++;
        setDirty();
    }

    void remove(BlockPos pos) {
        List<Entry> list = byChunk.get(ChunkPos.asLong(pos));
        if (list != null && list.removeIf(e -> e.pos.equals(pos))) {
            size = Math.max(0, size - 1);
            if (list.isEmpty()) byChunk.remove(ChunkPos.asLong(pos));
            setDirty();
        }
    }

    /** Every entry of {@code kind} in chunks within {@code range} blocks of pos (caller checks the real distance). */
    void near(BlockPos pos, int range, byte kind, Consumer<Entry> out) {
        if (size == 0) return;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4, r = (range >> 4) + 1;
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++) {
                List<Entry> list = byChunk.get(ChunkPos.asLong(x, z));
                if (list != null) for (Entry e : List.copyOf(list)) if (e.kind == kind) out.accept(e);
            }
    }

    void forEach(byte kind, Consumer<Entry> out) {
        if (size == 0) return;
        for (List<Entry> list : List.copyOf(byChunk.values())) for (Entry e : List.copyOf(list)) if (e.kind == kind) out.accept(e);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        ListTag list = new ListTag();
        for (List<Entry> l : byChunk.values())
            for (Entry e : l) {
                CompoundTag t = new CompoundTag();
                t.putLong("p", e.pos.asLong());
                t.putByte("k", e.kind);
                t.putFloat("a", e.a);
                t.putFloat("b", e.b);
                list.add(t);
            }
        tag.put("e", list);
        return tag;
    }

    static FarmSaved load(CompoundTag tag, HolderLookup.Provider reg) {
        FarmSaved s = new FarmSaved();
        for (Tag t : tag.getList("e", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            s.add(BlockPos.of(c.getLong("p")), c.getByte("k"), c.getFloat("a"), c.getFloat("b"));
        }
        return s;
    }
}
