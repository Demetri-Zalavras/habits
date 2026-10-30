package com.habits.perk.farming.mixin;

import com.habits.perk.farming.FarmHooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** LAVA_ANGLER. Both water checks in tick (float and bob, then "sink if not water") accept lava, so vanilla's own bite logic runs. */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean habits$lavaIsWater(FluidState state, TagKey<Fluid> tag, Operation<Boolean> original) {
        return original.call(state, tag) || tag == FluidTags.WATER && FarmHooks.lavaFloats((FishingHook) (Object) this, state);
    }
}
