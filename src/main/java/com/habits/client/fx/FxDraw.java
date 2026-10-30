package com.habits.client.fx;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/** Immediate-mode HUD quads with our own blend state (additive or normal). Float coordinates, so motion is sub-pixel smooth. */
final class FxDraw {
    private FxDraw() {}

    /** dst += src * srcAlpha. Solid-colour quads fade by alpha; FxTextures quads by {@link #light}. */
    static void additive() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
    }

    /** dst *= 1 - src: FxTextures quads tinted with {@link #grey} darken by their grey level. */
    static void darken() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR);
        RenderSystem.depthMask(false);
    }

    /** Vertex colour for an FxTextures quad under {@link #additive()}: rgb pre-scaled by {@code a}, alpha opaque (no discard). */
    static int light(int rgb, double a) {
        a = Math.max(0, Math.min(1, a));
        int r = (int) ((rgb >> 16 & 255) * a), g = (int) ((rgb >> 8 & 255) * a), b = (int) ((rgb & 255) * a);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    static int grey(double a) {
        return light(0xFFFFFF, a);
    }

    static void normal() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
    }

    static void reset() {
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    static int argb(int rgb, double alpha) {
        return (int) (Math.max(0, Math.min(1, alpha)) * 255) << 24 | rgb & 0xFFFFFF;
    }

    /** Textured quad, tinted; u0..u1 / v0..v1 per edge. */
    static void tex(Matrix4f m, ResourceLocation tex, float x0, float y0, float x1, float y1, int argb) {
        texUv(m, tex, x0, y0, x1, y1, 0, 0, 1, 0, 1, 1, 0, 1, argb);
    }

    /** Textured quad with an explicit uv per corner (top-left, top-right, bottom-right, bottom-left). */
    static void texUv(Matrix4f m, ResourceLocation tex, float x0, float y0, float x1, float y1,
                      float ua, float va, float ub, float vb, float uc, float vc, float ud, float vd, int argb) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, tex);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        b.addVertex(m, x0, y0, 0).setUv(ua, va).setColor(argb);
        b.addVertex(m, x0, y1, 0).setUv(ud, vd).setColor(argb);
        b.addVertex(m, x1, y1, 0).setUv(uc, vc).setColor(argb);
        b.addVertex(m, x1, y0, 0).setUv(ub, vb).setColor(argb);
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    /** A render target's colour texture (GL id, origin bottom-left, hence the flipped v). */
    static void texId(Matrix4f m, int id, float x0, float y0, float x1, float y1, int argb) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, id);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        b.addVertex(m, x0, y0, 0).setUv(0, 1).setColor(argb);
        b.addVertex(m, x0, y1, 0).setUv(0, 0).setColor(argb);
        b.addVertex(m, x1, y1, 0).setUv(1, 0).setColor(argb);
        b.addVertex(m, x1, y0, 0).setUv(1, 1).setColor(argb);
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    /** Tiles a texture region (u0..u1, v0..v1 = one tile of {@code size} px) over the rect, cropping the edge tiles. */
    static void tile(Matrix4f m, ResourceLocation tex, float x0, float y0, float x1, float y1,
                     float u0, float v0, float u1, float v1, int size, int argb) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, tex);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (float y = y0; y < y1; y += size)
            for (float x = x0; x < x1; x += size) {
                float xe = Math.min(x + size, x1), ye = Math.min(y + size, y1);
                float ue = u0 + (u1 - u0) * (xe - x) / size, ve = v0 + (v1 - v0) * (ye - y) / size;
                b.addVertex(m, x, y, 0).setUv(u0, v0).setColor(argb);
                b.addVertex(m, x, ye, 0).setUv(u0, ve).setColor(argb);
                b.addVertex(m, xe, ye, 0).setUv(ue, ve).setColor(argb);
                b.addVertex(m, xe, y, 0).setUv(ue, v0).setColor(argb);
            }
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    /** Solid-colour builder for rings and particles; finish with {@link #end}. */
    static BufferBuilder colored(VertexFormat.Mode mode) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        return Tesselator.getInstance().begin(mode, DefaultVertexFormat.POSITION_COLOR);
    }

    static void end(BufferBuilder b) {
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }
}
