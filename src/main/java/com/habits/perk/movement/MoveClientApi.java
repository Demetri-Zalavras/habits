package com.habits.perk.movement;

import net.minecraft.world.entity.player.Player;

/** What the client package (com.habits.client.movement) may read and set. Client side only. */
public final class MoveClientApi {
    private MoveClientApi() {}

    public static boolean active(Player p) {
        return MoveState.of(p).any;
    }

    public static boolean has(Player p, Mech m) {
        return MoveState.of(p).has(m);
    }

    public static double value(Player p, Mech m) {
        return MoveState.of(p).v(m);
    }

    public static int level(Player p, Mech m) {
        return MoveState.of(p).lv(m);
    }

    public static double param(Player p, Mech m, String key) {
        return MoveState.of(p).p(m, key);
    }

    /** Held keys and this tick's jump press, read by {@link Physics} on the next player tick. */
    public static void input(Player p, byte keys, boolean jumpEdge) {
        MoveState s = MoveState.of(p);
        s.keys = keys;
        s.jumpEdge |= jumpEdge;
    }

    /** Ticks since the local player last left the ground going up (for client-side checks that mirror the server). */
    public static boolean wasOnGround(Player p) {
        return MoveState.of(p).wasOnGround;
    }

    /** Dev screenshots only (DevShots plan "movement"): owns a few marker skills at Lv10 and builds a scene. */
    public static void devScene(net.minecraft.server.level.ServerPlayer p) {
        for (Mech m : new Mech[]{Mech.PATHFINDER, Mech.OVERWORLDER, Mech.TOMB_RAIDER, Mech.RUIN_READER})
            for (int i = 0; i < 60; i++) com.habits.core.HabitsApi.addCard(p, m.skillId);
        var level = p.serverLevel();
        net.minecraft.core.BlockPos at = p.blockPosition();
        p.setRespawnPosition(level.dimension(), at.offset(40, 0, -25), 0, true, false);
        MoveData d = p.getData(MoveData.TYPE);
        MoveData.remember(d.waypoints, new MoveData.Place("waypoint", net.minecraft.core.GlobalPos.of(level.dimension(), at.offset(-30, 0, 45))), 10);
        MoveData.remember(d.waypoints, new MoveData.Place("waypoint", net.minecraft.core.GlobalPos.of(level.dimension(), at.offset(70, 0, 60))), 10);
        level.setBlockAndUpdate(at.offset(2, 0, 5), net.minecraft.world.level.block.Blocks.TNT.defaultBlockState());
        level.setBlockAndUpdate(at.offset(-2, 0, 4), net.minecraft.world.level.block.Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
        level.setBlockAndUpdate(at.offset(0, 0, 7), net.minecraft.world.level.block.Blocks.SUSPICIOUS_SAND.defaultBlockState());
        level.setBlockAndUpdate(at.offset(1, 0, 6), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        p.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 25);
        Scans.run(p, MoveState.of(p));
    }

    /** DevShots "waterwalk": a pool with a kelp top, a waterlogged slab and a bubble column at the surface, the player
     * at Lv10 Water Walk dropped onto the kelp. */
    public static void waterScene(net.minecraft.server.level.ServerPlayer p) {
        for (int i = 0; i < 60; i++) com.habits.core.HabitsApi.addCard(p, Mech.JESUS_WALK.skillId);
        var level = p.serverLevel();
        net.minecraft.core.BlockPos at = p.blockPosition().below(); // the grass layer becomes the water surface
        for (int x = -4; x <= 4; x++) for (int z = -2; z <= 10; z++) for (int y = -2; y <= 0; y++)
            level.setBlockAndUpdate(at.offset(x, y, z), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        net.minecraft.core.BlockPos kelp = at.offset(0, 0, 4);
        level.setBlockAndUpdate(kelp.below(2), net.minecraft.world.level.block.Blocks.KELP_PLANT.defaultBlockState());
        level.setBlockAndUpdate(kelp.below(), net.minecraft.world.level.block.Blocks.KELP_PLANT.defaultBlockState());
        level.setBlockAndUpdate(kelp, net.minecraft.world.level.block.Blocks.KELP.defaultBlockState());
        level.setBlockAndUpdate(at.offset(1, 0, 6), net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true));
        level.setBlockAndUpdate(at.offset(-1, -2, 6), net.minecraft.world.level.block.Blocks.SOUL_SAND.defaultBlockState());
        level.setBlockAndUpdate(at.offset(-1, -1, 6), net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN.defaultBlockState());
        level.setBlockAndUpdate(at.offset(-1, 0, 6), net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN.defaultBlockState());
        p.teleportTo(level, kelp.getX() + 0.5, kelp.getY() + 1.2, kelp.getZ() + 0.5, 0, 30);
    }

    public static void forgetClient() {
        MoveState.CLIENT.clear();
    }
}
