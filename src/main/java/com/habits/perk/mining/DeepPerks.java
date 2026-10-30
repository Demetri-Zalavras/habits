package com.habits.perk.mining;

import com.habits.core.HabitsApi;
import com.habits.core.IdMatch;
import com.habits.perk.Conditions;
import com.habits.perk.Later;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerDestroyItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Mining spec batch (skill and mechanic ids {@code habits:mining/<name>}). Table-driven rule families (bonus drops, on-break
 * effects, reveals, refunds) share one code path each; the rest are one small hook apiece. docs/mechanics/mining-building.md
 * ("Mining spec batch") lists every id, value and param; custom card moments are fired from {@link #moments}.
 */
public final class DeepPerks {
    private DeepPerks() {}

    static final String P = "mining/";

    /** Bonus drop rules: params {@code block}, {@code item} ("self" = the block), {@code count}; value = % chance. */
    static final List<String> DROPS = List.of("tuff_cutter", "calcite_crack", "bone_digger", "moss_scraper", "magma_harvester",
            "fortress_looter", "prismarine_cutter", "rail_scavenger", "lichen_scraper", "glowstone_sifter", "void_pearl",
            "nylium_keeper", "netherite_nose", "echo_digger", "oreseer_gem_eye");
    /** On-break effect rules: params {@code block}, {@code effect}, {@code amplifier}; value = seconds. */
    static final List<String> EFFECTS = List.of("redstone_rush", "quartz_edge", "diamond_skin", "crystal_calm");
    /** Background reveal rules: params {@code block}, {@code color}, {@code glow_s}, {@code source}; value = radius. */
    static final List<String> REVEALS = List.of("geode_sense", "fossil_sense", "dig_site", "vein_seeker", "shrieker_sense", "lava_sense", "oreseer_sight");
    /** Placement refund rules: param {@code block}; value = % chance; underground only. */
    static final List<String> REFUNDS = List.of("wick_saver", "ladder_thrift");

    static void meanings(BiConsumer<String, String> m) {
        for (String id : DROPS) m.accept(P + id, "% chance of a bonus drop");
        for (String id : EFFECTS) m.accept(P + id, "effect seconds");
        for (String id : REVEALS) m.accept(P + id, "reveal radius in blocks");
        for (String id : REFUNDS) m.accept(P + id, "% chance a placed block is refunded");
        m.accept(P + "coal_stoker", "% chance coal ore restores hunger");
        m.accept(P + "last_legs", "% faster mining with a nearly broken tool");
        m.accept(P + "step_cutter", "% chance a stair step clears its headroom");
        m.accept(P + "tunnel_bore", "% chance a tunnel break clears two high");
        m.accept(P + "lightbringer", "% chance per second to clear Darkness");
        m.accept(P + "whetstone", "durability mended per minute underground");
        m.accept(P + "spare_pick", "spare swap level (6+ searches the whole inventory)");
        m.accept(P + "kiln_pick", "% chance dug drops come out smelted");
        m.accept(P + "ore_blood", "health healed per ore");
        m.accept(P + "echolocation", "monster ping radius in blocks");
        m.accept(P + "canary", "monster count radius in blocks");
        m.accept(P + "deep_wisdom", "% chance deep stone gives experience");
        m.accept(P + "stone_sounding", "blocks probed behind a broken block");
        m.accept(P + "danger_sense", "glow seconds on monsters that target you underground");
        m.accept(P + "prospector", "same-ore reveal radius in blocks");
        m.accept(P + "stone_skin", "% less damage taken right after a break");
        m.accept(P + "rock_crusher", "% chance junk stone that does not fit becomes experience");
        m.accept(P + "powder_miner", "% chance ore in your blasts drops in full");
        m.accept(P + "rich_seam", "% chance stone touching ore drops that ore");
        m.accept(P + "fatigue_proof", "% chance per second to clear Mining Fatigue");
        m.accept(P + "ender_pouch", "% chance overflow drops go to your ender chest");
        m.accept(P + "shockwave_pick", "% chance a stone break knocks monsters back");
        m.accept(P + "crystal_grower", "% chance per second for nearby budding amethyst to grow");
        m.accept(P + "earthbreaker", "blocks per stone break (3x3 face)");
        m.accept(P + "ore_compass", "ore tracking radius in blocks");
        m.accept(P + "escape_rope", "seconds between rope climbs");
        m.accept(P + "lucky_find", "% chance stone hides a treasure");
        m.accept(P + "fireproof_haul", "% chance mined drops ignore fire and blasts");
        m.accept(P + "mother_lode", "% chance a mined ore grows back");
        m.accept(P + "deep_core", "% faster mining below y 0");
        m.accept(P + "drill_run", "blocks dug per second while sprinting");
        m.accept(P + "worldbreaker", "blocks per pickaxe break (3x3 face, deeper per 9)");
    }

    static void ticks(Map<String, BiConsumer<Player, HabitsApi.PerkHit>> m) {
        for (String id : REVEALS) m.put(P + id, (p, h) -> reveal(p, h, P + id));
        m.put(P + "lightbringer", (p, h) -> shake(p, h, MobEffects.DARKNESS));
        m.put(P + "fatigue_proof", (p, h) -> shake(p, h, MobEffects.DIG_SLOWDOWN));
        m.put(P + "whetstone", DeepPerks::whetstone);
        m.put(P + "canary", DeepPerks::canary);
        m.put(P + "crystal_grower", DeepPerks::crystalGrower);
        m.put(P + "ore_compass", DeepPerks::oreCompass);
        m.put(P + "drill_run", DeepPerks::drillRun);
    }

    // ---------------------------------------------------------------- shared reads

    static HabitsApi.PerkHit hit(Player p, String name) {
        return MiningMechanics.hit(p, P + name);
    }

    static double lerp(HabitsApi.PerkHit h, String key, double a, double b) {
        return MiningMechanics.lerp(h, key, a, b);
    }

    static boolean underground(Player p) {
        return Conditions.test(p, "underground");
    }

    static boolean roll(Player p, double pct) {
        return p.getRandom().nextDouble() * 100 < pct;
    }

    private static final Map<String, Predicate<BlockState>> BLOCKS = new ConcurrentHashMap<>();

    /** A block {@link IdMatch} string ("minecraft:tuff,#minecraft:rails") as a state test, cached (tags resolve live). */
    static Predicate<BlockState> blocks(String match) {
        return BLOCKS.computeIfAbsent(match, k -> {
            IdMatch m = IdMatch.parse(k);
            List<Block> ids = m.ids().stream().map(BuiltInRegistries.BLOCK::get).filter(b -> b != Blocks.AIR).toList();
            List<TagKey<Block>> tags = m.tags().stream().map(t -> TagKey.create(Registries.BLOCK, t)).toList();
            return s -> {
                for (Block b : ids) if (s.is(b)) return true;
                for (TagKey<Block> t : tags) if (s.is(t)) return true;
                return false;
            };
        });
    }

    static boolean stone(BlockState s) {
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Tags.Blocks.COBBLESTONES);
    }

    /** Room in the inventory for at least part of {@code st}. */
    static boolean fits(Player p, ItemStack st) {
        Inventory inv = p.getInventory();
        return inv.getFreeSlot() >= 0 || inv.getSlotWithRemainingSpace(st) >= 0;
    }

    static void fire(Player p, String event, BlockPos pos) {
        BreakPerks.fire(p, P + event, pos);
    }

    // ---------------------------------------------------------------- per-player moment state (Work cleans on logout/stop)

    /** {window start, count} per streak kind. */
    private static final Map<UUID, Map<String, long[]>> STREAKS = Work.playerMap();
    private static final Map<UUID, Object[]> LAST_ORE = Work.playerMap();
    private static final Map<UUID, List<BlockPos>> BUDDING = Work.playerMap();

    /** Counts toward a streak: true (and resets) once {@code need} land within {@code window} ticks of the first. */
    static boolean streak(Player p, String kind, long now, long window, int need) {
        long[] s = STREAKS.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).computeIfAbsent(kind, k -> new long[]{now, 0});
        if (now - s[0] > window) { s[0] = now; s[1] = 0; }
        if (++s[1] < need) return false;
        s[0] = now;
        s[1] = 0;
        return true;
    }

    static void resetStreak(Player p, String kind) {
        Map<String, long[]> m = STREAKS.get(p.getUUID());
        if (m != null) m.remove(kind);
    }

    // ---------------------------------------------------------------- break: card moments + on-break mechanics

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (Work.busy || !(e.getPlayer() instanceof ServerPlayer p) || !MiningMechanics.allowed(p) || p.isCreative()) return;
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = e.getPos();
        BlockState s = e.getState();
        boolean under = underground(p);
        moments(p, level, pos, s, under);
        effects(p, level, pos, s, under);
    }

    static final int DEEP_Y = -32, DEEP_ORE_Y = -48;

    private static void moments(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, boolean under) {
        long now = level.getGameTime();
        ItemStack tool = p.getMainHandItem();
        BlockPos feet = p.blockPosition();
        BlockPos eye = BlockPos.containing(p.getEyePosition());
        Direction facing = p.getDirection();
        boolean ore = s.is(Tags.Blocks.ORES);

        boolean tunnel = pos.equals(feet.relative(facing)) || pos.equals(feet.above().relative(facing));
        if (under && tunnel) {
            fire(p, "tunnel_dig", pos);
            // a straight tunnel: tunnel blocks facing one way, 64 (32 long, two high) without turning
            if (!streak(p, "tunnel_" + facing.get2DDataValue(), now, 2400, 64)) {
                for (Direction d : Direction.Plane.HORIZONTAL) if (d != facing) resetStreak(p, "tunnel_" + d.get2DDataValue());
            } else fire(p, "long_tunnel", pos);
        }
        if (pos.getY() < DEEP_Y) fire(p, "deep_break", pos);
        if (tool.isDamageableItem() && tool.getMaxDamage() - tool.getDamageValue() <= tool.getMaxDamage() / 10) fire(p, "worn_tool_break", pos);
        Direction in = BreakPerks.hitFace(p, pos).getOpposite();
        BlockPos behind = pos.relative(in);
        if (level.isLoaded(behind) && (level.getBlockState(behind).isAir() || level.getFluidState(behind).is(FluidTags.LAVA))) fire(p, "hollow_break", pos);
        if (pos.equals(feet.below().relative(facing))) fire(p, "stair_dig", pos);
        if (p.hasEffect(MobEffects.DARKNESS)) fire(p, "darkness_break", pos);
        if (under && streak(p, "shift", now, 12000, 300)) fire(p, "long_shift", pos);
        if (under && p.isShiftKeyDown()) fire(p, "sneak_dig", pos);
        if (p.getInventory().getFreeSlot() < 0) fire(p, "full_pack", pos);
        if (stone(s) || s.is(BlockTags.BASE_STONE_NETHER)) { if (streak(p, "stone", now, 600, 40)) fire(p, "stone_streak", pos); }
        if (under && pos.equals(eye.above())) { if (streak(p, "dig_out", now, 400, 10)) fire(p, "dig_out", pos); }
        else if (!pos.equals(eye.above())) resetStreak(p, "dig_out");

        boolean air = false, buried = true, bedrock = false, oreNext = false, water = false;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!level.isLoaded(n)) { buried = false; continue; }
            BlockState ns = level.getBlockState(n);
            if (ns.isAir()) air = true;
            if (ns.isAir() || !ns.getFluidState().isEmpty()) buried = false;
            if (ns.is(Blocks.BEDROCK)) bedrock = true;
            if (ns.is(Tags.Blocks.ORES)) oreNext = true;
            if (ns.getFluidState().is(FluidTags.WATER) && ns.getFluidState().isSource()) water = true;
        }
        if (bedrock) fire(p, "bedrock_near", pos);
        if (under && water) fire(p, "flooded_dig", pos);
        if (!ore && oreNext && stone(s)) fire(p, "ore_adjacent", pos);
        if (s.is(Blocks.ANCIENT_DEBRIS)) { if (streak(p, "debris", now, 1200, 2)) fire(p, "debris_pair", pos); }
        if (!ore) return;
        if (buried) fire(p, "buried_ore", pos);
        if (air && under) fire(p, "exposed_ore", pos);
        if (pos.getY() < DEEP_ORE_Y) fire(p, "deep_ore", pos);
        if (streak(p, "ore_marathon", now, 12000, 100)) fire(p, "vein_marathon", pos);
        String kind = oreKind(s);
        Object[] last = LAST_ORE.put(p.getUUID(), new Object[]{kind, now});
        if (last != null && !kind.equals(last[0]) && now - (long) last[1] <= 100) fire(p, "ore_combo", pos);
        if (s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES) || s.is(BlockTags.GOLD_ORES)) {
            var m = STREAKS.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
            m.put("metal_" + kind, new long[]{now, 1});
            boolean all = true;
            for (String k : new String[]{"metal_iron", "metal_copper", "metal_gold"}) {
                long[] t = m.get(k);
                all &= t != null && now - t[0] <= 1200;
            }
            if (all) {
                for (String k : new String[]{"metal_iron", "metal_copper", "metal_gold"}) m.remove(k);
                fire(p, "metal_triplet", pos);
            }
        }
        if (s.is(BlockTags.DIAMOND_ORES) && BreakPerks.flood(level, pos, st -> st.is(BlockTags.DIAMOND_ORES), 1).isEmpty())
            fire(p, "diamond_cleared", pos);
    }

    /** "iron" for any block in c:ores/iron, else the block path. */
    static String oreKind(BlockState s) {
        return s.getTags().filter(t -> t.location().getNamespace().equals("c") && t.location().getPath().startsWith("ores/"))
                .map(t -> t.location().getPath().substring(5)).findFirst()
                .orElse(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath());
    }

    private static void effects(ServerPlayer p, ServerLevel level, BlockPos pos, BlockState s, boolean under) {
        HabitsApi.PerkHit h;
        boolean ore = s.is(Tags.Blocks.ORES);
        long now = level.getGameTime();
        ItemStack tool = p.getMainHandItem();
        BlockPos feet = p.blockPosition();
        Direction facing = p.getDirection();
        var tag = p.getPersistentData();

        for (String id : EFFECTS) {
            if ((h = hit(p, id)) == null || !blocks(h.skill().perk().param("block", "")).test(s)) continue;
            ResourceLocation eid = ResourceLocation.tryParse(h.skill().perk().param("effect", "minecraft:speed"));
            var effect = eid == null ? java.util.Optional.<net.minecraft.core.Holder.Reference<net.minecraft.world.effect.MobEffect>>empty() : BuiltInRegistries.MOB_EFFECT.getHolder(eid);
            if (effect.isEmpty()) continue;
            int amp = (int) Math.round(lerp(h, "amplifier", 1, 1)) - 1;
            p.addEffect(new MobEffectInstance(effect.get(), (int) (h.value() * 20), Math.max(0, amp), true, true));
        }
        if (s.is(BlockTags.COAL_ORES) && (h = hit(p, "coal_stoker")) != null && roll(p, h.value()))
            p.getFoodData().eat((int) Math.round(lerp(h, "food", 1, 3)), 0.5f);
        if (ore && (h = hit(p, "ore_blood")) != null) p.heal((float) h.value());

        // two-high tunnels and staircases
        boolean ok = tool.canPerformAction(ItemAbilities.PICKAXE_DIG) || tool.canPerformAction(ItemAbilities.SHOVEL_DIG);
        if (ok && under && (h = hit(p, "tunnel_bore")) != null && roll(p, h.value())) {
            BlockPos other = pos.equals(feet.relative(facing)) ? pos.above() : pos.equals(feet.above().relative(facing)) ? pos.below() : null;
            if (other != null) Work.queueBreak(p, List.of(other), BreakPerks.perTick(h), st -> BreakPerks.boreable(level, st) && p.hasCorrectToolForDrops(st));
        }
        if (ok && pos.equals(feet.below().relative(facing)) && (h = hit(p, "step_cutter")) != null && roll(p, h.value()))
            Work.queueBreak(p, List.of(pos.above(), pos.above(2)), 2, st -> BreakPerks.boreable(level, st) && p.hasCorrectToolForDrops(st));

        // earthbreaker: 3x3 on overworld stone
        if (stone(s) && !p.isShiftKeyDown() && tool.canPerformAction(ItemAbilities.PICKAXE_DIG) && (h = hit(p, "earthbreaker")) != null)
            Work.queueBreak(p, BreakPerks.area(pos, BreakPerks.hitFace(p, pos), 1, 1, (int) h.value() - 1), BreakPerks.perTick(h), DeepPerks::stone);
        // worldbreaker (Legendary class): 3x3 face on anything a pickaxe takes, one layer deeper per 9 blocks
        if (s.is(BlockTags.MINEABLE_WITH_PICKAXE) && !p.isShiftKeyDown() && tool.canPerformAction(ItemAbilities.PICKAXE_DIG) && (h = hit(p, "worldbreaker")) != null) {
            int n = (int) h.value();
            Work.queueBreak(p, BreakPerks.area(pos, BreakPerks.hitFace(p, pos), 1, (n + 8) / 9, n - 1), BreakPerks.perTick(h),
                    st -> st.is(BlockTags.MINEABLE_WITH_PICKAXE) && BreakPerks.boreable(level, st) && p.hasCorrectToolForDrops(st));
        }

        if (stone(s) && (h = hit(p, "echolocation")) != null && now >= tag.getLong("habits_echo_ready")) {
            tag.putLong("habits_echo_ready", now + (long) (lerp(h, "cooldown_s", 20, 5) * 20));
            int glow = (int) (lerp(h, "glow_s", 3, 3) * 20);
            for (LivingEntity m : monsters(p, h.value())) m.addEffect(new MobEffectInstance(MobEffects.GLOWING, glow, 0, true, false));
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE, net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 1.2f);
        }
        if (stone(s) && (h = hit(p, "shockwave_pick")) != null && roll(p, h.value())) {
            double push = lerp(h, "push", 0.6, 1.5);
            for (LivingEntity m : monsters(p, lerp(h, "radius", 3, 6))) {
                Vec3 away = m.position().subtract(p.position()).multiply(1, 0, 1).normalize();
                m.knockback(push, -away.x, -away.z);
            }
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, 0, 0, 0, 0);
        }
        if (ore && (h = hit(p, "prospector")) != null && now >= tag.getLong("habits_prospect_ready") && !Scanner.running(p, P + "prospector")) {
            tag.putLong("habits_prospect_ready", now + (long) (lerp(h, "cooldown_s", 3, 3) * 20));
            int count = (int) Math.round(lerp(h, "count", 1, 5));
            Predicate<BlockState> same = BreakPerks.sameOre(s);
            int color = Glow.oreColor(s);
            Scanner.start(p, P + "prospector", pos, (int) h.value(), Integer.MIN_VALUE, Integer.MAX_VALUE, same, hits -> {
                List<BlockPos> near = hits.stream().filter(b -> !b.equals(pos)).sorted(Comparator.comparingDouble(b -> b.distSqr(pos))).limit(count).toList();
                MiningNet.marks(p, near, near.stream().map(b -> color).toList(), 200, Glow.OUTLINE);
            });
        }
        if ((h = hit(p, "stone_sounding")) != null) sound(p, level, pos, h);
        if (ore && (h = hit(p, "mother_lode")) != null && BreakPerks.level(p, tool, Enchantments.SILK_TOUCH) == 0 && roll(p, h.value()))
            Later.run(level, 2, () -> { if (level.isLoaded(pos) && level.getBlockState(pos).isAir()) level.setBlock(pos, s, 3); });
    }

    static List<LivingEntity> monsters(Player p, double r) {
        return p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r),
                m -> m instanceof Enemy && m.isAlive() && m.distanceToSqr(p) <= r * r);
    }

    /** Stone sounding: lava or open space within value blocks behind the broken block, on the action bar. */
    private static void sound(ServerPlayer p, ServerLevel level, BlockPos pos, HabitsApi.PerkHit h) {
        Direction in = BreakPerks.hitFace(p, pos).getOpposite();
        for (int i = 1; i <= (int) h.value(); i++) {
            BlockPos b = pos.relative(in, i);
            if (!level.isLoaded(b)) return;
            if (level.getFluidState(b).is(FluidTags.LAVA)) {
                p.displayClientMessage(Component.translatable("habits.mining.sound_lava", i), true);
                return;
            }
            if (level.getBlockState(b).isAir()) {
                if (i > 1) p.displayClientMessage(Component.translatable("habits.mining.sound_hollow", i), true);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- drops: rules and extras before DropPerks, overflow after

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDropsEarly(BlockDropsEvent e) {
        if (!(e.getBreaker() instanceof ServerPlayer p) || !MiningMechanics.allowed(p) || p.isCreative()) return;
        ServerLevel level = e.getLevel();
        BlockPos pos = e.getPos();
        BlockState s = e.getState();
        RandomSource r = p.getRandom();
        HabitsApi.PerkHit h;
        boolean ore = s.is(Tags.Blocks.ORES);

        for (String id : DROPS) {
            if ((h = hit(p, id)) == null || !blocks(h.skill().perk().param("block", "")).test(s) || !roll(p, h.value())) continue;
            String item = h.skill().perk().param("item", "self");
            Item it = "self".equals(item) ? s.getBlock().asItem() : BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(item));
            int n = MiningMechanics.roll(lerp(h, "count", 1, 1), r);
            if (n > 0 && it != net.minecraft.world.item.Items.AIR) add(e, new ItemStack(it, n));
        }
        if (!ore && (s.is(BlockTags.MINEABLE_WITH_PICKAXE) || s.is(BlockTags.MINEABLE_WITH_SHOVEL)) && (h = hit(p, "kiln_pick")) != null) {
            for (ItemEntity ie : e.getDrops()) {
                ItemStack d = ie.getItem();
                if (!roll(p, h.value())) continue;
                var recipe = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(d), level);
                if (recipe.isEmpty()) continue;
                ie.setItem(recipe.get().value().getResultItem(level.registryAccess()).copyWithCount(d.getCount()));
            }
        }
        if (!ore && (h = hit(p, "deep_wisdom")) != null && pos.getY() < lerp(h, "max_y", 0, 0)
                && s.is(BlockTags.MINEABLE_WITH_PICKAXE) && roll(p, h.value()))
            e.setDroppedExperience(e.getDroppedExperience() + MiningMechanics.roll(lerp(h, "xp", 1, 3), r));
        if (!ore && stone(s) && (h = hit(p, "rich_seam")) != null && roll(p, h.value())) {
            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                BlockState ns = level.isLoaded(n) ? level.getBlockState(n) : null;
                if (ns == null || !ns.is(Tags.Blocks.ORES)) continue;
                for (ItemStack st : Block.getDrops(ns, level, n, null, p, e.getTool())) add(e, st);
                break;
            }
        }
        if (stone(s) && e.getTool().canPerformAction(ItemAbilities.PICKAXE_DIG) && (h = hit(p, "lucky_find")) != null && roll(p, h.value())) {
            List<Item> pool = IdMatch.parse(h.skill().perk().param("items", "minecraft:emerald")).ids().stream()
                    .map(BuiltInRegistries.ITEM::get).filter(i -> i != net.minecraft.world.item.Items.AIR).toList();
            if (!pool.isEmpty()) add(e, new ItemStack(pool.get(r.nextInt(pool.size()))));
        }
    }

    private static void add(BlockDropsEvent e, ItemStack st) {
        if (st.isEmpty()) return;
        BlockPos pos = e.getPos();
        ItemEntity ie = new ItemEntity(e.getLevel(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, st);
        ie.setDefaultPickUpDelay();
        e.getDrops().add(ie);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDropsLate(BlockDropsEvent e) {
        if (!(e.getBreaker() instanceof ServerPlayer p) || !MiningMechanics.allowed(p) || p.isCreative()) return;
        HabitsApi.PerkHit crush = hit(p, "rock_crusher"), pouch = hit(p, "ender_pouch"), proof = hit(p, "fireproof_haul");
        if (crush == null && pouch == null && proof == null) return;
        Predicate<ItemStack> junk = crush == null ? st -> false : junkTest(crush.skill().perk().param("junk", ""));
        var it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemEntity ie = it.next();
            ItemStack st = ie.getItem();
            if (crush != null && junk.test(st) && !fits(p, st) && roll(p, crush.value())) {
                e.setDroppedExperience(e.getDroppedExperience() + st.getCount());
                it.remove();
                continue;
            }
            if (pouch != null && !fits(p, st) && roll(p, pouch.value())) {
                ItemStack left = p.getEnderChestInventory().addItem(st.copy());
                if (left.isEmpty()) { it.remove(); continue; }
                ie.setItem(left);
            }
            if (proof != null && roll(p, proof.value())) ie.setInvulnerable(true);
        }
    }

    private static final Map<String, Predicate<ItemStack>> JUNK = new ConcurrentHashMap<>();

    static Predicate<ItemStack> junkTest(String match) {
        return JUNK.computeIfAbsent(match, k -> {
            IdMatch m = IdMatch.parse(k);
            List<Item> ids = m.ids().stream().map(BuiltInRegistries.ITEM::get).toList();
            List<TagKey<Item>> tags = m.tags().stream().map(t -> TagKey.create(Registries.ITEM, t)).toList();
            return st -> ids.contains(st.getItem()) || tags.stream().anyMatch(st::is);
        });
    }

    // ---------------------------------------------------------------- speed, damage

    @SubscribeEvent
    public static void onSpeed(PlayerEvent.BreakSpeed e) {
        Player p = e.getEntity();
        if (!MiningMechanics.allowed(p)) return;
        HabitsApi.PerkHit h;
        float speed = e.getNewSpeed();
        ItemStack tool = p.getMainHandItem();
        if (tool.isDamageableItem() && (h = hit(p, "last_legs")) != null
                && (tool.getMaxDamage() - tool.getDamageValue()) * 100.0 / tool.getMaxDamage() <= lerp(h, "below", 10, 25))
            speed *= (float) (1 + h.value() / 100);
        if ((h = hit(p, "deep_core")) != null && p.getY() < lerp(h, "max_y", 0, 0)) speed *= (float) (1 + h.value() / 100);
        e.setNewSpeed(speed);
    }

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !MiningMechanics.allowed(p)) return;
        long now = p.level().getGameTime();
        long[] last = BreakPerks.LAST_BREAK.get(p.getUUID());
        long since = last == null ? Long.MAX_VALUE : now - last[1];
        if (since <= 40 && e.getSource().getEntity() instanceof LivingEntity) fire(p, "hit_while_mining", p.blockPosition());
        HabitsApi.PerkHit h;
        float amount = e.getAmount();
        if ((h = hit(p, "stone_skin")) != null && since <= lerp(h, "window_s", 2, 4) * 20) amount *= (float) (1 - h.value() / 100);
        if ((h = hit(p, "deep_core")) != null && p.getY() < lerp(h, "max_y", 0, 0)) amount *= (float) (1 - lerp(h, "resist", 5, 30) / 100);
        if (amount != e.getAmount()) e.setAmount(amount);
    }

    // ---------------------------------------------------------------- other moments: placing, targeting, spawns, shrieks, blasts, meals

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (Work.busy || !(e.getEntity() instanceof ServerPlayer p) || !MiningMechanics.allowed(p) || p.isCreative() || !underground(p)) return;
        BlockState s = e.getPlacedBlock();
        if (s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.SOUL_TORCH) || s.is(Blocks.SOUL_WALL_TORCH)) fire(p, "torch_underground", e.getPos());
        if (s.is(Blocks.LADDER)) fire(p, "ladder_underground", e.getPos());
        for (String id : REFUNDS) {
            HabitsApi.PerkHit h = hit(p, id);
            if (h != null && blocks(h.skill().perk().param("block", "")).test(s) && roll(p, h.value())) {
                Item item = s.getBlock().asItem();
                if (item != net.minecraft.world.item.Items.AIR) Work.give(p, new ItemStack(item));
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent e) {
        if (!(e.getNewAboutToBeSetTarget() instanceof ServerPlayer p) || !(e.getEntity() instanceof Enemy)) return;
        if (!MiningMechanics.allowed(p) || !underground(p)) return;
        if (e.getEntity() instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == p) return;
        fire(p, "ambushed", e.getEntity().blockPosition());
        HabitsApi.PerkHit h = hit(p, "danger_sense");
        if (h != null) e.getEntity().addEffect(new MobEffectInstance(MobEffects.GLOWING, (int) (h.value() * 20), 0, true, false));
    }

    @SubscribeEvent
    public static void onSpawn(FinalizeSpawnEvent e) {
        if (!(e.getEntity() instanceof Enemy) || e.getSpawnType() != net.minecraft.world.entity.MobSpawnType.NATURAL) return;
        ServerLevel level = e.getLevel().getLevel();
        for (ServerPlayer p : level.players())
            if (p.distanceToSqr(e.getX(), e.getY(), e.getZ()) <= 256 && MiningMechanics.allowed(p) && underground(p))
                fire(p, "monster_near", e.getEntity().blockPosition());
    }

    @SubscribeEvent
    public static void onGameEvent(VanillaGameEvent e) {
        if (e.getVanillaEvent().is(GameEvent.SHRIEK) && e.getCause() instanceof ServerPlayer p && MiningMechanics.allowed(p))
            fire(p, "shrieker_near", BlockPos.containing(e.getEventPosition()));
    }

    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish e) {
        if (e.getEntity() instanceof ServerPlayer p && MiningMechanics.allowed(p) && e.getItem().has(DataComponents.FOOD) && underground(p))
            fire(p, "underground_meal", p.blockPosition());
    }

    @SubscribeEvent
    public static void onBlast(ExplosionEvent.Detonate e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getExplosion().getDirectSourceEntity() instanceof PrimedTnt)
                || !(e.getExplosion().getIndirectSourceEntity() instanceof ServerPlayer p) || !MiningMechanics.allowed(p)) return;
        HabitsApi.PerkHit h = hit(p, "powder_miner");
        boolean any = false;
        var it = e.getAffectedBlocks().iterator();
        while (it.hasNext()) {
            BlockPos b = it.next();
            BlockState s = level.getBlockState(b);
            if (!s.is(Tags.Blocks.ORES)) continue;
            any = true;
            if (h == null || !roll(p, h.value())) continue;
            // the plain loot table without the blast's 1/radius survival roll
            Block.dropResources(s, level, b, level.getBlockEntity(b), p, ItemStack.EMPTY);
            level.removeBlock(b, false);
            it.remove();
        }
        if (any) fire(p, "tnt_ore", p.blockPosition());
    }

    // ---------------------------------------------------------------- spare pick

    static final ItemAbility[] KINDS = {ItemAbilities.PICKAXE_DIG, ItemAbilities.AXE_DIG, ItemAbilities.SHOVEL_DIG, ItemAbilities.HOE_DIG,
            ItemAbilities.SWORD_DIG, ItemAbilities.SHEARS_DIG};

    @SubscribeEvent
    public static void onToolBreak(PlayerDestroyItemEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || e.getHand() != InteractionHand.MAIN_HAND || e.getOriginal().isEmpty()) return;
        HabitsApi.PerkHit h = hit(p, "spare_pick");
        if (h == null) return;
        ItemStack broke = e.getOriginal().copy();
        Predicate<ItemStack> same = st -> st.is(broke.getItem());
        for (ItemAbility a : KINDS) if (broke.canPerformAction(a)) { same = st -> st.canPerformAction(a); break; }
        Predicate<ItemStack> match = same;
        int slots = h.level() >= (int) lerp(h, "inventory_level", 6, 6) ? 36 : 9;
        Later.run(p.serverLevel(), 1, () -> {
            Inventory inv = p.getInventory();
            if (!p.getMainHandItem().isEmpty()) return;
            for (int i = 0; i < slots; i++) {
                if (i == inv.selected || !match.test(inv.getItem(i))) continue;
                inv.setItem(inv.selected, inv.getItem(i));
                inv.setItem(i, ItemStack.EMPTY);
                return;
            }
        });
    }

    // ---------------------------------------------------------------- escape rope

    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickItem e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !p.isShiftKeyDown()) return;
        HabitsApi.PerkHit h = hit(p, "escape_rope");
        if (h == null || !BuiltInRegistries.ITEM.getKey(e.getItemStack().getItem()).toString().equals(h.skill().perk().param("item", "minecraft:string"))) return;
        ServerLevel level = p.serverLevel();
        if (!underground(p) || level.dimensionType().hasCeiling()) return;
        e.setCanceled(true);
        long now = level.getGameTime();
        long ready = p.getPersistentData().getLong("habits_rope_ready");
        if (now < ready) {
            p.displayClientMessage(Component.translatable("habits.mining.rope_wait", (ready - now + 19) / 20), true);
            return;
        }
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
        if (top <= p.getY() + 2) return;
        p.getPersistentData().putLong("habits_rope_ready", now + (long) (h.value() * 20));
        if (!p.getAbilities().instabuild) e.getItemStack().shrink(1);
        p.teleportTo(p.getBlockX() + 0.5, top, p.getBlockZ() + 0.5);
        p.resetFallDistance();
        level.playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.LADDER_STEP, net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
    }

    // ---------------------------------------------------------------- tick mechanics

    static void reveal(Player player, HabitsApi.PerkHit h, String kind) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 20 != 0) return;
        int r = (int) h.value();
        if (!Glow.due(p, kind, r, 100)) return;
        Predicate<BlockState> match = blocks(h.skill().perk().param("block", ""));
        Predicate<BlockState> test = "1".equals(h.skill().perk().param("source", "0")) ? st -> match.test(st) && st.getFluidState().isSource() : match;
        int color;
        try {
            color = Integer.parseInt(h.skill().perk().param("color", "FFFFFF"), 16);
        } catch (NumberFormatException ex) {
            color = 0xFFFFFF;
        }
        int c = color;
        int ticks = (int) (lerp(h, "glow_s", 6, 15) * 20);
        Scanner.start(p, kind, p.blockPosition(), r, Integer.MIN_VALUE, Integer.MAX_VALUE, test,
                hits -> MiningNet.marks(p, hits, hits.stream().map(b -> c).toList(), ticks, Glow.OUTLINE));
    }

    static void shake(Player p, HabitsApi.PerkHit h, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        if (p.level().isClientSide() || p.tickCount % 20 != 0 || !p.hasEffect(effect)) return;
        if (roll(p, h.value())) p.removeEffect(effect);
    }

    static void whetstone(Player p, HabitsApi.PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 60 != 0 || !underground(p)) return;
        ItemStack st = p.getMainHandItem();
        if (!st.isDamageableItem() || st.getDamageValue() <= 0) return;
        int fix = MiningMechanics.roll(h.value() / 20, p.getRandom());
        if (fix > 0) st.setDamageValue(Math.max(0, st.getDamageValue() - fix));
    }

    static void canary(Player p, HabitsApi.PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 40 != 0 || !underground(p)) return;
        int n = monsters(p, h.value()).size();
        if (n > 0) p.displayClientMessage(Component.translatable(n == 1 ? "habits.mining.canary.one" : "habits.mining.canary", n), true);
    }

    static void crystalGrower(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 20 != 0) return;
        ServerLevel level = p.serverLevel();
        int r = (int) lerp(h, "radius", 8, 16);
        if (Glow.due(p, P + "crystal_grower", r, 600))
            Scanner.start(p, P + "crystal_grower", p.blockPosition(), r, Integer.MIN_VALUE, Integer.MAX_VALUE,
                    st -> st.is(Blocks.BUDDING_AMETHYST), hits -> BUDDING.put(p.getUUID(), List.copyOf(hits.subList(0, Math.min(64, hits.size())))));
        for (BlockPos b : BUDDING.getOrDefault(p.getUUID(), List.of())) {
            if (!level.isLoaded(b) || !roll(p, h.value())) continue;
            BlockState s = level.getBlockState(b);
            if (s.is(Blocks.BUDDING_AMETHYST)) s.randomTick(level, b, level.random);
        }
    }

    static void oreCompass(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || p.tickCount % 40 != 0) return;
        if (!(p.getOffhandItem().getItem() instanceof BlockItem bi) || !bi.getBlock().defaultBlockState().is(Tags.Blocks.ORES)) return;
        if (Scanner.running(p, P + "ore_compass")) return;
        BlockState want = bi.getBlock().defaultBlockState();
        BlockPos at = p.blockPosition();
        Scanner.start(p, P + "ore_compass", at, (int) h.value(), Integer.MIN_VALUE, Integer.MAX_VALUE, BreakPerks.sameOre(want), hits -> {
            var best = hits.stream().min(Comparator.comparingDouble(b -> b.distSqr(at)));
            if (best.isEmpty()) {
                p.displayClientMessage(Component.translatable("habits.mining.compass_none", bi.getDescription()), true);
                return;
            }
            MiningNet.marks(p, List.of(best.get()), List.of(Glow.oreColor(want)), 60, Glow.TRAIL);
            p.displayClientMessage(Component.translatable("habits.mining.compass", bi.getDescription(), (int) Math.sqrt(best.get().distSqr(at))), true);
        });
    }

    static void drillRun(Player player, HabitsApi.PerkHit h) {
        if (!(player instanceof ServerPlayer p) || !p.isSprinting() || !underground(p)) return;
        int every = Math.max(1, (int) Math.round(20 / Math.max(0.1, h.value())));
        if (p.tickCount % every != 0 || Work.pending(p) > 0 || !p.getMainHandItem().canPerformAction(ItemAbilities.PICKAXE_DIG)) return;
        ServerLevel level = p.serverLevel();
        Direction f = p.getDirection();
        BlockPos feet = p.blockPosition().relative(f);
        List<BlockPos> ahead = new ArrayList<>();
        for (BlockPos b : new BlockPos[]{feet.above(), feet}) {
            BlockState s = level.getBlockState(b);
            if (level.isLoaded(b) && BreakPerks.boreable(level, s) && p.hasCorrectToolForDrops(s)) { ahead.add(b); break; }
        }
        if (!ahead.isEmpty()) Work.queueBreak(p, ahead, (int) Math.max(1, lerp(h, "per_tick", 2, 2)),
                st -> BreakPerks.boreable(level, st) && p.hasCorrectToolForDrops(st));
    }
}
