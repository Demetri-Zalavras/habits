package com.habits.perk.building;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** {@code habits:statue}. Placed by a Building player it becomes theirs and their one active statue; right-click cycles
 * the blessing (owner) or shows it (anyone). */
public class StatueBlock extends BaseEntityBlock {
    private static final MapCodec<StatueBlock> CODEC = simpleCodec(StatueBlock::new);
    private static final VoxelShape SHAPE = Shapes.or(Block.box(1, 0, 1, 15, 6, 15), Block.box(4, 6, 5, 12, 16, 11));

    public StatueBlock(Properties p) {
        super(p);
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos pos, CollisionContext c) { return SHAPE; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState s) { return new StatueBlockEntity(pos, s); }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> type) {
        return l.isClientSide ? null : createTickerHelper(type, Statue.BE.get(), StatueBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level l, BlockPos pos, BlockState s, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(l, pos, s, placer, stack);
        placed(l instanceof ServerLevel sl ? sl : null, pos, placer instanceof Player p ? p : null);
    }

    static void placed(@Nullable ServerLevel l, BlockPos pos, @Nullable Player p) {
        if (l == null || !Bld.allowed(p) || !(l.getBlockEntity(pos) instanceof StatueBlockEntity be)) return;
        if (!Statue.sculptor(p)) {
            Bld.say(p, "statue.inert");
            return;
        }
        be.owner = p.getUUID();
        be.boosted = Statue.boosted(p);
        be.setChanged();
        Statue.activate(l, pos, be.owner);
        Statue.show(p, be, "statue.awake");
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState s, Level l, BlockPos pos, Player p, BlockHitResult hit) {
        if (l instanceof ServerLevel sl) use(sl, pos, p);
        return InteractionResult.sidedSuccess(l.isClientSide);
    }

    static void use(ServerLevel l, BlockPos pos, Player p) {
        if (!Bld.allowed(p) || !(l.getBlockEntity(pos) instanceof StatueBlockEntity be)) return;
        boolean mine = p.getUUID().equals(be.owner) && Statue.sculptor(p);
        if (be.owner == null) Bld.say(p, "statue.inert");
        else if (!Statue.active(l, pos, be.owner)) {
            if (!mine) { Bld.say(p, "statue.dormant"); return; }
            be.setBoosted(Statue.boosted(p));
            Statue.activate(l, pos, be.owner);
            Statue.show(p, be, "statue.awake");
        } else {
            if (mine) {
                be.cycle();
                be.setBoosted(Statue.boosted(p));
            }
            Statue.show(p, be, "statue.current");
        }
    }

    @Override
    protected void onRemove(BlockState s, Level l, BlockPos pos, BlockState now, boolean moved) {
        if (!s.is(now.getBlock()) && l instanceof ServerLevel sl && l.getBlockEntity(pos) instanceof StatueBlockEntity be && be.owner != null)
            Statue.removed(sl, pos, be.owner);
        super.onRemove(s, l, pos, now, moved);
    }
}
