package com.habits.perk.mining;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.HashMap;
import java.util.Map;

/** Remembers where lava met water and made stone (cobble generators), for Stone Alchemy. Bounded per dimension;
 * not saved, so a restart forgets old generator spots until they generate again. */
public final class Generators {
    private Generators() {}

    static final int CAP = 4096;
    private static final Map<ResourceKey<Level>, LongLinkedOpenHashSet> MADE = new HashMap<>();

    static {
        Work.STOP_CLEANUP.add(MADE::clear);
    }

    @SubscribeEvent
    public static void onFluidMakesBlock(BlockEvent.FluidPlaceBlockEvent e) {
        if (!(e.getLevel() instanceof Level level) || level.isClientSide()) return;
        if (!e.getNewState().is(Blocks.COBBLESTONE) && !e.getNewState().is(Blocks.STONE)) return;
        LongLinkedOpenHashSet set = MADE.computeIfAbsent(level.dimension(), k -> new LongLinkedOpenHashSet());
        set.addAndMoveToLast(e.getPos().asLong());
        if (set.size() > CAP) set.removeFirstLong();
    }

    static boolean isGenerated(Level level, BlockPos pos) {
        LongLinkedOpenHashSet set = MADE.get(level.dimension());
        return set != null && set.contains(pos.asLong());
    }

    /** Test hook. */
    static void mark(Level level, BlockPos pos) {
        MADE.computeIfAbsent(level.dimension(), k -> new LongLinkedOpenHashSet()).add(pos.asLong());
    }
}
