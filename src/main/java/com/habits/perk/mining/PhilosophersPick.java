package com.habits.perk.mining;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Philosopher's Pick (Mythical): ores you mine can drop the next ore up; the card drops for breaking all 8 overworld
 * ores in one trip underground ({@code habits:ore_rainbow}). */
final class PhilosophersPick {
    private PhilosophersPick() {}

    /** The 8 ore kinds, in transmute order (gold goes to lapis or redstone, both go to diamond). */
    private static final List<TagKey<Block>> KINDS = List.of(Tags.Blocks.ORES_COAL, Tags.Blocks.ORES_COPPER, Tags.Blocks.ORES_IRON,
            Tags.Blocks.ORES_GOLD, Tags.Blocks.ORES_LAPIS, Tags.Blocks.ORES_REDSTONE, Tags.Blocks.ORES_DIAMOND, Tags.Blocks.ORES_EMERALD);
    private static final int ALL = (1 << KINDS.size()) - 1;

    /** Ore kinds broken this trip, as a bitmask over {@link #KINDS}. Cleared when the player sees the sky. */
    static final Map<UUID, Integer> RAINBOW = Work.playerMap();

    private static int kind(BlockState s) {
        for (int i = 0; i < KINDS.size(); i++) if (s.is(KINDS.get(i))) return i;
        return -1;
    }

    /** Nothing above the eyes up to the top of the world. Heightmap, not sky light: light updates lag behind block changes. */
    static boolean underSky(ServerPlayer p) {
        BlockPos eye = BlockPos.containing(p.getEyePosition());
        return eye.getY() >= p.level().getHeight(Heightmap.Types.WORLD_SURFACE, eye.getX(), eye.getZ());
    }

    /** From BreakPerks' custom moments, for every ore a player breaks. */
    static void oreBroken(ServerPlayer p, BlockPos pos, BlockState s) {
        int k = kind(s);
        if (k < 0) return;
        if (underSky(p)) { RAINBOW.remove(p.getUUID()); return; }
        int mask = RAINBOW.getOrDefault(p.getUUID(), 0) | 1 << k;
        if (mask != ALL) { RAINBOW.put(p.getUUID(), mask); return; }
        RAINBOW.remove(p.getUUID());
        BreakPerks.fire(p, "ore_rainbow", pos);
    }

    @SubscribeEvent
    public static void onTick(PlayerTickEvent.Post e) {
        if (e.getEntity() instanceof ServerPlayer p && p.tickCount % 10 == 0 && RAINBOW.containsKey(p.getUUID()) && underSky(p))
            RAINBOW.remove(p.getUUID());
    }

    /** The next ore up, or null at the top (emerald) or for other ores. */
    @Nullable
    static Block next(BlockState s, RandomSource r) {
        return switch (kind(s)) {
            case 0 -> Blocks.COPPER_ORE;
            case 1 -> Blocks.IRON_ORE;
            case 2 -> Blocks.GOLD_ORE;
            case 3 -> r.nextBoolean() ? Blocks.LAPIS_ORE : Blocks.REDSTONE_ORE;
            case 4, 5 -> Blocks.DIAMOND_ORE;
            case 6 -> Blocks.EMERALD_ORE;
            default -> null;
        };
    }

    /** DropPerks: the drops after a transmute roll (and the netherite scrap roll on diamond ore), or null if unchanged. */
    @Nullable
    static List<ItemStack> drops(ServerPlayer p, HabitsApi.PerkHit h, ServerLevel level, BlockPos pos, BlockState s, ItemStack tool, List<ItemStack> drops) {
        RandomSource r = p.getRandom();
        List<ItemStack> out = null;
        Block up = next(s, r);
        if (up != null && r.nextDouble() * 100 < h.value())
            out = new ArrayList<>(Block.getDrops(up.defaultBlockState(), level, pos, null, p, tool.isEmpty() ? new ItemStack(Items.WOODEN_PICKAXE) : tool));
        if (s.is(Tags.Blocks.ORES_DIAMOND) && h.level() >= MiningMechanics.lerp(h, "scrap_from", 10, 10)
                && r.nextDouble() * 100 < MiningMechanics.lerp(h, "scrap_chance", 2, 2)) {
            if (out == null) out = new ArrayList<>(drops);
            out.add(new ItemStack(Items.NETHERITE_SCRAP));
        }
        return out;
    }
}
