package com.habits.perk.ranged.mixin;

import com.habits.perk.ranged.Impacts;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Breeze Breaker: a breeze doesn't bounce our breaking projectile back; the hit lands like on any other mob. */
@Mixin(Breeze.class)
public abstract class BreezeMixin {
    @Inject(method = "deflection", at = @At("RETURN"), cancellable = true)
    private void habits$noDeflect(Projectile pr, CallbackInfoReturnable<ProjectileDeflection> cir) {
        if (cir.getReturnValue() != ProjectileDeflection.NONE && Impacts.breaks(pr)) cir.setReturnValue(ProjectileDeflection.NONE);
    }
}
