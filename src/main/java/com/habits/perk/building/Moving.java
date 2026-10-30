package com.habits.perk.building;

import com.habits.core.HabitsApi;
import com.habits.core.IdMatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Getting around a build: safety net, hover, sky builder flight, seats, elevator, spawn proofer, pole climbing. */
public final class Moving {
    private Moving() {}

    static final String SEAT_TAG = "habits_seat";
    /** Seat entity per seated player (server). */
    static final Map<UUID, Entity> SEATS = Bld.playerMap();
    /** Sky builder: {fuel ticks left, 1 if we granted flight}. Server only. */
    static final Map<UUID, int[]> SKY = Bld.playerMap();
    static final Map<UUID, Long> NET_READY = Bld.playerMap();
    static final Map<UUID, Boolean> WAS_SNEAKING = Bld.playerMap();
    /** Hover budget in ticks, per side (client and integrated server share statics and UUIDs). */
    static final Map<UUID, Integer> HOVER_S = Bld.playerMap(), HOVER_C = new ConcurrentHashMap<>();

    static Map<String, BiConsumer<Player, HabitsApi.PerkHit>> ticks() {
        Map<String, BiConsumer<Player, HabitsApi.PerkHit>> t = new HashMap<>();
        t.put("safety_net", Moving::safetyNet);
        t.put("hover", Moving::hover);
        t.put("pole_climber", Moving::poles);
        t.put("spawn_proofer", Moving::spawnProofer);
        t.put("take_a_seat", Moving::seatHeal);
        return t;
    }

    static boolean holdingBlock(Player p) {
        return p.getMainHandItem().getItem() instanceof BlockItem;
    }

    // ---------------------------------------------------------------- safety net, hover

    static void safetyNet(Player pl, HabitsApi.PerkHit h) {
        if (!(pl instanceof ServerPlayer p) || p.onGround() || p.fallDistance <= Bld.lerp(h, "fall", 4, 4)) return;
        if (!(p.getMainHandItem().getItem() instanceof BlockItem bi) || p.getAbilities().flying) return;
        long now = p.serverLevel().getGameTime();
        if (NET_READY.getOrDefault(p.getUUID(), 0L) > now) return;
        BlockPos below = p.blockPosition().below();
        BlockState s = bi.getBlock().defaultBlockState();
        if (!s.isCollisionShapeFullBlock(p.level(), below) || !Bld.has(p, bi, 1) || !Bld.placeAs(p, below, s)) return;
        Bld.take(p, bi);
        p.fallDistance = 0;
        NET_READY.put(p.getUUID(), now + (long) (h.value() * 20));
    }

    static void hover(Player p, HabitsApi.PerkHit h) {
        Map<UUID, Integer> m = p.level().isClientSide ? HOVER_C : HOVER_S;
        if (p.onGround() || p.isInWater() || p.getAbilities().flying) { m.put(p.getUUID(), (int) (h.value() * 20)); return; }
        int left = m.getOrDefault(p.getUUID(), 0);
        if (left <= 0 || !p.isShiftKeyDown() || !holdingBlock(p)) return;
        m.put(p.getUUID(), left - 1);
        p.fallDistance = 0;
        if (p.isLocalPlayer()) {
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x, Math.max(0, v.y), v.z);
        }
    }

    // ---------------------------------------------------------------- poles

    static boolean pole(BlockState s) {
        return s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || s.is(Blocks.IRON_BARS) || s.is(Blocks.CHAIN)
                || s.is(Tags.Blocks.GLASS_PANES) || s.is(Blocks.END_ROD) || s.is(Blocks.LIGHTNING_ROD) || s.is(Blocks.BAMBOO);
    }

    static boolean nearPole(Player p) {
        var box = p.getBoundingBox().inflate(0.12, 0, 0.12);
        for (BlockPos q : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ)))
            if (pole(p.level().getBlockState(q))) return true;
        return false;
    }

    static void poles(Player p, HabitsApi.PerkHit h) {
        if (!p.horizontalCollision || p.onClimbable() || p.getAbilities().flying || !nearPole(p)) return;
        p.fallDistance = 0;
        if (!p.isLocalPlayer()) return;
        Vec3 v = p.getDeltaMovement();
        double speed = 0.2 * h.value() / 100.0;
        p.setDeltaMovement(v.x, p.isShiftKeyDown() ? Math.max(0, v.y) : Math.max(v.y, speed), v.z);
    }

    // ---------------------------------------------------------------- spawn proofer

    static void spawnProofer(Player pl, HabitsApi.PerkHit h) {
        if (!(pl instanceof ServerPlayer p) || p.tickCount % 20 != 0) return;
        if (!(p.getMainHandItem().getItem() instanceof BlockItem bi) || bi.getBlock().defaultBlockState().getLightEmission() <= 0) return;
        ServerLevel level = p.serverLevel();
        int r = (int) h.value();
        BlockPos at = p.blockPosition();
        DustParticleOptions dust = new DustParticleOptions(new Vector3f(1f, 0.15f, 0.1f), 1.2f);
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-r, -2, -r), at.offset(r, 2, r))) {
            if (!level.isLoaded(q) || !level.getBlockState(q).isAir()) continue;
            BlockPos below = q.below();
            BlockState floor = level.getBlockState(below);
            if (!floor.isFaceSturdy(level, below, net.minecraft.core.Direction.UP) || floor.is(BlockTags.SLABS) && !floor.isCollisionShapeFullBlock(level, below)) continue;
            if (level.getBrightness(LightLayer.BLOCK, q) > 0 || !level.getBlockState(q.above()).isAir()) continue;
            level.sendParticles(p, dust, false, q.getX() + 0.5, q.getY() + 0.1, q.getZ() + 0.5, 1, 0, 0, 0, 0);
        }
    }

    // ---------------------------------------------------------------- seats

    static boolean sit(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s) {
        if (p.isPassenger() || p.position().distanceToSqr(pos.getCenter()) > 9) return false;
        Display.BlockDisplay seat = EntityType.BLOCK_DISPLAY.create(level);
        if (seat == null) return false;
        seat.moveTo(pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, p.getYRot(), 0);
        seat.addTag(SEAT_TAG);
        level.addFreshEntity(seat);
        if (!p.startRiding(seat, true)) { seat.discard(); return false; }
        Entity old = SEATS.put(p.getUUID(), seat);
        if (old != null) old.discard();
        return true;
    }

    static void seatHeal(Player p, HabitsApi.PerkHit h) {
        if (p.level().isClientSide || p.tickCount % 20 != 0 || !(p.getVehicle() instanceof Display.BlockDisplay d) || !d.getTags().contains(SEAT_TAG)) return;
        p.heal((float) (h.value() / 60.0));
    }

    @SubscribeEvent
    public static void onSeatLoad(EntityJoinLevelEvent e) {
        // a seat saved with the chunk has lost its rider
        if (e.loadedFromDisk() && e.getEntity().getTags().contains(SEAT_TAG)) e.setCanceled(true);
    }

    static void logout(Player p) {
        Entity seat = SEATS.remove(p.getUUID());
        if (seat != null) seat.discard();
        SKY.remove(p.getUUID());
        HOVER_C.remove(p.getUUID());
    }

    // ---------------------------------------------------------------- server tick: seats, sky builder, elevator

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        Entity seat = SEATS.get(p.getUUID());
        if (seat != null && p.getVehicle() != seat) {
            seat.discard();
            SEATS.remove(p.getUUID());
        }
        skyBuilder(p);
        boolean sneak = p.isShiftKeyDown();
        Boolean was = WAS_SNEAKING.put(p.getUUID(), sneak);
        if (sneak && was != null && !was && p.onGround()) elevator(p, false);
    }

    static void skyBuilder(ServerPlayer p) {
        int[] st = SKY.get(p.getUUID());
        HabitsApi.PerkHit h = Bld.hit(p, "sky_builder");
        boolean survival = !p.isCreative() && !p.isSpectator();
        if (h == null || !survival) {
            if (st != null && st[1] == 1 && survival) revoke(p);
            if (st != null) SKY.remove(p.getUUID());
            return;
        }
        if (st == null) SKY.put(p.getUUID(), st = new int[2]);
        int full = (int) (h.value() * 20);
        if (p.onGround() && !p.getAbilities().flying) st[0] = full;
        boolean may = holdingBlock(p) && st[0] > 0;
        if (may && st[1] == 0) {
            st[1] = 1;
            p.getAbilities().mayfly = true;
            p.onUpdateAbilities();
        } else if (!may && st[1] == 1) {
            st[1] = 0;
            revoke(p);
        }
        if (st[1] == 1 && p.getAbilities().flying) st[0]--;
    }

    static void revoke(ServerPlayer p) {
        p.getAbilities().mayfly = false;
        p.getAbilities().flying = false;
        p.onUpdateAbilities();
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) elevator(p, true);
    }

    /** Up to the next matching block above (jump) or below (sneak) the one you stand on, with room to stand. Returns
     * the y the player was sent to, or {@link Integer#MIN_VALUE}. */
    static int elevator(ServerPlayer p, boolean up) {
        HabitsApi.PerkHit h = Bld.hit(p, "elevator");
        if (h == null) return Integer.MIN_VALUE;
        IdMatch match = IdMatch.parse(Bld.param(h, "block", "#minecraft:wool"));
        ServerLevel level = p.serverLevel();
        BlockPos on = p.getOnPos();
        if (!match.test(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(on).getBlock()), Registries.BLOCK, level.registryAccess())) return Integer.MIN_VALUE;
        int reach = (int) h.value();
        for (int i = 2; i <= reach; i++) {
            BlockPos q = up ? on.above(i) : on.below(i);
            if (!level.isLoaded(q)) return Integer.MIN_VALUE;
            if (!match.test(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()), Registries.BLOCK, level.registryAccess())) continue;
            if (!level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty()
                    || !level.getBlockState(q.above(2)).getCollisionShape(level, q.above(2)).isEmpty()) continue;
            p.teleportTo(p.getX(), q.getY() + 1, p.getZ());
            p.fallDistance = 0;
            level.playSound(null, q.above(), net.minecraft.sounds.SoundEvents.ITEM_FRAME_ADD_ITEM, net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, up ? 1.4f : 0.8f);
            return q.getY() + 1;
        }
        return Integer.MIN_VALUE;
    }
}
