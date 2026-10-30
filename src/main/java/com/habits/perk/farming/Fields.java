package com.habits.perk.farming;

import com.habits.core.HabitsApi.PerkHit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Spec batch: what happens when you harvest a ripe crop (bonus drops, streaks, area harvests). Mechanic ids are
 * {@code habits:farming/<skill>}; docs/mechanics/farming-nature.md has the table.
 */
public final class Fields {
    private Fields() {}

    static PerkHit hit(Player p, String skill) {
        return Farm.hit(p, "farming/" + skill);
    }

    /** Streak window and thresholds for the harvest_streak / big_streak events. */
    static final int WINDOW = 1200, STREAK = 32, BIG_STREAK = 96;

    static boolean hoe(ItemStack s) {
        return s.is(net.minecraft.tags.ItemTags.HOES);
    }

    @SubscribeEvent
    public static void onDrops(BlockDropsEvent e) {
        if (!(e.getBreaker() instanceof ServerPlayer p) || !Farm.ok(p)) return;
        ServerLevel l = e.getLevel();
        BlockState s = e.getState();
        BlockPos pos = e.getPos();
        Block b = s.getBlock();
        boolean fruit = (b == Blocks.MELON || b == Blocks.PUMPKIN) && Crops.grownOnStem(l, pos);
        if (!Farm.mature(s) && !fruit) return;
        Vec3 at = Vec3.atCenterOf(pos);
        List<ItemEntity> drops = e.getDrops();
        events(p, l, pos, s, at);
        streak(p, l);
        PerkHit h;
        if ((h = hit(p, "harvest_feast")) != null && Farm.roll(l, h.value())) p.getFoodData().eat(1, 0f);
        if ((h = hit(p, "gilded_harvest")) != null && p.getMainHandItem().is(Items.GOLDEN_HOE)) {
            Item crop = crop(drops, l, pos, s);
            if (crop != Items.AIR) Crops.add(l, drops, pos, new ItemStack(crop, Farm.round(l, h.value())));
        }
        if ((h = hit(p, "green_wisdom")) != null && Farm.roll(l, h.value()))
            ExperienceOrb.award(l, at, Math.max(1, Farm.round(l, Farm.lerp(h, "xp", 1, 3))));
        if ((h = hit(p, "giant_crop")) != null && Farm.roll(l, h.value())) Crops.add(l, drops, pos, giant(drops, l, pos, s));
        if (l.getGameTime() < Farm.data(p).getLong("habits_festival"))
            for (ItemEntity d : List.copyOf(drops)) Crops.add(l, drops, pos, d.getItem().copy());
        if ((h = hit(p, "verdant_crown")) != null && b instanceof CropBlock crop) verdantCrown(l, drops, pos, crop, h);
        if (Farm.busy) return; // the area harvests below never chain off their own breaks
        if ((h = hit(p, "harvest_king")) != null && Farm.roll(l, h.value())) harvestKing(p, l, pos, s, (int) Farm.lerp(h, "radius", 4, 12));
        if ((h = hit(p, "scythe")) != null && hoe(p.getMainHandItem()) && !fruit) scythe(p, pos, s, (int) h.value());
        if ((h = hit(p, "thousand_hands")) != null && !fruit) thousandHands(p, l, pos, s, (int) h.value());
    }

    /** Green Sovereign: a chance the drops come twice, and the crop always comes back, {@code grow} stages along. */
    static void verdantCrown(ServerLevel l, List<ItemEntity> drops, BlockPos pos, CropBlock crop, PerkHit h) {
        if (Farm.roll(l, h.value())) for (ItemEntity d : List.copyOf(drops)) Crops.add(l, drops, pos, d.getItem().copy());
        BlockState grown = crop.getStateForAge(Math.min(crop.getMaxAge() - 1, (int) Farm.lerp(h, "grow", 1, 4)));
        // the block is gone only after the drops; replant at the end of the tick unless something else already did
        com.habits.perk.Later.run(l, 0, () -> {
            if (Farm.loaded(l, pos) && l.getBlockState(pos).isAir() && grown.canSurvive(l, pos)) l.setBlock(pos, grown, 3);
        });
    }

    /** The custom events a ripe harvest can fire. */
    static void events(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s, Vec3 at) {
        if (l.isRainingAt(pos.above())) Farm.fire(p, "farming/rain_harvest", at);
        if (l.isNight()) Farm.fire(p, "farming/night_harvest", at);
        if (p.getFoodData().getFoodLevel() <= 6) Farm.fire(p, "farming/hungry_harvest", at);
        if (fullField(l, pos, s)) Farm.fire(p, "farming/full_field", at);
    }

    /** The 8 blocks around a broken crop are the same crop, all ripe. */
    static boolean fullField(ServerLevel l, BlockPos pos, BlockState s) {
        if (!Farm.mature(s)) return false;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockState t = l.getBlockState(pos.offset(dx, 0, dz));
                if (t.getBlock() != s.getBlock() || !Farm.mature(t)) return false;
            }
        return true;
    }

    /** Ripe harvests in one minute: 32 fires harvest_streak (and In the Zone, the festival), 96 fires big_streak. */
    static void streak(ServerPlayer p, ServerLevel l) {
        var d = Farm.data(p);
        long now = l.getGameTime();
        int n = now - d.getLong("habits_streak_t") > WINDOW ? 0 : d.getInt("habits_streak_n");
        if (n == 0) d.putLong("habits_streak_t", now);
        d.putInt("habits_streak_n", ++n);
        if (n == STREAK) {
            Farm.fire(p, "farming/harvest_streak", null);
            PerkHit h;
            if ((h = hit(p, "in_the_zone")) != null) {
                int t = (int) (h.value() * 20);
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, t, 0));
                p.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, t, 0));
            }
            if ((h = hit(p, "harvest_festival")) != null) {
                int t = (int) (h.value() * 20);
                d.putLong("habits_festival", now + t);
                p.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, t, 1));
                l.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1, p.getZ(), 20, 1, 1, 1, 0);
            }
        }
        if (n == BIG_STREAK) Farm.fire(p, "farming/big_streak", null);
    }

    /** The crop item of a harvest: the first drop that isn't the seed, else the seed (carrots, potatoes). */
    static Item crop(List<ItemEntity> drops, ServerLevel l, BlockPos pos, BlockState s) {
        Item seed = s.getBlock() == Blocks.MELON || s.getBlock() == Blocks.PUMPKIN ? Items.AIR : Crops.seedOf(l, pos, s);
        for (ItemEntity d : drops) if (!d.getItem().is(seed)) return d.getItem().getItem();
        return seed;
    }

    /** A whole block of the crop: hay for wheat, a wart block for wart, else 9 of the crop item. */
    static ItemStack giant(List<ItemEntity> drops, ServerLevel l, BlockPos pos, BlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.WHEAT) return new ItemStack(Items.HAY_BLOCK);
        if (b == Blocks.NETHER_WART) return new ItemStack(Items.NETHER_WART_BLOCK);
        if (b == Blocks.MELON) return new ItemStack(Items.MELON);
        if (b == Blocks.PUMPKIN) return new ItemStack(Items.PUMPKIN, 2);
        Item c = crop(drops, l, pos, s);
        return c == Items.AIR ? ItemStack.EMPTY : new ItemStack(c, 9);
    }

    /** Ripen every crop of the same kind around, a few columns a tick. */
    static void harvestKing(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s, int r) {
        Block kind = s.getBlock();
        if (!(kind instanceof CropBlock || kind instanceof NetherWartBlock || kind instanceof CocoaBlock)) return;
        var it = BlockPos.betweenClosed(pos.offset(-r, -2, -r), pos.offset(r, 2, r)).iterator();
        BlockJobs.add(p, BlockJobs.visiting(it, BlockJobs.BUDGET, q -> {
            BlockState t = l.getBlockState(q);
            if (t.getBlock() != kind || Farm.mature(t)) return 0;
            l.setBlock(q, Crops.atAge(t, Crops.maxAge(t)), Block.UPDATE_CLIENTS);
            if (l.random.nextInt(4) == 0) l.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, q.getX() + 0.5, q.getY() + 0.5, q.getZ() + 0.5, 2, 0.2, 0.2, 0.2, 0);
            return 4;
        }));
    }

    /** A 3 wide row ahead of you, {@code len} long, of ripe crops. */
    static void scythe(ServerPlayer p, BlockPos pos, BlockState s, int len) {
        Direction f = p.getDirection(), side = f.getClockWise();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        for (int a = 0; a <= len; a++)
            for (int b = -1; b <= 1; b++) {
                BlockPos q = pos.relative(f, a).relative(side, b);
                if (!q.equals(pos)) todo.add(q);
            }
        BlockJobs.add(p, BlockJobs.breaking(todo, BlockJobs.BUDGET, t -> t.is(BlockTags.CROPS) && Farm.mature(t), null, null));
    }

    /** The connected patch of the same ripe crop (sideways and one block up or down), up to {@code max}. */
    static void thousandHands(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s, int max) {
        Block kind = s.getBlock();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>(), todo = new ArrayDeque<>();
        seen.add(pos);
        open.add(pos);
        while (!open.isEmpty() && todo.size() < max) {
            BlockPos c = open.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos q = c.offset(dx, dy, dz);
                        if (todo.size() >= max || !seen.add(q) || !Farm.loaded(l, q)) continue;
                        BlockState t = l.getBlockState(q);
                        if (t.getBlock() != kind || !Farm.mature(t)) continue;
                        todo.add(q);
                        open.add(q);
                    }
        }
        if (!todo.isEmpty()) BlockJobs.add(p, BlockJobs.breaking(todo, BlockJobs.BUDGET, t -> t.getBlock() == kind && Farm.mature(t), null, null));
    }

    /** Seeds: any item that plants a crop or a stem. */
    static boolean seed(ItemStack s) {
        return s.is(Tags.Items.SEEDS) || s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.NETHER_WART);
    }
}
