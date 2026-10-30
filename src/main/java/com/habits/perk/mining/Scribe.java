package com.habits.perk.mining;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.ArrayList;
import java.util.List;

/** Scribe: signs you write starting with '#' become waypoints. Placed signs are watched for 5 minutes (the text
 * arrives when the edit screen closes). Waypoints live in the player's saved data and are synced to the HUD. */
final class Scribe {
    private Scribe() {}

    static final String PENDING = "habits_sign_pending", WAYPOINTS = "habits_waypoints";

    static void watch(ServerPlayer p, BlockPos pos) {
        ListTag list = p.getPersistentData().getList(PENDING, Tag.TAG_COMPOUND);
        CompoundTag c = new CompoundTag();
        c.putLong("p", pos.asLong());
        c.putLong("t", p.level().getGameTime() + 6000);
        list.add(c);
        while (list.size() > 16) list.remove(0);
        p.getPersistentData().put(PENDING, list);
    }

    static void tick(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 20 != 0) return;
        CompoundTag data = p.getPersistentData();
        boolean changed = p.tickCount % 2400 == 20; // resync now and then (login, dimension change)
        ListTag pending = data.getList(PENDING, Tag.TAG_COMPOUND);
        ServerLevel level = p.serverLevel();
        String dim = level.dimension().location().toString();
        for (int i = pending.size() - 1; i >= 0; i--) {
            CompoundTag c = pending.getCompound(i);
            BlockPos pos = BlockPos.of(c.getLong("p"));
            if (level.getGameTime() > c.getLong("t") || !level.isLoaded(pos)) { pending.remove(i); continue; }
            if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) { pending.remove(i); continue; }
            String first = sign.getFrontText().getMessage(0, false).getString().trim();
            if (first.isEmpty()) continue;
            pending.remove(i);
            if (first.startsWith("#") && first.length() > 1) {
                add(data, first.substring(1).trim(), pos, dim, (int) h.value());
                changed = true;
            }
        }
        data.put(PENDING, pending);
        if (changed) sync(p);
    }

    static void add(CompoundTag data, String name, BlockPos pos, String dim, int max) {
        ListTag wps = data.getList(WAYPOINTS, Tag.TAG_COMPOUND);
        CompoundTag w = new CompoundTag();
        w.putString("n", name.length() > 32 ? name.substring(0, 32) : name);
        w.putLong("p", pos.asLong());
        w.putString("d", dim);
        wps.add(w);
        while (wps.size() > Math.max(1, max)) wps.remove(0);
        data.put(WAYPOINTS, wps);
    }

    static void sync(ServerPlayer p) {
        ListTag wps = p.getPersistentData().getList(WAYPOINTS, Tag.TAG_COMPOUND);
        List<String> names = new ArrayList<>();
        List<BlockPos> pos = new ArrayList<>();
        List<String> dims = new ArrayList<>();
        for (int i = 0; i < wps.size(); i++) {
            CompoundTag w = wps.getCompound(i);
            names.add(w.getString("n"));
            pos.add(BlockPos.of(w.getLong("p")));
            dims.add(w.getString("d"));
        }
        MiningNet.send(p, new MiningNet.Waypoints(names, pos, dims));
    }
}
