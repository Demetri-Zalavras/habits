package com.habits.client.fx;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

/**
 * One card face painted flat into an offscreen target at screen resolution, so the 3D spin turns one textured quad
 * instead of the card's own layers (whose items and depth offsets fall apart under rotation and perspective). Painted
 * once per flip and face; only foil cards repaint every frame (the foil moves).
 */
final class CardTarget {
    static final CardTarget FRONT = new CardTarget(), BACK = new CardTarget();
    private static final Matrix4f ID = new Matrix4f(), PROJ = new Matrix4f(), SAVED = new Matrix4f();

    private static GuiGraphics gfx;
    private TextureTarget fbo;
    private Object key;

    private CardTarget() {}

    interface Painter { void paint(GuiGraphics g); }

    /** Texture id of this face for {@code key} (compared with equals); repaints only when the key or size changed, or {@code animated}. */
    int get(Minecraft mc, Object key, boolean animated, int cw, int ch, Painter painter) {
        double gs = mc.getWindow().getGuiScale();
        int pw = (int) Math.ceil(cw * gs), ph = (int) Math.ceil(ch * gs);
        if (fbo == null) {
            fbo = new TextureTarget(pw, ph, true, Minecraft.ON_OSX);
            fbo.setFilterMode(GL11.GL_LINEAR);
            this.key = null;
        } else if (fbo.width != pw || fbo.height != ph) {
            fbo.resize(pw, ph, Minecraft.ON_OSX);
            fbo.setFilterMode(GL11.GL_LINEAR);
            this.key = null;
        }
        if (animated || !key.equals(this.key)) {
            this.key = key;
            long t0 = System.nanoTime();
            paint(mc, pw, ph, gs, cw, ch, painter);
            if (FxShots.Perf.on) FxShots.Perf.paint(System.nanoTime() - t0);
        }
        return fbo.getColorTextureId();
    }

    void invalidate() {
        key = null;
    }

    private void paint(Minecraft mc, int pw, int ph, double gs, int cw, int ch, Painter painter) {
        if (gfx == null) gfx = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        fbo.setClearColor(0, 0, 0, 0);
        fbo.clear(Minecraft.ON_OSX);
        fbo.bindWrite(true);
        SAVED.set(RenderSystem.getProjectionMatrix());
        VertexSorting sort = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(PROJ.setOrtho(0, (float) (pw / gs), (float) (ph / gs), 0, 1000,
                net.neoforged.neoforge.client.ClientHooks.getGuiFarPlane()), VertexSorting.ORTHOGRAPHIC_Z);
        // colour blends as usual, alpha keeps the max of all layers: vanilla blits replace dst alpha, which would leave
        // the opaque card see-through wherever a translucent overlay was drawn last
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_MAX);
        painter.paint(gfx);
        gfx.flush();
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        // some layers (the art window) still leave alpha under 1, so the world showed through the card: double the alpha
        // four times (16x, clamped), leaving colour alone; fully transparent corners stay at 0
        RenderSystem.colorMask(false, false, false, true);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.DST_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.disableDepthTest();
        for (int i = 0; i < 4; i++) {
            BufferBuilder b = FxDraw.colored(VertexFormat.Mode.QUADS);
            b.addVertex(ID, 0, 0, 0).setColor(0xFFFFFFFF);
            b.addVertex(ID, 0, ch, 0).setColor(0xFFFFFFFF);
            b.addVertex(ID, cw, ch, 0).setColor(0xFFFFFFFF);
            b.addVertex(ID, cw, 0, 0).setColor(0xFFFFFFFF);
            FxDraw.end(b);
        }
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.setProjectionMatrix(SAVED, sort);
        mc.getMainRenderTarget().bindWrite(true);
    }
}
