package com.habits.perk.utility.mixin;

import com.habits.perk.utility.UtilityHooks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.item.component.BundleContents;
import org.apache.commons.lang3.math.Fraction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** bundle_up: free room is {@code Fraction.ONE - weight}; ONE becomes the player's capacity during a bundle click. */
@Mixin(BundleContents.Mutable.class)
public abstract class BundleContentsMixin {
    @ModifyExpressionValue(method = "getMaxAmountToAdd",
            at = @At(value = "FIELD", target = "Lorg/apache/commons/lang3/math/Fraction;ONE:Lorg/apache/commons/lang3/math/Fraction;"))
    private Fraction habits$bundleCap(Fraction one) {
        return UtilityHooks.bundleCap(one);
    }
}
