package com.habits.perk.building;

import com.habits.Habits;
import com.habits.core.Category;
import com.habits.core.CoreRegistries;
import com.habits.core.Specs;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Sculptor statue: owner gating, the blessing pulse, one active statue per owner. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class StatueTests {
    private StatueTests() {}

    static ServerPlayer sculptor(GameTestHelper h, BlockPos rel, boolean building) {
        ServerPlayer p = BuildingTests.player(h, rel, "endless_supply", 1);
        p.setData(CoreRegistries.SPECS, new Specs(List.of(Category.WARRIOR, building ? Category.BUILDING : Category.MINING), false));
        return p;
    }

    static StatueBlockEntity place(GameTestHelper h, ServerPlayer p, BlockPos rel) {
        ServerLevel l = h.getLevel();
        BlockPos at = h.absolutePos(rel);
        l.setBlock(at, Statue.BLOCK.get().defaultBlockState(), 3);
        StatueBlock.placed(l, at, p);
        return (StatueBlockEntity) l.getBlockEntity(at);
    }

    @GameTest(template = "empty")
    public static void statueOwnerGatingAndBlessing(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer miner = sculptor(h, new BlockPos(1, 1, 1), false);
        StatueBlockEntity inert = place(h, miner, new BlockPos(0, 1, 0));
        Statue.pulse(l, inert.getBlockPos(), inert, List.of(miner));
        h.assertTrue(inert.owner == null && !miner.hasEffect(MobEffects.REGENERATION), "a non-sculptor's statue stays inert");

        ServerPlayer owner = sculptor(h, new BlockPos(3, 1, 3), true);
        StatueBlockEntity be = place(h, owner, new BlockPos(4, 1, 4));
        h.assertTrue(owner.getUUID().equals(be.owner), "the sculptor owns the statue");
        ServerPlayer far = sculptor(h, new BlockPos(1, 1, 1), false);
        far.moveTo(far.getX() + Statue.RADIUS.getAsInt() + 4, far.getY(), far.getZ());
        Statue.pulse(l, be.getBlockPos(), be, List.of(miner, far));
        h.assertTrue(miner.hasEffect(MobEffects.REGENERATION), "a nearby player gets Vitality");
        h.assertFalse(far.hasEffect(MobEffects.REGENERATION), "a player out of range gets nothing");
        StatueBlock.use(l, be.getBlockPos(), owner);
        h.assertTrue(be.blessing() == Statue.Blessing.FORTUNE, "owner right-click cycles to Fortune");
        StatueBlock.use(l, be.getBlockPos(), miner);
        h.assertTrue(be.blessing() == Statue.Blessing.FORTUNE, "someone else's right-click only shows it");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void statueOneActivePerOwner(GameTestHelper h) {
        ServerLevel l = h.getLevel();
        ServerPlayer owner = sculptor(h, new BlockPos(2, 1, 2), true);
        StatueBlockEntity a = place(h, owner, new BlockPos(0, 1, 0));
        StatueBlockEntity b = place(h, owner, new BlockPos(4, 1, 4));
        h.assertFalse(Statue.active(l, a.getBlockPos(), owner.getUUID()), "the older statue goes dormant");
        h.assertTrue(Statue.active(l, b.getBlockPos(), owner.getUUID()), "the newest statue is active");
        Statue.pulse(l, a.getBlockPos(), a, List.of(owner));
        h.assertFalse(owner.hasEffect(MobEffects.REGENERATION), "a dormant statue blesses nobody");
        StatueBlock.use(l, a.getBlockPos(), owner);
        h.assertTrue(Statue.active(l, a.getBlockPos(), owner.getUUID()) && !Statue.active(l, b.getBlockPos(), owner.getUUID()),
                "re-awakening the old statue puts the new one to sleep");
        h.assertTrue(a.blessing() == Statue.Blessing.VITALITY, "re-awakening does not cycle the blessing");
        h.succeed();
    }
}
