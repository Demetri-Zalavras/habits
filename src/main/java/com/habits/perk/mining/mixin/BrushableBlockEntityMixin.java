package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Archaeologist: fewer brushes to finish and a chance to roll the loot table twice. Also fires the brush_done card
 * trigger for everyone. */
@Mixin(BrushableBlockEntity.class)
public abstract class BrushableBlockEntityMixin {
    @Shadow private ResourceKey<LootTable> lootTable;
    @Shadow private Direction hitDirection;
    /** The table the item came from (vanilla clears its own field when it unpacks on the first brush). */
    @Unique private ResourceKey<LootTable> habits$table;

    @ModifyExpressionValue(method = "brush", at = @At(value = "CONSTANT", args = "intValue=10"))
    private int habits$brushes(int vanilla, @Local(argsOnly = true) Player player) {
        return MiningHooks.brushesNeeded(player, vanilla);
    }

    @Inject(method = "unpackLootTable", at = @At("HEAD"))
    private void habits$rememberTable(Player player, CallbackInfo ci) {
        if (lootTable != null) habits$table = lootTable;
    }

    @Inject(method = "brushingCompleted", at = @At("HEAD"))
    private void habits$done(Player player, CallbackInfo ci) {
        BlockEntity be = (BlockEntity) (Object) this;
        MiningHooks.brushDone(player, be.getLevel(), be.getBlockPos(), habits$table, hitDirection == null ? Direction.UP : hitDirection);
    }
}
