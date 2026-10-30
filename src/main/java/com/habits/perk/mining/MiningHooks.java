package com.habits.perk.mining;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Logic behind the mining mixins (com.habits.perk.mining.mixin, which may hold mixin classes only). Every hook
 * starts with an O(1) ownership read: {@link MiningMechanics#fast} for a player, or one {@link MiningData} map read
 * for an owned block, and does nothing more for everyone else. */
public final class MiningHooks {
    private MiningHooks() {}

    static final ResourceLocation TRUCE = Habits.id("golden_truce"), SNOWPLOW = Habits.id("snowplow"),
            ICE = Habits.id("ice_carver"), MUFFLED = Habits.id("muffled_tools"), TIRELESS = Habits.id("tireless_tools"),
            GOLDSMITH = Habits.id("goldsmith"), ARCH = Habits.id("archaeologist"), WEB = Habits.id("web_cutter"),
            DRAIN = Habits.id("drain"), BEACON = Habits.id("beacon_builder"), HOPPER = Habits.id("hopper_hand"),
            PISTON = Habits.id("piston_power"), LAPIS = Habits.id("lapis_scholar");

    /** Persistent-data key on a falling stalactite dropped by Icicle Drop: the player it is credited to. */
    static final String ICICLE_OWNER = "habits_icicle_owner";

    // ---------------------------------------------------------------- golden truce

    /** True: mining gold or opening chests near piglins does not anger them. */
    public static boolean calmPiglins(Player p) {
        return MiningMechanics.fast(p, TRUCE) != null;
    }

    // ---------------------------------------------------------------- snowplow, web cutter (both sides: movement)

    public static boolean powderWalk(Entity e) {
        HabitsApi.PerkHit h = MiningMechanics.fast(e, SNOWPLOW);
        return h != null && h.level() >= (int) MiningMechanics.lerp(h, "powder_level", 10, 10);
    }

    /** The web slow multiplier for this entity, or null for no slow at all (100%). */
    @Nullable
    public static Vec3 webSlow(Entity e, Vec3 vanilla) {
        HabitsApi.PerkHit h = MiningMechanics.fast(e, WEB);
        if (h == null) return vanilla;
        double cut = Math.min(1, h.value() / 100);
        if (cut >= 1) return null;
        return new Vec3(vanilla.x + (1 - vanilla.x) * cut, vanilla.y + (1 - vanilla.y) * cut, vanilla.z + (1 - vanilla.z) * cut);
    }

    // ---------------------------------------------------------------- ice carver

    /** True: ice broken by this player leaves no water. */
    public static boolean keepIceDry(Player p) {
        return MiningMechanics.fast(p, ICE) != null;
    }

    // ---------------------------------------------------------------- muffled tools

    /** True when a sculk listener at {@code listener} must not hear this event: a mining or building event the
     * skilled player caused, with the listener within R of it. */
    public static boolean muffled(@Nullable Entity source, Holder<GameEvent> event, Vec3 at, GameEventListener listener, ServerLevel level) {
        if (!(source instanceof Player)
                || !(event.is(GameEvent.BLOCK_DESTROY.key()) || event.is(GameEvent.BLOCK_PLACE.key()) || event.is(GameEvent.BLOCK_CHANGE.key()))) return false;
        HabitsApi.PerkHit h = MiningMechanics.fast(source, MUFFLED);
        if (h == null) return false;
        Vec3 ear = listener.getListenerSource().getPosition(level).orElse(null);
        return ear != null && ear.distanceToSqr(at) <= h.value() * h.value();
    }

    /** True: shriekers never warn (and so never call the warden) for this player. */
    public static boolean noWarden(@Nullable Player p) {
        HabitsApi.PerkHit h = MiningMechanics.fast(p, MUFFLED);
        return h != null && h.level() >= (int) MiningMechanics.lerp(h, "silent_level", 10, 10);
    }

    // ---------------------------------------------------------------- goldsmith, tireless tools (ItemStack.hurtAndBreak)

    /** Goldsmith: golden tools take 1/mult of the wear (fraction rolled). */
    public static int goldWear(ItemStack stack, int amount, @Nullable Entity user) {
        if (amount <= 0 || !(stack.getItem() instanceof TieredItem t) || t.getTier() != Tiers.GOLD) return amount;
        HabitsApi.PerkHit h = MiningMechanics.fast(user, GOLDSMITH);
        return h == null || h.value() <= 1 ? amount : MiningMechanics.roll(amount / h.value(), user.getRandom());
    }

    /** Tireless tools: called once the new damage is stored and it reached max. Returns true when the tool was saved
     * (damage set back by value% of max, cooldown started), so vanilla skips the break. */
    public static boolean saveTool(ItemStack stack, @Nullable Entity user) {
        if (stack.getItem() instanceof ArmorItem) return false;
        HabitsApi.PerkHit h = MiningMechanics.fast(user, TIRELESS);
        if (h == null) return false;
        Player p = (Player) user;
        long now = p.level().getGameTime();
        var tag = p.getPersistentData();
        if (now < tag.getLong("habits_tireless_ready")) return false;
        tag.putLong("habits_tireless_ready", now + (long) (MiningMechanics.lerp(h, "cooldown_min", 20, 2) * 1200));
        stack.setDamageValue(Math.max(0, stack.getMaxDamage() - (int) Math.ceil(stack.getMaxDamage() * h.value() / 100)));
        p.displayClientMessage(Component.translatable("habits.mining.tireless_saved"), true);
        return true;
    }

    // ---------------------------------------------------------------- archaeologist (BrushableBlockEntity)

    /** Brushes needed to finish (vanilla 10), divided by the speed multiplier. */
    public static int brushesNeeded(@Nullable Player p, int vanilla) {
        HabitsApi.PerkHit h = MiningMechanics.fast(p, ARCH);
        return h == null ? vanilla : Math.max(1, (int) Math.ceil(vanilla / Math.max(1, h.value())));
    }

    /** Brushing finished: fire the card trigger, and maybe roll the block's loot table a second time. */
    public static void brushDone(Player p, Level level, BlockPos pos, @Nullable ResourceKey<LootTable> table, net.minecraft.core.Direction face) {
        if (!(level instanceof ServerLevel sl)) return;
        if (MiningMechanics.allowed(p)) BreakPerks.fire(p, "brush_done", pos);
        HabitsApi.PerkHit h = MiningMechanics.fast(p, ARCH);
        if (h == null || table == null || p.getRandom().nextDouble() * 100 >= MiningMechanics.lerp(h, "double", 5, 50)) return;
        LootParams params = new LootParams.Builder(sl).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withLuck(p.getLuck()).withParameter(LootContextParams.THIS_ENTITY, p).create(LootContextParamSets.CHEST);
        var items = sl.getServer().reloadableRegistries().getLootTable(table).getRandomItems(params);
        if (!items.isEmpty()) Block.popResourceFromFace(sl, pos, face, items.get(0));
    }

    // ---------------------------------------------------------------- owned blocks (MiningData, server only)

    @Nullable
    private static HabitsApi.PerkHit owned(Level level, BlockPos pos, String kind, ResourceLocation skill) {
        return level instanceof ServerLevel sl ? MiningMechanics.at(skill, MiningData.get(sl).owner(kind, pos)) : null;
    }

    /** Drain: BFS depth (vanilla 6) for an owned sponge. */
    public static int spongeDepth(Level level, BlockPos pos, int vanilla) {
        HabitsApi.PerkHit h = owned(level, pos, "drain", DRAIN);
        return h == null ? vanilla : Math.max(vanilla, (int) h.value());
    }

    /** Drain: blocks visited (vanilla 65) for an owned sponge. */
    public static int spongeLimit(Level level, BlockPos pos, int vanilla) {
        HabitsApi.PerkHit h = owned(level, pos, "drain", DRAIN);
        return h == null ? vanilla : Math.max(vanilla, (int) MiningMechanics.lerp(h, "limit", 80, 500) + 1);
    }

    /** Drain Lv10: an owned sponge stays dry after soaking. */
    public static boolean spongeStaysDry(Level level, BlockPos pos) {
        HabitsApi.PerkHit h = owned(level, pos, "drain", DRAIN);
        return h != null && h.level() >= (int) MiningMechanics.lerp(h, "dry_level", 10, 10);
    }

    /** Beacon builder: extra range in blocks for an owned beacon or conduit. */
    public static double beaconBonus(Level level, BlockPos pos) {
        HabitsApi.PerkHit h = owned(level, pos, "beacon_builder", BEACON);
        return h == null ? 0 : h.value();
    }

    /** Beacon builder: extra primary effect levels for an owned beacon. */
    public static int beaconAmp(Level level, BlockPos pos) {
        HabitsApi.PerkHit h = owned(level, pos, "beacon_builder", BEACON);
        return h != null && h.level() >= (int) MiningMechanics.lerp(h, "bonus_level", 10, 10) ? 1 : 0;
    }

    /** Hopper hand: transfer cooldown (vanilla 8) for an owned hopper. */
    public static int hopperCooldown(Level level, BlockPos pos, int vanilla) {
        HabitsApi.PerkHit h = owned(level, pos, "hopper_hand", HOPPER);
        return h == null ? vanilla : Math.max(1, (int) Math.round(vanilla / Math.max(1, h.value())));
    }

    /** Hopper hand: items per transfer (1, or {@code stack} from {@code stack_level}) for an owned hopper. */
    public static int hopperStack(Level level, BlockPos pos) {
        HabitsApi.PerkHit h = owned(level, pos, "hopper_hand", HOPPER);
        return h != null && h.level() >= (int) MiningMechanics.lerp(h, "stack_level", 10, 10) ? (int) MiningMechanics.lerp(h, "stack", 16, 16) : 1;
    }

    /** Hopper hand filter: an owned hopper named after an item ("Iron Ingot", "iron_ingot" or "minecraft:iron_ingot")
     * only takes that item. A name that is no item leaves the hopper unfiltered.
     * ponytail: matches the English item id, not localized names; add a name lookup if players ask. */
    public static boolean hopperRejects(Level level, BlockPos pos, Component name, ItemStack stack) {
        if (owned(level, pos, "hopper_hand", HOPPER) == null) return false;
        ResourceLocation id = ResourceLocation.tryParse(name.getString().trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_'));
        if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) return false;
        return !net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id);
    }

    /** Piston power: push limit for a new resolver. Server: the owner's value. Client: the client only resolves a push
     * the server already allowed, so it takes the skill's top value; retracts keep vanilla (a server pull that failed
     * must not animate). */
    public static int pistonLimit(Level level, BlockPos piston, boolean extending, int vanilla) {
        if (level instanceof ServerLevel) {
            HabitsApi.PerkHit h = owned(level, piston, "piston_power", PISTON);
            return h == null ? vanilla : Math.max(vanilla, (int) h.value());
        }
        if (!extending) return vanilla;
        HabitsApi.PerkHit top = MiningMechanics.at(PISTON, 10);
        return top == null ? vanilla : Math.max(vanilla, (int) top.value());
    }

    // ---------------------------------------------------------------- icicle drop

    /** Falling stalactites from Icicle Drop are credited to the player who dropped them. */
    public static DamageSource icicleSource(Entity falling, DamageSource vanilla) {
        if (!(falling instanceof FallingBlockEntity) || !(falling.level() instanceof ServerLevel sl)
                || !falling.getPersistentData().hasUUID(ICICLE_OWNER)) return vanilla;
        return sl.getEntity(falling.getPersistentData().getUUID(ICICLE_OWNER)) instanceof Player owner
                ? new DamageSource(vanilla.typeHolder(), falling, owner) : vanilla;
    }

    // ---------------------------------------------------------------- lapis scholar

    /** ExperienceOrb.playerTouch, the xp about to go to Mending: like Mending, it first repairs the held tool at
     * repair% of Mending's 2 durability per xp, and that xp is spent. Only the rest goes on to vanilla Mending and then
     * to the player, so one orb's xp never repairs twice. */
    public static int lapisRepair(Player p, int xp) {
        HabitsApi.PerkHit h = MiningMechanics.fast(p, LAPIS);
        ItemStack tool = p.getMainHandItem();
        if (h == null || xp <= 0 || !tool.isDamaged()) return xp;
        double rate = 2 * MiningMechanics.lerp(h, "repair", 10, 100) / 100;
        int fix = (int) Math.min(tool.getDamageValue(), Math.floor(xp * rate));
        if (fix <= 0) return xp;
        tool.setDamageValue(tool.getDamageValue() - fix);
        return xp - Math.min(xp, (int) Math.ceil(fix / rate));
    }
}
