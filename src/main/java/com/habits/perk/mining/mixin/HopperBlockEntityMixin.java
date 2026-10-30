package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.BooleanSupplier;

/** Hopper Hand, for hoppers you placed: shorter transfer cooldown, Lv10 moves a stack per transfer (repeated vanilla
 * single-item moves, so every filter and remainder path stays vanilla: no dupes), and a name filter. The ownership
 * read only happens after a move succeeded, or for a named hopper. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @ModifyArg(method = "tryMoveItems", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;setCooldown(I)V"))
    private static int habits$cooldown(int cooldown, @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos) {
        return MiningHooks.hopperCooldown(level, pos, cooldown);
    }

    @WrapOperation(method = "tryMoveItems", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;ejectItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)Z"))
    private static boolean habits$ejectStack(Level level, BlockPos pos, HopperBlockEntity be, Operation<Boolean> original) {
        if (!original.call(level, pos, be)) return false;
        int n = MiningHooks.hopperStack(level, pos);
        for (int i = 1; i < n && !be.isEmpty() && original.call(level, pos, be); i++) {}
        return true;
    }

    @WrapOperation(method = "tryMoveItems", at = @At(value = "INVOKE", target = "Ljava/util/function/BooleanSupplier;getAsBoolean()Z"))
    private static boolean habits$pullStack(BooleanSupplier pull, Operation<Boolean> original, @Local(argsOnly = true) Level level,
                                            @Local(argsOnly = true) BlockPos pos) {
        if (!original.call(pull)) return false;
        int n = MiningHooks.hopperStack(level, pos);
        for (int i = 1; i < n && original.call(pull); i++) {}
        return true;
    }

    /** Container.canPlaceItem (a default method HopperBlockEntity doesn't override): every insert path checks it. */
    public boolean canPlaceItem(int slot, ItemStack stack) {
        HopperBlockEntity self = (HopperBlockEntity) (Object) this;
        return !self.hasCustomName() || self.getLevel() == null
                || !MiningHooks.hopperRejects(self.getLevel(), self.getBlockPos(), self.getCustomName(), stack);
    }
}
