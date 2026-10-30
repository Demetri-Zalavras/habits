package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Piston Power: pistons you placed push longer rows. The limit is read once per resolver, then replaces every use
 * of the vanilla 12 in addBlockLine. */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {
    @Unique private int habits$limit;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void habits$readLimit(Level level, BlockPos pistonPos, Direction pistonDirection, boolean extending, CallbackInfo ci) {
        habits$limit = MiningHooks.pistonLimit(level, pistonPos, extending, PistonStructureResolver.MAX_PUSH_DEPTH);
    }

    @ModifyExpressionValue(method = "addBlockLine", at = @At(value = "CONSTANT", args = "intValue=12"))
    private int habits$push(int vanilla) {
        return habits$limit > 0 ? habits$limit : vanilla;
    }
}
