package com.habits.client.movement;

import com.habits.Habits;
import com.habits.perk.movement.Mech;
import com.habits.perk.movement.MoveClientApi;
import com.habits.perk.movement.MovePayloads;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Client side of the movement perks: reads input once per client tick (no allocation unless a packet is sent), sends
 * intents to the server, feeds held keys to the local-player physics, and owns the zoom, compass bar and outlines.
 */
@EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT)
public final class MovementClient {
    private MovementClient() {}

    static final String CATEGORY = "key.categories.habits.movement";
    static final KeyMapping ZOOM = new KeyMapping("key.habits.movement.zoom", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_ALT, CATEGORY); // not ` (Sophisticated Backpacks open key in the StarSword pack)
    static final KeyMapping WAYPOINT = new KeyMapping("key.habits.movement.waypoint", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, CATEGORY);
    static final KeyMapping MOUNT = new KeyMapping("key.habits.movement.mount", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, CATEGORY);
    static final KeyMapping TRAVEL = new KeyMapping("key.habits.movement.travel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PERIOD, CATEGORY);
    static final KeyMapping DASH = new KeyMapping("key.habits.movement.dash", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_CAPS_LOCK, CATEGORY); // not Left Alt (Shoulder Surfing free look, Create tool menu)

    private static byte lastKeys;
    private static boolean wasJump, wasSneak, wasAttack, launchSeen;
    private static long ticks;
    // 7 ticks matches vanilla's double-tap sprint window
    private static final DoubleTap sneakTap = new DoubleTap(7);
    private static int pendingHop;

    @EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        private ModEvents() {}

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent e) {
            e.register(ZOOM);
            e.register(WAYPOINT);
            e.register(MOUNT);
            e.register(TRAVEL);
            e.register(DASH);
        }

        @SubscribeEvent
        public static void layers(RegisterGuiLayersEvent e) {
            e.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, Habits.id("movement_compass"), CompassBar::render);
        }

        @SubscribeEvent
        public static void setup(FMLClientSetupEvent e) {
            com.habits.client.DevShots.PLANS.put("movement", shots -> {
                shots.add(new com.habits.client.DevShots.Shot("move_compass", 60,
                        mc -> com.habits.client.DevShots.serverDo(mc, MoveClientApi::devScene)));
                shots.add(new com.habits.client.DevShots.Shot("move_compass_turned", 30, mc -> com.habits.client.DevShots.serverDo(mc,
                        sp -> sp.teleportTo(sp.serverLevel(), sp.getX(), sp.getY(), sp.getZ(), 135, 10))));
            });
            com.habits.client.DevShots.PLANS.put("waterwalk", shots -> {
                shots.add(new com.habits.client.DevShots.Shot("water_kelp", 80, mc -> {
                    mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                    com.habits.client.DevShots.serverDo(mc, MoveClientApi::waterScene);
                }));
                // the same spot from the player's own eyes, plus where both sides think the player is
                shots.add(new com.habits.client.DevShots.Shot("water_kelp_eyes", 20, mc -> {
                    mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                    com.mojang.logging.LogUtils.getLogger().info("[hbshots] water walk client y {} onGround {}", mc.player.getY(), mc.player.onGround());
                    com.habits.client.DevShots.serverDo(mc, sp -> com.mojang.logging.LogUtils.getLogger()
                            .info("[hbshots] water walk server y {} block {}", sp.getY(), sp.level().getBlockState(sp.blockPosition())));
                }));
            });
            DashLab.register();
            MovePayloads.SINK = new MovePayloads.ClientSink() {
                @Override public void markers(MovePayloads.Markers m) { CompassBar.set(m); }
                @Override public void highlights(MovePayloads.Highlights h) { Outlines.blocks(h); }
                @Override public void glow(MovePayloads.Glow g) { Outlines.entities(g); }
                @Override public void travel(MovePayloads.TravelList t) { Minecraft.getInstance().setScreen(new TravelScreen(t)); }
            };
        }
    }

    static void send(byte kind, int arg) {
        PacketDistributor.sendToServer(new MovePayloads.Act(kind, arg));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ticks++;
        Outlines.tick(ticks);
        if (p == null || mc.isPaused()) return;
        if (!MoveClientApi.active(p)) {
            if (lastKeys != 0) {
                lastKeys = 0;
                PacketDistributor.sendToServer(new MovePayloads.Keys((byte) 0));
            }
            return;
        }
        Options o = mc.options;
        boolean free = mc.screen == null;
        boolean jump = free && o.keyJump.isDown(), fwd = free && o.keyUp.isDown(), sprint = free && o.keySprint.isDown();
        boolean sneak = free && o.keyShift.isDown(), attack = free && o.keyAttack.isDown();
        byte keys = (byte) ((jump ? MovePayloads.K_JUMP : 0) | (fwd ? MovePayloads.K_FORWARD : 0) | (sprint ? MovePayloads.K_SPRINT : 0));
        boolean jumpEdge = jump && !wasJump;
        if (keys != lastKeys) {
            lastKeys = keys;
            PacketDistributor.sendToServer(new MovePayloads.Keys(keys));
        }
        MoveClientApi.input(p, keys, jumpEdge);

        if (jumpEdge) {
            boolean air = !p.onGround() && !p.isInWater() && !p.onClimbable() && !p.isFallFlying() && !p.getAbilities().flying && !p.isPassenger();
            if (sneak && MoveClientApi.has(p, Mech.ENDWALKER)) send(MovePayloads.BLINK, 0);
            else if (air && MoveClientApi.has(p, Mech.WALL_JUMP) && nearWall(p)) send(MovePayloads.WALL_JUMP, 0);
            else if (air && MoveClientApi.has(p, Mech.DOUBLE_JUMP)) send(MovePayloads.DOUBLE_JUMP, 0);
            if (p.onGround() && p.level().dimension() == Level.END && MoveClientApi.has(p, Mech.END_ISLANDER)) pendingHop = 3;
        }
        if (pendingHop > 0 && --pendingHop < 2 && !p.onGround()) {
            if (p.getDeltaMovement().y > 0) send(MovePayloads.ISLAND_HOP, 0);
            pendingHop = 0;
        }
        while (DASH.consumeClick())
            if (MoveClientApi.has(p, Mech.DASH) || MoveClientApi.has(p, Mech.RIPTIDE_RIDER) || MoveClientApi.has(p, Mech.BLINK))
                send(MovePayloads.DASH, 0);
        if (sneak && !wasSneak && sneakTap.press(ticks) && MoveClientApi.has(p, Mech.CAVE_DWELLER)) send(MovePayloads.PING, 0);
        while (WAYPOINT.consumeClick()) send(sneak ? MovePayloads.WAYPOINT_CLEAR : MovePayloads.WAYPOINT, 0);
        while (MOUNT.consumeClick()) send(MovePayloads.MOUNT, 0);
        while (TRAVEL.consumeClick()) send(MovePayloads.TRAVEL_LIST, 0);
        if (attack && !wasAttack) mark(p);
        wings(p);
        wasJump = jump;
        wasSneak = sneak;
        wasAttack = attack;
    }

    static boolean nearWall(LocalPlayer p) {
        AABB bb = p.getBoundingBox().deflate(0, 0.2, 0);
        return !p.level().noCollision(p, bb.inflate(0.2, 0, 0.2));
    }

    /** Spyglass Scout marks the block you look at; Savanna Stalker (zoomed) marks the mob you look at. */
    static void mark(LocalPlayer p) {
        if (p.isScoping() && MoveClientApi.has(p, Mech.SPYGLASS_SCOUT)) {
            HitResult hit = p.pick(512, 1, false);
            if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK)
                PacketDistributor.sendToServer(new MovePayloads.MarkSpot(b.getBlockPos()));
        } else if (zooming(p) && MoveClientApi.param(p, Mech.SAVANNA_STALKER, "mark") > 0) {
            Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(160));
            EntityHitResult hit = ProjectileUtil.getEntityHitResult(p, eye, end, p.getBoundingBox().expandTowards(p.getLookAngle().scale(160)).inflate(1),
                    en -> !en.isSpectator() && en.isPickable() && en != p, 160 * 160);
            if (hit != null) send(MovePayloads.MARK_ENTITY, hit.getEntity().getId());
        }
    }

    static boolean zooming(LocalPlayer p) {
        return ZOOM.isDown() && MoveClientApi.has(p, Mech.SAVANNA_STALKER);
    }

    /** Wings of Wind: once the server's launch tops out, open the elytra the way vanilla does on a jump press. */
    static void wings(LocalPlayer p) {
        if (!MoveClientApi.has(p, Mech.WINGS_OF_WIND)) return;
        double vy = p.getDeltaMovement().y;
        if (vy > 1.0 && !p.onGround()) launchSeen = true;
        if (p.onGround() || p.isFallFlying()) {
            launchSeen = false;
            return;
        }
        if (launchSeen && vy <= 0.2 && p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) && p.tryToStartFallFlying()) {
            p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            launchSeen = false;
        }
    }

    @SubscribeEvent
    public static void fov(ComputeFovModifierEvent e) {
        if (e.getPlayer() instanceof LocalPlayer p && zooming(p))
            e.setNewFovModifier(e.getNewFovModifier() / (float) Math.max(1, MoveClientApi.value(p, Mech.SAVANNA_STALKER)));
    }

    /** Saddle Up whistle: sneak + right-click air with an empty hand (only the client sees empty clicks). */
    @SubscribeEvent
    public static void emptyClick(PlayerInteractEvent.RightClickEmpty e) {
        if (e.getHand() == InteractionHand.MAIN_HAND && e.getEntity().isShiftKeyDown() && e.getItemStack().isEmpty()
                && MoveClientApi.param(e.getEntity(), Mech.SADDLE_UP, "whistle") > 0) send(MovePayloads.WHISTLE, 0);
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        MoveClientApi.forgetClient();
        CompassBar.clear();
        Outlines.clear();
        lastKeys = 0;
    }
}
