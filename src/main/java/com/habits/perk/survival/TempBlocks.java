package com.habits.perk.survival;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Blocks a perk placed for a moment (Lava Walker basalt, Flame Dancer fire). Saved with the level, so a restart or an
 * unloaded chunk never leaves them behind: they revert as soon as their chunk is loaded and their time is up. Breaking one
 * reverts it on the spot with no drops (no free basalt).
 */
public final class TempBlocks extends SavedData {
    record Entry(BlockState original, BlockState placed, long until) {}

    final Map<Long, Entry> blocks = new HashMap<>();

    static TempBlocks get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(TempBlocks::new, (t, reg) -> load(t, reg), null),
                "habits_survival_temp_blocks");
    }

    /** Place {@code state} at {@code pos} for {@code ticks}; false if that spot is already temporary. */
    static boolean place(ServerLevel level, BlockPos pos, BlockState state, long ticks) {
        TempBlocks t = get(level);
        long key = pos.asLong();
        if (t.blocks.containsKey(key)) return false;
        t.blocks.put(key, new Entry(level.getBlockState(pos), state, level.getGameTime() + ticks));
        level.setBlock(pos, state, Block.UPDATE_ALL);
        t.setDirty();
        return true;
    }

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post e) {
        if (e.getLevel() instanceof ServerLevel level && level.getGameTime() % 10 == 0) revertDue(level);
    }

    static void revertDue(ServerLevel level) {
        TempBlocks t = get(level);
        if (t.blocks.isEmpty()) return;
        long now = level.getGameTime();
        for (Iterator<Map.Entry<Long, Entry>> it = t.blocks.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (en.getValue().until > now) continue;
            BlockPos pos = BlockPos.of(en.getKey());
            if (!level.isLoaded(pos)) continue; // never load a chunk for this; revert when it is back
            if (level.getBlockState(pos) == en.getValue().placed) level.setBlock(pos, en.getValue().original, Block.UPDATE_ALL);
            it.remove();
            t.setDirty();
        }
    }

    @SubscribeEvent
    public static void broken(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        TempBlocks t = get(level);
        if (t.blocks.isEmpty()) return;
        Entry en = t.blocks.remove(e.getPos().asLong());
        if (en == null) return;
        e.setCanceled(true);
        level.setBlock(e.getPos(), en.original, Block.UPDATE_ALL);
        t.setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        ListTag list = new ListTag();
        blocks.forEach((pos, en) -> {
            CompoundTag c = new CompoundTag();
            c.putLong("pos", pos);
            c.put("original", NbtUtils.writeBlockState(en.original));
            c.put("placed", NbtUtils.writeBlockState(en.placed));
            c.putLong("until", en.until);
            list.add(c);
        });
        tag.put("blocks", list);
        return tag;
    }

    static TempBlocks load(CompoundTag tag, HolderLookup.Provider reg) {
        TempBlocks t = new TempBlocks();
        var blocks = reg.lookupOrThrow(Registries.BLOCK);
        for (Tag x : tag.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            t.blocks.put(c.getLong("pos"), new Entry(NbtUtils.readBlockState(blocks, c.getCompound("original")),
                    NbtUtils.readBlockState(blocks, c.getCompound("placed")), c.getLong("until")));
        }
        return t;
    }
}
