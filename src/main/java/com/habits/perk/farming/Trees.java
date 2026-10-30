package com.habits.perk.farming;

import com.habits.core.HabitsApi.PerkHit;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Trees, leaves, mushrooms and shears. */
public final class Trees {
    private Trees() {}

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (Farm.busy || !(e.getPlayer() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel l) || !Farm.ok(p)) return;
        BlockState s = e.getState();
        BlockPos pos = e.getPos();
        timber(p, l, pos, s);
        if (naturalLeaves(s)) leafBlower(p, pos);
        topiary(p, l, pos, s);
        shroomFeller(p, l, pos, s);
    }

    /** Leaves a tree grew (not player placed). Modded leaves without the persistent flag count as grown. */
    static boolean naturalLeaves(BlockState s) {
        return s.is(BlockTags.LEAVES) && !(s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT));
    }

    /** Leaves of a natural tree, or the wart caps of a nether fungus. */
    static boolean crown(BlockState s) {
        return naturalLeaves(s) || s.is(BlockTags.WART_BLOCKS);
    }

    /** Flood fill over the 26 neighbours, nearest first, at most {@code max} blocks; never reads an unloaded chunk. */
    static List<BlockPos> connected(Level l, BlockPos start, Predicate<BlockState> what, int max) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty() && out.size() < max) {
            BlockPos c = q.poll();
            out.add(c);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = c.offset(dx, dy, dz);
                        if (seen.add(n) && Farm.loaded(l, n) && what.test(l.getBlockState(n))) q.add(n);
                    }
        }
        return out;
    }

    static void timber(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s) {
        if (!s.is(BlockTags.LOGS) || p.isShiftKeyDown()) return;
        ItemStack axe = p.getMainHandItem();
        if (!axe.canPerformAction(ItemAbilities.AXE_DIG)) return;
        PerkHit h = Farm.hit(p, "timber");
        if (h == null) return;
        fell(p, l, pos, (int) h.value(), (int) Farm.lerp(h, "per_tick", 2, 8), axe);
    }

    /** Queue the rest of a natural tree for felling. Returns the number of logs queued (0 = not a natural tree). */
    static int fell(ServerPlayer p, ServerLevel l, BlockPos pos, int max, int perTick, ItemStack axe) {
        List<BlockPos> logs = connected(l, pos, t -> t.is(BlockTags.LOGS), max + 1);
        boolean natural = false;
        BlockPos top = pos;
        for (BlockPos b : logs) if (b.getY() > top.getY()) top = b;
        // a capped search may stop below the crown: climb the trunk from the highest log found (bounded, loaded only)
        for (int i = 0; i < 64 && Farm.loaded(l, top.above()) && l.getBlockState(top.above()).is(BlockTags.LOGS); i++) top = top.above();
        List<BlockPos> check = new ArrayList<>(logs);
        check.add(top);
        natural = touchesCrown(l, check);
        // bent or branching trunks (Terralith, Twilight Forest) reach their leaves past the capped search: look wider
        if (!natural && logs.size() > max) natural = touchesCrown(l, connected(l, pos, t -> t.is(BlockTags.LOGS), CROWN_SEARCH));
        if (!natural) return 0; // a house, not a tree
        logs.remove(0);
        if (logs.isEmpty()) return 0;
        BlockPos crownAt = top;
        BlockJobs.add(p, BlockJobs.breaking(new ArrayDeque<>(logs), perTick, t -> t.is(BlockTags.LOGS), axe, () -> leafBlower(p, crownAt)));
        return logs.size();
    }

    /** Most logs read when looking for a tree's leaves past a capped fell. */
    static final int CROWN_SEARCH = 512;

    static boolean touchesCrown(Level l, List<BlockPos> logs) {
        for (BlockPos b : logs)
            for (var d : net.minecraft.core.Direction.values()) if (crown(l.getBlockState(b.relative(d)))) return true;
        return false;
    }

    static void leafBlower(ServerPlayer p, BlockPos center) {
        PerkHit h = Farm.hit(p, "leaf_blower");
        if (h == null) return;
        int r = (int) h.value();
        double bonus = Farm.lerp(h, "bonus", 0, 50);
        ServerLevel l = p.serverLevel();
        var it = BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r)).iterator();
        BlockJobs.add(p, BlockJobs.visiting(it, BlockJobs.BUDGET, q -> {
            if (q.distSqr(center) > r * r) return 0;
            BlockState s = l.getBlockState(q);
            if (!naturalLeaves(s)) return 0;
            Block.dropResources(s, l, q);
            if (Farm.roll(l, bonus))
                for (ItemStack it2 : Block.getDrops(s, l, q, null))
                    if (it2.is(ItemTags.SAPLINGS) || it2.is(Items.APPLE)) Block.popResource(l, q, it2);
            l.removeBlock(q, false);
            return BlockJobs.READS_PER_OP; // a removal with drops costs a full op
        }));
    }

    static boolean soft(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.is(Blocks.VINE) || s.is(Blocks.GLOW_LICHEN) || s.is(Blocks.COBWEB) || s.is(Blocks.SHORT_GRASS)
                || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN) || s.is(Blocks.DEAD_BUSH)
                || s.is(BlockTags.FLOWERS) || s.is(Blocks.HANGING_ROOTS);
    }

    static void topiary(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s) {
        ItemStack shears = p.getMainHandItem();
        if (!shears.is(Tags.Items.TOOLS_SHEAR) || !soft(s)) return;
        if (s.is(BlockTags.LEAVES) || s.is(Blocks.VINE) || s.is(Blocks.COBWEB)) Farm.fire(p, "shear_plant", Vec3.atCenterOf(pos));
        PerkHit h = Farm.hit(p, "topiary");
        if (h == null) return;
        int r = (int) h.value();
        double chance = Farm.lerp(h, "chance", 30, 100);
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-r, -r, -r), pos.offset(r, r, r)))
            if (!q.equals(pos) && Farm.loaded(l, q) && soft(l.getBlockState(q)) && Farm.roll(l, chance)) todo.add(q.immutable());
        if (!todo.isEmpty()) BlockJobs.add(p, BlockJobs.breaking(todo, BlockJobs.BUDGET, Trees::soft, shears, null));
    }

    static boolean shroom(BlockState s) {
        return s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.MUSHROOM_STEM);
    }

    static void shroomFeller(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s) {
        if (!shroom(s) || p.isShiftKeyDown()) return;
        PerkHit h = Farm.hit(p, "shroom_feller");
        if (h == null) return;
        List<BlockPos> all = connected(l, pos, Trees::shroom, (int) h.value() + 1);
        all.remove(0);
        if (!all.isEmpty()) BlockJobs.add(p, BlockJobs.breaking(new ArrayDeque<>(all), BlockJobs.BUDGET, Trees::shroom, null, null));
    }

    /** Bone meal on a small mushroom always makes a huge one. Returns true when it handled the event. */
    static boolean forceMushroom(BonemealEvent e, ServerPlayer p, ServerLevel l) {
        if (!(e.getState().getBlock() instanceof MushroomBlock m) || Farm.hit(p, "shroom_feller") == null) return false;
        e.setSuccessful(true);
        if (m.growMushroom(l, e.getPos(), e.getState(), l.random)) l.levelEvent(1505, e.getPos(), 15);
        e.getStack().shrink(1);
        return true;
    }

    /** Drops hook (called from Crops.onDrops): apple trigger and shroom bonus. */
    static void onDrops(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s, List<ItemEntity> drops) {
        if (s.is(BlockTags.LEAVES) && Crops.count(drops, Items.APPLE) > 0) Farm.fire(p, "apple_from_leaves", Vec3.atCenterOf(pos));
        PerkHit h;
        if ((s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.BROWN_MUSHROOM_BLOCK)) && (h = Farm.hit(p, "shroom_feller")) != null) {
            double mult = 1 + Farm.lerp(h, "bonus", 0, 2);
            for (ItemEntity d : drops)
                if (d.getItem().is(Items.RED_MUSHROOM) || d.getItem().is(Items.BROWN_MUSHROOM))
                    d.getItem().setCount(Math.min(64, Farm.round(l, d.getItem().getCount() * mult)));
        }
    }

    static void sapTap(ServerPlayer p, ServerLevel l, BlockState s) {
        PerkHit h = Farm.hit(p, "sap_tap");
        if (h == null || !s.is(BlockTags.LOGS) || !p.getOffhandItem().is(Items.GLASS_BOTTLE) || !Farm.roll(l, h.value())) return;
        p.getOffhandItem().shrink(1);
        Farm.give(p, new ItemStack(Items.HONEY_BOTTLE));
    }

    /** Punch a log: maybe shake loot out of its crown. */
    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock e) {
        if (e.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START || !(e.getLevel() instanceof ServerLevel l)
                || !(e.getEntity() instanceof ServerPlayer p) || !l.getBlockState(e.getPos()).is(BlockTags.LOGS)) return;
        PerkHit h = Farm.hit(p, "apple_shaker");
        if (h == null || !Farm.cooldown(p, "habits_shake", 0.5) || !Farm.roll(l, h.value())) return;
        BlockPos pos = e.getPos();
        for (int i = 0; i < 16; i++) {
            BlockPos q = pos.offset(l.random.nextInt(5) - 2, 1 + l.random.nextInt(6), l.random.nextInt(5) - 2);
            if (!Farm.loaded(l, q)) continue;
            BlockState leaf = l.getBlockState(q);
            if (!naturalLeaves(leaf)) continue;
            List<ItemStack> loot = new ArrayList<>(Farm.loot(l, Farm.table("farming/tree_shake"), Vec3.atCenterOf(q), p));
            if (h.level() >= 5) for (ItemStack it : Block.getDrops(leaf, l, q, null)) if (it.is(ItemTags.SAPLINGS)) loot.add(it);
            if (Farm.roll(l, Farm.lerp(h, "golden", 0, 1))) loot.add(new ItemStack(Items.GOLDEN_APPLE));
            for (ItemStack it : loot) Block.popResource(l, q.below(), it);
            l.levelEvent(2001, q, Block.getId(leaf)); // one leaf-burst effect
            return;
        }
    }

    // ---------------------------------------------------------------- treespeak

    static boolean megaKind(BlockState s) {
        return s.is(Blocks.SPRUCE_SAPLING) || s.is(Blocks.JUNGLE_SAPLING) || s.is(Blocks.DARK_OAK_SAPLING);
    }

    /** The north-west corner of a 2x2 of the same sapling containing pos, or null. */
    static BlockPos square(Level l, BlockPos pos, Block b) {
        for (int dx = -1; dx <= 0; dx++)
            for (int dz = -1; dz <= 0; dz++) {
                BlockPos c = pos.offset(dx, 0, dz);
                if (l.getBlockState(c).is(b) && l.getBlockState(c.east()).is(b) && l.getBlockState(c.south()).is(b) && l.getBlockState(c.south().east()).is(b))
                    return c;
            }
        return null;
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getLevel() instanceof ServerLevel l) || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (!p.getMainHandItem().isEmpty()) return;
        BlockState s = l.getBlockState(e.getPos());
        if (!(s.getBlock() instanceof SaplingBlock sb)) return;
        PerkHit h = Farm.hit(p, "treespeak");
        if (h == null) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        long now = l.getGameTime(), ready = Farm.data(p).getLong("habits_treespeak");
        if (now < ready) {
            long secs = (ready - now + 19) / 20;
            p.displayClientMessage(Component.translatable(secs == 1 ? "habits.farming.cooldown.one" : "habits.farming.cooldown", secs), true);
            return;
        }
        if (speak(l, e.getPos(), s, sb, h.level() >= (int) Farm.lerp(h, "mega_level", 10, 10)))
            Farm.data(p).putLong("habits_treespeak", now + (long) (h.value() * 20));
    }

    /** Grow a sapling now. With {@code mega}, a lone spruce/jungle/dark oak sapling first fills out its 2x2. */
    static boolean speak(ServerLevel l, BlockPos pos, BlockState s, SaplingBlock sb, boolean mega) {
        List<BlockPos> added = new ArrayList<>();
        if (mega && megaKind(s) && square(l, pos, s.getBlock()) == null)
            for (BlockPos q : List.of(pos.east(), pos.south(), pos.south().east()))
                if (l.getBlockState(q).isAir() && s.canSurvive(l, q)) { l.setBlock(q, s.getBlock().defaultBlockState(), Block.UPDATE_ALL); added.add(q); }
        if (added.size() < 3) for (BlockPos q : added) l.removeBlock(q, false);
        for (int i = 0; i < 2 && l.getBlockState(pos).getBlock() instanceof SaplingBlock; i++)
            sb.advanceTree(l, pos, l.getBlockState(pos), l.random);
        boolean grew = !(l.getBlockState(pos).getBlock() instanceof SaplingBlock);
        if (!grew) for (BlockPos q : added) if (l.getBlockState(q).getBlock() instanceof SaplingBlock) l.removeBlock(q, false);
        return grew;
    }

    /** Trigger: a 2x2 sapling square grows; players within 16 blocks get the roll. */
    @SubscribeEvent
    public static void onGrowFeature(BlockGrowFeatureEvent e) {
        if (!(e.getLevel() instanceof ServerLevel l)) return;
        BlockState s = l.getBlockState(e.getPos());
        if (!(s.getBlock() instanceof SaplingBlock) || square(l, e.getPos(), s.getBlock()) == null) return;
        for (ServerPlayer p : l.players())
            if (p.distanceToSqr(Vec3.atCenterOf(e.getPos())) <= 256) Farm.fire(p, "mega_tree", Vec3.atCenterOf(e.getPos()));
    }

    // ---------------------------------------------------------------- forester

    /** Once a second: sapling items lying near you may plant themselves. */
    static void foresterTick(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || (l.getGameTime() + p.getId()) % 20 != 0) return;
        int delay = (int) (Farm.lerp(h, "delay", 10, 1) * 20);
        for (ItemEntity it : l.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(8), i -> i.getItem().is(ItemTags.SAPLINGS))) {
            if (it.getAge() < delay || it.getPersistentData().getBoolean("habits_forester")) continue;
            it.getPersistentData().putBoolean("habits_forester", true);
            if (!(it.getItem().getItem() instanceof BlockItem bi) || !Farm.roll(l, h.value())) continue;
            BlockPos at = it.blockPosition();
            BlockState sap = bi.getBlock().defaultBlockState();
            if (!l.getBlockState(at).canBeReplaced() || !l.getBlockState(at.below()).is(BlockTags.DIRT) || !sap.canSurvive(l, at)) continue;
            if (h.level() >= 10 && sap.hasProperty(SaplingBlock.STAGE)) sap = sap.setValue(SaplingBlock.STAGE, 1);
            l.setBlockAndUpdate(at, sap);
            it.getItem().shrink(1);
            if (it.getItem().isEmpty()) it.discard();
        }
    }
}
