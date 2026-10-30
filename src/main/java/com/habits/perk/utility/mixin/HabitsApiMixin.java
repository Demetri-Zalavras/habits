package com.habits.perk.utility.mixin;

import com.habits.core.HabitsApi;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.perk.utility.UtilityHooks;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** lucky_charm: scales core's drop-rate multiplier (the first double local, {@code mult}) for the triggering player.
 * Runs once per trigger that has candidate skills; core returns before the store when none do. */
@Mixin(value = HabitsApi.class, remap = false)
public abstract class HabitsApiMixin {
    @ModifyVariable(method = "onTrigger", at = @At("STORE"), ordinal = 0)
    private static double habits$luckyCharm(double mult, ServerPlayer player, TriggerType type, TriggerContext ctx) {
        return UtilityHooks.dropRate(player, mult);
    }
}
