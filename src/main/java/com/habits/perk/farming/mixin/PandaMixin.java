package com.habits.perk.farming.mixin;

import com.habits.perk.farming.FarmHooks;
import net.minecraft.world.entity.animal.Panda;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SNEEZE_HARVEST drops, on every finished sneeze (vanilla's own slimeball roll still happens). */
@Mixin(Panda.class)
public abstract class PandaMixin {
    @Inject(method = "afterSneeze", at = @At("TAIL"))
    private void habits$goodies(CallbackInfo ci) {
        FarmHooks.sneezed((Panda) (Object) this);
    }
}
