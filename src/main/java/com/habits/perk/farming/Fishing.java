package com.habits.perk.farming;

import com.habits.core.HabitsApi.PerkHit;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fishing perks. The bobber's bite timers are opened by the access transformer; lava fishing is vanilla's own bite logic
 * on lava (FishingHookMixin), slowed here and paid from our loot table in {@link #onFished}.
 */
public final class Fishing {
    private Fishing() {}

    static InteractionHand rodHand(Player p) {
        return p.getMainHandItem().canPerformAction(ItemAbilities.FISHING_ROD_CAST) ? InteractionHand.MAIN_HAND
                : p.getOffhandItem().canPerformAction(ItemAbilities.FISHING_ROD_CAST) ? InteractionHand.OFF_HAND : null;
    }

    // ================================================================ angler

    /** On each bite, roll once; a win reels it in for you (and recasts at max level). */
    static void anglerTick(Player p, PerkHit h) {
        FishingHook hook = p.fishing;
        if (hook == null || !(p.level() instanceof ServerLevel l)) return;
        var pd = hook.getPersistentData();
        if (hook.nibble <= 0) { pd.putBoolean("habits_rolled", false); return; }
        if (pd.getBoolean("habits_rolled")) return;
        pd.putBoolean("habits_rolled", true);
        if (Farm.roll(l, h.value())) reel(p, h.level() >= (int) Farm.lerp(h, "recast_level", 10, 10));
    }

    static boolean reel(Player p, boolean recast) {
        InteractionHand hand = rodHand(p);
        if (hand == null || p.fishing == null) return false;
        ItemStack rod = p.getItemInHand(hand);
        int dmg = p.fishing.retrieve(rod);
        rod.hurtAndBreak(dmg, p, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        p.level().playSound(null, p.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 1, 0.4f);
        if (recast && !rod.isEmpty() && p.fishing == null) rod.getItem().use(p.level(), p, hand);
        return true;
    }

    // ================================================================ storm angler

    static void stormTick(Player p, PerkHit h) {
        FishingHook hook = p.fishing;
        if (hook == null || !(p.level() instanceof ServerLevel l) || !hook.isInWater()) return;
        int lured = hook.timeUntilLured;
        if (lured <= 1) return;
        double extra = 0;
        if (!l.isRainingAt(hook.blockPosition().above()) && l.random.nextFloat() < 0.25f) extra += 1; // vanilla's rain bonus, everywhere
        double cut = Math.max(h.value(), l.isThundering() ? Farm.lerp(h, "thunder", 0, 75) : 0);
        extra += 1 / (1 - Math.min(90, cut) / 100) - 1;
        // vanilla rerolls the wait if it ever hits 0 between its own ticks, so stop at 1
        hook.timeUntilLured = Math.max(1, lured - Farm.round(l, extra));
    }

    // ================================================================ lava angler

    /** The bobber floats on lava (FishingHookMixin) and does not burn (EntityMixin); here its wait runs at the skill's speed. */
    static void lavaTick(Player p, PerkHit h) {
        FishingHook hook = p.fishing;
        if (hook == null || !(p.level() instanceof ServerLevel l) || !inLava(hook)) return;
        // vanilla counts the wait down by one a tick; give the tick back unless the speed roll wins
        if (hook.timeUntilLured > 1 && !Farm.roll(l, h.value())) hook.timeUntilLured++;
    }

    static boolean inLava(FishingHook hook) {
        return hook.level().getFluidState(hook.blockPosition()).is(FluidTags.LAVA);
    }

    static List<ItemStack> lavaLoot(ServerLevel l, PerkHit h, Vec3 at, Player p) {
        List<ItemStack> out = new ArrayList<>(Farm.loot(l, Farm.table("farming/lava_fishing"), at, p));
        if (Farm.roll(l, Farm.lerp(h, "scrap", 0, 1))) out.add(new ItemStack(Items.NETHERITE_SCRAP));
        return out;
    }

    // ================================================================ ice hole

    static void iceTick(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l)) return;
        var d = Farm.data(p);
        FishingHook hook = p.fishing;
        long[] holes = d.getLongArray("habits_holes");
        if (hook == null) {
            if (holes.length == 0) return;
            for (long at : holes) {
                BlockPos q = BlockPos.of(at);
                if (Farm.loaded(l, q) && l.getBlockState(q).is(Blocks.WATER) && l.getFluidState(q).isSource()) l.setBlockAndUpdate(q, Blocks.ICE.defaultBlockState());
            }
            d.remove("habits_holes");
            return;
        }
        BlockPos under = BlockPos.containing(hook.getX(), hook.getY() - 0.1, hook.getZ());
        if (!l.getBlockState(under).is(Blocks.ICE) || holes.length >= 16) return;
        l.setBlockAndUpdate(under, Blocks.WATER.defaultBlockState());
        long[] more = java.util.Arrays.copyOf(holes, holes.length + 1);
        more[holes.length] = under.asLong();
        d.put("habits_holes", new LongArrayTag(more));
    }

    // ================================================================ catches

    static final Set<Item> JUNK = Set.of(Items.LEATHER_BOOTS, Items.LEATHER, Items.BONE, Items.BOWL, Items.STRING, Items.STICK, Items.INK_SAC,
            Items.TRIPWIRE_HOOK, Items.ROTTEN_FLESH, Items.LILY_PAD, Items.BAMBOO, Items.POTION);
    static final Set<Item> TREASURE = Set.of(Items.ENCHANTED_BOOK, Items.BOW, Items.FISHING_ROD, Items.SADDLE, Items.NAME_TAG, Items.NAUTILUS_SHELL);

    static List<ItemStack> roll(ServerLevel l, ResourceKey<LootTable> table, FishingHook hook, Player p) {
        InteractionHand hand = rodHand(p);
        LootParams params = new LootParams.Builder(l)
                .withParameter(LootContextParams.ORIGIN, hook.position())
                .withParameter(LootContextParams.TOOL, hand == null ? ItemStack.EMPTY : p.getItemInHand(hand))
                .withParameter(LootContextParams.THIS_ENTITY, hook)
                .withLuck(p.getLuck())
                .create(LootContextParamSets.FISHING);
        return l.getServer().reloadableRegistries().getLootTable(table).getRandomItems(params);
    }

    /**
     * Vanilla spawns its own copy of the catch list, so changes work on the original stacks: a replaced item is emptied
     * (vanilla's entity for it vanishes) and every new item goes into the event list (so triggers see it) and is
     * spawned here, flying to the player like a normal catch.
     */
    @SubscribeEvent
    public static void onFished(ItemFishedEvent e) {
        Player p = e.getEntity();
        if (!(p.level() instanceof ServerLevel l) || !Farm.ok(p)) return;
        FishingHook hook = e.getHookEntity();
        List<ItemStack> drops = e.getDrops();
        List<ItemStack> add = new ArrayList<>();
        PerkHit junk = Farm.hit(p, "junk_recycler"), treasure = Farm.hit(p, "treasure_hunter"), grill = Farm.hit(p, "fresh_grill");
        ItemStack firstFish = ItemStack.EMPTY;
        PerkHit lava = Farm.hit(p, "lava_angler");
        if (lava != null && inLava(hook)) { // vanilla rolled its water table; a lava catch pays Nether loot instead
            for (ItemStack s : drops) s.setCount(0);
            add.addAll(lavaLoot(l, lava, hook.position(), p));
        }
        for (ItemStack s : List.copyOf(drops)) {
            if (s.isEmpty()) continue;
            List<ItemStack> now = List.of(s);
            if (junk != null && JUNK.contains(s.getItem()) && Farm.roll(l, junk.value()))
                now = roll(l, l.random.nextInt(18) == 0 ? BuiltInLootTables.FISHING_TREASURE : BuiltInLootTables.FISHING_FISH, hook, p);
            else if (treasure != null && !TREASURE.contains(s.getItem()) && Farm.roll(l, treasure.value()))
                now = roll(l, BuiltInLootTables.FISHING_TREASURE, hook, p);
            List<ItemStack> cooked = new ArrayList<>();
            for (ItemStack n : now) cooked.add(grill != null && Farm.roll(l, grill.value()) ? cook(n) : n);
            if (cooked.size() != 1 || cooked.get(0) != s) {
                s.setCount(0);
                add.addAll(cooked);
            }
            for (ItemStack n : cooked) if (firstFish.isEmpty() && n.is(ItemTags.FISHES)) firstFish = n;
        }
        PerkHit h;
        if ((h = Farm.hit(p, "big_bite")) != null) {
            if (Farm.roll(l, h.value())) add.addAll(roll(l, BuiltInLootTables.FISHING, hook, p));
            if (Farm.roll(l, Farm.lerp(h, "third", 0, 100))) add.addAll(roll(l, BuiltInLootTables.FISHING, hook, p));
        }
        if (treasure != null && Farm.roll(l, Farm.lerp(treasure, "chest", 0.2, 2)))
            add.addAll(Farm.loot(l, BuiltInLootTables.BURIED_TREASURE, hook.position(), p));
        if ((h = Farm.hit(p, "ice_hole")) != null && !firstFish.isEmpty() && l.getBiome(hook.blockPosition()).value().coldEnoughToSnow(hook.blockPosition())
                && Farm.roll(l, h.value()))
            add.add(firstFish.copy());
        for (ItemStack s : add) {
            if (s.isEmpty()) continue;
            drops.add(s);
            spawnCatch(hook, p, s);
        }
    }

    static ItemStack cook(ItemStack s) {
        if (s.is(Items.COD)) return new ItemStack(Items.COOKED_COD, s.getCount());
        if (s.is(Items.SALMON)) return new ItemStack(Items.COOKED_SALMON, s.getCount());
        return s;
    }

    /** Same throw, orb and stat as a vanilla catch. */
    static void spawnCatch(FishingHook hook, Player p, ItemStack s) {
        ItemEntity it = new ItemEntity(hook.level(), hook.getX(), hook.getY(), hook.getZ(), s);
        double dx = p.getX() - hook.getX(), dy = p.getY() - hook.getY(), dz = p.getZ() - hook.getZ();
        it.setDeltaMovement(dx * 0.1, dy * 0.1 + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08, dz * 0.1);
        hook.level().addFreshEntity(it);
        hook.level().addFreshEntity(new ExperienceOrb(hook.level(), p.getX(), p.getY() + 0.5, p.getZ() + 0.5, hook.getRandom().nextInt(6) + 1));
        if (s.is(ItemTags.FISHES)) p.awardStat(Stats.FISH_CAUGHT, 1);
    }
}
