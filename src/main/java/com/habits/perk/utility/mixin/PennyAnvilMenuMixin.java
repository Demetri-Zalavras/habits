package com.habits.perk.utility.mixin;

import com.habits.perk.utility.UtilityHooks;
import net.minecraft.world.inventory.AnvilMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * penny_pincher. In 1.21.1 createResult calls cost.set 7 times; ordinal 4 is {@code cost.set(k2)}, the full price.
 * Its int 40 appears 3 times: ordinal 0 is the stack price ({@code i = 40}, left alone), 1 the rename clamp, 2 the
 * "Too Expensive!" check. ItemCombinerMenu.player is public through core's access transformer.
 */
@Mixin(AnvilMenu.class)
public abstract class PennyAnvilMenuMixin {
    @ModifyArg(method = "createResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/DataSlot;set(I)V", ordinal = 4))
    private int habits$pennyCost(int cost) {
        return UtilityHooks.anvilCost(((AnvilMenu) (Object) this).player, cost);
    }

    @ModifyConstant(method = "createResult", constant = @Constant(intValue = 40, ordinal = 2))
    private int habits$pennyCap(int cap) {
        AnvilMenu self = (AnvilMenu) (Object) this;
        return UtilityHooks.anvilCap(self.player, self.getSlot(0).getItem());
    }
}
