package com.habits.perk.farming;

import com.habits.core.HabitsApi.PerkHit;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.perk.Later;
import com.habits.trigger.Triggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BambooLeaves;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Spec batch: planting, soil, water, compost, falls and the field guards. */
public final class Soil {
    private Soil() {}

    static PerkHit hit(Player p, String skill) {
        return Fields.hit(p, skill);
    }

    // ================================================================ planting

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (Farm.busy || !(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel l) || !Farm.ok(p)) return;
        BlockState s = e.getPlacedBlock();
        BlockPos pos = e.getPos();
        PerkHit h;
        if ((h = hit(p, "quick_stalk")) != null && Farm.roll(l, h.value())) {
            int bamboo = (int) Farm.lerp(h, "bamboo", 6, 12);
            Later.run(l, 1, () -> shootUp(l, pos, bamboo));
        }
        if ((h = hit(p, "head_start")) != null && Crops.maxAge(s) > 0 && Farm.roll(l, h.value())) {
            int max = (int) Math.floor(Crops.maxAge(s) * Farm.lerp(h, "max", 25, 75) / 100.0);
            if (max > 0) l.setBlock(pos, Crops.atAge(s, 1 + l.random.nextInt(max)), Block.UPDATE_CLIENTS);
        }
    }

    /** Grow a fresh cane or cactus to 3 tall, a bamboo shoot to {@code bamboo} tall. Stops at the first block in the way. */
    static void shootUp(ServerLevel l, BlockPos pos, int bamboo) {
        BlockState s = l.getBlockState(pos);
        Block b = s.getBlock();
        int height;
        BlockState piece;
        if (b == Blocks.SUGAR_CANE || b == Blocks.CACTUS) { height = 3; piece = b.defaultBlockState(); }
        else if (b == Blocks.BAMBOO_SAPLING) {
            height = bamboo;
            piece = Blocks.BAMBOO.defaultBlockState().setValue(BambooStalkBlock.LEAVES, BambooLeaves.SMALL);
            l.setBlock(pos, Blocks.BAMBOO.defaultBlockState(), Block.UPDATE_ALL);
        } else return;
        for (int i = 1; i < height; i++) {
            BlockPos q = pos.above(i);
            if (!l.getBlockState(q).isAir() || !piece.canSurvive(l, q)) break;
            l.setBlock(q, i == height - 1 && b == Blocks.BAMBOO_SAPLING ? piece.setValue(BambooStalkBlock.LEAVES, BambooLeaves.LARGE) : piece, Block.UPDATE_ALL);
        }
        l.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 6, 0.3, 0.8, 0.3, 0);
    }

    // ================================================================ right clicks

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getLevel() instanceof ServerLevel l) || !(e.getEntity() instanceof ServerPlayer p) || !Farm.ok(p)) return;
        BlockPos pos = e.getPos().immutable();
        BlockState s = l.getBlockState(pos);
        ItemStack held = e.getItemStack();
        PerkHit h;
        if (held.is(Items.WATER_BUCKET) && farmlandNear(l, pos)) Farm.fire(p, "farming/irrigate", Vec3.atCenterOf(pos));
        if (s.getBlock() instanceof ComposterBlock) compost(p, l, pos, s, held);
        if (s.is(Blocks.PUMPKIN) && held.is(net.neoforged.neoforge.common.Tags.Items.TOOLS_SHEAR)) carve(p, l, pos);
        if ((s.is(Blocks.CAULDRON) || s.is(Blocks.WATER_CAULDRON)) && (held.is(Items.WATER_BUCKET) || water(held)) && (h = hit(p, "sprinkler")) != null) {
            float r = (float) h.value();
            Later.run(l, 1, () -> { if (l.getBlockState(pos).is(Blocks.WATER_CAULDRON)) FarmSaved.get(l).add(pos, FarmSaved.SPRINKLER, r, 0); });
        }
        if ((s.getBlock() instanceof SweetBerryBushBlock || CaveVines.hasGlowBerries(s)) && !held.is(Items.BONE_MEAL) && (h = hit(p, "berry_picker")) != null)
            pickBerries(p, l, pos, (int) h.value());
        if (water(held) && (s.getBlock() instanceof FarmBlock || Crops.maxAge(s) > 0) && (h = hit(p, "watering_can")) != null) {
            waterAround(l, s.getBlock() instanceof FarmBlock ? pos : pos.below(), (int) h.value());
            if (!p.getAbilities().instabuild) p.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemUtils.createFilledResult(held, p, new ItemStack(Items.GLASS_BOTTLE)));
            l.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1, 1);
            cancel(e);
            return;
        }
        if (Farm.mature(s) && !held.is(Items.BONE_MEAL) && !p.isSecondaryUseActive() && (h = hit(p, "pick_and_plant")) != null) {
            pickAndPlant(p, l, pos, s, h);
            cancel(e);
        }
    }

    static void cancel(PlayerInteractEvent.RightClickBlock e) {
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        e.getEntity().swing(InteractionHand.MAIN_HAND, true);
    }

    static boolean water(ItemStack s) {
        return s.is(Items.POTION) && s.getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS, PotionContents.EMPTY).is(Potions.WATER);
    }

    static boolean farmlandNear(Level l, BlockPos pos) {
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 1, 2)))
            if (l.getBlockState(q).getBlock() instanceof FarmBlock) return true;
        return false;
    }

    /** Hot Compost: an extra layer per item; Black Gold: extra bone meal from a full bin; and the compost_ready event. */
    static void compost(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s, ItemStack held) {
        int before = s.getValue(ComposterBlock.LEVEL);
        PerkHit hot = hit(p, "hot_compost"), gold = hit(p, "black_gold");
        boolean filling = before < 7 && ComposterBlock.COMPOSTABLES.containsKey(held.getItem());
        if (!filling && before != ComposterBlock.READY) return;
        Later.run(l, 1, () -> {
            BlockState now = l.getBlockState(pos);
            if (!(now.getBlock() instanceof ComposterBlock)) return;
            int lv = now.getValue(ComposterBlock.LEVEL);
            if (before == ComposterBlock.READY) {
                if (lv == 0 && gold != null) {
                    int n = Farm.round(l, gold.value());
                    if (n > 0) Block.popResource(l, pos.above(), new ItemStack(Items.BONE_MEAL, n));
                }
                return;
            }
            if (lv > before && lv < 7 && hot != null && Farm.roll(l, hot.value())) {
                lv++;
                l.setBlock(pos, now.setValue(ComposterBlock.LEVEL, lv), Block.UPDATE_ALL);
                l.levelEvent(1500, pos, 1);
                if (lv == 7) l.scheduleTick(pos, now.getBlock(), 20);
            }
            if (lv >= 7) Farm.fire(p, "farming/compost_ready", Vec3.atCenterOf(pos));
        });
    }

    /** Pumpkin Carver: the carve event, extra seeds, and a chance it lights up. */
    static void carve(ServerPlayer p, ServerLevel l, BlockPos pos) {
        PerkHit h = hit(p, "pumpkin_carver");
        Later.run(l, 1, () -> {
            BlockState now = l.getBlockState(pos);
            if (!now.is(Blocks.CARVED_PUMPKIN)) return;
            Farm.fire(p, "farming/pumpkin_carve", Vec3.atCenterOf(pos));
            if (h == null) return;
            int seeds = Farm.round(l, Farm.lerp(h, "seeds", 0, 4));
            if (seeds > 0) Block.popResource(l, pos.above(), new ItemStack(Items.PUMPKIN_SEEDS, seeds));
            if (Farm.roll(l, h.value()))
                l.setBlock(pos, Blocks.JACK_O_LANTERN.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, now.getValue(HorizontalDirectionalBlock.FACING)), Block.UPDATE_ALL);
        });
    }

    static void pickBerries(ServerPlayer p, ServerLevel l, BlockPos pos, int r) {
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-r, -r, -r), pos.offset(r, r, r))) {
            if (q.equals(pos) || !Farm.loaded(l, q)) continue;
            BlockState t = l.getBlockState(q);
            if (t.getBlock() instanceof SweetBerryBushBlock && t.getValue(SweetBerryBushBlock.AGE) > 1) {
                int n = 1 + l.random.nextInt(2) + (t.getValue(SweetBerryBushBlock.AGE) == 3 ? 1 : 0);
                Block.popResource(l, q, new ItemStack(Items.SWEET_BERRIES, n));
                l.setBlock(q, t.setValue(SweetBerryBushBlock.AGE, 1), Block.UPDATE_CLIENTS);
            } else if (CaveVines.hasGlowBerries(t)) CaveVines.use(p, t, l, q.immutable());
        }
    }

    /** Water Can: wet farmland around, and one growth roll for each crop on it. */
    static void waterAround(ServerLevel l, BlockPos center, int r) {
        for (BlockPos q : BlockPos.betweenClosed(center.offset(-r, -1, -r), center.offset(r, 1, r))) {
            if (!Farm.loaded(l, q)) continue;
            BlockState t = l.getBlockState(q);
            if (!(t.getBlock() instanceof FarmBlock)) continue;
            l.setBlock(q, t.setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), Block.UPDATE_CLIENTS);
            BlockPos up = q.above();
            BlockState c = l.getBlockState(up);
            if (c.isRandomlyTicking() && Crops.maxAge(c) > 0) c.randomTick(l, up.immutable(), l.random);
            l.sendParticles(ParticleTypes.SPLASH, up.getX() + 0.5, up.getY() + 0.2, up.getZ() + 0.5, 3, 0.3, 0.1, 0.3, 0);
        }
    }

    /** Right-click harvest: the drops (one seed kept back to replant), the crop back at age 0, a harvest card roll. */
    static void pickAndPlant(ServerPlayer p, ServerLevel l, BlockPos pos, BlockState s, PerkHit h) {
        if (Crops.maxAge(s) <= 0) return; // melons, pumpkins, berries: vanilla handles them
        List<ItemStack> drops = new ArrayList<>(Block.getDrops(s, l, pos, null, p, p.getMainHandItem()));
        Item seed = Crops.seedOf(l, pos, s);
        for (ItemStack d : drops) if (d.is(seed)) { d.shrink(1); break; }
        if (Farm.roll(l, h.value())) {
            for (ItemStack d : drops) if (!d.isEmpty() && !d.is(seed)) { drops.add(new ItemStack(d.getItem())); break; }
        }
        for (ItemStack d : drops) if (!d.isEmpty()) Block.popResource(l, pos, d);
        l.setBlock(pos, Crops.atAge(s, 0), Block.UPDATE_ALL);
        l.playSound(null, pos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1, 1);
        Triggers.fire(p, TriggerType.HARVEST_CROP, TriggerContext.of(s.getBlock()).withPos(pos));
    }

    // ================================================================ air clicks

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getLevel() instanceof ServerLevel l) || !(e.getEntity() instanceof ServerPlayer p) || !Farm.ok(p) || !p.isShiftKeyDown()) return;
        ItemStack held = e.getItemStack();
        PerkHit h;
        if (held.is(Items.WATER_BUCKET) && p.getXRot() <= -60 && (h = hit(p, "rain_maker")) != null && l.canSeeSky(p.blockPosition().above())) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            long left = Farm.data(p).getLong("habits_rain_cd") - l.getGameTime();
            if (left > 0) {
                long secs = (left + 19) / 20;
                p.displayClientMessage(Component.translatable(secs == 1 ? "habits.farming.cooldown.one" : "habits.farming.cooldown", secs), true);
                return;
            }
            Farm.data(p).putLong("habits_rain_cd", l.getGameTime() + (long) (h.value() * 60 * 20));
            l.setWeatherParameters(0, (int) (Farm.lerp(h, "rain_minutes", 3, 10) * 60 * 20), true, false);
            l.playSound(null, p.blockPosition(), SoundEvents.WEATHER_RAIN_ABOVE, SoundSource.WEATHER, 1, 1);
            return;
        }
        if (held.getItem() instanceof BlockItem bi && (bi.getBlock() instanceof CropBlock || bi.getBlock() instanceof StemBlock) && (h = hit(p, "seed_storm")) != null) {
            double range = Farm.lerp(h, "range", 16, 48);
            Vec3 eye = p.getEyePosition();
            BlockHitResult r = l.clip(new ClipContext(eye, eye.add(p.getViewVector(1).scale(range)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (r.getType() != HitResult.Type.BLOCK) return;
            if (seedStorm(p, l, r.getBlockPos(), held, bi.getBlock(), (int) h.value()) > 0) {
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
            }
        }
    }

    /** Plant up to {@code max} seeds on empty farmland within 4 blocks of {@code at}, nearest first. Returns how many. */
    static int seedStorm(ServerPlayer p, ServerLevel l, BlockPos at, ItemStack seeds, Block crop, int max) {
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-4, -2, -4), at.offset(4, 2, 4)))
            if (Farm.loaded(l, q) && l.getBlockState(q).getBlock() instanceof FarmBlock && l.getBlockState(q.above()).isAir()) spots.add(q.above().immutable());
        spots.sort(Comparator.comparingDouble(q -> q.distSqr(at)));
        boolean free = p.getAbilities().instabuild;
        int n = 0;
        for (BlockPos q : spots) {
            if (n >= max || (!free && seeds.isEmpty())) break;
            BlockState c = crop.defaultBlockState();
            if (!c.canSurvive(l, q)) continue;
            l.setBlockAndUpdate(q, c);
            l.sendParticles(ParticleTypes.HAPPY_VILLAGER, q.getX() + 0.5, q.getY() + 0.2, q.getZ() + 0.5, 1, 0.2, 0.1, 0.2, 0);
            if (!free) seeds.shrink(1);
            n++;
        }
        return n;
    }

    // ================================================================ tools, bone meal

    @SubscribeEvent
    public static void onToolModify(BlockEvent.BlockToolModificationEvent e) {
        if (e.isSimulated() || e.getItemAbility() != ItemAbilities.HOE_TILL || !(e.getPlayer() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel l) || !Farm.ok(p)) return;
        BlockPos pos = e.getPos().immutable();
        PerkHit finds = hit(p, "dirt_finds"), rich = hit(p, "wonder_soil");
        if (finds == null && rich == null) return;
        Later.run(l, 1, () -> {
            BlockState now = l.getBlockState(pos);
            if (now == e.getState()) return; // nothing was tilled
            if (finds != null && Farm.roll(l, finds.value())) {
                boolean rare = Farm.roll(l, Farm.lerp(finds, "rare", 0, 10));
                Item it = pick(l, finds.skill().perk().param(rare ? "rare_items" : "items", "minecraft:wheat_seeds"));
                if (it != Items.AIR) Block.popResource(l, pos.above(), new ItemStack(it));
            }
            if (rich != null && now.getBlock() instanceof FarmBlock && Farm.roll(l, rich.value())) {
                FarmSaved.get(l).add(pos, FarmSaved.RICH, 0, 0);
                l.sendParticles(ParticleTypes.WAX_ON, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 8, 0.3, 0.1, 0.3, 0);
            }
        });
    }

    /** A random item from a comma list of ids (unknown ids are skipped). */
    static Item pick(Level l, String list) {
        String[] ids = list.split(",");
        for (int tries = 0; tries < 4; tries++) {
            ResourceLocation id = ResourceLocation.tryParse(ids[l.random.nextInt(ids.length)].trim());
            Item it = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            if (it != Items.AIR) return it;
        }
        return Items.AIR;
    }

    @SubscribeEvent
    public static void onBonemeal(BonemealEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel l) || !e.isValidBonemealTarget() || !Farm.ok(p)) return;
        BlockState s = e.getState();
        PerkHit h;
        if (s.is(BlockTags.FLOWERS) && (h = hit(p, "florist")) != null) {
            int n = Farm.round(l, h.value());
            if (n > 0) Block.popResource(l, e.getPos(), new ItemStack(s.getBlock().asItem(), n));
        }
        if ((h = hit(p, "bone_dry")) != null && Farm.roll(l, h.value()) && !p.getAbilities().instabuild) e.getStack().grow(1);
    }

    // ================================================================ growth

    /** Fertile Ground: a failed growth roll near you grows anyway. */
    @SubscribeEvent
    public static void onGrowPre(CropGrowEvent.Pre e) {
        if (!(e.getLevel() instanceof ServerLevel l) || l.players().isEmpty()) return;
        BlockPos pos = e.getPos();
        for (ServerPlayer p : l.players()) {
            PerkHit h = hit(p, "fertile_ground");
            if (h == null) continue;
            double r = Farm.lerp(h, "radius", 6, 16);
            if (p.distanceToSqr(Vec3.atCenterOf(pos)) <= r * r && Farm.roll(l, h.value())) {
                e.setResult(CropGrowEvent.Pre.Result.GROW);
                return;
            }
        }
    }

    /** Bumper Vine (extra fruits on a stem that just fruited) and Wonder Soil (rich farmland grows a second stage). */
    @SubscribeEvent
    public static void onGrowPost(CropGrowEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel l)) return;
        BlockPos pos = e.getPos();
        BlockState now = e.getState();
        if (e.getOriginalState().getBlock() instanceof StemBlock && now.getBlock() instanceof AttachedStemBlock) bumperVine(l, pos, now);
        FarmSaved saved = FarmSaved.get(l);
        if (saved.size == 0 || Crops.maxAge(now) == 0) return;
        BlockPos soil = pos.below();
        boolean[] rich = {false};
        saved.near(soil, 0, FarmSaved.RICH, en -> {
            if (!en.pos().equals(soil)) return;
            if (l.getBlockState(soil).getBlock() instanceof FarmBlock) rich[0] = true;
            else saved.remove(soil);
        });
        if (rich[0]) Crops.grow(l, pos, 1);
    }

    static void bumperVine(ServerLevel l, BlockPos stem, BlockState attached) {
        Direction grew = attached.getValue(HorizontalDirectionalBlock.FACING);
        BlockState fruit = l.getBlockState(stem.relative(grew));
        if (!fruit.is(Blocks.MELON) && !fruit.is(Blocks.PUMPKIN)) return;
        for (ServerPlayer p : l.players()) {
            PerkHit h = hit(p, "bumper_vine");
            if (h == null) continue;
            double r = Farm.lerp(h, "radius", 8, 24);
            if (p.distanceToSqr(Vec3.atCenterOf(stem)) > r * r || !Farm.roll(l, h.value())) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos q = stem.relative(d);
                BlockState below = l.getBlockState(q.below());
                if (d != grew && l.isEmptyBlock(q) && (below.getBlock() instanceof FarmBlock || below.is(BlockTags.DIRT))) l.setBlockAndUpdate(q, fruit.getBlock().defaultBlockState());
            }
            return;
        }
    }

    /** Sprinkler (mixin on FarmBlock.isNearWater): a tracked water cauldron within its radius waters this farmland. */
    public static boolean sprinkled(LevelReader level, BlockPos pos) {
        if (!(level instanceof ServerLevel l)) return false;
        FarmSaved saved = FarmSaved.get(l);
        if (saved.size == 0) return false;
        boolean[] wet = {false};
        saved.near(pos, 16, FarmSaved.SPRINKLER, en -> {
            if (wet[0]) return;
            BlockPos c = en.pos();
            if (Math.abs(c.getX() - pos.getX()) > en.a() || Math.abs(c.getZ() - pos.getZ()) > en.a() || Math.abs(c.getY() - pos.getY()) > 1) return;
            if (!Farm.loaded(l, c)) return;
            if (l.getBlockState(c).is(Blocks.WATER_CAULDRON)) wet[0] = true;
            else saved.remove(c);
        });
        return wet[0];
    }

    // ================================================================ falls and trampling

    static boolean soft(BlockState s) {
        return s.is(Blocks.HAY_BLOCK) || s.getBlock() instanceof FarmBlock || Crops.maxAge(s) > 0;
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !Farm.ok(p) || e.getDistance() < 3) return;
        ServerLevel l = p.serverLevel();
        BlockState on = l.getBlockState(p.getOnPos()), feet = l.getBlockState(p.blockPosition());
        if (on.is(Blocks.HAY_BLOCK)) Farm.fire(p, "farming/hay_landing", p.position());
        PerkHit h = hit(p, "hay_diver");
        if (h != null && e.getDistance() <= h.value() && (soft(on) || soft(feet))) e.setDamageMultiplier(0);
    }

    @SubscribeEvent
    public static void onTrample(BlockEvent.FarmlandTrampleEvent e) {
        if (!(e.getLevel() instanceof ServerLevel l)) return;
        var ent = e.getEntity();
        if (ent instanceof Player pl) {
            PerkHit h = hit(pl, "hay_diver");
            if (h != null && e.getFallDistance() <= h.value()) e.setCanceled(true);
            return;
        }
        if (ent.getControllingPassenger() instanceof Player) return;
        Vec3 at = Vec3.atCenterOf(e.getPos());
        for (ServerPlayer p : l.players()) {
            if (p.distanceToSqr(at) > 256) continue;
            Farm.fire(p, "farming/mob_trample", at);
            PerkHit h = hit(p, "crop_guard");
            if (h != null && p.distanceToSqr(at) <= h.value() * h.value()) e.setCanceled(true);
        }
    }

    // ================================================================ fights on the field

    static boolean onField(LivingEntity m) {
        Level l = m.level();
        return l.getBlockState(m.getOnPos()).getBlock() instanceof FarmBlock || Crops.maxAge(l.getBlockState(m.blockPosition())) > 0;
    }

    static boolean farmAnimal(net.minecraft.world.entity.Entity e) {
        return e instanceof net.minecraft.world.entity.animal.Cow || e instanceof net.minecraft.world.entity.animal.Sheep
                || e instanceof net.minecraft.world.entity.animal.Pig || e instanceof net.minecraft.world.entity.animal.Chicken
                || e instanceof net.minecraft.world.entity.animal.Rabbit || e instanceof net.minecraft.world.entity.animal.goat.Goat;
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getSource().getEntity() instanceof ServerPlayer p) || !(e.getEntity() instanceof Enemy) || !Farm.ok(p)) return;
        LivingEntity m = e.getEntity();
        if (onField(m)) Farm.fire(p, "farming/pest_kill", m.position());
        if (m.level().getEntitiesOfClass(Animal.class, m.getBoundingBox().inflate(8), Soil::farmAnimal).size() >= 3)
            Farm.fire(p, "farming/defend_herd", m.position());
    }

    /** Pest Patrol: monsters on farmland or crops near you get hurt once a second. */
    static void pestTick(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || (l.getGameTime() + p.getId()) % 20 != 0) return;
        double r = Farm.lerp(h, "radius", 16, 16);
        for (Monster m : Animals.near(p, Monster.class, r, Soil::onField)) m.hurt(l.damageSources().magic(), (float) h.value());
    }

    /** Pumpkin King: at night, in a pumpkin hat, monsters around are weakened and slowed. */
    static void pumpkinTick(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || (l.getGameTime() + p.getId()) % 20 != 0 || !l.isNight()) return;
        ItemStack head = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
        if (!head.is(Items.CARVED_PUMPKIN) && !head.is(Items.JACK_O_LANTERN)) return;
        for (Monster m : Animals.near(p, Monster.class, h.value(), m -> true)) {
            m.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0));
            m.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 0));
        }
    }

    // ================================================================ walking and riding

    /** Planter's Stride: each new block you step on, plant seeds from your bag on empty farmland in a strip. */
    static void strideTick(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || !p.onGround() || p.isPassenger()) return;
        long here = p.blockPosition().asLong();
        if (Farm.data(p).getLong("habits_stride") == here) return;
        Farm.data(p).putLong("habits_stride", here);
        plantStrip(p, l, p.blockPosition(), (int) h.value());
    }

    static void plantStrip(Player p, ServerLevel l, BlockPos feet, int width) {
        var inv = p.getInventory();
        for (BlockPos q : Crops.patch(feet, p.getDirection(), width, true)) {
            BlockState soil = l.getBlockState(q.below()), at = l.getBlockState(q);
            if (!(soil.getBlock() instanceof FarmBlock) || !at.isAir()) {
                // standing on farmland means feet are one block above it; also try the level below
                q = q.below();
                soil = l.getBlockState(q.below());
                at = l.getBlockState(q);
                if (!(soil.getBlock() instanceof FarmBlock) || !at.isAir()) continue;
            }
            for (int i = 0; i < inv.items.size(); i++) {
                ItemStack s = inv.items.get(i);
                if (!(s.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof CropBlock || bi.getBlock() instanceof StemBlock)) continue;
                BlockState c = bi.getBlock().defaultBlockState();
                if (!c.canSurvive(l, q)) break;
                l.setBlockAndUpdate(q, c);
                if (!p.getAbilities().instabuild) s.shrink(1);
                break;
            }
        }
    }

    /** Combine: while riding, ripe crops the mount walks over are harvested and set back to age 0. */
    static void combineTick(Player p, PerkHit h) {
        if (!(p instanceof ServerPlayer sp) || !(p.level() instanceof ServerLevel l) || p.getVehicle() == null) return;
        var mount = p.getVehicle();
        long here = mount.blockPosition().asLong();
        if (Farm.data(p).getLong("habits_combine") == here) return;
        Farm.data(p).putLong("habits_combine", here);
        for (BlockPos q : Crops.patch(mount.blockPosition(), mount.getDirection(), (int) h.value(), false)) {
            BlockState s = l.getBlockState(q);
            if (!(s.getBlock() instanceof CropBlock) || !Farm.mature(s)) continue;
            if (Farm.breakAs(sp, q) && l.getBlockState(q).isAir()) {
                BlockState fresh = Crops.atAge(s, 0);
                if (fresh.canSurvive(l, q)) l.setBlockAndUpdate(q, fresh);
            }
        }
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent e) {
        if (e.isDismounting() && e.getEntityBeingMounted() instanceof net.minecraft.world.entity.animal.Pig pig) Ranch.unboostPig(pig);
    }
}
