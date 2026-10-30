package com.habits.perk.mining;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;

import java.util.ArrayList;
import java.util.List;

/** Dispenser Tinkerer: only dispensers placed by a skilled player behave differently (positions in
 * {@link MiningData}); every other dispenser keeps vanilla behavior. Hopper Hand lives in the hopper mixin. */
public final class DispenserPerks {
    private DispenserPerks() {}

    static void setup(FMLCommonSetupEvent e) {
        e.enqueueWork(() -> {
            for (Item item : BuiltInRegistries.ITEM) {
                DispenseItemBehavior old = DispenserBlock.DISPENSER_REGISTRY.get(item);
                if (old == null || old.getClass() != DefaultDispenseItemBehavior.class) continue;
                ItemStack probe = new ItemStack(item);
                boolean tool = probe.canPerformAction(ItemAbilities.PICKAXE_DIG) || probe.canPerformAction(ItemAbilities.AXE_DIG)
                        || probe.canPerformAction(ItemAbilities.SHOVEL_DIG);
                if (!(item instanceof BlockItem) && !tool) continue;
                DispenserBlock.registerBehavior(item, (src, stack) -> {
                    int lvl = MiningData.get(src.level()).dispensers.get(src.pos().asLong());
                    ItemStack out = lvl > 0 && src.state().is(Blocks.DISPENSER) ? tinker(src, stack, lvl, tool) : null;
                    return out != null ? out : old.dispense(src, stack);
                });
            }
        });
    }

    /** Returns the remaining stack if the owned dispenser did something, null to fall back to vanilla. */
    static ItemStack tinker(BlockSource src, ItemStack stack, int lvl, boolean tool) {
        ServerLevel level = src.level();
        Direction facing = src.state().getValue(DispenserBlock.FACING);
        BlockPos target = src.pos().relative(facing);
        if (!level.isLoaded(target)) return null;
        var fake = FakePlayerFactory.getMinecraft(level);
        BlockState at = level.getBlockState(target);
        if (stack.getItem() instanceof BlockItem bi && !tool) {
            BlockState place = bi.getBlock().defaultBlockState();
            if (!at.canBeReplaced() || !place.canSurvive(level, target)) return null;
            BlockSnapshot snap = BlockSnapshot.create(level.dimension(), level, target);
            if (!level.setBlock(target, place, 3)) return null;
            if (EventHooks.onBlockPlace(fake, snap, facing)) { snap.restore(); return stack; }
            stack.shrink(1);
            return stack;
        }
        if (tool && lvl >= 5 && !at.isAir() && at.getDestroySpeed(level, target) >= 0 && at.getFluidState().isEmpty()) {
            if (CommonHooks.fireBlockBreak(level, net.minecraft.world.level.GameType.SURVIVAL, fake, target, at).isCanceled()) return stack;
            List<ItemStack> drops = !at.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(at)
                    ? Block.getDrops(at, level, target, level.getBlockEntity(target), null, stack) : new ArrayList<>();
            level.destroyBlock(target, false);
            for (ItemStack d : drops) Block.popResource(level, target, d);
            stack.hurtAndBreak(1, level, null, i -> {});
            return stack;
        }
        return null;
    }
}
