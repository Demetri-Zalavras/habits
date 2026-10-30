package com.habits.perk.farming;

import com.habits.core.HabitsApi.PerkHit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SuspiciousEffectHolder;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Crops, soil and flowers. */
public final class Crops {
    private Crops() {}

    // ================================================================ break

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (Farm.busy || !(e.getPlayer() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel l)) return;
        BlockState s = e.getState();
        BlockPos pos = e.getPos();
        if (Farm.mature(s) && l.isNight() && l.getMoonPhase() == 0) Farm.fire(p, "full_moon_harvest", Vec3.atCenterOf(pos));
        greenThumb(p, l, pos, s);
        if (stalkCutter(p, l, pos, s)) e.setCanceled(true);
        kelpLung(p, s);
        flowerPower(p, s);
    }

    static void greenThumb(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s) {
        if (!s.is(BlockTags.CROPS) || !Farm.mature(s)) return;
        PerkHit h = Farm.hit(p, "green_thumb");
        if (h == null) return;
        int r = (int) h.value();
        double chance = Farm.lerp(h, "chance", 30, 100);
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-r, -1, -r), pos.offset(r, 1, r))) {
            if (q.equals(pos) || !Farm.loaded(l, q)) continue;
            BlockState t = l.getBlockState(q);
            if (t.is(BlockTags.CROPS) && Farm.mature(t) && Farm.roll(l, chance)) todo.add(q.immutable());
        }
        if (!todo.isEmpty()) BlockJobs.add(p, BlockJobs.breaking(todo, BlockJobs.BUDGET, t -> t.is(BlockTags.CROPS) && Farm.mature(t), null, null));
    }

    /** Which stalk family a block is in (cane, bamboo, cactus, kelp), or -1. */
    static int stalk(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof SugarCaneBlock) return 0;
        if (b instanceof BambooStalkBlock) return 1;
        if (b instanceof CactusBlock) return 2;
        if (b == Blocks.KELP || b == Blocks.KELP_PLANT) return 3;
        return -1;
    }

    static BlockPos bottom(Level l, BlockPos pos, int fam) {
        BlockPos b = pos;
        for (int i = 0; i < 64 && stalk(l.getBlockState(b.below())) == fam; i++) b = b.below();
        return b;
    }

    /** Returns true when the broken base must stay (the break is cancelled and the stalk above is cut instead). */
    static boolean stalkCutter(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s) {
        int fam = stalk(s);
        if (fam < 0 || p.isShiftKeyDown()) return false;
        PerkHit h = Farm.hit(p, "stalk_cutter");
        if (h == null) return false;
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        BlockPos base = bottom(l, pos, fam);
        boolean keepBase = base.equals(pos) && stalk(l.getBlockState(pos.above())) == fam;
        if (keepBase) todo.add(pos.above());
        int r = (int) h.value();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos q = pos.offset(dx, 0, dz);
                if (!Farm.loaded(l, q) || stalk(l.getBlockState(q)) != fam) continue;
                BlockPos b = bottom(l, q, fam);
                if (stalk(l.getBlockState(b.above())) == fam) todo.add(b.above());
            }
        if (!todo.isEmpty()) BlockJobs.add(p, BlockJobs.breaking(todo, BlockJobs.BUDGET, t -> stalk(t) == fam, null, null));
        return keepBase;
    }

    static void kelpLung(ServerPlayer p, BlockState s) {
        if (!s.is(Blocks.KELP) && !s.is(Blocks.KELP_PLANT)) return;
        PerkHit h = Farm.hit(p, "kelp_lung");
        if (h == null) return;
        int cap = (int) (Farm.lerp(h, "cap", 30, 300) * 20);
        var d = Farm.data(p);
        d.putInt("habits_air", Math.min(cap, d.getInt("habits_air") + (int) (h.value() * 20)));
    }

    /** Air bank pays out only while you are drowning (eyes in water, air going down), never for air that refills on its own. */
    static void kelpTick(Player p, PerkHit h) {
        if (p.level().isClientSide) return;
        var d = Farm.data(p);
        int bank = d.getInt("habits_air"), air = p.getAirSupply(), max = p.getMaxAirSupply();
        if (bank <= 0 || air >= max || !p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) || p.canBreatheUnderwater()
                || net.minecraft.world.effect.MobEffectUtil.hasWaterBreathing(p)) return;
        int add = Math.min(bank, max - air);
        p.setAirSupply(air + add);
        d.putInt("habits_air", bank - add);
    }

    static void flowerPower(ServerPlayer p, BlockState s) {
        if (!s.is(BlockTags.FLOWERS)) return;
        PerkHit h = Farm.hit(p, "flower_power");
        SuspiciousEffectHolder holder = h == null ? null : SuspiciousEffectHolder.tryGet(s.getBlock());
        if (holder == null) return;
        int amp = (int) Farm.lerp(h, "amp", 0, 1);
        for (var en : holder.getSuspiciousEffects().effects())
            if (en.effect().value().getCategory() == MobEffectCategory.BENEFICIAL)
                p.addEffect(new MobEffectInstance(en.effect(), (int) (h.value() * 20), amp));
    }

    // ================================================================ drops

    @SubscribeEvent
    public static void onDrops(BlockDropsEvent e) {
        if (!(e.getBreaker() instanceof ServerPlayer p) || !Farm.ok(p)) return;
        ServerLevel l = e.getLevel();
        BlockState s = e.getState();
        BlockPos pos = e.getPos();
        List<ItemEntity> drops = e.getDrops();
        Block b = s.getBlock();
        boolean ripe = Farm.mature(s);
        if (ripe) {
            replant(p, l, pos, s, drops);
            spreadSeeds(p, l, pos, s, drops);
        }
        if (ripe || (b == Blocks.MELON || b == Blocks.PUMPKIN) && grownOnStem(l, pos)) {
            edensTouch(p, l, pos, b, drops);
            FarmTriggers.harvested(p, l, b, Vec3.atCenterOf(pos));
        }
        PerkHit h;
        if (ripe && b == Blocks.CARROTS && (h = Farm.hit(p, "carrot_top")) != null)
            swap(l, drops, Items.CARROT, Items.GOLDEN_CARROT, h.value());
        if (ripe && b == Blocks.POTATOES && (h = Farm.hit(p, "hot_potato")) != null) {
            swap(l, drops, Items.POTATO, Items.BAKED_POTATO, h.value());
            if (!Farm.roll(l, Farm.lerp(h, "poison", 50, 0))) drops.removeIf(d -> d.getItem().is(Items.POISONOUS_POTATO));
        }
        if (ripe && b == Blocks.NETHER_WART && (h = Farm.hit(p, "wart_warden")) != null && Farm.roll(l, h.value())) {
            double tear = Farm.lerp(h, "tear", 1, 5);
            if (l.random.nextDouble() < tear / (30 + tear)) add(l, drops, pos, new ItemStack(Items.GHAST_TEAR));
            else for (ItemStack it : Farm.loot(l, Farm.table("farming/brewers_bounty"), Vec3.atCenterOf(pos), p)) add(l, drops, pos, it);
        }
        if ((b == Blocks.MELON || b == Blocks.PUMPKIN) && (h = Farm.hit(p, "melon_smash")) != null) {
            if (b == Blocks.MELON && Farm.roll(l, h.value())) swapOne(l, drops, Items.MELON_SLICE, Items.GLISTERING_MELON_SLICE);
            double pull = Farm.lerp(h, "pull", 3, 10);
            if (p.distanceToSqr(Vec3.atCenterOf(pos)) <= pull * pull) {
                drops.removeIf(d -> {
                    if (!d.getItem().is(Items.MELON_SLICE) && !d.getItem().is(Items.GLISTERING_MELON_SLICE) && !d.getItem().is(Items.PUMPKIN)) return false;
                    Farm.give(p, d.getItem());
                    return true;
                });
            }
        }
        if ((b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN || b == Blocks.LARGE_FERN)
                && (h = Farm.hit(p, "grass_cutter")) != null && Farm.roll(l, h.value())) {
            if (Farm.roll(l, Farm.lerp(h, "rare", 2, 10)))
                for (ItemStack it : Farm.loot(l, Farm.table("farming/rare_seeds"), Vec3.atCenterOf(pos), p)) add(l, drops, pos, it);
            else add(l, drops, pos, new ItemStack(Items.WHEAT_SEEDS));
        }
        Trees.onDrops(p, l, pos, s, drops);
    }

    /**
     * A melon or pumpkin counts as a harvest only next to a grown stem, so placing and breaking one is not. By drop time the
     * block is gone and its attached stem has already turned back into a full-grown plain stem.
     */
    static boolean grownOnStem(Level l, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockState n = l.getBlockState(pos.relative(dir));
            if (n.getBlock() instanceof net.minecraft.world.level.block.AttachedStemBlock
                    && n.getValue(net.minecraft.world.level.block.AttachedStemBlock.FACING) == dir.getOpposite()) return true;
            if (n.getBlock() instanceof StemBlock && n.getValue(StemBlock.AGE) >= StemBlock.MAX_AGE) return true;
        }
        return false;
    }

    /** Eden's Touch (Mythical): value % of harvests add a golden crop; at Lv10 also a {@code notch} % enchanted golden apple. */
    static void edensTouch(ServerPlayer p, Level l, BlockPos pos, Block b, List<ItemEntity> drops) {
        PerkHit h = Farm.hit(p, "edens_touch");
        if (h == null) return;
        if (Farm.roll(l, h.value()))
            add(l, drops, pos, new ItemStack(b == Blocks.CARROTS ? Items.GOLDEN_CARROT : b == Blocks.MELON ? Items.GLISTERING_MELON_SLICE : Items.GOLDEN_APPLE));
        if (h.level() >= com.habits.core.Leveling.MAX_LEVEL && Farm.roll(l, num(h.skill().perk().param("notch", "0"))))
            add(l, drops, pos, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void add(Level l, List<ItemEntity> drops, BlockPos pos, ItemStack s) {
        if (s.isEmpty()) return;
        ItemEntity e = new ItemEntity(l, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, s);
        e.setDefaultPickUpDelay();
        drops.add(e);
    }

    /** Each {@code from} item has {@code percent} chance to become {@code to}. */
    static void swap(Level l, List<ItemEntity> drops, Item from, Item to, double percent) {
        for (ItemEntity d : List.copyOf(drops)) {
            if (!d.getItem().is(from)) continue;
            int k = 0;
            for (int i = 0; i < d.getItem().getCount(); i++) if (Farm.roll(l, percent)) k++;
            if (k == 0) continue;
            d.getItem().shrink(k);
            if (d.getItem().isEmpty()) drops.remove(d);
            add(l, drops, d.blockPosition(), new ItemStack(to, k));
        }
    }

    static void swapOne(Level l, List<ItemEntity> drops, Item from, Item to) {
        for (ItemEntity d : List.copyOf(drops))
            if (d.getItem().is(from)) {
                d.getItem().shrink(1);
                if (d.getItem().isEmpty()) drops.remove(d);
                add(l, drops, d.blockPosition(), new ItemStack(to));
                return;
            }
    }

    /** Take {@code n} of {@code item} out of the drops. False (nothing taken) if there aren't enough. */
    static boolean take(List<ItemEntity> drops, Item item, int n) {
        int have = 0;
        for (ItemEntity d : drops) if (d.getItem().is(item)) have += d.getItem().getCount();
        if (have < n) return false;
        for (ItemEntity d : List.copyOf(drops)) {
            if (n <= 0) break;
            if (!d.getItem().is(item)) continue;
            int k = Math.min(n, d.getItem().getCount());
            d.getItem().shrink(k);
            n -= k;
            if (d.getItem().isEmpty()) drops.remove(d);
        }
        return true;
    }

    static int count(List<ItemEntity> drops, Item item) {
        int n = 0;
        for (ItemEntity d : drops) if (d.getItem().is(item)) n += d.getItem().getCount();
        return n;
    }

    static Item seedOf(Level l, BlockPos pos, BlockState s) {
        return s.getBlock().getCloneItemStack(l, pos, s).getItem();
    }

    /** The same crop at {@code age}, keeping other properties (cocoa facing). */
    static BlockState atAge(BlockState s, int age) {
        if (s.getBlock() instanceof CropBlock c) return c.getStateForAge(age);
        if (s.getBlock() instanceof NetherWartBlock) return s.setValue(NetherWartBlock.AGE, age);
        if (s.getBlock() instanceof CocoaBlock) return s.setValue(CocoaBlock.AGE, age);
        return s;
    }

    static int maxAge(BlockState s) {
        if (s.getBlock() instanceof CropBlock c) return c.getMaxAge();
        if (s.getBlock() instanceof NetherWartBlock) return NetherWartBlock.MAX_AGE;
        if (s.getBlock() instanceof CocoaBlock) return CocoaBlock.MAX_AGE;
        return 0;
    }

    static void replant(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s, List<ItemEntity> drops) {
        PerkHit h = Farm.hit(p, "breadbasket");
        if (h == null || !Farm.roll(l, h.value()) || !l.getBlockState(pos).isAir()) return;
        int limit = (int) Math.floor(maxAge(s) * Farm.lerp(h, "growth", 0, 50) / 100.0);
        BlockState fresh = atAge(s, l.random.nextInt(limit + 1));
        if (fresh.canSurvive(l, pos) && take(drops, seedOf(l, pos, s), 1)) l.setBlockAndUpdate(pos, fresh);
    }

    static void spreadSeeds(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s, List<ItemEntity> drops) {
        if (!(s.getBlock() instanceof CropBlock crop)) return;
        PerkHit h = Farm.hit(p, "seed_spreader");
        if (h == null) return;
        Item seed = seedOf(l, pos, s);
        // carrots and potatoes are their own seed: that's the harvest, not spare seed
        if (seed.getDefaultInstance().has(net.minecraft.core.component.DataComponents.FOOD)) return;
        int spare = count(drops, seed);
        int n = Math.min(spare, (int) Farm.lerp(h, "seeds", 1, 64));
        int r = (int) h.value();
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-r, -1, -r), pos.offset(r, 1, r))) {
            if (n <= 0) break;
            if (q.equals(pos) || !Farm.loaded(l, q) || !l.getBlockState(q).isAir() || !(l.getBlockState(q.below()).getBlock() instanceof FarmBlock))
                continue;
            if (!take(drops, seed, 1)) break;
            l.setBlockAndUpdate(q, crop.defaultBlockState());
            l.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, q.getX() + 0.5, q.getY() + 0.3, q.getZ() + 0.5, 2, 0.2, 0.1, 0.2, 0);
            n--;
        }
    }

    // ================================================================ place

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (Farm.busy || !(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel l) || !Farm.ok(p)) return;
        BlockState s = e.getPlacedBlock();
        BlockPos pos = e.getPos();
        if (s.is(BlockTags.SAPLINGS) && l.isDay() && l.canSeeSky(pos.above())) Farm.fire(p, "sun_planting", Vec3.atCenterOf(pos));
        PerkHit h;
        if ((s.is(Blocks.JACK_O_LANTERN) || s.is(Blocks.CARVED_PUMPKIN)) && (h = Farm.hit(p, "scarecrow")) != null)
            FarmSaved.get(l).add(pos, FarmSaved.SCARECROW, (float) h.value(), 0);
        if (isGardenPlant(s) && (h = Farm.hit(p, "ancient_gardener")) != null)
            FarmSaved.get(l).add(pos, FarmSaved.GARDEN, (float) Farm.lerp(h, "radius", 2, 8), (float) h.value());
        if (s.is(Blocks.WITHER_ROSE) && (h = Farm.hit(p, "death_bloom")) != null)
            FarmSaved.get(l).add(pos, FarmSaved.ROSE, (float) h.value(), (float) Farm.lerp(h, "amp", 0, 2) + (h.level() >= 10 ? 100 : 0));
        if (s.getBlock() instanceof CropBlock && (h = Farm.hit(p, "sower")) != null) sow(p, l, pos, s, h);
    }

    static boolean isGardenPlant(BlockState s) {
        return s.is(Blocks.TORCHFLOWER) || s.is(Blocks.TORCHFLOWER_CROP) || s.is(Blocks.PITCHER_CROP) || s.is(Blocks.PITCHER_PLANT);
    }

    /** A {@code width} wide patch centred on pos: a single row across the facing direction on Lv1, a square after. */
    static List<BlockPos> patch(BlockPos pos, Direction facing, int width, boolean row) {
        List<BlockPos> out = new ArrayList<>();
        int h = width / 2;
        Direction side = facing.getClockWise();
        for (int a = -h; a <= h; a++)
            for (int b = row ? 0 : -h; b <= (row ? 0 : h); b++)
                out.add(pos.relative(side, a).relative(facing, b));
        return out;
    }

    static void sow(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s, PerkHit h) {
        ItemStack stack = p.getMainHandItem().getItem() instanceof BlockItem bi && bi.getBlock() == s.getBlock() ? p.getMainHandItem()
                : p.getOffhandItem().getItem() instanceof BlockItem bo && bo.getBlock() == s.getBlock() ? p.getOffhandItem() : ItemStack.EMPTY;
        if (stack.isEmpty()) return;
        boolean free = p.getAbilities().instabuild;
        for (BlockPos q : patch(pos, p.getDirection(), (int) h.value(), h.level() <= 1)) {
            if (q.equals(pos)) continue;
            if (!free && stack.getCount() <= 1) break; // the vanilla placement still takes one
            if (!Farm.loaded(l, q) || !l.getBlockState(q).isAir() || !(l.getBlockState(q.below()).getBlock() instanceof FarmBlock)) continue;
            l.setBlockAndUpdate(q, s.getBlock().defaultBlockState());
            if (!free) stack.shrink(1);
        }
    }

    // ================================================================ tools

    @SubscribeEvent
    public static void onToolModify(BlockEvent.BlockToolModificationEvent e) {
        if (e.isSimulated() || Farm.busy || !(e.getPlayer() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel l)) return;
        if (e.getItemAbility() == ItemAbilities.HOE_TILL) till(p, l, e.getPos(), e.getState(), e.getHeldItemStack());
        if (e.getItemAbility() == ItemAbilities.AXE_STRIP) Trees.sapTap(p, l, e.getState());
    }

    static boolean tillable(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.DIRT_PATH);
    }

    static void till(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s, ItemStack hoe) {
        PerkHit h = Farm.hit(p, "tiller");
        if (h == null || !tillable(s)) return;
        for (BlockPos q : patch(pos, p.getDirection(), (int) h.value(), h.level() <= 1)) {
            if (q.equals(pos) || hoe.isEmpty() || !Farm.loaded(l, q)) continue;
            if (!tillable(l.getBlockState(q)) || !l.getBlockState(q.above()).isAir()) continue;
            l.setBlockAndUpdate(q, Blocks.FARMLAND.defaultBlockState());
            hoe.hurtAndBreak(1, p, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        }
    }

    @SubscribeEvent
    public static void onTrample(BlockEvent.FarmlandTrampleEvent e) {
        Entity ent = e.getEntity();
        Player p = ent instanceof Player pl ? pl : ent.getControllingPassenger() instanceof Player cp ? cp : null;
        if (p == null || p.level().isClientSide) return;
        Farm.fire(p, "trample", Vec3.atCenterOf(e.getPos()));
        if (Farm.hit(p, "light_step") != null) e.setCanceled(true);
    }

    /** Keep farmland near you wet: a few random columns every 2 seconds instead of a scan. */
    static void lightStepTick(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || (l.getGameTime() + p.getId()) % 40 != 0) return;
        int r = (int) h.value();
        if (r <= 0) return;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 24; i++) {
            int x = p.getBlockX() + l.random.nextInt(2 * r + 1) - r, z = p.getBlockZ() + l.random.nextInt(2 * r + 1) - r;
            for (int dy = -2; dy <= 1; dy++) {
                q.set(x, p.getBlockY() + dy, z);
                if (!Farm.loaded(l, q)) break;
                BlockState t = l.getBlockState(q);
                if (t.getBlock() instanceof FarmBlock && t.getValue(FarmBlock.MOISTURE) < FarmBlock.MAX_MOISTURE)
                    l.setBlock(q, t.setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), Block.UPDATE_CLIENTS);
            }
        }
    }

    @SubscribeEvent
    public static void onBonemeal(BonemealEvent e) {
        if (Farm.busy || !(e.getLevel() instanceof ServerLevel l) || !(e.getPlayer() instanceof ServerPlayer p) || !e.isValidBonemealTarget()) return;
        if (Trees.forceMushroom(e, p, l)) return;
        PerkHit h = Farm.hit(p, "fertilizer");
        if (h == null) return;
        int r = (int) h.value(), fx = 0;
        double chance = Farm.lerp(h, "chance", 30, 100);
        BlockPos pos = e.getPos();
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-r, -1, -r), pos.offset(r, 1, r))) {
            if (q.equals(pos) || !Farm.loaded(l, q)) continue;
            BlockState t = l.getBlockState(q);
            if (!(t.getBlock() instanceof BonemealableBlock b) || t.is(Blocks.GRASS_BLOCK) || !b.isValidBonemealTarget(l, q, t) || !Farm.roll(l, chance)) continue;
            if (b.isBonemealSuccess(l, l.random, q, t)) b.performBonemeal(l, l.random, q, t);
            if (fx++ < 16) l.levelEvent(1505, q, 15); // bone meal sparkles, capped
        }
    }

    // ================================================================ ticks

    static void cocoaClimb(Player p, PerkHit h) {
        if (!p.horizontalCollision || p.getAbilities().flying || p.onClimbable() || p.isPassenger()) return;
        boolean log = false;
        for (BlockPos q : BlockPos.betweenClosed(BlockPos.containing(p.getBoundingBox().minX - 0.1, p.getY(), p.getBoundingBox().minZ - 0.1),
                BlockPos.containing(p.getBoundingBox().maxX + 0.1, p.getY() + 1, p.getBoundingBox().maxZ + 0.1)))
            if (p.level().getBlockState(q).is(BlockTags.LOGS)) { log = true; break; }
        if (!log) return;
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(v.x, p.isShiftKeyDown() ? 0 : h.value() / 20.0, v.z);
        p.resetFallDistance();
    }

    static boolean compostable(ItemStack s) {
        return s.is(Items.ROTTEN_FLESH) || s.is(Tags.Items.SEEDS) || s.is(ItemTags.LEAVES) || s.is(ItemTags.SAPLINGS);
    }

    static void rotTick(Player p, PerkHit h) {
        if (p.level().isClientSide || !Farm.cooldown(p, "habits_rot", Farm.lerp(h, "interval", 60, 10))) return;
        int need = Math.max(1, (int) Math.round(h.value())), have = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) if (compostable(inv.items.get(i))) have += inv.items.get(i).getCount();
        if (have < need) return;
        for (int i = 0; i < inv.items.size() && need > 0; i++) {
            ItemStack s = inv.items.get(i);
            if (!compostable(s)) continue;
            int k = Math.min(need, s.getCount());
            s.shrink(k);
            need -= k;
        }
        Farm.give(p, new ItemStack(Items.BONE_MEAL));
    }

    static boolean grows(BlockState s) {
        Block b = s.getBlock();
        return s.isRandomlyTicking() && (b instanceof BushBlock || b instanceof SugarCaneBlock || b instanceof CactusBlock
                || b instanceof CocoaBlock || b instanceof BambooStalkBlock);
    }

    /** Samples random spots each second (never a scan): {@code value * 8} block reads per 9x9 of area, at most {@code value} ticks. */
    static void growthAuraTick(Player p, PerkHit h) {
        if (p.level() instanceof ServerLevel l && (l.getGameTime() + p.getId()) % 20 == 0) growthAura(l, p, h);
    }

    /** One second of aura. Returns the growth ticks given. */
    static int growthAura(ServerLevel l, Player p, PerkHit h) {
        int r = (int) Farm.lerp(h, "radius", 4, 16), want = (int) h.value(), done = 0;
        // samples grow with the area so a farm keeps getting about value ticks however wide the radius is
        int tries = want * 8 * Math.max(1, r * r / 16);
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int i = 0; i < tries && done < want; i++) {
            q.set(p.getBlockX() + l.random.nextInt(2 * r + 1) - r, p.getBlockY() + l.random.nextInt(7) - 3, p.getBlockZ() + l.random.nextInt(2 * r + 1) - r);
            if (!Farm.loaded(l, q)) continue;
            BlockState s = l.getBlockState(q);
            if (!grows(s)) continue;
            s.randomTick(l, q.immutable(), l.random);
            done++;
        }
        return done;
    }

    /** Once per night at midnight: queue a budgeted pass over the surface around you. */
    static void harvestMoonTick(Player p, PerkHit h) {
        if (!(p instanceof ServerPlayer sp) || !(p.level() instanceof ServerLevel l)) return;
        long t = l.getDayTime() % 24000, day = l.getDayTime() / 24000;
        if (t < 18000 || t > 18200 || Farm.data(p).getLong("habits_moon") == day + 1) return;
        Farm.data(p).putLong("habits_moon", day + 1);
        harvestMoon(sp, h, l.getMoonPhase() == 0);
    }

    static void harvestMoon(ServerPlayer p, PerkHit h, boolean fullMoon) {
        ServerLevel l = p.serverLevel();
        int r = (int) Farm.lerp(h, "radius", 8, 48), stages = fullMoon ? 99 : (int) h.value();
        BlockPos c = p.blockPosition();
        Iterator<BlockPos> cols = BlockPos.betweenClosed(c.offset(-r, 0, -r), c.offset(r, 0, r)).iterator();
        // each column looks at your height +-3; a grown crop is a quiet state change (no drops, no neighbour updates)
        BlockJobs.add(p, BlockJobs.visiting(cols, BlockJobs.BUDGET, col -> {
            int cost = 6; // seven reads per column
            for (int y = c.getY() - 3; y <= c.getY() + 3; y++) if (grow(l, new BlockPos(col.getX(), y, col.getZ()), stages)) cost += 4;
            return cost;
        }));
    }

    /** Advance a crop by {@code stages}. Returns true when it changed. */
    static boolean grow(ServerLevel l, BlockPos q, int stages) {
        BlockState s = l.getBlockState(q);
        if (s.getBlock() instanceof CropBlock || s.getBlock() instanceof NetherWartBlock || s.getBlock() instanceof CocoaBlock) {
            int max = maxAge(s), age = s.getBlock() instanceof CropBlock c ? c.getAge(s)
                    : s.getValue(s.getBlock() instanceof NetherWartBlock ? NetherWartBlock.AGE : CocoaBlock.AGE);
            if (age >= max) return false;
            l.setBlock(q, atAge(s, Math.min(max, age + stages)), Block.UPDATE_CLIENTS);
            return true;
        }
        if (!(s.getBlock() instanceof StemBlock || s.getBlock() instanceof SweetBerryBushBlock) || !(s.getBlock() instanceof BonemealableBlock b)) return false;
        boolean any = false;
        for (int i = 0; i < Math.min(stages, 8) && b.isValidBonemealTarget(l, q, l.getBlockState(q)); i++) {
            b.performBonemeal(l, l.random, q, l.getBlockState(q));
            any = true;
        }
        return any;
    }

    static void flowerTrail(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || !p.onGround()) return;
        long here = p.blockPosition().asLong();
        var d = Farm.data(p);
        if (d.getLong("habits_trail") == here) return;
        d.putLong("habits_trail", here);
        BlockPos feet = p.blockPosition();
        if (Farm.roll(l, h.value()) && l.getBlockState(feet).isAir() && l.getBlockState(feet.below()).is(Blocks.GRASS_BLOCK)) {
            BuiltInRegistries.BLOCK.getTag(BlockTags.SMALL_FLOWERS).flatMap(t -> t.getRandomElement(l.random)).ifPresent(f -> {
                BlockState fs = f.value().defaultBlockState();
                if (!fs.is(Blocks.WITHER_ROSE) && fs.canSurvive(l, feet)) l.setBlockAndUpdate(feet, fs);
            });
        }
        if (h.level() >= (int) Farm.lerp(h, "nudge_level", 10, 10))
            for (BlockPos q : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 0, 1))) {
                BlockState s = l.getBlockState(q);
                if (s.getBlock() instanceof CropBlock) s.randomTick(l, q.immutable(), l.random);
            }
    }

    static boolean soil(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.MOSS_BLOCK) || s.is(BlockTags.DIRT);
    }

    static void photosynthesis(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || (l.getGameTime() + p.getId()) % 20 != 0) return;
        var d = Farm.data(p);
        if (!l.isDay() || l.isRaining() || !l.canSeeSky(p.blockPosition().above()) || !soil(l.getBlockState(p.blockPosition().below()))) {
            d.putInt("habits_sun", 0);
            return;
        }
        int secs = d.getInt("habits_sun") + 1;
        if (secs >= h.value()) {
            p.getFoodData().eat(1, 0.1f);
            secs = 0;
        }
        d.putInt("habits_sun", secs);
        if (h.level() >= 10) p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 40, 0, true, false));
    }

    // ================================================================ world hooks

    /** Growth aura trigger + ancient gardener boost. Only nearby players are checked, and gardens bail at once when none exist. */
    @SubscribeEvent
    public static void onCropGrow(CropGrowEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel l)) return;
        BlockPos pos = e.getPos();
        BlockState s = e.getState();
        if (Farm.mature(s))
            for (ServerPlayer p : l.players())
                if (p.distanceToSqr(Vec3.atCenterOf(pos)) <= 64) Farm.fire(p, "crop_grew_near", Vec3.atCenterOf(pos));
        if (!(s.getBlock() instanceof CropBlock) || Farm.mature(s)) return;
        FarmSaved saved = FarmSaved.get(l);
        if (saved.size == 0) return;
        float[] best = {0};
        saved.near(pos, 8, FarmSaved.GARDEN, en -> {
            if (en.pos().distSqr(pos) > en.a() * en.a()) return;
            if (!Farm.loaded(l, en.pos())) return;
            if (!isGardenPlant(l.getBlockState(en.pos()))) { saved.remove(en.pos()); return; }
            best[0] = Math.max(best[0], en.b());
        });
        int extra = Farm.round(l, best[0] / 100.0);
        if (extra > 0) grow(l, pos, extra);
    }

    /** Scarecrow: O(1) bail when no pumpkins are tracked in this level. */
    @SubscribeEvent
    public static void onSpawnCheck(MobSpawnEvent.PositionCheck e) {
        if (e.getSpawnType() != MobSpawnType.NATURAL || !(e.getEntity() instanceof Enemy)) return;
        ServerLevel l = e.getLevel().getLevel();
        FarmSaved saved = FarmSaved.get(l);
        if (saved.size == 0) return;
        BlockPos pos = e.getEntity().blockPosition();
        boolean[] block = {false};
        saved.near(pos, 24, FarmSaved.SCARECROW, en -> {
            if (block[0] || en.pos().distSqr(pos) > en.a() * en.a() || !Farm.loaded(l, en.pos())) return;
            BlockState t = l.getBlockState(en.pos());
            if (t.is(Blocks.JACK_O_LANTERN) || t.is(Blocks.CARVED_PUMPKIN)) block[0] = true;
            else saved.remove(en.pos());
        });
        if (block[0]) e.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
    }

    /** Wither roses: you (and, at your roses, other players and pets) don't take their wither. */
    @SubscribeEvent
    public static void onEffect(MobEffectEvent.Applicable e) {
        if (!e.getEffectInstance().is(MobEffects.WITHER)) return;
        LivingEntity ent = e.getEntity();
        if (!(ent.level() instanceof ServerLevel l) || !l.getBlockState(ent.blockPosition()).is(Blocks.WITHER_ROSE)) return;
        boolean safe = ent instanceof Player p && Farm.hit(p, "death_bloom") != null;
        if (!safe && (ent instanceof Player || ent instanceof TamableAnimal t && t.isTame())) {
            FarmSaved saved = FarmSaved.get(l);
            boolean[] mine = {false};
            saved.near(ent.blockPosition(), 0, FarmSaved.ROSE, en -> mine[0] |= en.pos().equals(ent.blockPosition()));
            safe = mine[0];
        }
        if (safe) e.setResult(MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
    }

    /** Your wither roses hit monsters standing in them, every half second. Walks only the tracked roses. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 10 != 0) return;
        for (ServerLevel l : e.getServer().getAllLevels()) {
            FarmSaved saved = FarmSaved.get(l);
            if (saved.size == 0) continue;
            saved.forEach(FarmSaved.ROSE, en -> {
                if (!Farm.loaded(l, en.pos())) return;
                if (!l.getBlockState(en.pos()).is(Blocks.WITHER_ROSE)) { saved.remove(en.pos()); return; }
                for (Monster m : l.getEntitiesOfClass(Monster.class, new AABB(en.pos()))) {
                    int amp = (int) en.b() % 100;
                    m.addEffect(new MobEffectInstance(MobEffects.WITHER, (int) (en.a() * 20), amp));
                    if (en.b() >= 100) m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, (int) (en.a() * 20), 1));
                }
            });
        }
    }
}
