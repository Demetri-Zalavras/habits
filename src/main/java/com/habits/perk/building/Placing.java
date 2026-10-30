package com.habits.perk.building;

import com.habits.core.HabitsApi;
import com.habits.perk.Later;
import com.habits.perk.mining.Work;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WoolCarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Placing: refunds, fills, lines, mirror, supply line, and the building custom events. */
public final class Placing {
    private Placing() {}

    /** Recent placements per player: {gameTime, blockId}, oldest first (streak and palette events, flow state). */
    static final Map<UUID, ArrayDeque<long[]>> RECENT = Bld.playerMap();
    /** Dismantle: positions this player placed, oldest first. */
    static final Map<UUID, LinkedHashSet<Long>> OWN = Bld.playerMap();
    static final Map<UUID, int[]> WAGE = Bld.playerMap();
    /** Game time of the last build_streak and palette event per player. */
    static final Map<UUID, long[]> SINCE = Bld.playerMap();
    /** Symmetry plane: dimension, axis (0 = x, 1 = z), coordinate of the center block. */
    record Plane(ResourceKey<Level> dim, int axis, int c) {}
    static final Map<UUID, Plane> PLANES = Bld.playerMap();

    static final String SUPPLY = "habits_supply", SUPPLY_DIM = "habits_supply_dim";

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (Work.busy || e.isCanceled() || !(e.getEntity() instanceof ServerPlayer p) || !Bld.allowed(p)) return;
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        BlockState s = e.getPlacedBlock();
        events(p, level, pos, s);

        HabitsApi.PerkHit h;
        if ((h = Bld.hit(p, "dismantle")) != null) {
            LinkedHashSet<Long> own = OWN.computeIfAbsent(p.getUUID(), k -> new LinkedHashSet<>());
            own.remove(pos.asLong());
            own.add(pos.asLong());
            while (own.size() > (int) h.value()) own.remove(own.iterator().next());
        }
        if ((h = Bld.hit(p, "builders_wage")) != null) {
            int[] n = WAGE.computeIfAbsent(p.getUUID(), k -> new int[1]);
            if (++n[0] >= (int) Bld.lerp(h, "per", 64, 64)) {
                n[0] = 0;
                p.giveExperiencePoints((int) Math.round(h.value()));
            }
        }
        if ((h = Bld.hit(p, "flow_state")) != null) {
            long now = level.getGameTime(), window = (long) (Bld.lerp(h, "window_s", 30, 30) * 20);
            int count = 0;
            for (long[] r : RECENT.getOrDefault(p.getUUID(), new ArrayDeque<>())) if (now - r[0] <= window) count++;
            if (count >= (int) Bld.lerp(h, "places", 20, 20))
                p.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, (int) (h.value() * 20), (int) Math.round(Bld.lerp(h, "amp", 0, 1)), false, false, true));
        }

        Item item = s.getBlock().asItem();
        boolean free = p.getAbilities().instabuild;
        List<Runnable> after = new ArrayList<>();
        if (!free && item != Items.AIR && (h = Bld.hit(p, "endless_supply")) != null && Bld.roll(p, h.value()))
            after.add(() -> Bld.give(p, new ItemStack(item)));
        if ((h = Bld.hit(p, "palette_brush")) != null) palette(p, level, pos, s, h, after);
        if (s.is(Blocks.DIRT) && (h = Bld.hit(p, "sod_layer")) != null && Bld.roll(p, h.value()))
            after.add(() -> {
                if (level.getBlockState(pos).is(Blocks.DIRT) && !level.getBlockState(pos.above()).isSolidRender(level, pos.above()))
                    Bld.changeAs(p, pos, Blocks.GRASS_BLOCK.defaultBlockState());
            });
        HabitsApi.PerkHit fill = null;
        String kind = null;
        boolean sneak = p.isShiftKeyDown();
        if (s.getBlock() instanceof WoolCarpetBlock) kind = "carpet_roll";
        else if (s.is(Tags.Blocks.GLASS_PANES) || s.is(Tags.Blocks.GLASS_BLOCKS)) kind = "glazier";
        else if (sneak && s.is(Blocks.CHAIN) && s.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y) kind = "chandelier";
        else if (sneak && (s.getBlock() instanceof VineBlock || s.is(Blocks.WEEPING_VINES) || s.is(Blocks.TWISTING_VINES) || s.is(Blocks.CAVE_VINES))) kind = "vine_drape";
        else if (sneak && s.is(BlockTags.SMALL_FLOWERS)) kind = "flower_bed";
        else if (sneak && s.is(Blocks.REDSTONE_WIRE)) kind = "wire_puller";
        else if (s.getBlock() instanceof TripWireHookBlock) kind = "tripwire_spool";
        else if (backfillable(s) && pos.getY() < p.getBlockY() && p.onGround()) kind = "backfill";
        if (kind != null) fill = Bld.hit(p, kind);
        HabitsApi.PerkHit sym = Bld.hit(p, "symmetry");
        HabitsApi.PerkHit supply = Bld.hit(p, "supply_line");
        HabitsApi.PerkHit stock = Bld.hit(p, "stock_count");
        if (fill == null && sym == null && supply == null && stock == null && after.isEmpty()) return;
        String k = kind;
        HabitsApi.PerkHit f = fill;
        Direction facing = p.getDirection();
        // runs after the placement: during this event NeoForge holds the hand stack at its old count, so items taken
        // from the hand now would be handed back
        Later.run(level, 0, () -> {
            if (!Bld.allowed(p)) return;
            after.forEach(Runnable::run);
            if (level.getBlockState(pos) != s) return;
            if (f != null) switch (k) {
                case "carpet_roll" -> carpets(p, level, pos, s, f);
                case "glazier" -> glazier(p, level, pos, s, facing, f);
                case "chandelier" -> line(p, level, pos, s, Direction.DOWN, (int) f.value());
                case "vine_drape" -> line(p, level, pos, s, s.is(Blocks.TWISTING_VINES) ? Direction.UP : Direction.DOWN, (int) f.value());
                case "flower_bed" -> flowers(p, level, pos, s, f);
                case "wire_puller" -> line(p, level, pos, s, facing, (int) f.value());
                case "tripwire_spool" -> tripwire(p, level, pos, s, f);
                case "backfill" -> backfill(p, level, pos, s, f);
                default -> { }
            }
            if (sym != null) mirror(p, level, pos, s, sym);
            if (supply != null) supply(p, level, item, supply);
            if (stock != null) stock(p, level, item, stock);
        });
    }

    // ---------------------------------------------------------------- custom events

    static void events(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s) {
        if (pos.getY() >= BuildingMechanics.HIGH_PLACE_Y.getAsInt()) Bld.fire(p, "high_place", pos);
        if (p.isEyeInFluid(FluidTags.WATER)) Bld.fire(p, "underwater_place", pos);
        if (s.getLightEmission(level, pos) > 0 && level.getBrightness(LightLayer.SKY, pos) == 0) Bld.fire(p, "light_place", pos);
        long now = level.getGameTime();
        ArrayDeque<long[]> q = RECENT.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
        q.addLast(new long[]{now, Block.getId(s.getBlock().defaultBlockState())});
        long keep = Math.max(BuildingMechanics.STREAK_SECONDS.getAsInt(), BuildingMechanics.PALETTE_SECONDS.getAsInt()) * 20L;
        while (!q.isEmpty() && now - q.peekFirst()[0] > keep) q.pollFirst();
        long streakT = BuildingMechanics.STREAK_SECONDS.getAsInt() * 20L, paletteT = BuildingMechanics.PALETTE_SECONDS.getAsInt() * 20L;
        long[] from = SINCE.computeIfAbsent(p.getUUID(), k -> new long[]{Long.MIN_VALUE, Long.MIN_VALUE});
        int streak = 0;
        Set<Long> kinds = new HashSet<>();
        for (long[] r : q) {
            if (r[0] > from[0] && now - r[0] <= streakT) streak++;
            if (r[0] > from[1] && now - r[0] <= paletteT) kinds.add(r[1]);
        }
        // each fires once per stretch: only placements after the last firing count toward the next one
        if (streak >= BuildingMechanics.STREAK_BLOCKS.getAsInt()) { from[0] = now; Bld.fire(p, "build_streak", pos); }
        if (kinds.size() >= BuildingMechanics.PALETTE_KINDS.getAsInt()) { from[1] = now; Bld.fire(p, "palette", pos); }
    }

    // ---------------------------------------------------------------- mechanics

    private static void palette(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h, List<Runnable> after) {
        ItemStack off = p.getOffhandItem();
        if (!(off.getItem() instanceof BlockItem bi) || bi.getBlock() == s.getBlock() || !p.getMainHandItem().is(s.getBlock().asItem())) return;
        if (!Bld.roll(p, h.value())) return;
        Item main = s.getBlock().asItem();
        after.add(() -> {
            if (level.getBlockState(pos) != s || !Bld.has(p, bi, 1)) return;
            BlockState ns = Block.updateFromNeighbourShapes(bi.getBlock().withPropertiesOf(s), level, pos);
            if (!ns.canSurvive(level, pos) || !Bld.changeAs(p, pos, ns)) return;
            Bld.take(p, bi);
            if (!p.getAbilities().instabuild) Bld.give(p, new ItemStack(main));
        });
    }

    /** Items one block of this state took to build: a double slab is two slabs, four candles four candles. */
    static int cost(BlockState s) {
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) return s.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
        for (var prop : List.of(BlockStateProperties.LAYERS, BlockStateProperties.CANDLES, BlockStateProperties.PICKLES,
                BlockStateProperties.EGGS, BlockStateProperties.FLOWER_AMOUNT))
            if (s.hasProperty(prop)) return s.getValue(prop);
        return 1;
    }

    /** Place {@code s} at each spot in order while the player can pay; returns how many were placed. */
    static int placeAll(Player p, List<BlockPos> spots, BlockState s) {
        Item item = s.getBlock().asItem();
        int c = cost(s), n = 0;
        for (BlockPos q : spots) {
            if (!Bld.has(p, item, c)) break;
            if (Bld.placeAs(p, q, s)) { for (int i = 0; i < c; i++) Bld.take(p, item); n++; }
        }
        return n;
    }

    static void line(Player p, ServerLevel level, BlockPos pos, BlockState s, Direction d, int n) {
        List<BlockPos> spots = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            BlockPos q = pos.relative(d, i);
            if (!level.isLoaded(q) || !level.getBlockState(q).isAir()) break;
            spots.add(q);
        }
        // a spot that cannot hold the block (no support) ends the line
        Item item = s.getBlock().asItem();
        for (BlockPos q : spots) {
            if (!Bld.has(p, item, 1) || !Bld.placeAs(p, q, s)) break;
            Bld.take(p, item);
        }
    }

    static void carpets(Player p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        int max = (int) h.value();
        List<BlockPos> spots = Bld.flood(level, pos, max + 1, Bld.FLAT, q -> q.equals(pos) || level.getBlockState(q).isAir() && s.canSurvive(level, q));
        spots.remove(pos);
        if (spots.size() <= max) placeAll(p, spots, s); // only a closed floor rolls out
    }

    static void glazier(Player p, ServerLevel level, BlockPos pos, BlockState s, Direction facing, HabitsApi.PerkHit h) {
        int max = (int) h.value();
        Direction[] plane = {Direction.UP, Direction.DOWN, facing.getClockWise(), facing.getCounterClockWise()};
        List<BlockPos> spots = Bld.flood(level, pos, max + 1, plane, q -> q.equals(pos) || level.getBlockState(q).isAir());
        spots.remove(pos);
        if (spots.size() <= max) placeAll(p, spots, s); // only closed openings fill, silently otherwise (every pane checks)
    }

    static boolean backfillable(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE)
                || s.is(Blocks.COBBLED_DEEPSLATE) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER);
    }

    static void backfill(Player p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        int max = (int) h.value();
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};
        List<BlockPos> spots = Bld.flood(level, pos, max + 1, dirs, q -> q.equals(pos)
                || q.getY() <= pos.getY() && level.getBlockState(q).canBeReplaced() && (level.getFluidState(q).isEmpty() || level.getFluidState(q).is(FluidTags.WATER)));
        spots.remove(pos);
        if (spots.size() > max) return; // open ground: nothing, silently (every low placement checks)
        spots.sort((a, b) -> Integer.compare(a.getY(), b.getY())); // bottom up, so sand and gravel never fall
        placeAll(p, spots, s);
    }

    static void flowers(Player p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        int r = (int) Math.round(Bld.lerp(h, "radius", 2, 3));
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-r, -1, -r), pos.offset(r, 1, r)))
            if (!q.equals(pos) && level.getBlockState(q).isAir() && s.canSurvive(level, q)) spots.add(q.immutable());
        Collections.shuffle(spots, new java.util.Random(p.getRandom().nextLong()));
        placeAll(p, spots.subList(0, Math.min((int) h.value(), spots.size())), s);
    }

    static void tripwire(Player p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        Direction f = s.getValue(TripWireHookBlock.FACING);
        List<BlockPos> gap = new ArrayList<>();
        for (int i = 1; i <= (int) h.value(); i++) {
            BlockPos q = pos.relative(f, i);
            BlockState at = level.getBlockState(q);
            if (at.getBlock() instanceof TripWireHookBlock && at.getValue(TripWireHookBlock.FACING) == f.getOpposite()) {
                placeAll(p, gap, Blocks.TRIPWIRE.defaultBlockState());
                return;
            }
            if (!at.isAir()) return;
            gap.add(q);
        }
    }

    static void mirror(Player p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        Plane pl = PLANES.get(p.getUUID());
        if (pl == null || pl.dim() != level.dimension()) return;
        int coord = pl.axis() == 0 ? pos.getX() : pos.getZ();
        if (coord == pl.c() || Math.abs(coord - pl.c()) > h.value()) return;
        BlockPos m = pl.axis() == 0 ? new BlockPos(2 * pl.c() - pos.getX(), pos.getY(), pos.getZ()) : new BlockPos(pos.getX(), pos.getY(), 2 * pl.c() - pos.getZ());
        placeAll(p, List.of(m), s.mirror(pl.axis() == 0 ? Mirror.FRONT_BACK : Mirror.LEFT_RIGHT));
    }

    static void supply(Player p, ServerLevel level, Item item, HabitsApi.PerkHit h) {
        if (!p.getMainHandItem().isEmpty() || item == Items.AIR) return;
        var tag = p.getPersistentData();
        if (!tag.contains(SUPPLY) || !level.dimension().location().toString().equals(tag.getString(SUPPLY_DIM))) return;
        BlockPos c = BlockPos.of(tag.getLong(SUPPLY));
        if (!level.isLoaded(c) || c.distSqr(p.blockPosition()) > h.value() * h.value()) return;
        if (!(level.getBlockEntity(c) instanceof Container box)) return;
        ItemStack got = ItemStack.EMPTY;
        for (int i = 0; i < box.getContainerSize() && got.getCount() < item.getDefaultMaxStackSize(); i++) {
            ItemStack in = box.getItem(i);
            if (!in.is(item)) continue;
            int n = Math.min(in.getCount(), item.getDefaultMaxStackSize() - got.getCount());
            if (got.isEmpty()) got = in.copyWithCount(n); else got.grow(n);
            in.shrink(n);
        }
        if (got.isEmpty()) return;
        box.setChanged();
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, got);
        Bld.say(p, "supply", got.getCount(), got.getHoverName());
    }

    static void stock(Player p, ServerLevel level, Item item, HabitsApi.PerkHit h) {
        if (item == Items.AIR) return;
        int n = p.getInventory().countItem(item);
        for (ItemStack s : p.getInventory().items) {
            var box = s.get(DataComponents.CONTAINER);
            if (box != null) for (ItemStack in : box.nonEmptyItems()) if (in.is(item)) n += in.getCount();
        }
        int r = (int) h.value();
        if (r > 0) {
            BlockPos at = p.blockPosition();
            for (int cx = (at.getX() - r) >> 4; cx <= (at.getX() + r) >> 4; cx++)
                for (int cz = (at.getZ() - r) >> 4; cz <= (at.getZ() + r) >> 4; cz++) {
                    if (!level.hasChunk(cx, cz)) continue;
                    LevelChunk ch = level.getChunk(cx, cz);
                    for (var be : ch.getBlockEntities().values())
                        if (be instanceof Container c && be.getBlockPos().distSqr(at) <= (double) r * r) n += c.countItem(item);
                }
        }
        Bld.say(p, "stock", new ItemStack(item).getHoverName(), n);
    }

    // ---------------------------------------------------------------- clone

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone e) {
        var from = e.getOriginal().getPersistentData();
        var to = e.getEntity().getPersistentData();
        for (String k : new String[]{SUPPLY, SUPPLY_DIM}) if (from.contains(k)) to.put(k, from.get(k).copy());
    }

}
