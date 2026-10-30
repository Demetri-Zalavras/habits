package com.habits.perk.farming.mixin;

import com.habits.perk.farming.FarmHooks;
import net.minecraft.world.entity.monster.Phantom;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CAT_PERSON. The same exit vanilla takes when a cat is near: the swoop stops and the phantom circles off. */
@Mixin(targets = "net.minecraft.world.entity.monster.Phantom$PhantomSweepAttackGoal")
public abstract class PhantomSweepAttackGoalMixin {
    @Shadow @Final Phantom this$0;

    @Inject(method = "canContinueToUse", at = @At("RETURN"), cancellable = true)
    private void habits$scaredOfYou(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && FarmHooks.phantomScared(this$0, this$0.getTarget())) cir.setReturnValue(false);
    }
}
