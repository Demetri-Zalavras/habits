package com.habits.perk.mining;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Wireless: bound levers live in the player's saved data ({pos, dim}). Flipping never loads chunks. */
final class Remote {
    private Remote() {}

    static final String KEY = "habits_levers";

    static void bind(ServerPlayer p, BlockPos pos, HabitsApi.PerkHit h) {
        CompoundTag data = p.getPersistentData();
        ListTag list = data.getList(KEY, Tag.TAG_COMPOUND);
        String dim = p.level().dimension().location().toString();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            if (c.getLong("p") == pos.asLong() && c.getString("d").equals(dim)) {
                list.remove(i);
                data.put(KEY, list);
                p.displayClientMessage(Component.translatable("habits.mining.lever_unbound", list.size(), (int) h.value()), true);
                return;
            }
        }
        if (list.size() >= (int) h.value()) list.remove(0);
        CompoundTag c = new CompoundTag();
        c.putLong("p", pos.asLong());
        c.putString("d", dim);
        list.add(c);
        data.put(KEY, list);
        p.displayClientMessage(Component.translatable("habits.mining.lever_bound", list.size(), (int) h.value()), true);
    }

    static void flip(ServerPlayer p) {
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "wireless");
        if (h == null) return;
        double range = MiningMechanics.lerp(h, "range", 16, 512);
        ServerLevel level = p.serverLevel();
        String dim = level.dimension().location().toString();
        ListTag list = p.getPersistentData().getList(KEY, Tag.TAG_COMPOUND);
        int flipped = 0;
        for (int i = 0; i < list.size() && i < (int) h.value(); i++) {
            CompoundTag c = list.getCompound(i);
            BlockPos pos = BlockPos.of(c.getLong("p"));
            if (!c.getString("d").equals(dim) || !level.isLoaded(pos) || pos.distSqr(p.blockPosition()) > range * range) continue;
            BlockState s = level.getBlockState(pos);
            if (s.getBlock() instanceof LeverBlock lever) {
                lever.pull(s, level, pos, p);
                flipped++;
            }
        }
        p.displayClientMessage(Component.translatable(flipped == 1 ? "habits.mining.lever_flipped.one" : "habits.mining.lever_flipped", flipped), true);
    }
}
