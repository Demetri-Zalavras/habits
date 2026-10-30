package com.habits.perk.building;

import com.habits.core.HabitsApi;
import com.habits.perk.Later;
import com.habits.perk.mining.Work;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Breaking and the odd ones out: dismantle, demolition hammer, slab splitter, moving day, brush clearer, offcuts,
 * sawmill, cozy home. */
public final class Breaking {
    private Breaking() {}

    static final TagKey<Block> CONCRETES = TagKey.create(Registries.BLOCK, ResourceLocation.parse("c:concretes"));

    /** Blocks a builder knocks down with the hammer: building pieces, never ores or block entities. */
    static boolean hammerable(BlockState s) {
        return !s.hasBlockEntity() && !s.is(Tags.Blocks.ORES) && (s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(BlockTags.WALLS)
                || s.is(BlockTags.FENCES) || s.is(BlockTags.PLANKS) || s.is(BlockTags.WOOL) || s.is(BlockTags.TERRACOTTA)
                || s.is(BlockTags.STONE_BRICKS) || s.is(CONCRETES) || s.is(Tags.Blocks.GLASS_BLOCKS) || s.is(Blocks.BRICKS)
                || s.is(Blocks.MUD_BRICKS) || s.is(Blocks.NETHER_BRICKS) || s.is(Blocks.DEEPSLATE_BRICKS) || s.is(Blocks.DEEPSLATE_TILES)
                || s.is(Blocks.POLISHED_BLACKSTONE_BRICKS) || s.is(Blocks.QUARTZ_BRICKS) || s.is(Blocks.END_STONE_BRICKS)
                || s.is(Blocks.PRISMARINE_BRICKS));
    }

    static boolean plant(BlockState s) {
        return !s.is(BlockTags.LEAVES) && (s.is(BlockTags.REPLACEABLE_BY_TREES) || s.is(BlockTags.SMALL_FLOWERS) || s.is(BlockTags.TALL_FLOWERS));
    }

    /** Dismantle: your recent placements break on the first hit. */
    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock e) {
        if (e.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START || Work.busy) return;
        if (!(e.getEntity() instanceof ServerPlayer p) || p.getAbilities().instabuild || Bld.hit(p, "dismantle") == null) return;
        LinkedHashSet<Long> own = Placing.OWN.get(p.getUUID());
        BlockPos pos = e.getPos();
        if (own == null || !own.remove(pos.asLong())) return;
        BlockState s = p.level().getBlockState(pos);
        if (s.isAir() || s.getDestroySpeed(p.level(), pos) < 0) return;
        if (Bld.breakAs(p, pos)) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (Work.busy || e.isCanceled() || !(e.getPlayer() instanceof ServerPlayer p) || !Bld.allowed(p)) return;
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        BlockState s = e.getState();
        HabitsApi.PerkHit h;
        LinkedHashSet<Long> own = Placing.OWN.get(p.getUUID());
        if (own != null) own.remove(pos.asLong());
        if (p.isShiftKeyDown() && s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.DOUBLE
                && (h = Bld.hit(p, "slab_splitter")) != null) {
            e.setCanceled(true);
            split(p, level, pos, s, h);
            return;
        }
        if (p.isShiftKeyDown() && p.getMainHandItem().isEmpty() && (h = Bld.hit(p, "moving_day")) != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if ((be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) && carry(p, level, pos, s, be, h)) {
                e.setCanceled(true);
                return;
            }
        }
        if (p.isShiftKeyDown() && hammerable(s) && (h = Bld.hit(p, "demolition_hammer")) != null) {
            HitResult hr = p.pick(p.blockInteractionRange() + 1, 1, false);
            Direction face = hr instanceof BlockHitResult b && b.getBlockPos().equals(pos) ? b.getDirection() : Direction.getNearest(p.getLookAngle()).getOpposite();
            HabitsApi.PerkHit hh = h;
            Later.run(level, 0, () -> hammer(p, level, pos, s, face, hh));
        }
        if (plant(s) && (h = Bld.hit(p, "brush_clearer")) != null) {
            int r = (int) h.value();
            List<Runnable> steps = new ArrayList<>();
            for (BlockPos q : BlockPos.betweenClosed(pos.offset(-r, -1, -r), pos.offset(r, 1, r))) {
                BlockPos at = q.immutable();
                if (!at.equals(pos)) steps.add(() -> { if (Bld.allowed(p) && plant(level.getBlockState(at))) Bld.breakAs(p, at); });
            }
            Bld.queue(p, steps, (int) Bld.lerp(h, "per_tick", 16, 16));
        }
    }

    static void split(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, HabitsApi.PerkHit h) {
        HitResult hr = p.pick(p.blockInteractionRange() + 1, 1, false);
        boolean top = hr instanceof BlockHitResult b && b.getBlockPos().equals(pos) ? b.getLocation().y - pos.getY() >= 0.5 : p.getEyeY() > pos.getY() + 0.5;
        if (!Bld.changeAs(p, pos, s.setValue(SlabBlock.TYPE, top ? SlabType.BOTTOM : SlabType.TOP))) return;
        if (!p.getAbilities().instabuild) Block.popResource(level, pos, new ItemStack(s.getBlock().asItem()));
        if (!Bld.roll(p, h.value())) p.getMainHandItem().hurtAndBreak(1, p, EquipmentSlot.MAINHAND);
    }

    static boolean carry(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, BlockEntity be, HabitsApi.PerkHit h) {
        net.minecraft.world.Container c = (net.minecraft.world.Container) be;
        int stacks = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) stacks++;
        if (stacks > (int) h.value()) { Bld.say(p, "too_heavy", stacks, (int) h.value()); return false; }
        ItemStack item = new ItemStack(s.getBlock().asItem());
        BlockItem.setBlockEntityData(item, be.getType(), be.saveCustomOnly(level.registryAccess()));
        item.applyComponents(be.collectComponents());
        c.clearContent(); // emptied first: removing the block must not spill its items
        if (!Bld.changeAs(p, pos, Blocks.AIR.defaultBlockState())) return false;
        Bld.give(p, item);
        return true;
    }

    static void hammer(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, Direction face, HabitsApi.PerkHit h) {
        int max = (int) h.value(), r = max > 8 ? 2 : 1;
        List<BlockPos> spots = new ArrayList<>();
        for (int a = -r; a <= r; a++)
            for (int b = -r; b <= r; b++) {
                if (a == 0 && b == 0) continue;
                spots.add(switch (face.getAxis()) {
                    case X -> pos.offset(0, a, b);
                    case Y -> pos.offset(a, 0, b);
                    case Z -> pos.offset(a, b, 0);
                });
            }
        spots.sort(java.util.Comparator.comparingInt(q -> q.distManhattan(pos)));
        int n = 0;
        for (BlockPos q : spots) {
            if (n >= max || !Bld.allowed(p)) break;
            if (level.getBlockState(q).getBlock() == s.getBlock() && Bld.breakAs(p, q)) n++;
        }
    }

    // ---------------------------------------------------------------- crafting

    @SubscribeEvent
    public static void onCraft(PlayerEvent.ItemCraftedEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Bld.allowed(p)) return;
        ItemStack out = e.getCrafting();
        HabitsApi.PerkHit h;
        if ((out.is(ItemTags.STAIRS) || out.is(ItemTags.SLABS) || out.is(ItemTags.WALLS) || out.is(ItemTags.FENCES) || out.is(Tags.Items.GLASS_PANES))
                && (h = Bld.hit(p, "offcuts")) != null && Bld.roll(p, h.value()))
            Bld.give(p, out.copyWithCount((out.getCount() + 1) / 2));
        if (out.is(ItemTags.PLANKS) && (h = Bld.hit(p, "sawmill")) != null && Bld.roll(p, h.value()))
            Bld.give(p, out.copyWithCount((int) Bld.lerp(h, "bonus", 2, 2)));
    }

    // ---------------------------------------------------------------- cozy home

    static boolean decor(BlockState s) {
        return s.is(BlockTags.CANDLES) || s.is(BlockTags.FLOWER_POTS) || s.is(BlockTags.BANNERS) || s.is(BlockTags.WOOL_CARPETS)
                || s.is(Blocks.BOOKSHELF) || s.is(Blocks.CHISELED_BOOKSHELF) || s.is(Blocks.LANTERN) || s.is(Blocks.SOUL_LANTERN)
                || s.is(Blocks.CAMPFIRE) || s.is(BlockTags.CAMPFIRES) || s.is(Blocks.DECORATED_POT) || s.is(Blocks.JUKEBOX);
    }

    /** Decor pieces around a spot: blocks plus paintings and item frames. */
    static int countDecor(ServerLevel level, BlockPos at, int r) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-r, -r, -r), at.offset(r, r, r))) if (decor(level.getBlockState(q))) n++;
        AABB box = new AABB(at).inflate(r);
        n += level.getEntitiesOfClass(Painting.class, box).size() + level.getEntitiesOfClass(ItemFrame.class, box).size();
        return n;
    }

    @SubscribeEvent
    public static void onWake(PlayerWakeUpEvent e) {
        if (e.wakeImmediately() || !(e.getEntity() instanceof ServerPlayer p) || !Bld.allowed(p) || !p.level().isDay()) return;
        HabitsApi.PerkHit h = Bld.hit(p, "cozy_home");
        if (h == null) return;
        BlockPos bed = p.getSleepingPos().orElse(p.blockPosition());
        if (countDecor(p.serverLevel(), bed, (int) Bld.lerp(h, "radius", 6, 6)) < (int) Bld.lerp(h, "decor", 6, 6)) return;
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, (int) (h.value() * 20), 0, false, true, true));
        Bld.say(p, "cozy");
    }
}
