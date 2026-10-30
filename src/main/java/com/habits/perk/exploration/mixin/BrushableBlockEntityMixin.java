package com.habits.perk.exploration.mixin;

import com.habits.perk.exploration.Finds;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exploration archaeology perks (sherd seeker, patient hands, dig sense) when a brush finishes a block. */
@Mixin(BrushableBlockEntity.class)
public abstract class BrushableBlockEntityMixin {
    @Inject(method = "brushingCompleted", at = @At("HEAD"))
    private void habits$exploreBrushed(Player player, CallbackInfo ci) {
        BlockEntity be = (BlockEntity) (Object) this;
        Finds.brushed(player, be.getLevel(), be.getBlockPos());
    }
}
