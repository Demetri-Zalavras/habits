package com.habits.perk.utility;

import com.habits.core.CardItems;
import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.core.component.DataComponents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Furnace, smoker, blast furnace and campfire perks. Block perks only touch stations the player used (see {@link Stations}). */
final class Furnaces {
    private Furnaces() {}

    static final ResourceLocation POCKET = U.mech("pocket_furnace", "seconds per item smelted in your bag");
    static final ResourceLocation STOKER = U.mech("stoker", "% faster cooking near you");
    static final ResourceLocation LINE_COOK = U.mech("line_cook", "% more saturation from smoked food");
    static final ResourceLocation ORE = U.mech("ore_whisperer", "% chance to double ingots from a blast furnace");
    static final ResourceLocation FUEL = U.mech("fuel_saver", "% longer fuel burn");
    static final ResourceLocation CAMPFIRE = U.mech("campfire_chef", "% faster campfire cooking");
    static final ResourceLocation HOT_HANDS = U.mech("hot_hands", "seconds of cooldown");

    static final TagKey<Item> INGOTS = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "ingots"));

    static void init() {}

    // ------------------------------------------------------------------ station tracking

    @SubscribeEvent
    static void onUse(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (!U.ok(p)) return;
        BlockState s = p.level().getBlockState(e.getPos());
        if (s.getBlock() instanceof AbstractFurnaceBlock || s.getBlock() instanceof BrewingStandBlock || s.getBlock() instanceof CampfireBlock) {
            Stations.used(p, e.getPos());
        }
    }

    // ------------------------------------------------------------------ slow tick (every 20 ticks)

    private static final Map<UUID, int[]> POCKET_STATE = U.playerMap(); // {ticks since last smelt, fuel ticks banked}
    private static final Map<UUID, double[]> CARRY = U.playerMap();

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.tickCount % 20 != 0 || !U.ok(p)) return;
        HabitsApi.PerkHit h;
        if ((h = U.hit(p, POCKET)) != null) pocketTick(p, h.value(), 20);
        if ((h = U.hit(p, STOKER)) != null) stoke(p, h.value());
        if ((h = U.hit(p, FUEL)) != null) saveFuel(p, h.value());
        if ((h = U.hit(p, CAMPFIRE)) != null) campfire(p, h);
    }

    /** Pocket furnace: one main-inventory item every {@code seconds}, paid with fuel from the inventory. */
    static boolean pocketTick(Player p, double seconds, int elapsed) {
        int[] st = POCKET_STATE.computeIfAbsent(p.getUUID(), k -> new int[2]);
        st[0] += elapsed;
        if (st[0] < seconds * 20) return false;
        var inv = p.getInventory();
        for (int i = 9; i < inv.items.size(); i++) {
            ItemStack in = inv.items.get(i);
            if (in.isEmpty() || CardItems.dataOf(in) != null) continue;
            ItemStack out = smeltResult(p.level(), in, RecipeType.SMELTING);
            if (out.isEmpty()) continue;
            if (st[1] < 200 && !burnFuel(p, st, in)) return false;
            st[0] = 0;
            st[1] -= 200;
            in.shrink(1);
            U.give(p, out);
            return true;
        }
        return false;
    }

    private static boolean burnFuel(Player p, int[] st, ItemStack smelting) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.items.size() && st[1] < 200; i++) {
            ItemStack f = inv.items.get(i);
            if (f.isEmpty() || f == smelting) continue;
            int burn = f.getBurnTime(RecipeType.SMELTING);
            if (burn <= 0) continue;
            ItemStack rest = f.hasCraftingRemainingItem() ? f.getCraftingRemainingItem() : ItemStack.EMPTY;
            f.shrink(1);
            st[1] += burn;
            U.give(p, rest);
        }
        return st[1] >= 200;
    }

    private static RecipeManager cachedFor;
    private static final Map<Item, Optional<ItemStack>> RESULTS = new HashMap<>();

    /** Result of cooking one {@code in} (cached per item for plain stacks), or empty. */
    static ItemStack smeltResult(Level level, ItemStack in, RecipeType<? extends net.minecraft.world.item.crafting.AbstractCookingRecipe> type) {
        boolean plain = in.getComponentsPatch().isEmpty() && type == RecipeType.SMELTING;
        if (plain) {
            if (cachedFor != level.getRecipeManager()) {
                RESULTS.clear();
                cachedFor = level.getRecipeManager();
            }
            Optional<ItemStack> c = RESULTS.get(in.getItem());
            if (c != null) return c.map(ItemStack::copy).orElse(ItemStack.EMPTY);
        }
        ItemStack out = level.getRecipeManager().getRecipeFor(type, new SingleRecipeInput(in.copyWithCount(1)), level)
                .map(r -> r.value().assemble(new SingleRecipeInput(in.copyWithCount(1)), level.registryAccess()))
                .orElse(ItemStack.EMPTY);
        if (plain) RESULTS.put(in.getItem(), out.isEmpty() ? Optional.empty() : Optional.of(out.copy()));
        return out;
    }

    static void clearCache() {
        RESULTS.clear();
        cachedFor = null;
    }

    /** Stoker: extra vanilla furnace ticks for used furnaces within 8 blocks. Max 8 furnaces, 40 extra ticks each per second. */
    static void stoke(Player p, double pct) {
        int n = 0;
        for (AbstractFurnaceBlockEntity f : Stations.used(p, AbstractFurnaceBlockEntity.class)) {
            if (n++ >= 8 || f.getBlockPos().distSqr(p.blockPosition()) > 64) continue;
            int extra = U.carry(p, Math.min(40, 20 * pct / 100));
            for (int i = 0; i < extra; i++) {
                AbstractFurnaceBlockEntity.serverTick(p.level(), f.getBlockPos(), f.getBlockState(), f);
            }
        }
    }

    /** Fuel saver: give back a share of each second's burn so fuel lasts (1 + pct/100) times as long. */
    static void saveFuel(Player p, double pct) {
        double share = pct / (100 + pct);
        for (AbstractFurnaceBlockEntity f : Stations.used(p, AbstractFurnaceBlockEntity.class)) {
            ContainerData d = f.dataAccess;
            if (d == null || d.get(0) <= 0) continue;
            int back = U.carry(p, 20 * share);
            d.set(0, Math.min(d.get(1), d.get(0) + back));
        }
    }

    static void campfire(Player p, HabitsApi.PerkHit h) {
        boolean near = false;
        for (CampfireBlockEntity c : Stations.used(p, CampfireBlockEntity.class)) {
            BlockState s = c.getBlockState();
            if (!s.getValue(CampfireBlock.LIT) || c.getBlockPos().distSqr(p.blockPosition()) > 16) continue;
            near = true;
            int extra = U.carry(p, 20 * h.value() / 100);
            for (int i = 0; i < extra; i++) CampfireBlockEntity.cookTick(p.level(), c.getBlockPos(), s, c);
        }
        if (near) U.effect(p, MobEffects.REGENERATION, (int) U.param(h, "regen_amp", 0, 1), 70);
    }

    // ------------------------------------------------------------------ takes from a furnace result slot

    /** Called from {@link Crafting#onStat} for every result taken (exact amount). */
    static void onTake(Player p, AbstractContainerMenu m, Item item, int amount) {
        if (!(m instanceof net.minecraft.world.inventory.AbstractFurnaceMenu)) return;
        HabitsApi.PerkHit h = U.hit(p, ORE);
        if (h != null && m instanceof BlastFurnaceMenu && new ItemStack(item).is(INGOTS)) {
            int extra = 0;
            for (int i = 0; i < amount; i++) if (U.roll(p, h.value())) extra++;
            if (extra > 0) U.give(p, new ItemStack(item, extra));
        }
    }

    // ------------------------------------------------------------------ line cook

    private static RecipeManager smokedFor;
    private static final Set<Item> SMOKED = new HashSet<>();

    static boolean smoked(Level level, Item item) {
        if (smokedFor != level.getRecipeManager()) {
            SMOKED.clear();
            smokedFor = level.getRecipeManager();
            for (var r : smokedFor.getAllRecipesFor(RecipeType.SMOKING)) SMOKED.add(r.value().getResultItem(level.registryAccess()).getItem());
        }
        return SMOKED.contains(item);
    }

    @SubscribeEvent
    static void onEat(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof Player p)) return;
        FoodProperties food = e.getItem().get(DataComponents.FOOD);
        if (food == null) return;
        HabitsApi.PerkHit h = U.hit(p, LINE_COOK);
        if (h == null || !smoked(p.level(), e.getItem().getItem())) return;
        var fd = p.getFoodData();
        fd.setSaturation(Math.min(fd.getFoodLevel(), fd.getSaturationLevel() + food.saturation() * (float) h.value() / 100f));
    }

    // ------------------------------------------------------------------ hot hands

    private static final Map<UUID, Long> HOT_CD = U.playerMap();

    /** Item use only (air, or a block that ignores the item), so sneak-placing sand or logs near a furnace still places them. */
    @SubscribeEvent
    static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getEntity().isShiftKeyDown() && e.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) hotHands(e.getEntity(), e);
    }

    private static void hotHands(Player p, PlayerInteractEvent e) {
        HabitsApi.PerkHit h = U.hit(p, HOT_HANDS);
        ItemStack held = p.getMainHandItem();
        if (h == null || held.isEmpty()) return;
        long now = p.level().getGameTime();
        if (now < HOT_CD.getOrDefault(p.getUUID(), 0L)) return;
        ItemStack one = smeltResult(p.level(), held, RecipeType.SMELTING);
        if (one.isEmpty() || !nearHeat(p)) return;
        int n = Math.min(held.getCount(), (int) U.param(h, "count", 1, 64));
        held.shrink(n);
        int total = one.getCount() * n;
        while (total > 0) {
            int k = Math.min(total, one.getMaxStackSize());
            U.give(p, one.copyWithCount(k));
            total -= k;
        }
        HOT_CD.put(p.getUUID(), now + (long) (h.value() * 20));
        p.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.FIRECHARGE_USE, net.minecraft.sounds.SoundSource.PLAYERS, 0.6f, 1.4f);
        if (e instanceof PlayerInteractEvent.RightClickItem ri) ri.setCanceled(true);
    }

    /** Fire, lava or a lit furnace within 2 blocks (125 block reads, only on use). */
    static boolean nearHeat(Player p) {
        BlockPos c = p.blockPosition();
        Level level = p.level();
        for (BlockPos b : BlockPos.betweenClosed(c.offset(-2, -1, -2), c.offset(2, 2, 2))) {
            if (!level.isLoaded(b)) continue;
            BlockState s = level.getBlockState(b);
            if (s.is(BlockTags.FIRE) || s.getFluidState().is(Fluids.LAVA)
                    || s.getBlock() instanceof AbstractFurnaceBlock && s.getValue(AbstractFurnaceBlock.LIT)
                    || s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ furnace and stand data (public via the access transformer)

    static ContainerData brewData(BrewingStandBlockEntity be) {
        return be.dataAccess;
    }
}
