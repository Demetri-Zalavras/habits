package com.habits.perk.mining.mixin;

import com.habits.perk.mining.MiningHooks;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Muffled Tools: sculk listeners within R don't hear your mining and building. */
@Mixin(VibrationSystem.Listener.class)
public abstract class VibrationListenerMixin {
    @Inject(method = "handleGameEvent", at = @At("HEAD"), cancellable = true)
    private void habits$muffle(ServerLevel level, Holder<GameEvent> event, GameEvent.Context context, Vec3 pos, CallbackInfoReturnable<Boolean> cir) {
        if (context.sourceEntity() != null && MiningHooks.muffled(context.sourceEntity(), event, pos, (GameEventListener) (Object) this, level))
            cir.setReturnValue(false);
    }
}
