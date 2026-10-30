package com.habits.perk.survival.client;

import com.habits.Habits;
import com.habits.perk.survival.Sk;
import com.habits.perk.survival.SurvivalHooks;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.joml.Matrix4f;

/** Client-only survival bits: key reads, lava fog, stew tooltip, pumpkin view, nausea, and the Owl Eyes ore outlines. */
@EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT)
public final class SurvivalClient {
    private SurvivalClient() {}

    public static boolean jumpDown() {
        return Minecraft.getInstance().options.keyJump.isDown();
    }

    private static boolean jumpWasDown, airJumpUsed;

    /** Spring Heels: one extra jump in the air while Jump Boost is on. */
    public static void doubleJump(Player p, double boost, boolean leaping, boolean jump) {
        if (p.onGround() || p.isInWater() || p.onClimbable()) airJumpUsed = false;
        else if (leaping && jump && !jumpWasDown && !airJumpUsed && !p.getAbilities().flying && !p.isFallFlying()) {
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x, boost, v.z);
            p.resetFallDistance();
            airJumpUsed = true;
        }
        jumpWasDown = jump;
    }

    /** Clear Head: cap the nausea wobble. */
    public static void calmNausea(Player p, double pct) {
        if (!(p instanceof LocalPlayer lp) || !lp.hasEffect(MobEffects.CONFUSION)) return;
        float cap = (float) Math.max(0, 1 - pct / 100);
        lp.spinningEffectIntensity = Math.min(lp.spinningEffectIntensity, cap);
        lp.oSpinningEffectIntensity = Math.min(lp.oSpinningEffectIntensity, cap);
    }

    @SubscribeEvent
    public static void fog(ViewportEvent.RenderFog e) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (e.getType() != FogType.LAVA || p == null || !p.hasEffect(MobEffects.FIRE_RESISTANCE)) return;
        double v = Sk.SALAMANDER.v(p);
        if (v <= 0) return;
        float far = e.getFarPlaneDistance();
        float clear = Minecraft.getInstance().gameRenderer.getRenderDistance() * 0.5f;
        e.setFarPlaneDistance(far + (float) ((clear - far) * Math.min(v, 100) / 100));
        e.setCanceled(true); // NeoForge applies the new fog only when cancelled
    }

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent e) {
        Player p = e.getEntity();
        if (p == null || !e.getItemStack().is(Items.SUSPICIOUS_STEW) || Sk.SUS_CONNOISSEUR.hit(p) == null) return;
        var effects = e.getItemStack().get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
        if (effects == null) return;
        double mult = 1 + Sk.SUS_CONNOISSEUR.v(p) / 100;
        for (var en : effects.effects()) {
            int secs = (int) Math.round(en.duration() * mult / 20);
            e.getToolTip().add(Component.translatable(secs == 1 ? "habits.survival.stew_effect.one" : "habits.survival.stew_effect",
                    en.effect().value().getDisplayName(), secs).withStyle(s -> s.withItalic(false).withColor(0x8FD18F)));
        }
    }

    /** Pumpkin Head: the carved pumpkin overlay's alpha (GuiMixin). */
    public static float pumpkinAlpha(float alpha) {
        LocalPlayer p = Minecraft.getInstance().player;
        double cut = p == null ? 0 : Sk.PUMPKIN_HEAD.v(p);
        return cut <= 0 ? alpha : alpha * (float) Math.max(0, 1 - cut / 100);
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        SurvivalHooks.clearGlow();
    }

    // ------------------------------------------------------------------ Owl Eyes: ore outlines through walls

    private static final LongArrayList ORES = new LongArrayList(), SCAN = new LongArrayList();
    private static int scanY = Integer.MIN_VALUE, scanRadius;
    private static BlockPos scanCenter = BlockPos.ZERO;
    private static final int MAX_BOXES = 256;

    /** Scans one horizontal layer per tick (at most 25x25 blocks), so a full pass at radius 12 spreads over 25 ticks. */
    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post e) {
        LocalPlayer p = Minecraft.getInstance().player;
        int r = p == null || !p.hasEffect(MobEffects.NIGHT_VISION) ? 0 : (int) Sk.OWL_EYES.v(p);
        if (r <= 0) {
            ORES.clear();
            scanY = Integer.MIN_VALUE;
            return;
        }
        if (scanY == Integer.MIN_VALUE || scanY > scanCenter.getY() + scanRadius) {
            ORES.clear();
            ORES.addAll(SCAN);
            SCAN.clear();
            scanCenter = p.blockPosition();
            scanRadius = r;
            scanY = scanCenter.getY() - r;
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -scanRadius; dx <= scanRadius; dx++) for (int dz = -scanRadius; dz <= scanRadius; dz++) {
            if (SCAN.size() >= MAX_BOXES) break;
            m.set(scanCenter.getX() + dx, scanY, scanCenter.getZ() + dz);
            if (p.level().getBlockState(m).is(Tags.Blocks.ORES)) SCAN.add(m.asLong());
        }
        scanY++;
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || ORES.isEmpty()) return;
        Vec3 cam = e.getCamera().getPosition();
        Matrix4f pose = e.getPoseStack().last().pose();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < ORES.size(); i++) {
            long l = ORES.getLong(i);
            float x = (float) (BlockPos.getX(l) - cam.x), y = (float) (BlockPos.getY(l) - cam.y), z = (float) (BlockPos.getZ(l) - cam.z);
            box(b, pose, x, y, z);
        }
        MeshData mesh = b.build();
        if (mesh == null) return;
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(mesh);
        RenderSystem.enableDepthTest();
    }

    private static void box(BufferBuilder b, Matrix4f m, float x, float y, float z) {
        float a = 0.02f, x1 = x + a, y1 = y + a, z1 = z + a, x2 = x + 1 - a, y2 = y + 1 - a, z2 = z + 1 - a;
        line(b, m, x1, y1, z1, x2, y1, z1); line(b, m, x1, y2, z1, x2, y2, z1); line(b, m, x1, y1, z2, x2, y1, z2); line(b, m, x1, y2, z2, x2, y2, z2);
        line(b, m, x1, y1, z1, x1, y2, z1); line(b, m, x2, y1, z1, x2, y2, z1); line(b, m, x1, y1, z2, x1, y2, z2); line(b, m, x2, y1, z2, x2, y2, z2);
        line(b, m, x1, y1, z1, x1, y1, z2); line(b, m, x2, y1, z1, x2, y1, z2); line(b, m, x1, y2, z1, x1, y2, z2); line(b, m, x2, y2, z1, x2, y2, z2);
    }

    private static void line(BufferBuilder b, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz) {
        b.addVertex(m, ax, ay, az).setColor(1f, 0.85f, 0.3f, 0.9f);
        b.addVertex(m, bx, by, bz).setColor(1f, 0.85f, 0.3f, 0.9f);
    }
}
