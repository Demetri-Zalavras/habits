package com.habits.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Who holds each Legendary class (one per world, Overgeared style). Saved with the overworld. Other rarities are not
 * tracked: any number of players can hold them. */
public final class ClassHolders extends SavedData {
    private final Map<ResourceLocation, UUID> holders = new HashMap<>();

    private static final Factory<ClassHolders> FACTORY = new Factory<>(ClassHolders::new, ClassHolders::load, null);

    static ClassHolders get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "habits_class_holders");
    }

    private static ClassHolders load(CompoundTag tag, HolderLookup.Provider lookup) {
        ClassHolders h = new ClassHolders();
        for (String k : tag.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(k);
            if (id != null && tag.hasUUID(k)) h.holders.put(id, tag.getUUID(k));
        }
        return h;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        holders.forEach((id, uuid) -> tag.putUUID(id.toString(), uuid));
        return tag;
    }

    /** True when {@code clazz} is free (or not Legendary, or already this player's); a Legendary is then taken. */
    static boolean claim(ServerPlayer p, SkillDef clazz) {
        if (clazz.rarity() != Rarity.LEGENDARY) return true;
        ClassHolders h = get(p.server);
        UUID owner = h.holders.get(clazz.id());
        if (owner != null && !owner.equals(p.getUUID())) return false;
        h.holders.put(clazz.id(), p.getUUID());
        h.setDirty();
        return true;
    }

    /** A Legendary {@code clazz} another player holds. */
    static boolean takenByOther(ServerPlayer p, SkillDef clazz) {
        UUID owner = clazz.rarity() == Rarity.LEGENDARY ? get(p.server).holders.get(clazz.id()) : null;
        return owner != null && !owner.equals(p.getUUID());
    }

    /** The player leaves {@code clazz}: a Legendary held by them goes back to the world. */
    static void release(ServerPlayer p, ResourceLocation clazz) {
        ClassHolders h = get(p.server);
        if (p.getUUID().equals(h.holders.get(clazz))) {
            h.holders.remove(clazz);
            h.setDirty();
        }
    }

    /** A Legendary class someone already holds (no more of its books roll). */
    static boolean taken(MinecraftServer server, ResourceLocation clazz) {
        return get(server).holders.containsKey(clazz);
    }
}
