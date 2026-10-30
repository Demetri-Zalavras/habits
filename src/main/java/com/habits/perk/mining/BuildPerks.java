package com.habits.perk.mining;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Placing and interacting: building wand, fences, rails, ladders, wrench, kindler, copper, bark, paths, keys. */
public final class BuildPerks {
    private BuildPerks() {}

    /** Oops: recent placements per player, newest last. {pos, blockStateId}. */
    static final Map<UUID, ArrayDeque<long[]>> UNDO = Work.playerMap();
    /** Game time a player sneak-used an item frame (curator invisibility). */
    private static final Map<UUID, Long> FRAME_USE = Work.playerMap();

    // ---------------------------------------------------------------- place

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (Work.busy || !(e.getEntity() instanceof ServerPlayer p) || !MiningMechanics.allowed(p)) return;
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        BlockState s = e.getPlacedBlock();
        long now = level.getGameTime();
        // custom moments
        long[] br = BreakPerks.LAST_BREAK.get(p.getUUID());
        // replanting the same crop (grown wheat -> wheat seeds) or a sapling where the trunk stood is no swap
        if (br != null && br[0] == pos.asLong() && now - br[1] <= 60 && Block.stateById((int) br[2]).getBlock() != s.getBlock()
                && !s.is(BlockTags.SAPLINGS)) BreakPerks.fire(p, "swap_block", pos);
        if (!p.onGround() && p.getDeltaMovement().y < -0.2) BreakPerks.fire(p, "fall_place", pos);
        if (!p.onGround() && pos.getX() == p.getBlockX() && pos.getZ() == p.getBlockZ() && pos.getY() < p.getY()) BreakPerks.fire(p, "jump_pillar", pos);
        if (p.isShiftKeyDown() && p.onGround() && pos.getY() == p.getBlockY() - 1 && !pos.equals(p.getOnPos())
                && level.getBlockState(pos.below()).isAir()) BreakPerks.fire(p, "edge_bridge", pos);
        if (p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 4.3) BreakPerks.fire(p, "long_reach", pos);
        BreakPerks.LAST_PLACE.put(p.getUUID(), new long[]{pos.asLong(), now});

        HabitsApi.PerkHit h;
        if ((h = MiningMechanics.hit(p, "oops")) != null) {
            ArrayDeque<long[]> q = UNDO.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
            q.addLast(new long[]{pos.asLong(), Block.getId(s)});
            while (q.size() > (int) h.value()) q.pollFirst();
        }
        if (s.is(BlockTags.CANDLES) && (h = MiningMechanics.hit(p, "candlelight")) != null) MiningData.get(level).addCandle(pos, (int) h.value());
        // owned blocks read by the mixin hooks (MiningHooks); every placement of the kind overwrites the record
        if (s.is(Blocks.HOPPER)) own(p, level, pos, "hopper_hand");
        if (s.is(Blocks.PISTON) || s.is(Blocks.STICKY_PISTON)) own(p, level, pos, "piston_power");
        if (s.is(Blocks.SPONGE) || s.is(Blocks.WET_SPONGE)) own(p, level, pos, "drain"); // vanilla soaks after this event
        if (s.is(Blocks.BEACON) || s.is(Blocks.CONDUIT)) own(p, level, pos, "beacon_builder");
        if (s.is(Blocks.DISPENSER) || s.is(Blocks.DROPPER)) {
            HabitsApi.PerkHit d = MiningMechanics.hit(p, "dispenser_tinkerer");
            MiningData.get(level).setDispenser(pos, d == null ? 0 : d.level());
        }
        if (s.is(Blocks.SPAWNER)) restoreSpawner(p, level, pos);
        Doors.placed(p, level, pos, s);
        if (s.is(BlockTags.BANNERS) && MiningMechanics.hit(p, "standard_bearer") != null) {
            p.getPersistentData().putLong("habits_banner_pos", pos.asLong());
            p.getPersistentData().putString("habits_banner_dim", level.dimension().location().toString());
        }
        if (s.is(BlockTags.ALL_SIGNS) && MiningMechanics.hit(p, "scribe") != null) Scribe.watch(p, pos);
        if (s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM && p.isShiftKeyDown()
                && (h = MiningMechanics.hit(p, "half_measures")) != null && h.level() >= (int) MiningMechanics.lerp(h, "top_level", 5, 5))
            level.setBlock(pos, s.setValue(SlabBlock.TYPE, SlabType.TOP), 3);
        if (s.is(BlockTags.CONCRETE_POWDER) && (h = MiningMechanics.hit(p, "concrete_mixer")) != null) concrete(p, level, pos, s, h);
        // perks that spend blocks run after the placement: during this event NeoForge has the held stack reset to its
        // old count and writes the new count back afterwards, so anything taken from the hand now is handed back
        HabitsApi.PerkHit fenceHit = (s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS)) ? MiningMechanics.hit(p, "fence_line") : null;
        HabitsApi.PerkHit railHit = s.is(BlockTags.RAILS) ? MiningMechanics.hit(p, "rail_layer") : null;
        HabitsApi.PerkHit wandHit = p.isShiftKeyDown() && s.isCollisionShapeFullBlock(level, pos) ? MiningMechanics.hit(p, "masons_wand") : null;
        HabitsApi.PerkHit pillarHit = !p.onGround() && pos.getX() == p.getBlockX() && pos.getZ() == p.getBlockZ() && pos.getY() < p.getY()
                ? MiningMechanics.hit(p, "pillar_up") : null;
        if (fenceHit != null || railHit != null || wandHit != null || pillarHit != null) later(level, () -> {
            if (level.getBlockState(pos) != s || !MiningMechanics.allowed(p)) return;
            if (fenceHit != null) fenceLine(p, level, pos, s, fenceHit);
            if (railHit != null) rails(p, level, pos, s, railHit);
            if (wandHit != null) wand(p, level, pos, s, wandHit);
            if (pillarHit != null) pillar(p, level, pos, s, pillarHit);
        });
    }

    private static Item itemOf(BlockState s) {
        return s.getBlock().asItem();
    }

    /** Building wand: fill matching faces on the same surface around the placed block. */
    static void wand(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        HitResult hr = p.pick(p.blockInteractionRange() + 1, 1, false);
        Direction face = hr instanceof BlockHitResult b && b.getBlockPos().equals(pos) ? b.getDirection()
                : Direction.getNearest(p.getEyePosition().subtract(Vec3.atCenterOf(pos)));
        BlockState against = level.getBlockState(pos.relative(face.getOpposite()));
        Item item = itemOf(s);
        int cost = Work.cost(s), placed = 0;
        for (BlockPos q : BreakPerks.area(pos, face, 2, 1, 24)) {
            if (placed >= (int) h.value()) break;
            if (!level.isLoaded(q) || !level.getBlockState(q.relative(face.getOpposite())).equals(against)) continue;
            if (!Work.has(p, item, cost)) break;
            if (Work.placeAs(p, q, s, face)) { for (int i = 0; i < cost; i++) Work.take(p, item); placed++; }
        }
    }

    static void pillar(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        int extra = (int) h.value();
        Item item = itemOf(s);
        int cost = Work.cost(s), up = 0;
        for (int i = 1; i <= extra; i++) {
            BlockPos q = pos.above(i);
            if (!level.getBlockState(q).canBeReplaced() || !level.getBlockState(q.above(2)).canBeReplaced() || !Work.has(p, item, cost)) break;
            // lift first so the new block never lands inside the player
            p.teleportTo(p.getX(), q.getY() + 1.0, p.getZ());
            if (!Work.placeAs(p, q, s, Direction.UP)) break;
            for (int k = 0; k < cost; k++) Work.take(p, item);
            up++;
        }
        if (h.level() <= 3 || up > 0) p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20, 0, true, false));
    }

    static void fenceLine(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        int gap = (int) h.value();
        Item item = itemOf(s);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int end = -1;
            for (int i = 1; i <= gap + 1; i++) {
                BlockState b = level.getBlockState(pos.relative(d, i));
                if (b.is(s.getBlock())) { end = i; break; }
                if (!b.canBeReplaced()) break;
            }
            for (int i = 1; i < end; i++) {
                if (!Work.has(p, item, 1)) return;
                if (Work.placeAs(p, pos.relative(d, i), s.getBlock().defaultBlockState(), Direction.UP)) Work.take(p, item);
            }
        }
    }

    static void rails(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        Direction d = p.getDirection();
        int every = (int) MiningMechanics.lerp(h, "powered_every", 8, 8);
        Item item = itemOf(s);
        for (int i = 1; i < (int) h.value(); i++) {
            BlockPos q = pos.relative(d, i);
            if (!level.isLoaded(q) || !level.getBlockState(q).isAir() || !level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)) break;
            boolean powered = i % every == 0 && Work.has(p, Items.POWERED_RAIL, 1) && !p.getAbilities().instabuild;
            Item use = powered ? Items.POWERED_RAIL : item;
            if (!Work.has(p, use, 1)) break;
            BlockState rail = ((BlockItem) use).getBlock().defaultBlockState();
            if (!Work.placeAs(p, q, rail, Direction.UP)) break;
            Work.take(p, use);
        }
    }

    static void concrete(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        int r = (int) h.value();
        boolean ok = r >= 99;
        if (!ok) {
            for (BlockPos q : BlockPos.betweenClosed(pos.offset(-r, -r, -r), pos.offset(r, r, r)))
                if (level.isLoaded(q) && level.getFluidState(q).is(FluidTags.WATER)) { ok = true; break; }
        }
        if (!ok) return;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        Block hard = BuiltInRegistries.BLOCK.get(id.withPath(id.getPath().replace("_concrete_powder", "_concrete")));
        if (hard != Blocks.AIR && hard != s.getBlock()) Work.changeAs(p, pos, hard.defaultBlockState());
    }

    /** Sponge soaks a bigger area (our own bounded flood), and dries at the top level. */
    /** Record the placer's level of the skill named {@code kind} (0 = forget) for an owned block. */
    private static void own(ServerPlayer p, ServerLevel level, BlockPos pos, String kind) {
        HabitsApi.PerkHit h = MiningMechanics.hit(p, kind);
        MiningData.get(level).setOwner(kind, pos, h == null ? 0 : h.level());
    }

    private static void restoreSpawner(ServerPlayer p, ServerLevel level, BlockPos pos) {
        ItemStack held = p.getMainHandItem().is(Items.SPAWNER) ? p.getMainHandItem() : p.getOffhandItem();
        var data = held.get(DataComponents.CUSTOM_DATA);
        if (data == null || !(level.getBlockEntity(pos) instanceof SpawnerBlockEntity be)) return;
        String mob = data.copyTag().getString(DropPerks.SPAWNER_MOB);
        EntityType.byString(mob).ifPresent(t -> {
            be.setEntityId(t, level.getRandom());
            be.setChanged();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        });
    }

    // ---------------------------------------------------------------- right click block

    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || e.getHand() != InteractionHand.MAIN_HAND || !MiningMechanics.allowed(p)) return;
        ServerLevel level = p.serverLevel();
        BlockPos pos = e.getPos();
        BlockState s = level.getBlockState(pos);
        ItemStack held = p.getMainHandItem();
        HabitsApi.PerkHit h;

        if (held.isEmpty() && p.isShiftKeyDown()) {
            if (s.getBlock() instanceof LeverBlock && (h = MiningMechanics.hit(p, "wireless")) != null) { Remote.bind(p, pos, h); cancel(e); return; }
            if ((h = MiningMechanics.hit(p, "copper_keeper")) != null && h.level() >= 10 && s.getBlock() instanceof WeatheringCopper) {
                Block next = WeatheringCopper.NEXT_BY_BLOCK.get().get(s.getBlock());
                Block to = next != null ? next : WeatheringCopper.getFirst(s.getBlock());
                if (to != s.getBlock()) Work.changeAs(p, pos, to.withPropertiesOf(s));
                cancel(e);
                return;
            }
            if ((h = MiningMechanics.hit(p, "wrench")) != null && wrench(p, level, pos, s, h)) { cancel(e); return; }
        }
        if (held.is(Items.LADDER) && s.is(Blocks.LADDER) && (h = MiningMechanics.hit(p, "ladder_layer")) != null) {
            ladders(p, level, pos, s, h);
            cancel(e);
            return;
        }
        if (held.is(Items.SCAFFOLDING) && s.is(Blocks.SCAFFOLDING) && p.isShiftKeyDown() && (h = MiningMechanics.hit(p, "scaffolder")) != null) {
            BlockPos top = pos;
            while (level.getBlockState(top.above()).is(Blocks.SCAFFOLDING) && top.getY() < level.getMaxBuildHeight()) top = top.above();
            for (int i = 1; i <= (int) h.value(); i++) {
                if (!Work.has(p, Items.SCAFFOLDING, 1) || !Work.placeAs(p, top.above(i), Blocks.SCAFFOLDING.defaultBlockState(), Direction.UP)) break;
                Work.take(p, Items.SCAFFOLDING);
            }
            cancel(e);
            return;
        }
        if ((held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE)) && lightable(s) && (h = MiningMechanics.hit(p, "kindler")) != null)
            kindle(p, level, pos, held, h);
        if (held.is(Items.HONEYCOMB) && HoneycombItem.getWaxed(s).isPresent() && (h = MiningMechanics.hit(p, "copper_keeper")) != null) {
            BreakPerks.fire(p, "copper_work", pos);
            for (BlockPos q : connected(level, pos, s.getBlock(), (int) h.value() - 1))
                HoneycombItem.getWaxed(level.getBlockState(q)).ifPresent(w -> Work.changeAs(p, q, w));
        }
        if ((held.is(Items.ITEM_FRAME) || held.is(Items.GLOW_ITEM_FRAME)) && p.isShiftKeyDown() && MiningMechanics.hit(p, "curator") != null)
            FRAME_USE.put(p.getUUID(), level.getGameTime());
        if (s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.FENCE_GATES)) Doors.used(p, pos, s);
    }

    private static void cancel(PlayerInteractEvent.RightClickBlock e) {
        e.setCanceled(true);
        e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
    }

    /** Wrench: rotate in place. Lv1 stairs; Lv4 logs and trapdoors; Lv7 any facing or axis block. Never beds, doors,
     * chests or other two-part blocks. */
    static boolean wrench(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        if (s.is(BlockTags.BEDS) || s.is(BlockTags.DOORS) || s.hasProperty(BlockStateProperties.CHEST_TYPE)
                || s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || s.hasProperty(BlockStateProperties.BED_PART)
                || (s.hasProperty(BlockStateProperties.EXTENDED) && s.getValue(BlockStateProperties.EXTENDED))) return false;
        int lvl = h.level();
        boolean allowed = s.getBlock() instanceof StairBlock
                || (lvl >= 4 && (s.getBlock() instanceof RotatedPillarBlock || s.getBlock() instanceof TrapDoorBlock))
                || lvl >= 7;
        if (!allowed) return false;
        BlockState next = cycle(s, "facing");
        if (next == s) next = cycle(s, "axis");
        if (next == s || !next.canSurvive(level, pos)) return false;
        return Work.changeAs(p, pos, next);
    }

    private static <T extends Comparable<T>> BlockState cycle(BlockState s, String name) {
        for (Property<?> prop : s.getProperties()) if (prop.getName().equals(name)) return cycleProp(s, prop);
        return s;
    }

    private static <T extends Comparable<T>> BlockState cycleProp(BlockState s, Property<T> prop) {
        List<T> vals = new ArrayList<>(prop.getPossibleValues());
        int i = vals.indexOf(s.getValue(prop));
        return s.setValue(prop, vals.get((i + 1) % vals.size()));
    }

    static void ladders(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        Direction step = p.isShiftKeyDown() ? Direction.UP : Direction.DOWN;
        BlockPos q = pos;
        while (level.getBlockState(q.relative(step)).is(Blocks.LADDER) && Math.abs(q.getY() - pos.getY()) < 384) q = q.relative(step);
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, s.getValue(LadderBlock.FACING));
        for (int i = 1; i <= (int) h.value(); i++) {
            BlockPos n = q.relative(step, i);
            if (!Work.has(p, Items.LADDER, 1) || !Work.placeAs(p, n, ladder, step)) break;
            Work.take(p, Items.LADDER);
        }
    }

    static boolean lightable(BlockState s) {
        return s.is(BlockTags.CANDLES) || s.is(BlockTags.CAMPFIRES) || s.is(BlockTags.CANDLE_CAKES) || s.is(Blocks.TNT);
    }

    /** Kindler: light every lightable block within R too; flint and steel may not wear. */
    static void kindle(ServerPlayer p, ServerLevel level, BlockPos pos, ItemStack held, HabitsApi.PerkHit h) {
        int r = (int) h.value();
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-r, -r, -r), pos.offset(r, r, r))) {
            if (q.equals(pos) || !level.isLoaded(q)) continue;
            BlockState b = level.getBlockState(q);
            if (b.is(Blocks.TNT)) {
                BlockPos at = q.immutable();
                if (Work.changeAs(p, at, b)) {
                    b.onCaughtFire(level, at, Direction.UP, p);
                    level.removeBlock(at, false);
                }
            } else if (lightable(b) && b.hasProperty(BlockStateProperties.LIT) && !b.getValue(BlockStateProperties.LIT)
                    && !(b.hasProperty(BlockStateProperties.WATERLOGGED) && b.getValue(BlockStateProperties.WATERLOGGED))) {
                Work.changeAs(p, q.immutable(), b.setValue(BlockStateProperties.LIT, true));
            }
        }
        if (held.is(Items.FLINT_AND_STEEL) && p.getRandom().nextDouble() * 100 < MiningMechanics.lerp(h, "no_wear", 10, 100)) {
            int before = held.getDamageValue();
            later(level, () -> {
                if (held.is(Items.FLINT_AND_STEEL)) held.setDamageValue(before);
            });
        }
    }

    /** Same-block flood (6 neighbours), loaded only, not counting start. */
    static List<BlockPos> connected(ServerLevel level, BlockPos start, Block block, int max) {
        List<BlockPos> out = new ArrayList<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>(List.of(start));
        Set<BlockPos> seen = new HashSet<>(List.of(start));
        while (!q.isEmpty() && out.size() < max) {
            BlockPos c = q.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (out.size() >= max || !seen.add(n) || !level.isLoaded(n) || !level.getBlockState(n).is(block)) continue;
                out.add(n);
                q.add(n);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- tool modifications: strip, scrape, path

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onToolModify(BlockEvent.BlockToolModificationEvent e) {
        if (Work.busy || e.isSimulated() || e.isCanceled() || !(e.getPlayer() instanceof ServerPlayer p) || !MiningMechanics.allowed(p)) return;
        ItemAbility a = e.getItemAbility();
        ServerLevel level = p.serverLevel();
        BlockPos pos = e.getPos();
        BlockState before = e.getState();
        HabitsApi.PerkHit h;
        if (a == ItemAbilities.AXE_STRIP && before.is(BlockTags.LOGS) && (h = MiningMechanics.hit(p, "bark_peeler")) != null) {
            double sticks = MiningMechanics.lerp(h, "stick_chance", 0, 50);
            List<BlockPos> logs = BreakPerks.flood(level, pos, before.getBlock(), (int) h.value() - 1);
            later(level, () -> {
                for (BlockPos q : logs) {
                    if (modify(p, level, q, a) && p.getRandom().nextDouble() * 100 < sticks) Block.popResource(level, q, new ItemStack(Items.STICK));
                }
            });
        }
        if ((a == ItemAbilities.AXE_SCRAPE || a == ItemAbilities.AXE_WAX_OFF) && (h = MiningMechanics.hit(p, "copper_keeper")) != null) {
            BreakPerks.fire(p, "copper_work", pos);
            List<BlockPos> blocks = connected(level, pos, before.getBlock(), (int) h.value() - 1);
            later(level, () -> { for (BlockPos q : blocks) modify(p, level, q, a); });
        }
        if (a == ItemAbilities.SHOVEL_FLATTEN) {
            BreakPerks.fire(p, "make_path", pos);
            if ((h = MiningMechanics.hit(p, "path_maker")) != null) {
                int r = (int) h.value();
                later(level, () -> {
                    for (BlockPos q : BlockPos.betweenClosed(pos.offset(-r, 0, -r), pos.offset(r, 0, r)))
                        if (!q.equals(pos) && level.isLoaded(q) && level.getBlockState(q.above()).isAir()) modify(p, level, q.immutable(), a);
                });
            }
        }
    }

    /** Apply a tool action to one more block as the player (no extra durability). */
    private static boolean modify(ServerPlayer p, ServerLevel level, BlockPos q, ItemAbility a) {
        BlockState b = level.getBlockState(q);
        UseOnContext ctx = new UseOnContext(level, p, InteractionHand.MAIN_HAND, p.getMainHandItem(),
                new BlockHitResult(Vec3.atCenterOf(q), Direction.UP, q, false));
        boolean was = Work.busy;
        Work.busy = true;
        BlockState to;
        try {
            to = b.getToolModifiedState(ctx, a, false);
        } finally {
            Work.busy = was;
        }
        return to != null && to != b && Work.changeAs(p, q, to);
    }

    private static void later(ServerLevel level, Runnable r) {
        com.habits.perk.Later.run(level, 0, r);
    }

    // ---------------------------------------------------------------- right click item (air)

    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || e.getHand() != InteractionHand.MAIN_HAND || !MiningMechanics.allowed(p)) return;
        ItemStack held = e.getItemStack();
        HabitsApi.PerkHit h;
        if ((held.is(Items.TORCH) || held.is(Items.SOUL_TORCH) || held.is(Items.LANTERN) || held.is(Items.SOUL_LANTERN))
                && (h = MiningMechanics.hit(p, "torch_toss")) != null) torchToss(p, held, h);
        if (held.is(Items.WATER_BUCKET) && (h = MiningMechanics.hit(p, "plumber")) != null && p.getRandom().nextDouble() * 100 < h.value()) {
            ServerLevel level = p.serverLevel();
            later(level, () -> { if (p.getMainHandItem().is(Items.BUCKET) && p.getMainHandItem().getCount() == 1) p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.WATER_BUCKET)); });
        }
        if (held.is(Items.BUCKET) && (h = MiningMechanics.hit(p, "plumber")) != null && h.level() >= (int) MiningMechanics.lerp(h, "scoop_level", 6, 6)) {
            HitResult hr = p.pick(p.blockInteractionRange(), 1, true);
            if (hr instanceof BlockHitResult b && p.level().getFluidState(b.getBlockPos()).is(FluidTags.WATER) && p.level().getFluidState(b.getBlockPos()).isSource()) {
                BlockPos c = b.getBlockPos();
                ServerLevel level = p.serverLevel();
                later(level, () -> {
                    for (BlockPos q : BlockPos.betweenClosed(c.offset(-1, 0, -1), c.offset(1, 0, 1)))
                        if (level.getBlockState(q).is(Blocks.WATER) && level.getFluidState(q).isSource()) Work.removeAs(p, q.immutable());
                });
            }
        }
    }

    static void torchToss(ServerPlayer p, ItemStack held, HabitsApi.PerkHit h) {
        ServerLevel level = p.serverLevel();
        Vec3 eye = p.getEyePosition();
        BlockHitResult hr = level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(h.value())), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (hr.getType() != HitResult.Type.BLOCK) return;
        Direction face = hr.getDirection();
        BlockPos at = hr.getBlockPos().relative(face);
        Item item = held.getItem();
        boolean lantern = item == Items.LANTERN || item == Items.SOUL_LANTERN;
        BlockState state;
        if (lantern) {
            if (face == Direction.UP) state = ((BlockItem) item).getBlock().defaultBlockState();
            else if (face == Direction.DOWN) state = ((BlockItem) item).getBlock().defaultBlockState().setValue(BlockStateProperties.HANGING, true);
            else return;
        } else {
            Block floor = item == Items.TORCH ? Blocks.TORCH : Blocks.SOUL_TORCH;
            Block wall = item == Items.TORCH ? Blocks.WALL_TORCH : Blocks.SOUL_WALL_TORCH;
            if (face == Direction.UP) state = floor.defaultBlockState();
            else if (face == Direction.DOWN) return;
            else state = wall.defaultBlockState().setValue(WallTorchBlock.FACING, face);
        }
        if (Work.has(p, item, 1) && Work.placeAs(p, at, state, face)) Work.take(p, item);
    }

    // ---------------------------------------------------------------- left click: auto tool, block swapper

    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || e.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START || !MiningMechanics.allowed(p)) return;
        ServerLevel level = p.serverLevel();
        BlockPos pos = e.getPos();
        BlockState s = level.getBlockState(pos);
        if (s.isAir()) return;
        ItemStack held = p.getMainHandItem();
        HabitsApi.PerkHit h;
        if (p.isShiftKeyDown() && held.getItem() instanceof BlockItem bi && bi.getBlock() != s.getBlock()
                && (h = MiningMechanics.hit(p, "block_swapper")) != null) {
            swap(p, level, pos, s, bi, e.getFace() == null ? Direction.UP : e.getFace(), h);
            e.setCanceled(true);
            return;
        }
        if ((h = MiningMechanics.hit(p, "auto_tool")) != null) autoTool(p, s, h); // any held item, blocks included
    }

    static void swap(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, BlockItem bi, Direction face, HabitsApi.PerkHit h) {
        List<BlockPos> targets = new ArrayList<>();
        targets.add(pos);
        for (BlockPos q : BreakPerks.area(pos, face, 2, 1, 24)) if (level.getBlockState(q).is(s.getBlock())) targets.add(q);
        int max = (int) h.value();
        int done = 0;
        for (BlockPos q : targets) {
            if (done >= max || !Work.has(p, bi, 1)) break;
            BlockState old = level.getBlockState(q);
            if (old.hasBlockEntity() || old.getDestroySpeed(level, q) < 0) continue;
            if (!Work.removeAs(p, q)) continue;
            if (!Work.placeAs(p, q, bi.getBlock().defaultBlockState(), face)) { level.setBlock(q, old, 3); continue; }
            Work.take(p, bi);
            if (!p.getAbilities().instabuild) Work.give(p, new ItemStack(old.getBlock().asItem()));
            done++;
        }
    }

    /** Auto tool: switch to the fastest tool for the block. Below Lv6 only the hotbar is searched; from Lv6 the whole
     * inventory, swapping the tool into your hand. */
    static void autoTool(ServerPlayer p, BlockState s, HabitsApi.PerkHit h) {
        var inv = p.getInventory();
        int range = h.value() >= MiningMechanics.lerp(h, "inventory_level", 6, 6) ? inv.items.size() : 9;
        float bestSpeed = inv.getSelected().getDestroySpeed(s);
        boolean bestOk = inv.getSelected().isCorrectToolForDrops(s);
        int best = -1;
        for (int i = 0; i < range; i++) {
            ItemStack st = inv.items.get(i);
            if (st.isEmpty() || i == inv.selected) continue;
            boolean ok = st.isCorrectToolForDrops(s) || !s.requiresCorrectToolForDrops();
            float sp = st.getDestroySpeed(s);
            if (sp <= 1) continue;
            if ((ok && !bestOk) || (ok == bestOk && sp > bestSpeed)) { best = i; bestSpeed = sp; bestOk = ok; }
        }
        if (best < 0) return;
        if (best < 9) {
            inv.selected = best;
            p.connection.send(new ClientboundSetCarriedItemPacket(best));
        } else {
            ItemStack tool = inv.items.get(best);
            inv.items.set(best, inv.getSelected());
            inv.items.set(inv.selected, tool);
        }
    }

    // ---------------------------------------------------------------- entities: armor stand, item frame

    @SubscribeEvent
    public static void onEntityUse(PlayerInteractEvent.EntityInteractSpecific e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getTarget() instanceof ArmorStand stand) || !p.isShiftKeyDown()
                || e.getHand() != InteractionHand.MAIN_HAND || !MiningMechanics.allowed(p)) return;
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "tailors_dummy");
        if (h == null || stand.isMarker()) return;
        int lvl = h.level();
        List<EquipmentSlot> slots = new ArrayList<>(List.of(EquipmentSlot.HEAD));
        if (lvl >= 4) slots.add(EquipmentSlot.CHEST);
        if (lvl >= 7) { slots.add(EquipmentSlot.LEGS); slots.add(EquipmentSlot.FEET); }
        if (lvl >= 10) { slots.add(EquipmentSlot.MAINHAND); slots.add(EquipmentSlot.OFFHAND); }
        for (EquipmentSlot slot : slots) {
            ItemStack mine = p.getItemBySlot(slot).copy();
            ItemStack theirs = stand.getItemBySlot(slot).copy();
            p.setItemSlot(slot, theirs);
            stand.setItemSlot(slot, mine);
        }
        e.setCanceled(true);
        e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
    }

    @SubscribeEvent
    public static void onFrameJoin(EntityJoinLevelEvent e) {
        if (!(e.getEntity() instanceof ItemFrame frame) || !(e.getLevel() instanceof ServerLevel level) || FRAME_USE.isEmpty()) return;
        for (Player pl : level.players()) {
            Long t = FRAME_USE.get(pl.getUUID());
            if (t != null && t == level.getGameTime() && pl.distanceToSqr(frame) < 64) {
                frame.setInvisible(true);
                FRAME_USE.remove(pl.getUUID());
                return;
            }
        }
    }

    // ---------------------------------------------------------------- key actions from the client

    static void action(ServerPlayer p, int kind) {
        if (!MiningMechanics.allowed(p)) return;
        switch (kind) {
            case MiningNet.Action.UNDO -> undo(p);
            case MiningNet.Action.REMOTE -> Remote.flip(p);
            case MiningNet.Action.AIR_PLACE -> airPlace(p);
            default -> {}
        }
    }

    /** Oops: take back the newest placement still standing; its item comes back. Blocks with a block entity are
     * refused (dropped from the list, left standing) so their contents are never lost. */
    static void undo(ServerPlayer p) {
        ArrayDeque<long[]> q = UNDO.get(p.getUUID());
        if (q == null || MiningMechanics.hit(p, "oops") == null) return;
        ServerLevel level = p.serverLevel();
        while (!q.isEmpty()) {
            long[] last = q.pollLast();
            BlockPos pos = BlockPos.of(last[0]);
            BlockState now = level.getBlockState(pos);
            if (Block.getId(now) != (int) last[1] || pos.distSqr(p.blockPosition()) > 64 * 64) continue;
            // a block entity (chest, shulker, named block) holds data the plain item would lose: refuse, keep the block
            if (level.getBlockEntity(pos) != null) {
                p.displayClientMessage(Component.translatable("habits.mining.undo_refused", q.size()), true);
                return;
            }
            if (Work.removeAs(p, pos) && !p.getAbilities().instabuild) Work.give(p, new ItemStack(now.getBlock().asItem()));
            p.displayClientMessage(Component.translatable("habits.mining.undo", q.size()), true);
            return;
        }
        p.displayClientMessage(Component.translatable("habits.mining.undo_empty"), true);
    }

    static void airPlace(ServerPlayer p) {
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "air_place");
        if (h == null || !(p.getMainHandItem().getItem() instanceof BlockItem bi)) return;
        long now = p.level().getGameTime();
        if (now < p.getPersistentData().getLong("habits_airplace_ready")) return;
        BlockPos at = BlockPos.containing(p.getEyePosition().add(p.getLookAngle().scale(Math.max(1, h.value()))));
        if (!p.level().getBlockState(at).isAir() || !Work.has(p, bi, 1)) return;
        if (Work.placeAs(p, at, bi.getBlock().defaultBlockState(), Direction.UP)) {
            Work.take(p, bi);
            p.getPersistentData().putLong("habits_airplace_ready", now + (long) (MiningMechanics.lerp(h, "cooldown_s", 2, 0) * 20));
        }
    }
}
