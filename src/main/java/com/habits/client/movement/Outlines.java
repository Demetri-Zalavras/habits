package com.habits.client.movement;

import com.habits.Habits;
import com.habits.perk.movement.MovePayloads;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Through-wall outlines: block lists from the server (cached in a static vertex buffer, rebuilt only when a list
 * changes or expires) and glowing entities (a handful of moving boxes, re-uploaded per frame into one reused buffer).
 */
@EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT)
public final class Outlines {
    private Outlines() {}

    static final int KINDS = 5;
    static final float[][] RGB = {{1f, 0.85f, 0.2f}, {1f, 0.25f, 0.25f}, {0.95f, 0.8f, 0.5f}, {0.75f, 0.4f, 1f}, {1f, 0.3f, 1f}};
    @SuppressWarnings("unchecked")
    private static final List<BlockPos>[] LISTS = new List[KINDS];
    private static final long[] UNTIL = new long[KINDS];
    private static final Int2LongOpenHashMap GLOW = new Int2LongOpenHashMap();
    private static final PoseStack IDENTITY = new PoseStack();
    private static final Matrix4f MV = new Matrix4f();
    private static VertexBuffer blocksVbo, glowVbo;
    private static boolean dirty, hasBlocks;
    private static BlockPos origin = BlockPos.ZERO;
    private static long now;

    static void blocks(MovePayloads.Highlights h) {
        int k = h.kind();
        if (k < 0 || k >= KINDS) return;
        LISTS[k] = h.blocks();
        UNTIL[k] = now + h.ticks();
        dirty = true;
    }

    static void entities(MovePayloads.Glow g) {
        for (int id : g.ids()) GLOW.put(id, now + g.ticks());
    }

    static void tick(long ticks) {
        now = ticks;
        for (int k = 0; k < KINDS; k++)
            if (LISTS[k] != null && now > UNTIL[k]) {
                LISTS[k] = null;
                dirty = true;
            }
        if (!GLOW.isEmpty()) GLOW.int2LongEntrySet().removeIf(en -> en.getLongValue() < now);
    }

    static void clear() {
        for (int k = 0; k < KINDS; k++) LISTS[k] = null;
        GLOW.clear();
        dirty = true;
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (dirty) rebuild();
        if (!hasBlocks && GLOW.isEmpty()) return;
        Vec3 cam = e.getCamera().getPosition();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull(); // line quads face either way
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.lineWidth(2f);
        if (hasBlocks) {
            MV.set(e.getModelViewMatrix()).translate((float) (origin.getX() - cam.x), (float) (origin.getY() - cam.y), (float) (origin.getZ() - cam.z));
            blocksVbo.bind();
            blocksVbo.drawWithShader(MV, e.getProjectionMatrix(), GameRenderer.getRendertypeLinesShader());
            VertexBuffer.unbind();
        }
        if (!GLOW.isEmpty()) drawGlow(e, cam);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void drawGlow(RenderLevelStageEvent e, Vec3 cam) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        float pt = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        int n = 0;
        for (Int2LongMap.Entry en : GLOW.int2LongEntrySet()) {
            Entity ent = level.getEntity(en.getIntKey());
            if (ent == null || n++ > 128) continue;
            double ox = Mth.lerp(pt, ent.xOld, ent.getX()) - ent.getX(), oy = Mth.lerp(pt, ent.yOld, ent.getY()) - ent.getY(),
                    oz = Mth.lerp(pt, ent.zOld, ent.getZ()) - ent.getZ();
            AABB b = ent.getBoundingBox();
            LevelRenderer.renderLineBox(IDENTITY, bb, b.minX + ox - cam.x, b.minY + oy - cam.y, b.minZ + oz - cam.z,
                    b.maxX + ox - cam.x, b.maxY + oy - cam.y, b.maxZ + oz - cam.z, 1f, 1f, 1f, 0.9f);
        }
        MeshData mesh = bb.build();
        if (mesh == null) return;
        if (glowVbo == null) glowVbo = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        glowVbo.bind();
        glowVbo.upload(mesh);
        glowVbo.drawWithShader(e.getModelViewMatrix(), e.getProjectionMatrix(), GameRenderer.getRendertypeLinesShader());
        VertexBuffer.unbind();
    }

    private static void rebuild() {
        dirty = false;
        hasBlocks = false;
        BlockPos o = null;
        for (List<BlockPos> l : LISTS) if (l != null && !l.isEmpty()) { o = l.get(0); break; }
        if (o == null) return;
        origin = o;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        for (int k = 0; k < KINDS; k++) {
            List<BlockPos> l = LISTS[k];
            if (l == null) continue;
            float[] c = RGB[k];
            if (k == MovePayloads.H_SECRET) {
                for (int i = 0; i + 1 < l.size(); i += 2) box(bb, l.get(i), l.get(i + 1), c);
            } else for (BlockPos b : l) box(bb, b, b, c);
        }
        MeshData mesh = bb.build();
        if (mesh == null) return;
        if (blocksVbo == null) blocksVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        blocksVbo.bind();
        blocksVbo.upload(mesh);
        VertexBuffer.unbind();
        hasBlocks = true;
    }

    private static void box(BufferBuilder bb, BlockPos a, BlockPos b, float[] c) {
        LevelRenderer.renderLineBox(IDENTITY, bb, a.getX() - origin.getX() + 0.02, a.getY() - origin.getY() + 0.02, a.getZ() - origin.getZ() + 0.02,
                b.getX() + 1 - origin.getX() - 0.02, b.getY() + 1 - origin.getY() - 0.02, b.getZ() + 1 - origin.getZ() - 0.02, c[0], c[1], c[2], 1f);
    }
}
