package com.habits.perk.farming.mixin;

import com.habits.perk.farming.Soil;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FarmBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** SPRINKLER. Runs on a farmland random tick only when vanilla found no water; bails when no sprinkler is tracked. */
@Mixin(FarmBlock.class)
public abstract class FarmBlockMixin {
    @ModifyReturnValue(method = "isNearWater", at = @At("RETURN"))
    private static boolean habits$sprinkler(boolean wet, LevelReader level, BlockPos pos) {
        return wet || Soil.sprinkled(level, pos);
    }
}
