package com.habits.perk.mining;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Doorkeeper: doors you open close behind you; at high level they open as you walk up. Iron doors excluded. Doors a
 * Doorkeeper places are locked to mobs (no villager or piglin opening, no zombie breaking). */
public final class Doors {
    private Doors() {}

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Habits.MODID);
    /** Chunk attachment: lower halves of doors a Doorkeeper placed here ({@link BlockPos#asLong}). */
    static final Supplier<AttachmentType<LongOpenHashSet>> PLACED_DOORS = ATTACHMENTS.register("placed_doors",
            () -> AttachmentType.builder(() -> new LongOpenHashSet())
                    .serialize(Codec.LONG.listOf().xmap(LongOpenHashSet::new, List::copyOf)).build());

    static void init(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }

    private static BlockPos lower(BlockPos pos, BlockState s) {
        return s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    static boolean locked(Level level, BlockPos pos, BlockState s) {
        if (!(s.getBlock() instanceof DoorBlock)) return false;
        LevelChunk c = level.getChunkAt(pos);
        return c.hasData(PLACED_DOORS) && c.getData(PLACED_DOORS).contains(lower(pos, s).asLong());
    }

    private static void mark(Level level, BlockPos lower, boolean placed) {
        LevelChunk c = level.getChunkAt(lower);
        if (placed ? c.getData(PLACED_DOORS).add(lower.asLong())
                : c.hasData(PLACED_DOORS) && c.getData(PLACED_DOORS).remove(lower.asLong()))
            c.setUnsaved(true);
    }

    /** EntityPlaceEvent: a Doorkeeper's door is marked; anyone else's door at that spot is not. */
    static void placed(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s) {
        if (s.getBlock() instanceof DoorBlock) mark(level, lower(pos, s), MiningMechanics.hit(p, "doorkeeper") != null);
    }

    /** Mixin hook (DoorBlock.setOpen, DoorInteractGoal.canUse): a mob may not use a Doorkeeper's door. Server only. */
    public static boolean mobBlocked(@Nullable Entity e, Level level, BlockPos pos) {
        return e != null && !(e instanceof Player) && !level.isClientSide && locked(level, pos, level.getBlockState(pos));
    }

    /** Mixin hook (BlockStateBase.onRemove, server): the door left this spot, forget the mark. One instanceof for others. */
    public static void removed(BlockState old, Level level, BlockPos pos, BlockState now) {
        if (old.getBlock() instanceof DoorBlock && old.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER && !now.is(old.getBlock()))
            mark(level, pos, false);
    }

    /** Doors a player opened: {pos, closeAt}. Same dimension only (cleared on dimension change by the level check). */
    private static final Map<UUID, List<long[]>> OPEN = Work.playerMap();

    static boolean wooden(BlockState s) {
        return (s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.FENCE_GATES))
                && !s.is(Blocks.IRON_DOOR) && !s.is(Blocks.IRON_TRAPDOOR) && s.hasProperty(BlockStateProperties.OPEN);
    }

    /** Right-click on a door: if it is closed now, vanilla is about to open it, so schedule the close. */
    static void used(ServerPlayer p, BlockPos pos, BlockState s) {
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "doorkeeper");
        if (h == null || !wooden(s) || s.getValue(BlockStateProperties.OPEN)) return;
        track(p, pos, h);
    }

    private static void track(ServerPlayer p, BlockPos pos, HabitsApi.PerkHit h) {
        List<long[]> list = OPEN.computeIfAbsent(p.getUUID(), k -> new ArrayList<>());
        list.removeIf(a -> a[0] == pos.asLong());
        if (list.size() < 16) list.add(new long[]{pos.asLong(), p.level().getGameTime() + (long) (h.value() * 20)});
    }

    static void tick(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 4 != 0) return;
        ServerLevel level = p.serverLevel();
        long now = level.getGameTime();
        if (h.level() >= (int) MiningMechanics.lerp(h, "auto_open_level", 8, 8)) {
            Vec3 ahead = p.position().add(p.getLookAngle().multiply(1, 0, 1).normalize().scale(1.2));
            BlockPos front = BlockPos.containing(ahead.x, p.getY() + 0.5, ahead.z);
            BlockState s = level.getBlockState(front);
            if (wooden(s) && !s.getValue(BlockStateProperties.OPEN) && p.getDeltaMovement().horizontalDistanceSqr() > 0.001) {
                setOpen(p, level, front, s, true);
                track(p, front, h);
            }
        }
        List<long[]> list = OPEN.get(p.getUUID());
        if (list == null) return;
        list.removeIf(a -> {
            if (now < a[1]) return false;
            BlockPos pos = BlockPos.of(a[0]);
            if (!level.isLoaded(pos)) return true;
            BlockState s = level.getBlockState(pos);
            if (!wooden(s)) return true;
            // wait while the player still stands in the doorway
            if (p.getBoundingBox().inflate(0.2).intersects(new net.minecraft.world.phys.AABB(pos))) return false;
            if (s.getValue(BlockStateProperties.OPEN)) setOpen(p, level, pos, s, false);
            return true;
        });
    }

    private static void setOpen(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, boolean open) {
        if (s.getBlock() instanceof DoorBlock door) door.setOpen(p, level, s, pos, open);
        else Work.changeAs(p, pos, s.setValue(BlockStateProperties.OPEN, open));
    }
}
