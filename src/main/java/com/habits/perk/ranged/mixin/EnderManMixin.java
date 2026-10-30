package com.habits.perk.ranged.mixin;

import com.habits.perk.ranged.Impacts;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Breeze Breaker: an enderman treats our breaking projectile as a plain hit, so it takes the damage instead of teleporting. */
@Mixin(EnderMan.class)
public abstract class EnderManMixin {
    @WrapOperation(method = "hurt", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/damagesource/DamageSource;is(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean habits$noDodge(DamageSource src, TagKey<DamageType> tag, Operation<Boolean> op) {
        return op.call(src, tag) && !Impacts.breaks(src.getDirectEntity());
    }
}
