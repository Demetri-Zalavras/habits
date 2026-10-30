package com.habits.perk.farming.mixin;

import com.habits.perk.farming.FarmHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.animal.Panda;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** SNEEZE_HARVEST rate: vanilla's sneeze chance times the skill's multiplier. */
@Mixin(targets = "net.minecraft.world.entity.animal.Panda$PandaSneezeGoal")
public abstract class PandaSneezeGoalMixin {
    @Shadow @Final private Panda panda;

    @ModifyReturnValue(method = "canUse", at = @At("RETURN"))
    private boolean habits$moreSneezes(boolean original) {
        return original || FarmHooks.extraSneeze(panda);
    }
}
