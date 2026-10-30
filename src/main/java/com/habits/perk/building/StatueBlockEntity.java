package com.habits.perk.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Statue state: owner (null = inert), chosen blessing, and the owner's boost tier as of their last visit. */
public class StatueBlockEntity extends BlockEntity {
    @Nullable UUID owner;
    int blessing;
    boolean boosted;

    public StatueBlockEntity(BlockPos pos, BlockState state) {
        super(Statue.BE.get(), pos, state);
    }

    Statue.Blessing blessing() {
        return Statue.Blessing.values()[Math.floorMod(blessing, Statue.Blessing.values().length)];
    }

    void cycle() {
        blessing = (blessing().ordinal() + 1) % Statue.Blessing.values().length;
        setChanged();
    }

    void setBoosted(boolean b) {
        if (b == boosted) return;
        boosted = b;
        setChanged();
    }

    static void serverTick(Level level, BlockPos pos, BlockState state, StatueBlockEntity be) {
        if (level instanceof ServerLevel l && l.getGameTime() % Statue.INTERVAL.getAsInt() == 0) Statue.pulse(l, pos, be, l.players());
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.saveAdditional(tag, reg);
        if (owner != null) tag.putUUID("owner", owner);
        tag.putInt("blessing", blessing);
        tag.putBoolean("boosted", boosted);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider reg) {
        super.loadAdditional(tag, reg);
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        blessing = tag.getInt("blessing");
        boosted = tag.getBoolean("boosted");
    }
}
