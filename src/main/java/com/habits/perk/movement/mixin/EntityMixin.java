package com.habits.perk.movement.mixin;

import com.habits.perk.movement.MoveHooks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** MUD_WADER (webs, berry bushes and powder snow slow you less) and TOMB_RAIDER (plates and tripwires ignore you). */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @ModifyVariable(method = "makeStuckInBlock", at = @At("HEAD"), argsOnly = true)
    private Vec3 habits$mudWader(Vec3 multiplier) {
        return MoveHooks.stuck((Entity) (Object) this, multiplier);
    }

    @ModifyReturnValue(method = "isIgnoringBlockTriggers", at = @At("RETURN"))
    private boolean habits$trapSense(boolean vanilla) {
        return MoveHooks.ignoresTraps((Entity) (Object) this, vanilla);
    }
}
