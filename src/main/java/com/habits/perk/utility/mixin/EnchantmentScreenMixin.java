package com.habits.perk.utility.mixin;

import com.habits.perk.utility.client.UtilityClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** bookworm: the offer tooltip (vanilla's only renderComponentTooltip in render) lists more of the offer's enchants. */
@Mixin(EnchantmentScreen.class)
public abstract class EnchantmentScreenMixin extends AbstractContainerScreen<EnchantmentMenu> {
    private EnchantmentScreenMixin(EnchantmentMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;renderComponentTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V"))
    private void habits$bookwormHints(GuiGraphics g, Font font, List<Component> lines, int x, int y, Operation<Void> op) {
        // vanilla hovers row j at topPos + 14 + 19 * j, 17 high
        UtilityClient.bookwormHints(menu, lines, Math.floorDiv(y - topPos - 14, 19));
        op.call(g, font, lines, x, y);
    }
}
