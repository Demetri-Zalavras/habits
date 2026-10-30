package com.habits.perk.mining.client;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.perk.mining.MiningNet;
import com.habits.perk.mining.SpeedPerks;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Client side of mining-building: through-wall marks (bounded list, drawn with one line batch), sparkle and trail
 * particles (capped per tick), momentum sync, the waypoint HUD (lines rebuilt twice a second, not per frame), redstone
 * power readout and the undo / remote keys.
 */
public final class MiningClient {
    private MiningClient() {}

    /** Most marks kept at once, and particles spawned per tick. */
    static final int MAX_MARKS = 400, PARTICLES_PER_TICK = 8;

    record Mark(BlockPos pos, float r, float g, float b, long until, int style) {}

    private static final List<Mark> MARKS = new ArrayList<>();
    private static MiningNet.Waypoints waypoints = new MiningNet.Waypoints(List.of(), List.of(), List.of());
    private static final List<String> HUD = new ArrayList<>();

    static final KeyMapping UNDO = new KeyMapping("key.habits.mining_undo", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SEMICOLON, "key.categories.habits_mining");
    static final KeyMapping REMOTE = new KeyMapping("key.habits.mining_remote", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.habits_mining");

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterKeyMappingsEvent e) -> { e.register(UNDO); e.register(REMOTE); });
        NeoForge.EVENT_BUS.addListener(MiningClient::onTick);
        NeoForge.EVENT_BUS.addListener(MiningClient::onRender);
        NeoForge.EVENT_BUS.addListener(MiningClient::onHud);
        NeoForge.EVENT_BUS.addListener(MiningClient::onRightClickEmpty);
        MiningNet.sink = new MiningNet.ClientSink() {
            @Override public void marks(MiningNet.Marks m) { addMarks(m); }
            @Override public void momentum(int stacks) { SpeedPerks.clientMomentum = stacks; }
            @Override public void waypoints(MiningNet.Waypoints w) { waypoints = w; }
        };
    }

    static void addMarks(MiningNet.Marks m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long until = mc.level.getGameTime() + m.ticks();
        // a new scan of the same kind replaces the old marks of that style
        MARKS.removeIf(k -> k.style == m.style());
        for (int i = 0; i < m.pos().size() && MARKS.size() < MAX_MARKS; i++) {
            int c = i < m.colors().size() ? m.colors().get(i) : 0xFFFFFF;
            MARKS.add(new Mark(m.pos().get(i), ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, until, m.style()));
        }
    }

    // ---------------------------------------------------------------- tick: expiry, particles, keys, hud, redstone

    private static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { MARKS.clear(); return; }
        long now = mc.level.getGameTime();
        MARKS.removeIf(m -> m.until < now);
        int budget = PARTICLES_PER_TICK;
        for (Mark m : MARKS) {
            if (budget <= 0) break;
            if (m.style == 1 && (now + m.pos.hashCode()) % 20 == 0 && m.pos.distSqr(p.blockPosition()) < 24 * 24) {
                mc.level.addParticle(ParticleTypes.WHITE_ASH, m.pos.getX() + mc.level.random.nextDouble(), m.pos.getY() + 1.05,
                        m.pos.getZ() + mc.level.random.nextDouble(), 0, 0.02, 0);
                budget--;
            } else if (m.style == 2 && now % 4 == 0) {
                Vec3 from = p.getEyePosition().add(0, -0.4, 0);
                Vec3 to = Vec3.atCenterOf(m.pos);
                Vec3 dir = to.subtract(from);
                double len = dir.length();
                if (len < 1) continue;
                dir = dir.scale(1 / len);
                DustParticleOptions dust = new DustParticleOptions(new org.joml.Vector3f(m.r, m.g, m.b), 1f);
                for (int i = 1; i <= 4 && budget > 0; i++, budget--) {
                    Vec3 at = from.add(dir.scale(Math.min(len, 1.2 * i + (now % 8) / 8.0)));
                    mc.level.addParticle(dust, at.x, at.y, at.z, 0, 0, 0);
                }
            }
        }
        while (UNDO.consumeClick()) PacketDistributor.sendToServer(new MiningNet.Action(MiningNet.Action.UNDO));
        while (REMOTE.consumeClick()) PacketDistributor.sendToServer(new MiningNet.Action(MiningNet.Action.REMOTE));
        if (now % 10 == 0) rebuildHud(p);
        if (now % 4 == 0) redstone(mc, p);
    }

    private static void rebuildHud(LocalPlayer p) {
        HUD.clear();
        HabitsApi.PerkHit h = HabitsApi.mechanicHit(p, Habits.id("scribe"));
        if (h == null || !p.isAlive()) return;
        String range = h.skill().perk().param("range", "32..2000");
        double max;
        try {
            String[] ab = range.split("\\.\\.");
            double a = Double.parseDouble(ab[0]), b = Double.parseDouble(ab[ab.length - 1]);
            max = a + (b - a) * (Math.min(10, h.level()) - 1) / 9.0;
        } catch (RuntimeException ex) {
            max = 32;
        }
        String dim = p.level().dimension().location().toString();
        for (int i = 0; i < waypoints.names().size() && HUD.size() < 8; i++) {
            BlockPos at = waypoints.pos().get(i);
            boolean here = waypoints.dims().get(i).equals(dim);
            if (!here) {
                if (h.level() >= 10) HUD.add("§7" + waypoints.names().get(i) + " (" + Component.translatable("habits.mining.other_world").getString() + ")");
                continue;
            }
            double dist = Math.sqrt(at.distToCenterSqr(p.position()));
            if (dist > max) continue;
            double ang = Math.toDegrees(Math.atan2(at.getZ() + 0.5 - p.getZ(), at.getX() + 0.5 - p.getX())) - 90 - p.getYRot();
            HUD.add(arrow(Mth.wrapDegrees(ang)) + " " + waypoints.names().get(i) + "  " + (int) dist + "m");
        }
    }

    private static String arrow(double deg) {
        String[] a = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int i = (int) Math.floorMod(Math.round(deg / 45.0), 8);
        return a[i];
    }

    private static void redstone(Minecraft mc, LocalPlayer p) {
        HabitsApi.PerkHit h = HabitsApi.mechanicHit(p, Habits.id("redstone_sight"));
        if (h == null) return;
        HitResult hr = p.pick(h.value(), 1, false);
        if (!(hr instanceof BlockHitResult b) || hr.getType() != HitResult.Type.BLOCK) return;
        BlockState s = mc.level.getBlockState(b.getBlockPos());
        int power;
        if (s.getBlock() instanceof RedStoneWireBlock) power = s.getValue(RedStoneWireBlock.POWER);
        else if (s.getBlock() instanceof RepeaterBlock || s.getBlock() instanceof ComparatorBlock || s.hasProperty(BlockStateProperties.POWERED))
            power = mc.level.getBestNeighborSignal(b.getBlockPos());
        else if (s.hasProperty(BlockStateProperties.POWER)) power = s.getValue(BlockStateProperties.POWER);
        else return;
        mc.gui.setOverlayMessage(Component.translatable("habits.mining.redstone_power", power), false);
    }

    // ---------------------------------------------------------------- render: one line batch, depth test off

    private static final RenderType XRAY_LINES = new RenderType("habits_mining_xray", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 1536, false, false,
            () -> { RenderType.lines().setupRenderState(); RenderSystem.disableDepthTest(); },
            () -> { RenderSystem.enableDepthTest(); RenderType.lines().clearRenderState(); }) {};

    private static void onRender(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || MARKS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 cam = e.getCamera().getPosition();
        PoseStack pose = e.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buf.getBuffer(XRAY_LINES);
        long now = mc.level.getGameTime();
        for (Mark m : MARKS) {
            if (m.style != 0) continue;
            float a = Math.min(1f, (m.until - now) / 20f) * 0.9f;
            if (a <= 0.02f) continue;
            BlockPos q = m.pos;
            LevelRenderer.renderLineBox(pose, vc, q.getX() + 0.02, q.getY() + 0.02, q.getZ() + 0.02, q.getX() + 0.98, q.getY() + 0.98,
                    q.getZ() + 0.98, m.r, m.g, m.b, a);
        }
        buf.endBatch(XRAY_LINES);
        pose.popPose();
    }

    private static void onHud(RenderGuiEvent.Post e) {
        if (HUD.isEmpty() || Minecraft.getInstance().options.hideGui) return;
        var font = Minecraft.getInstance().font;
        int y = 4;
        for (String line : HUD) {
            e.getGuiGraphics().drawString(font, line, 4, y, 0xFFFFFF, true);
            y += 10;
        }
    }

    // ---------------------------------------------------------------- air place

    private static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty e) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || e.getEntity() != p || e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (p.getMainHandItem().getItem() instanceof BlockItem && HabitsApi.mechanicHit(p, Habits.id("air_place")) != null)
            PacketDistributor.sendToServer(new MiningNet.Action(MiningNet.Action.AIR_PLACE));
    }
}
