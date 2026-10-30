package com.habits.perk.building;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.SkillProgress;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One test per building mechanic group. Builds stay inside x/z 0..4 so neighbouring tests never touch. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class BuildingTests {
    private BuildingTests() {}

    static ServerPlayer player(GameTestHelper h, BlockPos rel, String skill, int level) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-build"));
        Triggers.TEST_PLAYERS.add(p.getUUID());
        p.setGameMode(GameType.SURVIVAL);
        Vec3 at = h.absoluteVec(Vec3.atBottomCenterOf(rel));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(Bld.id(skill), new SkillProgress(level, 0, true)));
        return p;
    }

    static void also(ServerPlayer p, String skill, int level) {
        p.setData(CoreRegistries.COLLECTION, HabitsApi.collection(p).with(Bld.id(skill), new SkillProgress(level, 0, true)));
    }

    static BlockPos abs(GameTestHelper h, int x, int y, int z) {
        return h.absolutePos(new BlockPos(x, y, z));
    }

    static void fill(ServerLevel l, BlockPos a, BlockPos b, BlockState s) {
        for (BlockPos q : BlockPos.betweenClosed(a, b)) l.setBlock(q, s, 3);
    }

    /** What a hand placement does: the block appears, then the place event fires. */
    static void place(ServerPlayer p, BlockPos at, BlockState s) {
        ServerLevel l = p.serverLevel();
        BlockSnapshot snap = BlockSnapshot.create(l.dimension(), l, at);
        l.setBlock(at, s, 3);
        NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(snap, l.getBlockState(at.below()), p));
    }

    static int count(ServerLevel l, BlockPos a, BlockPos b, net.minecraft.world.level.block.Block block) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(a, b)) if (l.getBlockState(q).is(block)) n++;
        return n;
    }

    // ---------------------------------------------------------------- fills and lines

    /** Carpet roll covers the walled floor; backfill fills a closed pit from the bottom; wire puller lays dust ahead. */
    @GameTest(template = "empty")
    public static void fillsAndLines(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        // a 3x3 room with walls: the carpet rolls out over the 8 other floor spots
        BlockPos floor = abs(h, 0, 40, 0);
        fill(l, floor, floor.offset(4, 0, 4), Blocks.STONE.defaultBlockState());
        fill(l, floor.offset(0, 1, 0), floor.offset(4, 1, 4), Blocks.STONE.defaultBlockState());
        fill(l, floor.offset(1, 1, 1), floor.offset(3, 1, 3), Blocks.AIR.defaultBlockState());
        ServerPlayer p = player(h, new BlockPos(2, 44, 2), "carpet_roll", 10);
        p.getInventory().add(new ItemStack(Items.RED_CARPET, 64));
        place(p, floor.offset(2, 1, 2), Blocks.RED_CARPET.defaultBlockState());
        // a closed 1x1x3 pit under a player standing beside it
        BlockPos pit = abs(h, 2, 50, 2);
        fill(l, pit.offset(-1, -4, -1), pit.offset(1, 0, 1), Blocks.STONE.defaultBlockState());
        fill(l, pit.offset(0, -3, 0), pit, Blocks.AIR.defaultBlockState());
        ServerPlayer d = player(h, new BlockPos(3, 51, 2), "backfill", 10);
        d.setOnGround(true);
        d.getInventory().add(new ItemStack(Items.DIRT, 64));
        place(d, pit.below(), Blocks.DIRT.defaultBlockState());
        // sneak-placed dust runs south, the way the player faces at yaw 0
        BlockPos wire = abs(h, 2, 60, 0);
        fill(l, wire.below(), wire.below().offset(0, 0, 4), Blocks.STONE.defaultBlockState());
        ServerPlayer w = player(h, new BlockPos(2, 61, 0), "wire_puller", 10);
        w.setYRot(0); // south (+z)
        w.setShiftKeyDown(true);
        w.getInventory().add(new ItemStack(Items.REDSTONE, 64));
        place(w, wire, Blocks.REDSTONE_WIRE.defaultBlockState());
        h.runAfterDelay(2, () -> {
            h.assertValueEqual(count(l, floor.offset(1, 1, 1), floor.offset(3, 1, 3), Blocks.RED_CARPET), 9, "carpets over the room floor");
            h.assertValueEqual(p.getInventory().countItem(Items.RED_CARPET), 56, "eight carpets paid");
            h.assertValueEqual(count(l, pit.offset(0, -3, 0), pit.below(), Blocks.DIRT), 3, "pit filled from below the placed block");
            h.assertValueEqual(count(l, wire, wire.offset(0, 0, 4), Blocks.REDSTONE_WIRE), 5, "dust runs ahead to the end of the floor");
            h.succeed();
        });
    }

    /** Sod layer at Lv10 always grows grass; endless supply below 100% is a roll, so it is not timed here. */
    @GameTest(template = "empty")
    public static void sodLayerGrowsGrass(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos at = abs(h, 2, 70, 2);
        ServerPlayer p = player(h, new BlockPos(2, 72, 0), "sod_layer", 10);
        place(p, at, Blocks.DIRT.defaultBlockState());
        h.runAfterDelay(2, () -> {
            h.assertTrue(l.getBlockState(at).is(Blocks.GRASS_BLOCK), "dirt became grass");
            h.succeed();
        });
    }

    // ---------------------------------------------------------------- tools

    /** Painter recolors connected wool and pays dye; the builder's stick flips a stair's shape; chisel follows the stonecutter. */
    @GameTest(template = "empty")
    public static void paintStickChisel(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos w = abs(h, 0, 80, 0);
        fill(l, w, w.offset(3, 0, 0), Blocks.WHITE_WOOL.defaultBlockState());
        ServerPlayer p = player(h, new BlockPos(2, 82, 2), "painter", 10);
        ItemStack dye = new ItemStack(Items.RED_DYE, 4);
        p.setItemInHand(InteractionHand.MAIN_HAND, dye);
        h.assertTrue(Using.paint(p, l, w, l.getBlockState(w), DyeColor.RED, dye, HabitsApi.mechanicHit(p, Bld.id("painter"))), "painted");
        h.assertValueEqual(count(l, w, w.offset(3, 0, 0), Blocks.RED_WOOL), 4, "all connected wool");
        h.assertValueEqual(p.getInventory().countItem(Items.RED_DYE), 3, "16 per dye at Lv10: one dye");

        also(p, "builders_stick", 1);
        BlockPos st = abs(h, 2, 80, 2);
        l.setBlock(st, Blocks.OAK_STAIRS.defaultBlockState(), 3);
        var hit = HabitsApi.mechanicHit(p, Bld.id("builders_stick"));
        Using.STICK.put(p.getUUID(), "shape");
        Using.stick(p, l, st, l.getBlockState(st), false, hit);
        h.assertTrue(l.getBlockState(st).getValue(StairBlock.SHAPE) != net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT, "shape changed");
        h.assertTrue(Using.editable(l.getBlockState(st), hit).stream().noneMatch(x -> x.getName().equals("facing")), "Lv1 cannot turn blocks");

        also(p, "chisel", 10);
        BlockPos c = abs(h, 4, 80, 4);
        l.setBlock(c, Blocks.STONE_BRICKS.defaultBlockState(), 3);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        Using.chisel(p, l, c, l.getBlockState(c), p.getMainHandItem(), HabitsApi.mechanicHit(p, Bld.id("chisel")));
        h.assertTrue(l.getBlockState(c).is(Blocks.CHISELED_STONE_BRICKS), "stone bricks chiseled, got " + l.getBlockState(c));
        h.succeed();
    }

    /** Fill wand fills the paper-marked box from the inventory, spread over ticks. */
    @GameTest(template = "empty")
    public static void fillWandFillsBox(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos a = abs(h, 0, 90, 0), b = abs(h, 2, 91, 2);
        fill(l, a.below(), b.below().offset(0, -1, 0), Blocks.STONE.defaultBlockState());
        ServerPlayer p = player(h, new BlockPos(2, 95, 2), "fill_wand", 10);
        p.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        Using.tape(p, l, a);
        Using.tape(p, l, b);
        h.assertTrue(Using.fillBox(p, l, (net.minecraft.world.item.BlockItem) Items.COBBLESTONE, HabitsApi.mechanicHit(p, Bld.id("fill_wand"))), "fill started");
        Bld.drain(p);
        h.assertValueEqual(count(l, a, b, Blocks.COBBLESTONE), 18, "3x2x3 box");
        h.assertValueEqual(p.getInventory().countItem(Items.COBBLESTONE), 64 - 18, "paid per block");
        h.succeed();
    }

    // ---------------------------------------------------------------- breaking

    /** Slab splitter leaves the other half; moving day picks a chest up with its items. */
    @GameTest(template = "empty")
    public static void splitAndCarry(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos s = abs(h, 2, 100, 2);
        l.setBlock(s, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE), 3);
        ServerPlayer p = player(h, new BlockPos(2, 101, 0), "slab_splitter", 10);
        p.setXRot(90); // looking down: eyes above the slab, so the top half goes
        Breaking.split(p, l, s, l.getBlockState(s), HabitsApi.mechanicHit(p, Bld.id("slab_splitter")));
        h.assertTrue(l.getBlockState(s).getValue(SlabBlock.TYPE) == SlabType.BOTTOM, "bottom half stays");

        also(p, "moving_day", 10);
        BlockPos c = abs(h, 0, 100, 0);
        l.setBlock(c, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity chest = (ChestBlockEntity) l.getBlockEntity(c);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
        h.assertTrue(Breaking.carry(p, l, c, l.getBlockState(c), chest, HabitsApi.mechanicHit(p, Bld.id("moving_day"))), "carried");
        h.assertTrue(l.getBlockState(c).isAir(), "chest gone");
        ItemStack got = p.getInventory().items.stream().filter(x -> x.is(Items.CHEST)).findFirst().orElse(ItemStack.EMPTY);
        h.assertTrue(got.has(DataComponents.BLOCK_ENTITY_DATA) && got.get(DataComponents.BLOCK_ENTITY_DATA).copyTag().toString().contains("diamond"), "contents ride along");
        h.assertTrue(l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(c).inflate(2),
                e -> e.getItem().is(Items.DIAMOND)).isEmpty(), "nothing spilled");
        h.succeed();
    }

    // ---------------------------------------------------------------- movement

    /** Sky builder grants flight only while a block is held; the elevator rides to the next wool up. */
    @GameTest(template = "empty")
    public static void skyBuilderAndElevator(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer p = player(h, new BlockPos(2, 111, 2), "sky_builder", 10);
        l.setBlock(abs(h, 2, 110, 2), Blocks.WHITE_WOOL.defaultBlockState(), 3);
        l.setBlock(abs(h, 2, 116, 2), Blocks.WHITE_WOOL.defaultBlockState(), 3);
        p.setOnGround(true);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        Moving.skyBuilder(p);
        h.assertTrue(p.getAbilities().mayfly, "may fly holding a block");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Moving.skyBuilder(p);
        h.assertFalse(p.getAbilities().mayfly, "flight taken back");

        also(p, "elevator", 10);
        h.assertValueEqual(Moving.elevator(p, true), abs(h, 2, 117, 2).getY(), "rides up to the next wool");
        h.succeed();
    }

    // ---------------------------------------------------------------- crafting, events

    /** Sawmill at Lv10 always adds planks; a build at height fires the high_place event. */
    @GameTest(template = "empty")
    public static void sawmillAndEvents(GameTestHelper h) {
        ServerPlayer p = player(h, new BlockPos(2, 1, 2), "sawmill", 10);
        NeoForge.EVENT_BUS.post(new PlayerEvent.ItemCraftedEvent(p, new ItemStack(Items.OAK_PLANKS, 4), new SimpleContainer(1)));
        h.assertValueEqual(p.getInventory().countItem(Items.OAK_PLANKS), 2, "two extra planks");

        List<String> seen = new ArrayList<>();
        Triggers.Spy spy = (sp, type, ctx) -> { if (sp == p && type == TriggerType.CUSTOM) seen.add(String.valueOf(ctx.target())); };
        Triggers.SPIES.add(spy);
        try {
            BlockPos high = new BlockPos(h.absolutePos(BlockPos.ZERO).getX(), BuildingMechanics.HIGH_PLACE_Y.getAsInt() + 5, h.absolutePos(BlockPos.ZERO).getZ());
            place(p, high, Blocks.STONE.defaultBlockState());
            h.getLevel().setBlock(high, Blocks.AIR.defaultBlockState(), 3);
        } finally {
            Triggers.SPIES.remove(spy);
        }
        h.assertTrue(seen.contains("habits:building/high_place"), "high_place fired: " + seen);
        h.succeed();
    }

    /** Opening one gate of a row opens the matching gates beside it. */
    @GameTest(template = "empty")
    public static void doubleDoorsOpenTheRow(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        BlockPos g = abs(h, 0, 120, 2);
        for (int i = 0; i < 3; i++) l.setBlock(g.east(i), Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, Direction.NORTH), 3);
        ServerPlayer p = player(h, new BlockPos(1, 121, 0), "double_doors", 10);
        l.setBlock(g, l.getBlockState(g).setValue(net.minecraft.world.level.block.FenceGateBlock.OPEN, true), 3);
        Using.doors(p, l, g, HabitsApi.mechanicHit(p, Bld.id("double_doors")));
        h.assertTrue(l.getBlockState(g.east(2)).getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN), "the row opened");
        h.succeed();
    }
}
