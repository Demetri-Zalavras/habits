package com.habits.perk.mining;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-dimension saved positions: warding candles (pos -> radius, indexed by chunk) and blocks placed by skilled
 * players (dispensers, plus the {@link #OWNED_KINDS}: pos -> the placer's skill level). Lives with the world save. */
final class MiningData extends SavedData {
    /** chunk -> (candle pos -> ward radius). */
    final Long2ObjectOpenHashMap<Long2IntOpenHashMap> candles = new Long2ObjectOpenHashMap<>();
    /** Owned block kinds read by the mixin hooks; each maps pos -> the placer's level of the skill named after it. */
    static final String[] OWNED_KINDS = {"hopper_hand", "piston_power", "drain", "beacon_builder"};
    final java.util.Map<String, Long2IntOpenHashMap> owned = new java.util.HashMap<>();
    /** dispenser pos -> owner's dispenser tinkerer level when placing it. */
    final Long2IntOpenHashMap dispensers = new Long2IntOpenHashMap();

    private static final Factory<MiningData> FACTORY = new Factory<>(MiningData::new, MiningData::load);

    /** One HashMap read once the data is cached (hot path for the hopper and piston hooks). */
    static MiningData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "habits_mining");
    }

    {
        for (String k : OWNED_KINDS) owned.put(k, new Long2IntOpenHashMap());
    }

    /** The placer's skill level for an owned block, 0 when not owned. */
    int owner(String kind, BlockPos pos) {
        Long2IntOpenHashMap m = owned.get(kind);
        return m.isEmpty() ? 0 : m.get(pos.asLong());
    }

    /** Record (level > 0) or forget (0) who placed a block. Every placement of the kind calls this, so a stranger's
     * block on an old owned spot clears it. */
    void setOwner(String kind, BlockPos pos, int level) {
        Long2IntOpenHashMap m = owned.get(kind);
        if (level > 0 ? m.put(pos.asLong(), level) != level : m.remove(pos.asLong()) != m.defaultReturnValue()) setDirty();
    }

    void addCandle(BlockPos pos, int radius) {
        candles.computeIfAbsent(ChunkPos.asLong(pos), k -> new Long2IntOpenHashMap()).put(pos.asLong(), radius);
        setDirty();
    }

    void removeCandle(long pos) {
        long chunk = ChunkPos.asLong(BlockPos.of(pos));
        Long2IntOpenHashMap m = candles.get(chunk);
        if (m != null && m.remove(pos) != m.defaultReturnValue()) {
            if (m.isEmpty()) candles.remove(chunk);
            setDirty();
        }
    }

    void setDispenser(BlockPos pos, int level) {
        if (level > 0) dispensers.put(pos.asLong(), level); else dispensers.remove(pos.asLong());
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        candles.values().forEach(m -> m.long2IntEntrySet().forEach(en -> {
            CompoundTag c = new CompoundTag();
            c.putLong("p", en.getLongKey());
            c.putInt("r", en.getIntValue());
            list.add(c);
        }));
        tag.put("candles", list);
        owned.forEach((kind, m) -> {
            ListTag ol = new ListTag();
            m.long2IntEntrySet().forEach(en -> {
                CompoundTag c = new CompoundTag();
                c.putLong("p", en.getLongKey());
                c.putInt("l", en.getIntValue());
                ol.add(c);
            });
            tag.put("owned_" + kind, ol);
        });
        ListTag dl = new ListTag();
        dispensers.long2IntEntrySet().forEach(en -> {
            CompoundTag c = new CompoundTag();
            c.putLong("p", en.getLongKey());
            c.putInt("l", en.getIntValue());
            dl.add(c);
        });
        tag.put("dispensers", dl);
        return tag;
    }

    static MiningData load(CompoundTag tag, HolderLookup.Provider registries) {
        MiningData d = new MiningData();
        for (Tag t : tag.getList("candles", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            d.addCandle(BlockPos.of(c.getLong("p")), c.getInt("r"));
        }
        d.owned.forEach((kind, m) -> {
            for (Tag t : tag.getList("owned_" + kind, Tag.TAG_COMPOUND)) m.put(((CompoundTag) t).getLong("p"), ((CompoundTag) t).getInt("l"));
        });
        for (Tag t : tag.getList("dispensers", Tag.TAG_COMPOUND)) d.dispensers.put(((CompoundTag) t).getLong("p"), ((CompoundTag) t).getInt("l"));
        return d;
    }
}
