package com.habits.perk.utility;

import com.habits.core.CardItems;
import com.habits.core.HabitsApi;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Crafting table, crafter, stonecutter and smithing perks. */
final class Crafting {
    private Crafting() {}

    static final ResourceLocation HANDY = U.mech("handy", "% chance a craft gives one extra item");
    static final ResourceLocation THRIFTY = U.mech("thrifty_hands", "% chance one ingredient comes back");
    static final ResourceLocation WORKBENCH = U.mech("pocket_workbench", "portable table cooldown in seconds");
    static final ResourceLocation RECIPE_MEMORY = U.mech("recipe_memory", "refill sources (1 bundles, 2 shulkers, 3 ender chest)");
    static final ResourceLocation MASS = U.mech("mass_production", "chest pull radius in blocks");
    static final ResourceLocation CLOCKWORK = U.mech("clockwork", "seconds between timed crafts");
    static final ResourceLocation TINKERER = U.mech("tinkerer", "% bonus max durability");
    static final ResourceLocation SIGNATURE = U.mech("signature_work", "% chance of a bonus enchant level");
    static final ResourceLocation MASON = U.mech("mason", "% chance of one extra stonecutter item");
    static final ResourceLocation STONE_SENSE = U.mech("stone_sense", "stacks converted per use");
    static final ResourceLocation SMITH = U.mech("smith", "% chance to keep the smithing template");

    static void init() {}

    // ------------------------------------------------------------------ crafting table / 2x2 grid

    /** Session craft counts per item (signature_work trigger). */
    private static final Map<UUID, Map<Item, Integer>> SESSION = U.playerMap();
    /** Grid snapshot taken at a craft, refilled on the next tick (recipe_memory, mass_production). */
    private static final Map<UUID, Refill> REFILL = U.playerMap();

    private record Refill(AbstractContainerMenu menu, CraftingContainer grid, ItemStack[] before) {}

    @SubscribeEvent
    static void onCraft(PlayerEvent.ItemCraftedEvent e) {
        Player p = e.getEntity();
        ItemStack out = e.getCrafting();
        if (!U.ok(p) || out.isEmpty() || !(e.getInventory() instanceof CraftingContainer grid)) return;
        int filled = 0;
        List<Item> kinds = new ArrayList<>();
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack s = grid.getItem(i);
            if (s.isEmpty()) continue;
            filled++;
            if (!kinds.contains(s.getItem())) kinds.add(s.getItem());
        }
        if (filled >= 5) U.fire(p, "big_craft");
        if (p.containerMenu.getCarried() != out) U.fire(p, "shift_craft");
        Map<Item, Integer> session = SESSION.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
        // signature_work ("crafting 64 of something"): once per 64 made, then the count starts over
        if (session.merge(out.getItem(), out.getCount(), Integer::sum) >= 64) {
            session.remove(out.getItem());
            U.fire(p, "mass_craft");
        }

        boolean bonusSafe = bonusSafe(p, grid, out, kinds.size());
        HabitsApi.PerkHit h;
        if (bonusSafe && (h = U.hit(p, HANDY)) != null && U.roll(p, h.value())) U.give(p, out.copyWithCount(1));
        if (bonusSafe && (h = U.hit(p, THRIFTY)) != null && U.roll(p, h.value())) refundOne(p, grid);

        if (out.isDamageableItem()) {
            ItemStack real = taken(p, out);
            if (real != null) {
                if ((h = U.hit(p, TINKERER)) != null) {
                    real.set(DataComponents.MAX_DAMAGE, (int) Math.round(real.getMaxDamage() * (1 + h.value() / 100)));
                }
                if ((h = U.hit(p, SIGNATURE)) != null) sign(p, real, h.value());
            }
        }

        if (U.hit(p, RECIPE_MEMORY) != null || U.hit(p, MASS) != null || pocketRefill(p, p.containerMenu)) {
            AbstractContainerMenu m = p.containerMenu;
            if (m instanceof CraftingMenu || m instanceof InventoryMenu) {
                Refill r = REFILL.get(p.getUUID());
                if (r == null || r.grid != grid) {
                    ItemStack[] before = new ItemStack[grid.getContainerSize()];
                    for (int i = 0; i < before.length; i++) before[i] = grid.getItem(i).copy();
                    REFILL.put(p.getUUID(), new Refill(m, grid, before));
                }
            }
        }
    }

    /**
     * Extra output or refunds are only safe on plain multi-ingredient recipes. One-ingredient recipes are usually
     * reversible (ingots to block and back) and would loop into infinite items; special recipes (book or map cloning,
     * shulker dyeing, banner copying) carry contents. Containers never qualify.
     */
    static boolean bonusSafe(Player p, CraftingContainer grid, ItemStack out, int kinds) {
        if (kinds < 2 || out.isDamageableItem() || U.holdsItems(out) || CardItems.dataOf(out) != null
                || out.has(DataComponents.WRITTEN_BOOK_CONTENT)) return false;
        var recipe = p.level().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid.asCraftInput(), p.level());
        return recipe.isPresent() && !(recipe.get().value() instanceof CustomRecipe);
    }

    private static void refundOne(Player p, CraftingContainer grid) {
        List<ItemStack> pick = new ArrayList<>();
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack s = grid.getItem(i);
            if (!s.isEmpty() && !s.hasCraftingRemainingItem() && !s.isDamageableItem() && !U.holdsItems(s)) pick.add(s);
        }
        if (!pick.isEmpty()) U.give(p, pick.get(p.getRandom().nextInt(pick.size())).copyWithCount(1));
    }

    /**
     * The stack the player actually received. A normal click hands over the event stack itself (it is now carried); a
     * shift-click fires the event with a copy after moving the real stack, so find an identical one in the inventory
     * (identical stacks are interchangeable). Null when it went somewhere else (dropped).
     */
    @Nullable
    static ItemStack taken(Player p, ItemStack event) {
        if (p.containerMenu.getCarried() == event) return event;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != event && ItemStack.isSameItemSameComponents(s, event)) return s;
        }
        return null;
    }

    private static void sign(Player p, ItemStack s, double pct) {
        Component line = Component.translatable("habits.utility.signed", p.getName()).setStyle(Style.EMPTY.withItalic(false)
                .withColor(0xB0B0B0));
        s.set(DataComponents.LORE, s.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).withLineAdded(line));
        if (!U.roll(p, pct)) return;
        List<Holder<Enchantment>> pool = new ArrayList<>();
        p.registryAccess().registryOrThrow(Registries.ENCHANTMENT).holders().forEach(h -> {
            if (!h.is(EnchantmentTags.CURSE) && h.is(EnchantmentTags.IN_ENCHANTING_TABLE) && h.value().canEnchant(s)) pool.add(h);
        });
        if (pool.isEmpty()) return;
        Holder<Enchantment> pick = pool.get(p.getRandom().nextInt(pool.size()));
        EnchantmentHelper.updateEnchantments(s, m -> {
            int cur = m.getLevel(pick);
            // never more than one level past the vanilla max
            m.set(pick, Math.min(cur + 1, pick.value().getMaxLevel() + 1));
        });
    }

    // ------------------------------------------------------------------ grid refill (recipe_memory, mass_production)

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.level().isClientSide) return;
        Refill r = REFILL.remove(p.getUUID());
        if (r != null && U.ok(p) && p.containerMenu == r.menu) refill(p, r);
        if (p.tickCount % 10 == 0 && U.ok(p)) clockwork(p);
    }

    static void refill(Player p, Refill r) {
        HabitsApi.PerkHit mem = U.hit(p, RECIPE_MEMORY);
        HabitsApi.PerkHit mass = U.hit(p, MASS);
        int tier = mem == null ? 0 : U.tier(mem);
        List<Container> chests = mass == null ? List.of() : Pull.nearby(p, (int) mass.value());
        boolean pocket = pocketRefill(p, r.menu);
        boolean changed = false;
        for (int i = 0; i < r.before.length; i++) {
            ItemStack want = r.before[i];
            ItemStack now = r.grid.getItem(i);
            if (want.isEmpty() || !now.isEmpty()) continue;
            int need = want.getCount();
            ItemStack got = pocket ? Pull.fromMain(p, s -> ItemStack.isSameItemSameComponents(s, want), need) : ItemStack.EMPTY;
            for (Container c : chests) if (got.isEmpty()) got = Pull.fromContainer(c, s -> ItemStack.isSameItemSameComponents(s, want), need);
            if (got.isEmpty() && tier >= 1) got = Pull.fromBundles(p, s -> ItemStack.isSameItemSameComponents(s, want), need);
            if (got.isEmpty() && tier >= 2) got = Pull.fromShulkers(p, s -> ItemStack.isSameItemSameComponents(s, want), need);
            if (got.isEmpty() && tier >= 3) got = Pull.fromContainer(p.getEnderChestInventory(), s -> ItemStack.isSameItemSameComponents(s, want), need);
            if (!got.isEmpty()) {
                r.grid.setItem(i, got);
                changed = true;
            }
        }
        if (changed) r.menu.slotsChanged(r.grid);
    }

    // ------------------------------------------------------------------ pocket workbench

    private static final Map<UUID, Long> WORKBENCH_CD = U.playerMap();

    @SubscribeEvent
    static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        Player p = e.getEntity();
        if (!p.isShiftKeyDown() || !e.getItemStack().is(Items.CRAFTING_TABLE)) return;
        HabitsApi.PerkHit h = U.hit(p, WORKBENCH);
        if (h == null) return;
        long now = p.level().getGameTime();
        if (now < WORKBENCH_CD.getOrDefault(p.getUUID(), 0L)) return;
        WORKBENCH_CD.put(p.getUUID(), now + (long) (h.value() * 20));
        openWorkbench(p);
        e.setCanceled(true);
    }

    static void openWorkbench(Player p) {
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new PocketMenu(id, inv, ContainerLevelAccess.create(pl.level(), pl.blockPosition())),
                Component.translatable("container.crafting")));
    }

    /** The portable table: a crafting table that never goes out of range. */
    static final class PocketMenu extends CraftingMenu {
        PocketMenu(int id, net.minecraft.world.entity.player.Inventory inv, ContainerLevelAccess access) {
            super(id, inv, access);
        }

        @Override
        public boolean stillValid(Player player) {
            return player.isAlive();
        }
    }

    /** From refill_level the portable table's grid refills from the main inventory after each craft. */
    static boolean pocketRefill(Player p, AbstractContainerMenu m) {
        HabitsApi.PerkHit h;
        return m instanceof PocketMenu && (h = U.hit(p, WORKBENCH)) != null && h.level() >= U.param(h, "refill_level", 10, 10);
    }

    // ------------------------------------------------------------------ clockwork crafters

    private static final Map<UUID, Map<Long, Integer>> CRAFTER_TIMERS = U.playerMap();

    @SubscribeEvent
    static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof Player p) || !U.ok(p)) return;
        var b = e.getPlacedBlock();
        if (b.is(Blocks.CRAFTER) || b.is(Blocks.CAKE)) Stations.placed(p, e.getPos());
    }

    static void clockwork(Player p) {
        HabitsApi.PerkHit h = U.hit(p, CLOCKWORK);
        if (h == null) return;
        Map<Long, Integer> timers = CRAFTER_TIMERS.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
        int every = Math.max(10, (int) Math.round(h.value() * 20));
        for (CrafterBlockEntity c : Stations.placed(p, CrafterBlockEntity.class)) {
            long key = c.getBlockPos().asLong();
            int t = timers.merge(key, 10, Integer::sum);
            if (t < every) continue;
            timers.put(key, t - every); // keep the leftover so a 0.75 s period averages 0.75 s, not 1 s
            var state = c.getBlockState();
            if (state.getBlock() instanceof CrafterBlock && p.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                sl.scheduleTick(c.getBlockPos(), state.getBlock(), 1);
            }
        }
        if (timers.size() > Stations.CAP * 2) timers.clear();
    }

    // ------------------------------------------------------------------ result-slot takes (stonecutter, smithing)

    /** Last stonecutter cut per player: input item, output item, output count per input. */
    private record Cut(Item in, Item out, int count) {}

    private static final Map<UUID, Cut> LAST_CUT = U.playerMap();

    @SubscribeEvent
    static void onStat(StatAwardEvent e) {
        if (e.getStat().getType() != Stats.ITEM_CRAFTED || e.getValue() <= 0 || !(e.getEntity() instanceof ServerPlayer p) || !U.ok(p)) return;
        if (!(e.getStat().getValue() instanceof Item item)) return;
        int amount = com.habits.trigger.Triggers.statDelta(e);
        if (amount <= 0) return;
        AbstractContainerMenu m = p.containerMenu;
        if (m instanceof StonecutterMenu sm) {
            U.fire(p, "stonecutter_take");
            ItemStack in = sm.getSlot(StonecutterMenu.INPUT_SLOT).getItem();
            if (!in.isEmpty()) LAST_CUT.put(p.getUUID(), new Cut(in.getItem(), item, amount));
            HabitsApi.PerkHit h = U.hit(p, MASON);
            if (h != null && U.roll(p, h.value())) U.give(p, new ItemStack(item));
        } else if (m instanceof SmithingMenu sm) {
            ItemStack template = sm.getSlot(SmithingMenu.TEMPLATE_SLOT).getItem();
            U.fire(p, "smithing_take");
            if (template.is(ItemTags.TRIM_TEMPLATES)) U.fire(p, "smithing_trim");
            HabitsApi.PerkHit h = U.hit(p, SMITH);
            if (h != null && !template.isEmpty() && U.roll(p, h.value())) U.give(p, template.copyWithCount(1));
        } else {
            Furnaces.onTake(p, m, item, amount);
        }
    }

    // ------------------------------------------------------------------ stone sense

    @SubscribeEvent
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (!p.isShiftKeyDown() || !p.level().getBlockState(e.getPos()).is(Blocks.STONECUTTER)) return;
        HabitsApi.PerkHit h = U.hit(p, STONE_SENSE);
        Cut cut = LAST_CUT.get(p.getUUID());
        ItemStack held = p.getMainHandItem();
        if (h == null || cut == null || held.isEmpty() || held.getItem() != cut.in || e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!stillCuts(p, cut)) return;
        e.setCanceled(true);
        convert(p, cut, (int) h.value());
    }

    private static boolean stillCuts(Player p, Cut cut) {
        var in = new SingleRecipeInput(new ItemStack(cut.in));
        return p.level().getRecipeManager().getRecipesFor(RecipeType.STONECUTTING, in, p.level()).stream()
                .anyMatch(r -> r.value().getResultItem(p.registryAccess()).is(cut.out)
                        && r.value().getResultItem(p.registryAccess()).getCount() == cut.count);
    }

    /** Convert up to {@code stacks} stacks of the cut input (held stack first) into the cut output. */
    static int convert(Player p, Cut cut, int stacks) {
        var inv = p.getInventory();
        List<Integer> slots = new ArrayList<>();
        slots.add(inv.selected);
        for (int i = 0; i < inv.items.size(); i++) if (i != inv.selected) slots.add(i);
        int done = 0, made = 0;
        for (int slot : slots) {
            if (done >= stacks) break;
            ItemStack s = inv.items.get(slot);
            if (s.isEmpty() || s.getItem() != cut.in || !s.getComponentsPatch().isEmpty()) continue;
            int n = s.getCount();
            inv.items.set(slot, ItemStack.EMPTY);
            int total = n * cut.count;
            while (total > 0) {
                int k = Math.min(total, cut.out.getDefaultMaxStackSize());
                U.give(p, new ItemStack(cut.out, k));
                total -= k;
            }
            made += n;
            done++;
        }
        return made;
    }

    static Cut cut(Item in, Item out, int count) {
        return new Cut(in, out, count);
    }
}
