package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** TRIAL_TAKER: from vault_level, trial and ominous vaults eject one extra item for the owner. Server only. */
@Mixin(VaultBlockEntity.Server.class)
public abstract class VaultServerMixin {
    @ModifyReturnValue(method = "resolveItemsToEject", at = @At("RETURN"))
    private static List<ItemStack> habits$trialTaker(List<ItemStack> vanilla, @Local(argsOnly = true) ServerLevel level,
                                                     @Local(argsOnly = true) VaultConfig config, @Local(argsOnly = true) BlockPos pos,
                                                     @Local(argsOnly = true) Player player) {
        return MoveHooks.vaultLoot(level, config, pos, player, vanilla);
    }
}
