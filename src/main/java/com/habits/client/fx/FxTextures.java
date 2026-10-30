package com.habits.client.fx;

import com.habits.Habits;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.Random;

/**
 * Smooth gradients generated at runtime. The shape is in RGB (grey level) with alpha 255, because the vanilla
 * position_tex_color shader discards alpha below 0.1, which would cut a soft falloff into a hard disc. Draw them with
 * {@link FxDraw#additive()} or {@link FxDraw#darken()}. Each texel gets +-0.5 LSB of noise (dither) against 8-bit bands.
 */
final class FxTextures {
    static final ResourceLocation GLOW = Habits.id("fx/gen/glow");
    static final ResourceLocation VIGNETTE = Habits.id("fx/gen/vignette");
    /** 1D across u: transparent, peak at 0.5, transparent. For the diagonal light sweep. */
    static final ResourceLocation BAND = Habits.id("fx/gen/band");

    private static boolean made;

    private FxTextures() {}

    static void ensure() {
        if (made) return;
        made = true;
        Random r = new Random(7);
        // radial glow: soft core, long smooth tail fading to zero at the rim
        make(GLOW, 256, 256, r, (x, y) -> {
            double d = Math.min(1, Math.hypot(x - .5, y - .5) * 2);
            return Math.pow(1 - d, 1.6) * (0.7 + 0.3 * Math.exp(-d * d * 6));
        });
        // darkens the edges: 0 in the middle, full at the corners
        make(VIGNETTE, 256, 256, r, (x, y) -> {
            double d = Math.min(1, Math.hypot(x - .5, y - .5) / Math.hypot(.5, .5) / .85);
            return d * d * (3 - 2 * d);
        });
        make(BAND, 128, 1, r, (x, y) -> {
            double d = Math.min(1, Math.abs(x - .5) / .5);
            double s = 1 - d;
            return s * s * (3 - 2 * s);
        });
    }

    private interface Shape { double alpha(double x, double y); }

    private static void make(ResourceLocation id, int w, int h, Random r, Shape s) {
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double a = s.alpha((x + .5) / w, (y + .5) / h) * 255 + r.nextDouble() - .5;
                int v = Math.max(0, Math.min(255, (int) Math.round(a)));
                img.setPixelRGBA(x, y, 0xFF000000 | v << 16 | v << 8 | v);
            }
        DynamicTexture t = new DynamicTexture(img);
        t.setFilter(true, false);
        t.bind();
        RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        RenderSystem.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        Minecraft.getInstance().getTextureManager().register(id, t);
    }
}
