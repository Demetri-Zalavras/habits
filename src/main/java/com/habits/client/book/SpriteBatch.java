package com.habits.client.book;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.GuiSpriteManager;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * GUI sprites drawn exactly like {@code GuiGraphics.blitSprite} (same quads, UVs, order and shader), but every slice and
 * tile of every sprite between {@link #begin} and {@link #end} goes out as one draw. Vanilla issues one draw per slice
 * and tile: about 45 for the binder's leather alone. One GL draw blends its quads in submission order, so the pixels are
 * the same. The pose and shader color must not change inside a batch, and nothing else may use the Tesselator.
 */
final class SpriteBatch {
    private SpriteBatch() {}

    private static BufferBuilder buf;
    private static Matrix4f pose;
    private static ResourceLocation atlas;

    static void begin(GuiGraphics g) {
        RenderSystem.enableBlend(); // what CardRenderer.blit did before each sprite
        pose = g.pose().last().pose();
        buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        atlas = null;
    }

    static void end() {
        MeshData mesh = buf.build();
        buf = null;
        if (mesh == null) return;
        RenderSystem.setShaderTexture(0, atlas);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        BufferUploader.drawWithShader(mesh);
    }

    /** One sprite, scaled by its own GUI scaling (stretch, tile or nine-slice) like {@code blitSprite(sprite, x, y, w, h)}. */
    static void add(ResourceLocation id, int x, int y, int width, int height) {
        GuiSpriteManager sprites = Minecraft.getInstance().getGuiSprites();
        TextureAtlasSprite sp = sprites.getSprite(id);
        if (atlas == null) atlas = sp.atlasLocation();
        else if (!atlas.equals(sp.atlasLocation())) throw new IllegalStateException("one atlas per batch: " + id);
        GuiSpriteScaling sc = sprites.getSpriteScaling(sp);
        if (sc instanceof GuiSpriteScaling.Stretch) stretch(sp, x, y, width, height);
        else if (sc instanceof GuiSpriteScaling.Tile t) tiled(sp, x, y, width, height, 0, 0, t.width(), t.height(), t.width(), t.height());
        else if (sc instanceof GuiSpriteScaling.NineSlice ns) nineSlice(sp, ns, x, y, width, height);
    }

    // ---------------------------------------------------------------- vanilla GuiGraphics (1.21.1), one quad per innerBlit

    private static void quad(int x1, int x2, int y1, int y2, float minU, float maxU, float minV, float maxV) {
        buf.addVertex(pose, (float) x1, (float) y1, 0f).setUv(minU, minV);
        buf.addVertex(pose, (float) x1, (float) y2, 0f).setUv(minU, maxV);
        buf.addVertex(pose, (float) x2, (float) y2, 0f).setUv(maxU, maxV);
        buf.addVertex(pose, (float) x2, (float) y1, 0f).setUv(maxU, minV);
    }

    private static void stretch(TextureAtlasSprite sp, int x, int y, int width, int height) {
        if (width != 0 && height != 0) quad(x, x + width, y, y + height, sp.getU0(), sp.getU1(), sp.getV0(), sp.getV1());
    }

    private static void part(TextureAtlasSprite sp, int texW, int texH, int u, int v, int x, int y, int uWidth, int vHeight) {
        if (uWidth != 0 && vHeight != 0)
            quad(x, x + uWidth, y, y + vHeight, sp.getU((float) u / (float) texW), sp.getU((float) (u + uWidth) / (float) texW),
                    sp.getV((float) v / (float) texH), sp.getV((float) (v + vHeight) / (float) texH));
    }

    private static void tiled(TextureAtlasSprite sp, int x, int y, int width, int height, int u, int v, int spriteW, int spriteH,
                              int nsW, int nsH) {
        if (width <= 0 || height <= 0) return;
        if (spriteW <= 0 || spriteH <= 0)
            throw new IllegalArgumentException("Tiled sprite texture size must be positive, got " + spriteW + "x" + spriteH);
        for (int i = 0; i < width; i += spriteW) {
            int j = Math.min(spriteW, width - i);
            for (int k = 0; k < height; k += spriteH) part(sp, nsW, nsH, u, v, x + i, y + k, j, Math.min(spriteH, height - k));
        }
    }

    private static void nineSlice(TextureAtlasSprite sp, GuiSpriteScaling.NineSlice ns, int x, int y, int width, int height) {
        GuiSpriteScaling.NineSlice.Border b = ns.border();
        int W = ns.width(), H = ns.height();
        int i = Math.min(b.left(), width / 2), j = Math.min(b.right(), width / 2);
        int k = Math.min(b.top(), height / 2), l = Math.min(b.bottom(), height / 2);
        if (width == W && height == H) {
            part(sp, W, H, 0, 0, x, y, width, height);
        } else if (height == H) {
            part(sp, W, H, 0, 0, x, y, i, height);
            tiled(sp, x + i, y, width - j - i, height, i, 0, W - j - i, H, W, H);
            part(sp, W, H, W - j, 0, x + width - j, y, j, height);
        } else if (width == W) {
            part(sp, W, H, 0, 0, x, y, width, k);
            tiled(sp, x, y + k, width, height - l - k, 0, k, W, H - l - k, W, H);
            part(sp, W, H, 0, H - l, x, y + height - l, width, l);
        } else {
            part(sp, W, H, 0, 0, x, y, i, k);
            tiled(sp, x + i, y, width - j - i, k, i, 0, W - j - i, k, W, H);
            part(sp, W, H, W - j, 0, x + width - j, y, j, k);
            part(sp, W, H, 0, H - l, x, y + height - l, i, l);
            tiled(sp, x + i, y + height - l, width - j - i, l, i, H - l, W - j - i, l, W, H);
            part(sp, W, H, W - j, H - l, x + width - j, y + height - l, j, l);
            tiled(sp, x, y + k, i, height - l - k, 0, k, i, H - l - k, W, H);
            tiled(sp, x + i, y + k, width - j - i, height - l - k, i, k, W - j - i, H - l - k, W, H);
            tiled(sp, x + width - j, y + k, i, height - l - k, W - j, k, j, H - l - k, W, H); // vanilla passes i as the width here too
        }
    }
}
