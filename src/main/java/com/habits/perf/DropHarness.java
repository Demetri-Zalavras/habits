package com.habits.perf;

import com.habits.Habits;
import com.habits.core.CardItems;
import com.habits.core.CoreInit;
import com.habits.core.CoreRegistries;
import com.habits.core.IdMatch;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import com.habits.core.TargetKind;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerSpec;
import com.habits.core.TriggerType;
import com.habits.trigger.TriggerEvents;
import com.habits.trigger.Triggers;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Dev-only card drop-rate measurement (docs/DROPRATES.md). Inert unless {@code HABITS_DROPS=1}. On server start it
 * plays every skill's action on one fake player until about {@link #TARGET} of that skill's cards land in the
 * inventory, writes {@code drops/measured.csv} (relative to the run dir) and halts the server.
 * <p>Paths, most real first: STAT (vanilla {@code StatsCounter.increment}, so StatAwardEvent, the trigger listener and
 * its once-a-second batch flush run for real), XP (a real PickupXp event + flush), BREAK (a real survival
 * {@code destroyBlock} with the tool in hand), FIRE ({@code Triggers.fire} with the context the listener or perk
 * package builds, for everything else). The count is the cards that really arrived, so the gate, the catalog
 * matchers, the per-unit math and the drop-rate config all count.
 */
@EventBusSubscriber(modid = Habits.MODID)
public final class DropHarness {
    private DropHarness() {}

    private static final Logger LOG = LogUtils.getLogger();
    static final boolean ON = "1".equals(System.getenv("HABITS_DROPS"));
    static final int TARGET = PerfHarness.intEnv("HABITS_DROPS_N", 300);
    /** Effort cap: stop after this many times the iterations the nominal chance needs for TARGET drops. */
    static final int GIVE_UP = 20;
    static final long MAX_ITERS = 3_000_000;

    static ServerLevel level;
    static FakePlayer p;
    static BlockPos at;
    static Method poll;

    record Row(SkillDef d, String path, @Nullable ResourceLocation target, @Nullable ResourceLocation item,
               @Nullable ResourceLocation entity, double amount, String unit, long events, double units, int drops, String note) {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent e) {
        if (!ON) return;
        MinecraftServer server = e.getServer();
        level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        at = level.getSharedSpawnPos().above(2);
        p = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("habits-drops".getBytes()), "drops"));
        p.setGameMode(GameType.SURVIVAL);
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        PerfHarness.allow(p.getUUID(), true);
        try {
            poll = TriggerEvents.class.getDeclaredMethod("poll", ServerPlayer.class);
            poll.setAccessible(true);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        List<SkillDef> all = new ArrayList<>(SkillRegistry.all());
        all.sort(Comparator.comparing(d -> d.id().toString()));
        List<String> out = new ArrayList<>();
        out.add("skill,rarity,trigger,path,target,item,entity,amount_per_event,unit,events,units,drops,units_per_drop,"
                + "measured_chance_per_unit,nominal_chance_per_unit,note");
        long t0 = System.nanoTime();
        for (SkillDef d : all) {
            Row r;
            try {
                r = measure(d);
            } catch (Throwable t) {
                LOG.error("[habits-drops] {} failed", d.id(), t);
                r = new Row(d, "error", null, null, null, 0, "", 0, 0, 0, t.toString().replace(',', ';'));
            }
            out.add(csv(r));
            reset();
        }
        out.add(0, "# HABITS_DROPS run: " + all.size() + " skills, target " + TARGET + " drops each, drop_rate config "
                + CoreInit.DROP_RATE.get() + ", " + (System.nanoTime() - t0) / 1_000_000_000 + " s");
        try {
            Files.createDirectories(Path.of("drops"));
            Files.write(Path.of("drops/measured.csv"), out);
            LOG.info("[habits-drops] wrote {} rows to {}", all.size(), Path.of("drops/measured.csv").toAbsolutePath());
        } catch (IOException ex) {
            LOG.error("[habits-drops] write failed", ex);
        }
        server.halt(false);
    }

    static String csv(Row r) {
        TriggerSpec s = r.d.trigger();
        double nominal = Math.min(1, s.chance() * CoreInit.DROP_RATE.get());
        return String.join(",", r.d.id().getPath(), r.d.rarity().getSerializedName(), s.type().getSerializedName(), r.path,
                str(r.target), str(r.item), str(r.entity), num(r.amount), r.unit, Long.toString(r.events), num(r.units),
                Integer.toString(r.drops), r.drops == 0 ? "" : num(r.units / r.drops), num(r.drops / Math.max(r.units, 1e-12)),
                num(nominal), r.note);
    }

    static String str(@Nullable ResourceLocation id) {
        return id == null ? "" : id.toString();
    }

    static String num(double v) {
        return String.format(Locale.ROOT, "%.6g", v);
    }

    static void reset() {
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    // ---------------------------------------------------------------- one skill

    static Row measure(SkillDef d) {
        TriggerSpec s = d.trigger();
        TriggerType t = s.type();
        RegistryAccess access = level.registryAccess();
        ResourceLocation target = s.match().map(m -> pick(m, t.targetKind, access)).orElseGet(() -> defaultTarget(t));
        ResourceLocation item = s.item().map(m -> pick(m, TargetKind.ITEM, access)).orElse(null);
        ResourceLocation entity = s.entity().map(m -> pick(m, TargetKind.ENTITY, access)).orElse(null);
        double chance = Math.min(1, s.chance() * CoreInit.DROP_RATE.get());
        return switch (t) {
            case WALK_DISTANCE, SPRINT_DISTANCE, SNEAK_DISTANCE, SWIM_DISTANCE, CLIMB_DISTANCE, ELYTRA_DISTANCE,
                 BOAT_DISTANCE, RIDE_DISTANCE, JUMP -> moveStat(d, t, target, chance);
            case STAT_INCREMENT -> customStat(d, target, chance);
            case USE_ITEM, BUCKET_EMPTY, ITEM_BREAK, PICKUP_ITEM -> itemStat(d, t, target, chance);
            case XP_PICKUP -> xp(d, chance);
            case BREAK_BLOCK, HARVEST_CROP -> breakBlock(d, t, target, item, chance);
            default -> fire(d, t, target, item, entity, chance);
        };
    }

    /** Loop {@code step} until TARGET cards of {@code d} arrived (or the effort cap). step returns the units it did. */
    static long[] run(SkillDef d, double pPerIter, java.util.function.DoubleSupplier step, double[] units) {
        long cap = (long) Math.min(MAX_ITERS, Math.ceil(GIVE_UP * TARGET / Math.max(pPerIter, 1e-9)));
        long i = 0;
        int drops = 0;
        while (i < cap && drops < TARGET) {
            units[0] += step.getAsDouble();
            i++;
            drops += collect(d);
        }
        return new long[]{i, drops};
    }

    /** Cards of {@code d} that landed in the inventory since the last call; every card is removed. */
    static int collect(SkillDef d) {
        int n = 0;
        var items = p.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            ItemStack st = items.get(i);
            if (st.isEmpty() || !st.is(CoreRegistries.CARD.get())) continue;
            if (CardItems.skillOf(st) == d) n += st.getCount();
            items.set(i, ItemStack.EMPTY);
        }
        return n;
    }

    static void flush() {
        try {
            poll.invoke(null, p);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static Row moveStat(SkillDef d, TriggerType t, @Nullable ResourceLocation mount, double chance) {
        // one iteration = one second of the movement, as the listener batches it (cm per second, or jumps)
        Stat<ResourceLocation> stat;
        int perSecond;
        String note = "";
        switch (t) {
            case WALK_DISTANCE -> { stat = Stats.CUSTOM.get(Stats.WALK_ONE_CM); perSecond = 432; }
            case SPRINT_DISTANCE -> { stat = Stats.CUSTOM.get(Stats.SPRINT_ONE_CM); perSecond = 561; }
            case SNEAK_DISTANCE -> { stat = Stats.CUSTOM.get(Stats.CROUCH_ONE_CM); perSecond = 131; }
            case SWIM_DISTANCE -> { stat = Stats.CUSTOM.get(Stats.SWIM_ONE_CM); perSecond = 300; }
            case CLIMB_DISTANCE -> { stat = Stats.CUSTOM.get(Stats.CLIMB_ONE_CM); perSecond = 235; }
            case ELYTRA_DISTANCE -> { stat = Stats.CUSTOM.get(Stats.AVIATE_ONE_CM); perSecond = 3000; }
            case BOAT_DISTANCE -> { stat = Stats.CUSTOM.get(Stats.BOAT_ONE_CM); perSecond = 800; }
            case JUMP -> { stat = Stats.CUSTOM.get(Stats.JUMP); perSecond = 1; }
            default -> {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(mount);
                Entity e = type.create(level);
                if (e == null) return new Row(d, "stat", mount, null, null, 0, "block", 0, 0, 0, "mount not creatable");
                e.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                level.addFreshEntity(e);
                ride(e); // FakePlayer.startRiding always refuses; the listener only reads getVehicle()
                note = "vehicle set directly";
                ResourceLocation rl = type == EntityType.PIG ? Stats.PIG_ONE_CM : type == EntityType.STRIDER ? Stats.STRIDER_ONE_CM
                        : type == EntityType.MINECART ? Stats.MINECART_ONE_CM : Stats.HORSE_ONE_CM;
                stat = Stats.CUSTOM.get(rl);
                perSecond = 800;
            }
        }
        boolean jump = t == TriggerType.JUMP;
        double scale = jump ? 1 : 0.01;
        double perIter = 1 - Math.pow(1 - chance, perSecond * scale);
        double[] units = {0};
        int ps = perSecond;
        Stat<ResourceLocation> st = stat;
        long[] r = run(d, perIter, () -> {
            p.getStats().increment(p, st, ps);
            flush();
            return ps * scale;
        }, units);
        if (p.getVehicle() != null) { Entity v = p.getVehicle(); ride(null); v.discard(); }
        return new Row(d, "stat", t == TriggerType.RIDE_DISTANCE ? mount : null, null, null, perSecond * scale,
                jump ? "jump" : "block", r[0], units[0], (int) r[1], note);
    }

    static void ride(@Nullable Entity vehicle) {
        try {
            java.lang.reflect.Field f = Entity.class.getDeclaredField("vehicle");
            f.setAccessible(true);
            f.set(p, vehicle);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static Row customStat(SkillDef d, ResourceLocation statId, double chance) {
        Stat<ResourceLocation> stat = Stats.CUSTOM.get(BuiltInRegistries.CUSTOM_STAT.get(statId)); // the registered instance: the stat registry keys by identity
        String path = statId.getPath();
        // one iteration = one action for count stats, one second for cm and tick stats
        boolean cm = path.endsWith("_one_cm");
        boolean ticks = path.endsWith("_time") || path.startsWith("time_since");
        int delta = cm ? 400 : ticks ? 20 : 1;
        double scale = cm ? 0.01 : ticks ? 0.05 : 1;
        double perIter = 1 - Math.pow(1 - chance, delta);
        double[] units = {0};
        long[] r = run(d, perIter, () -> {
            p.getStats().increment(p, stat, delta);
            flush();
            return delta * scale;
        }, units);
        return new Row(d, "stat", statId, null, null, delta * scale, cm ? "block" : ticks ? "second" : "count", r[0], units[0], (int) r[1], "");
    }

    static Row itemStat(SkillDef d, TriggerType t, ResourceLocation itemId, double chance) {
        var item = BuiltInRegistries.ITEM.get(itemId);
        Stat<?> stat = switch (t) {
            case ITEM_BREAK -> Stats.ITEM_BROKEN.get(item);
            case PICKUP_ITEM -> Stats.ITEM_PICKED_UP.get(item);
            default -> Stats.ITEM_USED.get(item);
        };
        double[] units = {0};
        long[] r = run(d, chance, () -> {
            p.getStats().increment(p, stat, 1);
            return 1;
        }, units);
        return new Row(d, "stat", itemId, null, null, 1, "count", r[0], units[0], (int) r[1], "");
    }

    static Row xp(SkillDef d, double chance) {
        // one iteration = one orb worth 3 points, flushed like the once-a-second batch
        int value = 3;
        double[] units = {0};
        long[] r = run(d, 1 - Math.pow(1 - chance, value), () -> {
            NeoForge.EVENT_BUS.post(new PlayerXpEvent.PickupXp(p, new ExperienceOrb(level, at.getX(), at.getY(), at.getZ(), value)));
            flush();
            return value;
        }, units);
        return new Row(d, "xp", null, null, null, value, "xp", r[0], units[0], (int) r[1], "");
    }

    static Row breakBlock(SkillDef d, TriggerType t, ResourceLocation blockId, @Nullable ResourceLocation toolId, double chance) {
        Block b = BuiltInRegistries.BLOCK.get(blockId);
        BlockState state = t == TriggerType.HARVEST_CROP ? mature(b) : b.defaultBlockState();
        ItemStack tool = toolId != null ? new ItemStack(BuiltInRegistries.ITEM.get(toolId)) : toolFor(state);
        BlockPos pos = at.east(2);
        double[] units = {0};
        long[] r = run(d, chance, () -> {
            p.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            p.gameMode.destroyBlock(pos);
            return 1;
        }, units);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        return new Row(d, "break", blockId, toolId != null ? toolId : BuiltInRegistries.ITEM.getKey(tool.getItem()), null, 1,
                "count", r[0], units[0], (int) r[1], "");
    }

    static BlockState mature(Block b) {
        BlockState s = b.defaultBlockState();
        if (b instanceof CropBlock c) return c.getStateForAge(c.getMaxAge());
        if (b instanceof CocoaBlock) return s.setValue(CocoaBlock.AGE, CocoaBlock.MAX_AGE);
        if (b instanceof NetherWartBlock) return s.setValue(NetherWartBlock.AGE, NetherWartBlock.MAX_AGE);
        if (b instanceof SweetBerryBushBlock) return s.setValue(SweetBerryBushBlock.AGE, SweetBerryBushBlock.MAX_AGE);
        return s;
    }

    static ItemStack toolFor(BlockState s) {
        if (s.is(BlockTags.MINEABLE_WITH_PICKAXE)) return new ItemStack(Items.DIAMOND_PICKAXE);
        if (s.is(BlockTags.MINEABLE_WITH_AXE)) return new ItemStack(Items.DIAMOND_AXE);
        if (s.is(BlockTags.MINEABLE_WITH_SHOVEL)) return new ItemStack(Items.DIAMOND_SHOVEL);
        if (s.is(BlockTags.MINEABLE_WITH_HOE)) return new ItemStack(Items.DIAMOND_HOE);
        return ItemStack.EMPTY;
    }

    static Row fire(SkillDef d, TriggerType t, @Nullable ResourceLocation target, @Nullable ResourceLocation item,
                    @Nullable ResourceLocation entity, double chance) {
        double amount = amountFor(d.trigger());
        double perIter = t.perUnit ? 1 - Math.pow(1 - chance, amount) : chance;
        TriggerContext ctx = new TriggerContext(target, amount, item, entity);
        double[] units = {0};
        long[] r = run(d, perIter, () -> {
            Triggers.fire(p, t, ctx);
            return t.perUnit ? amount : 1;
        }, units);
        return new Row(d, "fire", target, item, entity, amount, t.perUnit ? "unit" : "count", r[0], units[0], (int) r[1], "");
    }

    /** The amount the real listener or perk site passes per event (see TriggerEvents and the perk packages). Only
     * min-gated skills and per-unit types care. ENCHANT is lapis consumed (1..3), so 3 is the most it can be. */
    static double amountFor(TriggerSpec s) {
        double real = switch (s.type()) {
            case ENCHANT -> 3;
            case DAMAGE_DEALT -> 6;
            case DAMAGE_TAKEN -> 4;
            case SHIELD_BLOCK -> 5;
            case ANVIL -> 5;
            default -> 1;
        };
        return s.type() == TriggerType.ENCHANT ? real : Math.max(real, s.min());
    }

    @Nullable
    static ResourceLocation defaultTarget(TriggerType t) {
        return switch (t) {
            case BREAK_BLOCK, HARVEST_CROP -> id(Blocks.STONE);
            case PLACE_BLOCK -> id(Blocks.COBBLESTONE);
            case INTERACT_BLOCK -> id(Blocks.NETHERRACK);
            case OPEN_CONTAINER -> id(Blocks.CHEST);
            case PLANT, BONEMEAL -> id(Blocks.WHEAT);
            case TILL -> id(Blocks.DIRT);
            case KILL_ENTITY -> rl("zombie");
            case BREED, SHEAR, INTERACT_ENTITY -> rl("cow");
            case TAME -> rl("wolf");
            case DAMAGE_DEALT -> rl("player_attack");
            case DAMAGE_TAKEN, SHIELD_BLOCK, DIE -> rl("mob_attack");
            case SHOOT_PROJECTILE -> rl("bow");
            case EAT -> rl("bread");
            case DRINK_POTION -> rl("swiftness");
            case USE_ITEM, PICKUP_ITEM -> rl("cobblestone");
            case BUCKET_FILL -> rl("water_bucket");
            case BUCKET_EMPTY -> rl("water_bucket");
            case ITEM_BREAK -> rl("iron_pickaxe");
            case CRAFT -> rl("stick");
            case SMELT -> rl("iron_ingot");
            case BREW -> rl("awkward");
            case ENCHANT -> rl("sharpness");
            case ANVIL -> rl("diamond_sword");
            case TRADE -> rl("emerald");
            case FISH -> rl("cod");
            case ENTER_BIOME -> rl("plains");
            case ENTER_DIMENSION -> rl("the_nether");
            case ENTER_STRUCTURE -> rl("village_plains");
            default -> null;
        };
    }

    static ResourceLocation rl(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    static ResourceLocation id(Block b) {
        return BuiltInRegistries.BLOCK.getKey(b);
    }

    /** First listed id, else the first member of the first tag that has one. */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ResourceLocation pick(IdMatch m, TargetKind kind, RegistryAccess access) {
        if (!m.ids().isEmpty()) return m.ids().get(0);
        if (kind.registry == null) return null;
        Optional<? extends Registry<?>> reg = access.registry((ResourceKey) kind.registry);
        if (reg.isEmpty()) return null;
        for (ResourceLocation tag : m.tags()) {
            var set = ((Registry) reg.get()).getTag(TagKey.create((ResourceKey) kind.registry, tag));
            if (set.isPresent()) for (Object h : (Iterable<?>) set.get())
                if (h instanceof Holder<?> hh && hh.unwrapKey().isPresent()) return ((ResourceKey<?>) hh.unwrapKey().get()).location();
        }
        return null;
    }
}
