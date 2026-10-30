package com.habits.perk.utility;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerDestroyItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Inventory, container and pickup perks. */
final class Inventory {
    private Inventory() {}

    static final ResourceLocation RUMMAGER = U.mech("rummager", "chest helper tier (1 deposit matching, 2 also sorts)");
    static final ResourceLocation LOOT_GOBLIN = U.mech("loot_goblin", "% chance per extra loot roll");
    static final ResourceLocation BARREL = U.mech("barrel_roll", "stacks a broken barrel keeps");
    static final ResourceLocation ENDER = U.mech("ender_link", "remote ender chest tier (1 chest item, 2 also pearl, 3 also a key)");
    static final ResourceLocation SHULKER = U.mech("shulker_sense", "shulker tier (1 open in hand, 2 also catch overflow)");
    static final ResourceLocation BUNDLE = U.mech("bundle_up", "times the vanilla bundle capacity");
    static final ResourceLocation MAGPIE = U.mech("magpie", "item pull radius in blocks");
    static final ResourceLocation PACK_RAT = U.mech("pack_rat", "overflow slots");
    static final ResourceLocation BUTTER = U.mech("butterfingers", "throw distance multiplier");
    static final ResourceLocation HOTBAR = U.mech("hotbar_swap", "inventory rows in the swap cycle");
    static final ResourceLocation REFILL = U.mech("auto_refill", "refill tier (1 blocks, 2 tools, 3 from shulkers)");
    static final ResourceLocation HOARDER = U.mech("hoarder", "luck per different item");
    static final ResourceLocation MINIMALIST = U.mech("minimalist", "% speed per empty slot");
    static final ResourceLocation ORB_MAGNET = U.mech("orb_magnet", "XP orb pull radius in blocks");

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Habits.MODID);
    /** Pack rat storage. Saved and copied on death: without keepInventory {@link #onDeathDrops} empties it into the drops
     * first (drops fire before the respawn copy), so only keepInventory carries it to the new player. */
    static final Supplier<AttachmentType<List<ItemStack>>> PACK = ATTACHMENTS.register("utility_pack_rat",
            () -> AttachmentType.<List<ItemStack>>builder(() -> List.of()).serialize(ItemStack.OPTIONAL_CODEC.listOf()).copyOnDeath().build());

    static void init(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
    }

    // ------------------------------------------------------------------ chests: rummager, loot goblin

    private record LootPending(ResourceKey<LootTable> table, BlockPos pos, long time) {}

    static final Map<UUID, LootPending> LOOT = U.playerMap();

    @SubscribeEvent
    static void onClickBlock(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (!U.ok(p)) return;
        if (p.level().getBlockEntity(e.getPos()) instanceof RandomizableContainerBlockEntity r && r.getLootTable() != null) {
            // per-player loot chests (Lootr) keep their table forever, so remember which ones this player already opened
            var looted = Mobs.persisted(p).getList("habits_looted", net.minecraft.nbt.Tag.TAG_STRING);
            var key = net.minecraft.nbt.StringTag.valueOf(p.level().dimension().location() + "|" + e.getPos().asLong());
            if (looted.contains(key)) return;
            looted.add(key);
            if (looted.size() > 2048) looted.remove(0);
            Mobs.persisted(p).put("habits_looted", looted);
            U.fire(p, "loot_chest_first", e.getPos().getCenter());
            LOOT.put(p.getUUID(), new LootPending(r.getLootTable(), e.getPos().immutable(), p.level().getGameTime()));
        }
    }

    @SubscribeEvent
    static void onOpen(PlayerContainerEvent.Open e) {
        Player p = e.getEntity();
        if (!U.ok(p) || !(e.getContainer() instanceof ChestMenu menu)) return;
        LootPending lp = LOOT.remove(p.getUUID());
        HabitsApi.PerkHit h;
        if (lp != null && lp.time == p.level().getGameTime() && lootrConverting(p, menu, lp)) {
            // Lootr cancelled the vanilla fill and swaps this chest for its own next tick, spilling whatever we put in;
            // forget it so the first open of the Lootr chest gets the roll instead
            var looted = Mobs.persisted(p).getList("habits_looted", net.minecraft.nbt.Tag.TAG_STRING);
            looted.remove(net.minecraft.nbt.StringTag.valueOf(p.level().dimension().location() + "|" + lp.pos.asLong()));
            Mobs.persisted(p).put("habits_looted", looted);
        } else if (lp != null && lp.time == p.level().getGameTime() && (h = U.hit(p, LOOT_GOBLIN)) != null) extraLoot(p, menu.getContainer(), lp, h);
        if (!(menu.getContainer() instanceof PackContainer) && (h = U.hit(p, RUMMAGER)) != null) {
            if (p.isShiftKeyDown()) depositMatching(p, menu.getContainer());
            if (U.tier(h) >= 2) sort(menu.getContainer());
        }
    }

    /** The menu shows the vanilla chest itself and its loot table survived opening: something (Lootr) stopped the fill. */
    static boolean lootrConverting(Player p, ChestMenu menu, LootPending lp) {
        if (!(p.level().getBlockEntity(lp.pos) instanceof RandomizableContainerBlockEntity be) || be.getLootTable() == null) return false;
        Container c = menu.getContainer();
        return c == be || c instanceof net.minecraft.world.CompoundContainer cc && cc.contains(be);
    }

    static int extraLoot(Player p, Container c, LootPending lp, HabitsApi.PerkHit h) {
        if (!(p.level() instanceof ServerLevel sl)) return 0;
        LootTable table = sl.getServer().reloadableRegistries().getLootTable(lp.table);
        LootParams params = new LootParams.Builder(sl).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(lp.pos))
                .withParameter(LootContextParams.THIS_ENTITY, p).withLuck(p.getLuck()).create(LootContextParamSets.CHEST);
        int rolls = 0;
        for (int i = 0; i < (int) U.param(h, "rolls", 1, 3); i++) {
            if (!U.roll(p, h.value())) continue;
            rolls++;
            for (ItemStack s : table.getRandomItems(params)) {
                U.insert(c, s, false);
                U.give(p, s); // a full chest spills into the inventory, the ground only past that
            }
        }
        return rolls;
    }

    /** Move main-inventory items (not the hotbar) whose item is already in the chest. */
    static int depositMatching(Player p, Container c) {
        Set<Item> have = new HashSet<>();
        for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) have.add(c.getItem(i).getItem());
        int moved = 0;
        var inv = p.getInventory();
        for (int i = 9; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || !have.contains(s.getItem())) continue;
            int before = s.getCount();
            U.insert(c, s, false);
            moved += before - s.getCount();
        }
        if (moved > 0) p.level().playSound(null, p.blockPosition(), SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 0.6f, 1);
        return moved;
    }

    /** Merge and sort a container by item id. Same items in, same items out. */
    static void sort(Container c) {
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.removeItemNoUpdate(i);
            if (!s.isEmpty()) all.add(s);
        }
        all.sort(Comparator.comparing((ItemStack s) -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).thenComparing(s -> -s.getCount()));
        SimpleContainer tmp = new SimpleContainer(c.getContainerSize());
        for (ItemStack s : all) {
            for (int i = 0; i < tmp.getContainerSize() && !s.isEmpty(); i++) {
                ItemStack t = tmp.getItem(i);
                if (t.isEmpty()) tmp.setItem(i, s.split(s.getCount()));
                else if (ItemStack.isSameItemSameComponents(t, s) && t.getCount() < t.getMaxStackSize()) {
                    int n = Math.min(t.getMaxStackSize() - t.getCount(), s.getCount());
                    t.grow(n);
                    s.shrink(n);
                }
            }
        }
        for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, tmp.getItem(i));
        c.setChanged();
    }

    // ------------------------------------------------------------------ barrels keep their contents

    @SubscribeEvent
    static void onBreak(BlockEvent.BreakEvent e) {
        Player p = e.getPlayer();
        if (p.isCreative() || !(e.getLevel() instanceof ServerLevel level) || !level.getBlockState(e.getPos()).is(Blocks.BARREL)) return;
        HabitsApi.PerkHit h = U.hit(p, BARREL);
        if (h == null || !(level.getBlockEntity(e.getPos()) instanceof BarrelBlockEntity barrel) || barrel.getLootTable() != null) return;
        if (keepBarrel(level, e.getPos(), barrel, (int) h.value())) e.setCanceled(true);
    }

    /** Pack up to {@code stacks} stacks into the barrel item; the rest drop. Refuses (vanilla break) if any stack holds items. */
    static boolean keepBarrel(ServerLevel level, BlockPos pos, BarrelBlockEntity barrel, int stacks) {
        List<ItemStack> kept = new ArrayList<>(), dropped = new ArrayList<>();
        for (int i = 0; i < barrel.getContainerSize(); i++) {
            ItemStack s = barrel.getItem(i);
            if (s.isEmpty()) continue;
            if (U.holdsItems(s)) return false; // no barrels inside shulkers inside barrels
            (kept.size() < stacks ? kept : dropped).add(s.copy());
        }
        for (int i = 0; i < barrel.getContainerSize(); i++) barrel.setItem(i, ItemStack.EMPTY);
        ItemStack item = new ItemStack(Items.BARREL);
        if (!kept.isEmpty()) item.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(kept));
        if (barrel.hasCustomName()) item.set(DataComponents.CUSTOM_NAME, barrel.getCustomName());
        level.removeBlock(pos, false);
        Vec3 at = Vec3.atCenterOf(pos);
        level.addFreshEntity(new ItemEntity(level, at.x, at.y, at.z, item));
        for (ItemStack s : dropped) level.addFreshEntity(new ItemEntity(level, at.x, at.y, at.z, s));
        return true;
    }

    // ------------------------------------------------------------------ right-click items: ender link, shulker, pack rat

    @SubscribeEvent
    static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        Player p = e.getEntity();
        if (!U.ok(p) || e.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack s = e.getItemStack();
        HabitsApi.PerkHit h;
        if (p.isShiftKeyDown() && (h = U.hit(p, ENDER)) != null
                && (s.is(Items.ENDER_CHEST) || s.is(Items.ENDER_PEARL) && U.tier(h) >= 2)) {
            openEnder(p);
            e.setCanceled(true);
        } else if ((h = U.hit(p, SHULKER)) != null && s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock && s.getCount() == 1) {
            openShulker(p, s);
            e.setCanceled(true);
        } else if (p.isShiftKeyDown() && s.is(CoreRegistries.BOOK.get()) && (U.hit(p, PACK_RAT) != null || !pack(p).isEmpty())) {
            openPack(p);
            e.setCanceled(true);
        }
    }

    /** The Ender Link key (UtilityNet): tier 3 opens the ender chest with nothing in hand. */
    static boolean enderKey(Player p) {
        HabitsApi.PerkHit h = U.hit(p, ENDER);
        if (h == null || U.tier(h) < 3) return false;
        openEnder(p);
        return true;
    }

    static void openEnder(Player p) {
        var ec = p.getEnderChestInventory();
        ec.setActiveChest(null);
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> ChestMenu.threeRows(id, inv, ec), Component.translatable("container.enderchest")));
    }

    static void openShulker(Player p, ItemStack held) {
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new HandShulkerMenu(id, inv, held), held.getHoverName()));
    }

    /**
     * A shulker box opened from the main hand. The menu edits a copy and writes it back to the held stack after every
     * click. The held slot is locked (no pick up, swap, throw), and the menu closes the moment the hand stops holding
     * that exact stack, so the box can never be in two places at once.
     */
    static final class HandShulkerMenu extends ShulkerBoxMenu {
        final ItemStack held;
        final SimpleContainer box;
        final int heldSlot;
        final int hotbarIndex;

        HandShulkerMenu(int id, net.minecraft.world.entity.player.Inventory inv, ItemStack held) {
            this(id, inv, held, new SimpleContainer(27));
        }

        private HandShulkerMenu(int id, net.minecraft.world.entity.player.Inventory inv, ItemStack held, SimpleContainer box) {
            super(id, inv, fill(box, held));
            this.held = held;
            this.box = box;
            this.hotbarIndex = inv.selected;
            this.heldSlot = 27 + 27 + inv.selected;
        }

        private static SimpleContainer fill(SimpleContainer box, ItemStack held) {
            NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
            held.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
            for (int i = 0; i < 27; i++) box.setItem(i, items.get(i));
            return box;
        }

        @Override
        public void clicked(int slot, int button, ClickType type, Player player) {
            if (!stillValid(player)) return;
            if (slot == heldSlot || type == ClickType.SWAP && button == hotbarIndex) return;
            super.clicked(slot, button, type, player);
            save();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            if (index == heldSlot) return ItemStack.EMPTY;
            ItemStack r = super.quickMoveStack(player, index);
            save();
            return r;
        }

        @Override
        public boolean stillValid(Player player) {
            return player.isAlive() && player.getMainHandItem() == held && player.getInventory().selected == hotbarIndex;
        }

        @Override
        public void removed(Player player) {
            if (player.getMainHandItem() == held) save();
            super.removed(player);
        }

        void save() {
            List<ItemStack> items = new ArrayList<>(27);
            for (int i = 0; i < 27; i++) items.add(box.getItem(i).copy());
            held.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        }
    }

    // ------------------------------------------------------------------ pack rat storage

    static List<ItemStack> pack(Player p) {
        return p.getData(PACK);
    }

    static int packCap(Player p) {
        return (int) U.v(p, PACK_RAT);
    }

    /** Live container over the attachment: every change writes straight back. */
    static final class PackContainer extends SimpleContainer {
        final Player owner;

        PackContainer(Player owner, int size) {
            super(size);
            this.owner = owner;
            List<ItemStack> stored = pack(owner);
            for (int i = 0; i < Math.min(size, stored.size()); i++) setItem(i, stored.get(i).copy());
            addListener(c -> write());
        }

        void write() {
            List<ItemStack> out = new ArrayList<>(getContainerSize());
            for (int i = 0; i < getContainerSize(); i++) out.add(getItem(i).copy());
            owner.setData(PACK, out);
        }
    }

    static void openPack(Player p) {
        ejectBooks(p);
        p.openMenu(new SimpleMenuProvider((id, inv, pl) -> packMenu(id, inv, p), Component.translatable("habits.utility.pack")));
    }

    static ChestMenu packMenu(int id, net.minecraft.world.entity.player.Inventory inv, Player p) {
        int stored = pack(p).size();
        int rows = Math.max(1, Math.min(6, (Math.max(packCap(p), stored) + 8) / 9));
        PackContainer c = new PackContainer(p, rows * 9);
        MenuType<?>[] types = {MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6};
        ChestMenu m = new ChestMenu(types[rows - 1], id, inv, c, rows);
        // the book is the only way in, so it may never go inside: swap the pack slots for ones that refuse it
        for (int i = 0; i < rows * 9; i++) {
            Slot o = m.slots.get(i);
            Slot n = new Slot(c, o.getContainerSlot(), o.x, o.y) {
                @Override
                public boolean mayPlace(ItemStack st) {
                    return !isBook(st);
                }
            };
            n.index = i;
            m.slots.set(i, n);
        }
        return m;
    }

    static boolean isBook(ItemStack s) {
        return s.is(CoreRegistries.BOOK.get());
    }

    /** Hand back any book already stuck in the pack (from before books were refused): inventory first, else at the feet. */
    static void ejectBooks(Player p) {
        List<ItemStack> list = new ArrayList<>(pack(p));
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++)
            if (isBook(list.get(i))) { out.add(list.get(i).copy()); list.set(i, ItemStack.EMPTY); }
        if (out.isEmpty()) return;
        p.setData(PACK, list);
        for (ItemStack b : out) if (!p.getInventory().add(b)) p.drop(b, false);
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!e.getEntity().level().isClientSide) ejectBooks(e.getEntity());
    }

    /** Put {@code s} into the first {@code cap} pack slots (merging first). Shrinks {@code s}. */
    static void packInsert(Player p, ItemStack s, int cap) {
        if (isBook(s)) return;
        List<ItemStack> list = new ArrayList<>(pack(p));
        while (list.size() < cap) list.add(ItemStack.EMPTY);
        SimpleContainer c = new SimpleContainer(cap);
        for (int i = 0; i < cap; i++) c.setItem(i, list.get(i));
        int before = s.getCount();
        U.insert(c, s, false);
        if (s.getCount() == before) return;
        for (int i = 0; i < cap; i++) list.set(i, c.getItem(i));
        p.setData(PACK, list);
    }

    @SubscribeEvent
    static void onDeathDrops(LivingDropsEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide) return;
        if (p.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
        List<ItemStack> stored = pack(p);
        if (stored.isEmpty()) return;
        p.setData(PACK, List.of());
        for (ItemStack s : stored) if (!s.isEmpty() && !s.is(com.habits.perk.survival.Life.LOST_ON_DEATH)) e.getDrops().add(new ItemEntity(p.level(), p.getX(), p.getY(), p.getZ(), s.copy()));
    }

    // ------------------------------------------------------------------ pickups: bundles, shulker overflow, pack rat

    private static final Map<UUID, Long> FULL_FIRED = U.playerMap();

    @SubscribeEvent
    static void onPickup(ItemEntityPickupEvent.Pre e) {
        Player p = e.getPlayer();
        ItemEntity ie = e.getItemEntity();
        if (!U.ok(p) || ie.hasPickUpDelay() || ie.getTarget() != null && !ie.getTarget().equals(p.getUUID())) return;
        ItemStack s = ie.getItem();
        if (s.isEmpty() || com.habits.core.CardItems.dataOf(s) != null) return;
        int before = s.getCount();
        HabitsApi.PerkHit h;
        if (!fits(p, s)) {
            long now = p.level().getGameTime();
            if (now >= FULL_FIRED.getOrDefault(p.getUUID(), 0L)) {
                FULL_FIRED.put(p.getUUID(), now + 100);
                U.fire(p, "pickup_full", ie.position());
            }
            if ((h = U.hit(p, SHULKER)) != null && U.tier(h) >= 2) intoShulkers(p, s);
            if (!s.isEmpty() && (h = U.hit(p, PACK_RAT)) != null) packInsert(p, s, (int) h.value());
        }
        if (s.getCount() != before) {
            ie.setItem(s);
            p.take(ie, before - s.getCount());
            if (s.isEmpty()) {
                ie.discard();
                e.setCanPickup(TriState.FALSE);
            }
        }
    }

    static boolean fits(Player p, ItemStack s) {
        var inv = p.getInventory();
        return inv.getFreeSlot() >= 0 || inv.getSlotWithRemainingSpace(s) >= 0;
    }

    static void intoShulkers(Player p, ItemStack s) {
        if (!s.getItem().canFitInsideContainerItems()) return;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && !s.isEmpty(); i++) {
            ItemStack b = inv.getItem(i);
            if (b.getCount() != 1 || !(b.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock)) continue;
            NonNullList<ItemStack> items = NonNullList.withSize(27, ItemStack.EMPTY);
            b.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
            SimpleContainer c = new SimpleContainer(27);
            for (int k = 0; k < 27; k++) c.setItem(k, items.get(k));
            int before = s.getCount();
            U.insert(c, s, false);
            if (s.getCount() == before) continue;
            List<ItemStack> out = new ArrayList<>(27);
            for (int k = 0; k < 27; k++) out.add(c.getItem(k));
            b.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(out));
        }
    }

    // ------------------------------------------------------------------ tosses: butterfingers

    private record Toss(UUID entity, long time) {}

    private static final Map<UUID, List<Toss>> TOSSED = U.playerMap();

    @SubscribeEvent
    static void onToss(ItemTossEvent e) {
        Player p = e.getPlayer();
        ItemEntity ie = e.getEntity();
        Odd.tossed(p, ie);
        HabitsApi.PerkHit h = U.hit(p, BUTTER);
        if (h == null) return;
        ie.setDeltaMovement(ie.getDeltaMovement().multiply(h.value(), 1, h.value()));
        if (h.level() >= U.param(h, "keep_from", 5, 5)) ie.setUnlimitedLifetime();
        if (h.level() >= U.param(h, "return_from", 10, 10)) {
            List<Toss> list = TOSSED.computeIfAbsent(p.getUUID(), k -> new ArrayList<>());
            list.add(new Toss(ie.getUUID(), p.level().getGameTime()));
            if (list.size() > 64) list.remove(0);
        }
    }

    static void returnTossed(Player p, long after) {
        List<Toss> list = TOSSED.get(p.getUUID());
        if (list == null || !(p.level() instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        list.removeIf(t -> {
            if (now - t.time < after) return false;
            if (sl.getEntity(t.entity) instanceof ItemEntity ie && ie.isAlive()) {
                ItemStack s = ie.getItem();
                p.getInventory().add(s);
                if (s.isEmpty()) ie.discard();
                else ie.setItem(s);
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ hotbar swap

    @SubscribeEvent
    static void onSwap(LivingSwapItemsEvent.Hands e) {
        if (!(e.getEntity() instanceof Player p) || !U.ok(p)) return;
        U.fire(p, "swap_hands");
        HabitsApi.PerkHit h = U.hit(p, HOTBAR);
        if (h == null || !p.isShiftKeyDown()) return;
        e.setCanceled(true);
        rotateRows(p, (int) h.value());
    }

    /** Hotbar takes row 1, row 1 takes row 2 ... the last row in the cycle takes the old hotbar. Pure reference moves. */
    static void rotateRows(Player p, int rows) {
        var items = p.getInventory().items;
        rows = Math.max(1, Math.min(3, rows));
        for (int col = 0; col < 9; col++) {
            ItemStack first = items.get(col);
            for (int r = 0; r < rows; r++) items.set(col + 9 * r, items.get(col + 9 * (r + 1)));
            items.set(col + 9 * rows, first);
        }
        p.inventoryMenu.broadcastChanges();
    }

    // ------------------------------------------------------------------ auto refill

    private record Pending(int slot, Item item, boolean tool) {}

    private static final Map<UUID, Pending> REFILLS = U.playerMap();

    @SubscribeEvent
    static void onToolBreak(PlayerDestroyItemEvent e) {
        Player p = e.getEntity();
        if (e.getHand() == InteractionHand.MAIN_HAND && U.ok(p)) {
            REFILLS.put(p.getUUID(), new Pending(p.getInventory().selected, e.getOriginal().getItem(), true));
        }
    }

    @SubscribeEvent
    static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.getEntity() instanceof Player p && U.ok(p) && p.getMainHandItem().getCount() <= 1 && p.getMainHandItem().getItem() instanceof BlockItem) {
            REFILLS.put(p.getUUID(), new Pending(p.getInventory().selected, p.getMainHandItem().getItem(), false));
        }
    }

    static boolean refill(Player p) {
        Pending pd = REFILLS.remove(p.getUUID());
        if (pd == null) return false;
        var inv = p.getInventory();
        if (!inv.items.get(pd.slot).isEmpty()) return false;
        U.fire(p, "stack_used_up");
        HabitsApi.PerkHit h = U.hit(p, REFILL);
        if (h == null || pd.tool && U.tier(h) < 2) return false;
        for (int i = 0; i < inv.items.size(); i++) {
            if (i == pd.slot) continue;
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && s.getItem() == pd.item) {
                inv.items.set(pd.slot, s);
                inv.items.set(i, ItemStack.EMPTY);
                return true;
            }
        }
        if (U.tier(h) >= 3) {
            ItemStack got = Pull.fromShulkers(p, s -> s.getItem() == pd.item, pd.item.getDefaultMaxStackSize());
            if (!got.isEmpty()) {
                inv.items.set(pd.slot, got);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ ticks

    private static final ResourceLocation HOARD_LUCK = Habits.id("utility_hoarder");
    private static final ResourceLocation MIN_SPEED = Habits.id("utility_minimalist_speed");
    private static final ResourceLocation MIN_ATTACK = Habits.id("utility_minimalist_attack");
    private static final Map<UUID, int[]> HOARD_TIME = U.playerMap(); // {hoarding seconds, minimal seconds}

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.level().isClientSide) return;
        if (REFILLS.containsKey(p.getUUID())) refill(p);
        if (!U.ok(p)) return;
        if (p.tickCount % 5 == 0) {
            double r = U.v(p, MAGPIE);
            if (r > 0) magnet(p, r);
            r = U.v(p, ORB_MAGNET);
            if (r > 0) orbs(p, r);
        }
        if (p.tickCount % 20 == 0) {
            stats(p, 1);
            HabitsApi.PerkHit h = U.hit(p, BUTTER);
            if (h != null && h.level() >= U.param(h, "return_from", 10, 10)) returnTossed(p, (long) (U.param(h, "return_after", 60, 60) * 20));
        }
    }

    /** Hoarder and minimalist modifiers (removed when the perk is off), plus their milestone triggers. */
    static void stats(Player p, int seconds) {
        var inv = p.getInventory();
        Set<Item> kinds = new HashSet<>();
        int emptyMain = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty()) kinds.add(s.getItem());
            else if (i >= 9) emptyMain++;
        }
        int[] t = HOARD_TIME.computeIfAbsent(p.getUUID(), k -> new int[2]);
        t[0] = kinds.size() >= 27 ? t[0] + seconds : 0;
        t[1] = emptyMain == 27 ? t[1] + seconds : 0;
        if (t[0] >= 600) { t[0] = 0; U.fire(p, "hoarder_10min"); }
        if (t[1] >= 600) { t[1] = 0; U.fire(p, "minimalist_10min"); }
        double luck = U.v(p, HOARDER) * Math.min(27, kinds.size());
        U.attr(p, Attributes.LUCK, HOARD_LUCK, luck, AttributeModifier.Operation.ADD_VALUE);
        double fast = U.v(p, MINIMALIST) * emptyMain / 100;
        U.attr(p, Attributes.MOVEMENT_SPEED, MIN_SPEED, fast, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        U.attr(p, Attributes.ATTACK_SPEED, MIN_ATTACK, fast, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
    }

    /** Pull up to 16 ready items within {@code r} onto the player, every 5 ticks. */
    static int magnet(Player p, double r) {
        int n = 0;
        for (ItemEntity ie : p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(r))) {
            if (n >= 16) break;
            if (!ie.isAlive() || ie.hasPickUpDelay() || ie.getTarget() != null && !ie.getTarget().equals(p.getUUID())) continue;
            ie.setPos(p.getX(), p.getY(), p.getZ());
            n++;
        }
        return n;
    }

    /** Fly up to 32 XP orbs beyond vanilla's 8 blocks toward the player. */
    static int orbs(Player p, double r) {
        int n = 0;
        for (ExperienceOrb o : p.level().getEntitiesOfClass(ExperienceOrb.class, p.getBoundingBox().inflate(r))) {
            if (n++ >= 32) break;
            Vec3 d = p.position().add(0, p.getEyeHeight() / 2, 0).subtract(o.position());
            if (d.lengthSqr() < 49) continue;
            o.setDeltaMovement(d.normalize().scale(0.8));
        }
        return n;
    }
}
