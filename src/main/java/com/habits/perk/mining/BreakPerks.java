package com.habits.perk.mining;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** BreakEvent mechanics (run before the block goes) and the custom mining moments that drop cards. */
public final class BreakPerks {
    private BreakPerks() {}

    // ---------------------------------------------------------------- per-player state (logout + stop cleanup via Work)

    /** Stonebreaker momentum: stacks and the game time of the last break. */
    static final class Momentum { int stacks; long last; }
    static final Map<UUID, Momentum> MOMENTUM = Work.playerMap();
    /** Game times of recent ore breaks (vein miner card). */
    private static final Map<UUID, ArrayDeque<Long>> ORE_TIMES = Work.playerMap();
    /** Last hotbar change time and slot (auto tool card). */
    private static final Map<UUID, long[]> SLOT_SWAP = Work.playerMap();
    /** Last placed block (oops card) and last broken block (swapper card): {pos, time, blockId}. */
    static final Map<UUID, long[]> LAST_PLACE = Work.playerMap();
    static final Map<UUID, long[]> LAST_BREAK = Work.playerMap();

    static final String STATIC_CHARGES = "habits_static_charges";

    // ---------------------------------------------------------------- main break hook

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (Work.busy || !(e.getPlayer() instanceof ServerPlayer p) || !MiningMechanics.allowed(p) || p.isCreative()) return;
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        BlockState s = e.getState();

        if (shaftSinker(p, level, pos)) { e.setCanceled(true); return; }
        if (halfMeasures(p, level, pos, s)) { e.setCanceled(true); return; }

        customMoments(p, level, pos, s);
        momentum(p, level);
        effects(p, level, pos, s);
        multiBreak(p, level, pos, s);
        icicleDrop(p, level, pos, s);
        magmaMiner(p, level, pos);
        if (s.getBlock() instanceof InfestedBlock && MiningMechanics.hit(p, "bug_hunter") != null) {
            pendingInfest = pos.immutable();
            pendingInfestTime = level.getGameTime();
            pendingInfestPlayer = p.getUUID();
        }
        LAST_BREAK.put(p.getUUID(), new long[]{pos.asLong(), level.getGameTime(), Block.getId(s)});
    }

    // ---------------------------------------------------------------- custom moments (CUSTOM trigger ids, see docs)

    static void fire(Player p, String event, BlockPos pos) {
        Triggers.fire(p, TriggerType.CUSTOM, new TriggerContext(Habits.id(event), 1, null, null, Vec3.atCenterOf(pos)));
    }

    private static void customMoments(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s) {
        long now = level.getGameTime();
        ItemStack tool = p.getMainHandItem();
        if (s.is(Tags.Blocks.ORES)) {
            ArrayDeque<Long> q = ORE_TIMES.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
            q.add(now);
            while (!q.isEmpty() && now - q.peekFirst() > 60) q.poll();
            if (q.size() >= 3) { q.clear(); fire(p, "ore_streak", pos); }
            if (level(p, tool, Enchantments.FORTUNE) > 0) fire(p, "fortune_ore", pos);
            PhilosophersPick.oreBroken(p, pos, s);
        }
        if ((s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL)) && level.getBlockState(pos.above()).is(s.getBlock())) fire(p, "sand_column", pos);
        if (p.isEyeInFluid(FluidTags.WATER)) fire(p, "underwater_break", pos);
        BlockPos eye = BlockPos.containing(p.getEyePosition());
        if (level.getBrightness(LightLayer.BLOCK, eye) == 0 && level.getBrightness(LightLayer.SKY, eye) == 0) fire(p, "dark_break", pos);
        if (pos.equals(p.blockPosition().below())) fire(p, "dig_down", pos);
        if (pos.equals(eye.above())) fire(p, "dig_up", pos);
        if (!p.onGround()) fire(p, "midair_break", pos);
        for (Direction d : Direction.values())
            if (level.getFluidState(pos.relative(d)).is(FluidTags.LAVA)) { fire(p, "lava_side_break", pos); break; }
        if (p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > 4.3) fire(p, "long_reach", pos);
        long[] swap = SLOT_SWAP.get(p.getUUID());
        if (swap != null && now - swap[0] <= 20) fire(p, "quick_swap_break", pos);
        if (MiningMechanics.bareHand(tool)) fire(p, "bare_hand_break", pos);
        if (tool.getItem() instanceof TieredItem t) {
            if (t.getTier() == Tiers.WOOD || t.getTier() == Tiers.STONE) fire(p, "low_tier_break", pos);
            if (t.getTier() == Tiers.GOLD) fire(p, "gold_tool_break", pos);
        }
        if (level(p, tool, Enchantments.SILK_TOUCH) > 0) fire(p, "silk_break", pos);
        long[] placed = LAST_PLACE.get(p.getUUID());
        if (placed != null && placed[0] == pos.asLong() && now - placed[1] <= 200) fire(p, "quick_undo", pos);
        if ((s.is(Blocks.COBBLESTONE) || s.is(Blocks.STONE)) && Generators.isGenerated(level, pos)) fire(p, "generator_stone", pos);
        if (nearSpawner(level, pos)) fire(p, "break_near_spawner", pos);
    }

    static int level(Player p, ItemStack stack, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> ench) {
        if (stack.isEmpty()) return 0;
        return EnchantmentHelper.getItemEnchantmentLevel(p.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(ench), stack);
    }

    /** Spawner or trial spawner within 8 blocks: reads block entity maps of the (loaded) chunks around, no block scan. */
    private static boolean nearSpawner(ServerLevel level, BlockPos pos) {
        // only the chunks the 8-block box touches (usually one or two)
        for (int x = (pos.getX() - 8) >> 4; x <= (pos.getX() + 8) >> 4; x++)
            for (int z = (pos.getZ() - 8) >> 4; z <= (pos.getZ() + 8) >> 4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values())
                    if ((be.getBlockState().is(Blocks.SPAWNER) || be.getBlockState().is(Blocks.TRIAL_SPAWNER))
                            && be.getBlockPos().distSqr(pos) <= 64) return true;
            }
        return false;
    }

    /** Hotbar slot changes, for the auto tool card. Server side, cheap compare. */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        long[] st = SLOT_SWAP.get(p.getUUID());
        int sel = p.getInventory().selected;
        if (st == null) SLOT_SWAP.put(p.getUUID(), new long[]{Long.MIN_VALUE / 2, sel});
        else if (st[1] != sel) { st[0] = p.level().getGameTime(); st[1] = sel; }
    }

    // ---------------------------------------------------------------- stonebreaker momentum

    private static void momentum(ServerPlayer p, ServerLevel level) {
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "stonebreaker");
        if (h == null) return;
        Momentum m = MOMENTUM.computeIfAbsent(p.getUUID(), k -> new Momentum());
        long now = level.getGameTime();
        int max = (int) Math.round(MiningMechanics.lerp(h, "max_stacks", 5, 10));
        int before = m.stacks;
        m.stacks = now - m.last <= 40 ? Math.min(max, m.stacks + 1) : 1;
        m.last = now;
        if (m.stacks != before) MiningNet.send(p, new MiningNet.Momentum(m.stacks));
    }

    /** Drain: after 2s without a break, lose one stack every half second. Called from the stonebreaker tick. */
    static void momentumTick(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 10 != 0) return;
        Momentum m = MOMENTUM.get(p.getUUID());
        if (m == null || m.stacks == 0 || p.level().getGameTime() - m.last <= 40) return;
        m.stacks--;
        MiningNet.send(p, new MiningNet.Momentum(m.stacks));
    }

    static int momentumStacks(Player p) {
        Momentum m = MOMENTUM.get(p.getUUID());
        return m == null ? 0 : m.stacks;
    }

    // ---------------------------------------------------------------- single-shot effects

    private static void effects(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s) {
        HabitsApi.PerkHit h;
        boolean ore = s.is(Tags.Blocks.ORES);
        if (ore && (h = MiningMechanics.hit(p, "gold_rush")) != null) {
            int amp = (int) Math.round(MiningMechanics.lerp(h, "amplifier", 1, 3)) - 1;
            if (s.is(BlockTags.GOLD_ORES) || p.hasEffect(MobEffects.DIG_SPEED))
                p.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, (int) (h.value() * 20), amp, true, true));
        }
        if (s.is(BlockTags.EMERALD_ORES) && (h = MiningMechanics.hit(p, "emerald_echo")) != null) {
            int amp = (int) Math.round(MiningMechanics.lerp(h, "amplifier", 1, 5)) - 1;
            p.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, (int) (h.value() * 20), amp, true, true));
        }
        if (s.is(Blocks.NETHER_QUARTZ_ORE) && (h = MiningMechanics.hit(p, "crystal_splinter")) != null) {
            double r = MiningMechanics.lerp(h, "radius", 3, 6);
            for (LivingEntity mob : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(r),
                    m -> m instanceof Enemy && m.isAlive() && m.distanceToSqr(Vec3.atCenterOf(pos)) <= r * r))
                mob.hurt(p.damageSources().playerAttack(p), (float) h.value());
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 12, 0.4, 0.4, 0.4, 0.4);
        }
        if (s.is(Blocks.ANCIENT_DEBRIS) && (h = MiningMechanics.hit(p, "debris_diver")) != null)
            p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, (int) (MiningMechanics.lerp(h, "fire_s", 3, 15) * 20), 0, true, true));
        if (s.is(BlockTags.REDSTONE_ORES) && (h = MiningMechanics.hit(p, "ore_pulse")) != null) Glow.orePulse(p, h);
        if (s.is(BlockTags.COPPER_ORES) && (h = MiningMechanics.hit(p, "static_pick")) != null) {
            int max = (int) h.value();
            var tag = p.getPersistentData();
            int now = Math.min(max, tag.getInt(STATIC_CHARGES) + 1);
            tag.putInt(STATIC_CHARGES, now);
            p.displayClientMessage(Component.translatable("habits.mining.static_charges", now, max), true);
        }
        if (s.is(BlockTags.ICE) && (h = MiningMechanics.hit(p, "ice_carver")) != null && h.level() >= 5)
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 60, 1, true, true));
    }

    // ---------------------------------------------------------------- multi-block breakers (budgeted, see Work)

    /** Face of the block the player is hitting (server ray). Falls back to the side facing the player. */
    static Direction hitFace(ServerPlayer p, BlockPos pos) {
        HitResult r = p.pick(p.blockInteractionRange() + 1, 1, false);
        if (r instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(pos)) return b.getDirection();
        return Direction.getNearest(p.getEyePosition().subtract(Vec3.atCenterOf(pos)));
    }

    static int perTick(HabitsApi.PerkHit h) {
        return (int) Math.max(1, MiningMechanics.lerp(h, "per_tick", 4, 4));
    }

    private static void multiBreak(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s) {
        ItemStack tool = p.getMainHandItem();
        HabitsApi.PerkHit h;
        // vein miner: connected ores of the same kind (stone and deepslate variants mix)
        if (s.is(Tags.Blocks.ORES) && !p.isShiftKeyDown() && (h = MiningMechanics.hit(p, "vein_miner")) != null && p.hasCorrectToolForDrops(s)) {
            java.util.function.Predicate<BlockState> same = sameOre(s);
            List<BlockPos> vein = flood(level, pos, same, (int) h.value());
            Work.queueBreak(p, vein, perTick(h), same);
        }
        // deep miner: bore through deepslate behind, along your look
        if ((s.is(Blocks.DEEPSLATE) || s.is(Blocks.COBBLED_DEEPSLATE)) && (h = MiningMechanics.hit(p, "deep_miner")) != null
                && p.getRandom().nextDouble() * 100 < h.value()) {
            Direction d = Direction.getNearest(p.getLookAngle());
            int n = (int) Math.round(MiningMechanics.lerp(h, "extra", 1, 3));
            List<BlockPos> line = new ArrayList<>();
            for (int i = 1; i <= n; i++) line.add(pos.relative(d, i));
            Work.queueBreak(p, line, perTick(h), st -> boreable(level, st) && p.hasCorrectToolForDrops(st));
        }
        // excavator: shovel area on dirt-like blocks
        if (s.is(BlockTags.MINEABLE_WITH_SHOVEL) && !p.isShiftKeyDown() && tool.canPerformAction(ItemAbilities.SHOVEL_DIG)
                && (h = MiningMechanics.hit(p, "excavator")) != null) {
            Work.queueBreak(p, area(pos, hitFace(p, pos), 1, 2, (int) h.value() - 1), perTick(h), st -> st.is(BlockTags.MINEABLE_WITH_SHOVEL));
        }
        // hellraker: pickaxe area on nether stone
        if (s.is(BlockTags.BASE_STONE_NETHER) && !p.isShiftKeyDown() && tool.canPerformAction(ItemAbilities.PICKAXE_DIG)
                && (h = MiningMechanics.hit(p, "hellraker")) != null) {
            Work.queueBreak(p, area(pos, hitFace(p, pos), 2, 1, (int) h.value() - 1), perTick(h), st -> st.is(BlockTags.BASE_STONE_NETHER));
        }
        // sandman: the column above comes down at once (before it turns into falling blocks)
        if ((s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL)) && (h = MiningMechanics.hit(p, "sandman")) != null) {
            for (int i = 1; i <= (int) h.value(); i++) {
                BlockPos up = pos.above(i);
                if (!level.isLoaded(up) || !level.getBlockState(up).is(s.getBlock())) break;
                Work.breakAs(p, up);
                if (p.getMainHandItem().isEmpty() && !tool.isEmpty()) break;
            }
        }
    }

    /** Not air, no block entity, not unbreakable, not harder than obsidian. */
    static boolean boreable(ServerLevel level, BlockState st) {
        return !st.isAir() && !st.hasBlockEntity() && st.getFluidState().isEmpty()
                && st.getBlock().defaultDestroyTime() >= 0 && st.getBlock().defaultDestroyTime() <= 50;
    }

    /** Flood fill (26 neighbours) over loaded blocks of {@code block}, not counting {@code start}. Visits at most
     * 4x max+32 positions, so a giant vein costs a bounded amount. */
    static List<BlockPos> flood(ServerLevel level, BlockPos start, Block block, int max) {
        return flood(level, start, st -> st.is(block), max);
    }

    /** {@link #flood} over blocks matching {@code match}. */
    static List<BlockPos> flood(ServerLevel level, BlockPos start, java.util.function.Predicate<BlockState> match, int max) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        int budget = max * 4 + 32;
        while (!q.isEmpty() && out.size() < max && budget-- > 0) {
            BlockPos c = q.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = c.offset(dx, dy, dz);
                        if (!seen.add(n) || !level.isLoaded(n) || !match.test(level.getBlockState(n))) continue;
                        if (out.size() < max) { out.add(n); q.add(n); }
                    }
        }
        return out;
    }

    /** Same ore as {@code s}: shares one of its {@code c:ores/<kind>} tags (iron_ore and deepslate_iron_ore, modded
     * ores tagged the same way), else the same block. */
    static java.util.function.Predicate<BlockState> sameOre(BlockState s) {
        List<net.minecraft.tags.TagKey<Block>> kinds = s.getTags()
                .filter(t -> t.location().getNamespace().equals("c") && t.location().getPath().startsWith("ores/")).toList();
        if (kinds.isEmpty()) return st -> st.is(s.getBlock());
        return st -> st.is(s.getBlock()) || kinds.stream().anyMatch(st::is);
    }

    /** A square of radius {@code r} on the face plane, {@code depth} layers deep away from the face, nearest first,
     * not including {@code pos}, at most {@code max}. */
    static List<BlockPos> area(BlockPos pos, Direction face, int r, int depth, int max) {
        List<BlockPos> out = new ArrayList<>();
        if (max <= 0) return out;
        Direction.Axis ax = face.getAxis();
        Direction in = face.getOpposite();
        for (int dist = 0; dist <= r * 2 + depth; dist++)
            for (int d = 0; d < depth; d++)
                for (int a = -r; a <= r; a++)
                    for (int b = -r; b <= r; b++) {
                        if (Math.abs(a) + Math.abs(b) + d != dist) continue;
                        if (d == 0 && a == 0 && b == 0) continue;
                        BlockPos q = switch (ax) {
                            case X -> pos.offset(0, a, b);
                            case Y -> pos.offset(a, 0, b);
                            case Z -> pos.offset(a, b, 0);
                        };
                        out.add(q.relative(in, d));
                        if (out.size() >= max) return out;
                    }
        return out;
    }

    // ---------------------------------------------------------------- safety and odd ones

    /** Shaft sinker: stop a dig-down break with lava, a big drop or the void underneath. */
    private static boolean shaftSinker(ServerPlayer p, ServerLevel level, BlockPos pos) {
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "shaft_sinker");
        if (h == null || !pos.equals(p.blockPosition().below())) return false;
        int n = (int) h.value();
        boolean plug = h.level() >= (int) MiningMechanics.lerp(h, "plug_level", 10, 10);
        boolean danger = false;
        for (int i = 1; i <= n; i++) {
            BlockPos b = pos.below(i);
            if (b.getY() < level.getMinBuildHeight()) { danger = true; break; }
            if (!level.isLoaded(b)) break;
            if (level.getFluidState(b).is(FluidTags.LAVA)) {
                if (plug && Work.has(p, Items.COBBLESTONE, 1) && Work.placeAs(p, b, Blocks.COBBLESTONE.defaultBlockState(), Direction.UP)) {
                    Work.take(p, Items.COBBLESTONE);
                    continue;
                }
                danger = true;
                break;
            }
        }
        if (!danger) {
            int air = 0;
            for (int i = 1; i <= 3; i++) if (level.getBlockState(pos.below(i)).getCollisionShape(level, pos.below(i)).isEmpty()
                    && level.getFluidState(pos.below(i)).isEmpty()) air++;
            danger = air == 3 || pos.getY() - 1 < level.getMinBuildHeight();
        }
        if (danger) p.displayClientMessage(Component.translatable("habits.mining.shaft_danger"), true);
        return danger;
    }

    /** Half measures: break only the slab half you are looking at. */
    private static boolean halfMeasures(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s) {
        if (!(s.getBlock() instanceof SlabBlock) || s.getValue(SlabBlock.TYPE) != SlabType.DOUBLE
                || MiningMechanics.hit(p, "half_measures") == null) return false;
        HitResult r = p.pick(p.blockInteractionRange() + 1, 1, false);
        if (!(r instanceof BlockHitResult b) || !b.getBlockPos().equals(pos)) return false;
        boolean top = b.getLocation().y - pos.getY() >= 0.5;
        level.setBlock(pos, s.setValue(SlabBlock.TYPE, top ? SlabType.BOTTOM : SlabType.TOP), 3);
        if (!p.isCreative()) Block.popResource(level, pos, new ItemStack(s.getBlock()));
        p.getMainHandItem().hurtAndBreak(1, p, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        return true;
    }

    /** Icicle drop: breaking the base of a hanging dripstone drops the rest as falling stalactites aimed at a mob. */
    private static void icicleDrop(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s) {
        if (!s.is(Blocks.POINTED_DRIPSTONE) || s.getValue(PointedDripstoneBlock.TIP_DIRECTION) != Direction.DOWN) return;
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "icicle_drop");
        if (h == null || level.getBlockState(pos.above()).is(Blocks.POINTED_DRIPSTONE)) return;
        double aim = MiningMechanics.lerp(h, "aim", 3, 8);
        BlockPos tip = pos;
        while (level.getBlockState(tip.below()).is(Blocks.POINTED_DRIPSTONE) && pos.getY() - tip.getY() < 16) tip = tip.below();
        Vec3 tipV = Vec3.atCenterOf(tip);
        Mob target = null;
        double best = aim * aim;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(tip).inflate(aim, 32, aim), m -> m.isAlive() && m.getY() < tipV.y)) {
            double dx = m.getX() - tipV.x, dz = m.getZ() - tipV.z;
            if (dx * dx + dz * dz < best) { best = dx * dx + dz * dz; target = m; }
        }
        for (BlockPos b = pos.below(); b.getY() >= tip.getY(); b = b.below()) {
            BlockState bs = level.getBlockState(b);
            FallingBlockEntity f = FallingBlockEntity.fall(level, b, bs);
            int size = pos.getY() - tip.getY() + 1;
            f.setHurtsEntities((float) (Math.max(size, 1) * h.value()), (int) (40 * h.value()));
            f.getPersistentData().putUUID(MiningHooks.ICICLE_OWNER, p.getUUID()); // credited in PointedDripstoneBlockMixin
            if (target != null) {
                Vec3 to = target.position().subtract(f.position());
                double t = Math.max(1, Math.sqrt(Math.max(0.5, f.getY() - target.getY())) * 4);
                f.setDeltaMovement(to.x / t / 2, 0, to.z / t / 2);
            }
        }
    }

    /** Magma miner: lava around the dig cools into stone (sources into obsidian at high level). */
    private static void magmaMiner(ServerPlayer p, ServerLevel level, BlockPos pos) {
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "magma_miner");
        if (h == null) return;
        int r = (int) h.value();
        boolean sources = h.level() >= (int) MiningMechanics.lerp(h, "sources_level", 6, 6);
        for (BlockPos b : BlockPos.betweenClosed(pos.offset(-r, -r, -r), pos.offset(r, r, r))) {
            if (!level.isLoaded(b)) continue;
            var fs = level.getFluidState(b);
            if (!fs.is(FluidTags.LAVA)) continue;
            if (fs.isSource() && !sources) continue;
            Work.placeAs(p, b.immutable(), (fs.isSource() ? Blocks.OBSIDIAN : Blocks.STONE).defaultBlockState(), Direction.UP);
        }
    }

    // ---------------------------------------------------------------- bug hunter silverfish

    private static BlockPos pendingInfest;
    private static long pendingInfestTime;
    private static UUID pendingInfestPlayer;

    static {
        Work.STOP_CLEANUP.add(() -> pendingInfest = null);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (pendingInfest == null || !(e.getEntity() instanceof Silverfish s) || !(e.getLevel() instanceof ServerLevel level)) return;
        if (level.getGameTime() != pendingInfestTime || s.blockPosition().distSqr(pendingInfest) > 4) return;
        Player p = level.getPlayerByUUID(pendingInfestPlayer);
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "bug_hunter");
        if (h == null) return;
        double cut = MiningMechanics.lerp(h, "hp_cut", 20, 100);
        if (cut >= 100) { e.setCanceled(true); return; }
        s.setHealth((float) Math.max(1, s.getMaxHealth() * (1 - cut / 100)));
    }

    // ---------------------------------------------------------------- static pick

    /** Sneak-right-click stone with a pickaxe: spend a charge to blast a cube out. */
    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !p.isShiftKeyDown() || e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!p.getMainHandItem().canPerformAction(ItemAbilities.PICKAXE_DIG)) return;
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "static_pick");
        if (h == null) return;
        ServerLevel level = p.serverLevel();
        BlockState s = level.getBlockState(e.getPos());
        if (!s.is(BlockTags.BASE_STONE_OVERWORLD) && !s.is(Tags.Blocks.COBBLESTONES)) return;
        var tag = p.getPersistentData();
        int charges = tag.getInt(STATIC_CHARGES);
        if (charges <= 0) {
            p.displayClientMessage(Component.translatable("habits.mining.static_empty"), true);
            return;
        }
        tag.putInt(STATIC_CHARGES, charges - 1);
        int r = (int) Math.round(MiningMechanics.lerp(h, "width", 3, 5)) / 2;
        Direction face = e.getFace() == null ? hitFace(p, e.getPos()) : e.getFace();
        List<BlockPos> cube = area(e.getPos(), face, r, 3, 200);
        cube.add(0, e.getPos());
        Work.queueBreak(p, cube, perTick(h) * 2, st -> boreable(level, st) && !st.is(BlockTags.FEATURES_CANNOT_REPLACE));
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, e.getPos().getX() + 0.5, e.getPos().getY() + 0.5,
                e.getPos().getZ() + 0.5, 20, 0.6, 0.6, 0.6, 0.2);
        e.setCanceled(true);
    }
}
