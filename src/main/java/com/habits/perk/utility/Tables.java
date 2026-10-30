package com.habits.perk.utility;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.GrindstoneEvent;
import net.neoforged.neoforge.event.enchanting.EnchantmentLevelSetEvent;
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Anvil, grindstone and enchanting table perks. */
final class Tables {
    private Tables() {}

    static final ResourceLocation PENNY = U.mech("penny_pincher", "% off anvil level costs");
    static final ResourceLocation STEEL = U.mech("steel_nerves", "% chance the anvil takes no damage");
    static final ResourceLocation UNBINDER = U.mech("unbinder", "% more grindstone XP");
    static final ResourceLocation POLISH = U.mech("polish", "% chance the grindstone strips curses");
    static final ResourceLocation OVERCHARGE = U.mech("overcharge", "% chance an enchant goes one past max");
    static final ResourceLocation OVERFORGE = U.mech("overforge", "% chance an anvil or table enchant goes one past max");
    static final ResourceLocation BOOKWORM = U.mech("bookworm", "seconds between free rerolls");
    static final ResourceLocation ARCANE = U.mech("arcane_discount", "% chance to keep the lapis");
    static final ResourceLocation LIBRARIAN = U.mech("librarians_nose", "extra bookshelf reach in blocks");

    static void init() {}

    // ------------------------------------------------------------------ anvil

    @SubscribeEvent
    static void onAnvilTake(AnvilRepairEvent e) {
        Player p = e.getEntity();
        HabitsApi.PerkHit h = U.hit(p, STEEL);
        if (h == null) return;
        if (U.roll(p, h.value())) e.setBreakChance(0);
        ItemStack left = e.getLeft(), out = e.getOutput();
        if (!out.isDamageableItem() || out.getDamageValue() >= left.getDamageValue()) return;
        ItemStack real = Crafting.taken(p, out);
        if (real == null) return;
        // a bonus on what the anvil repaired, so topping off a nearly full tool cannot restore half its durability
        int extra = (int) Math.round((left.getDamageValue() - out.getDamageValue()) * U.param(h, "repair", 5, 50) / 100);
        real.setDamageValue(Math.max(0, real.getDamageValue() - extra));
    }

    /** overforge: the anvil result gets the same one-past-max roll as the table. */
    @SubscribeEvent
    static void onAnvilOverforge(AnvilRepairEvent e) {
        Player p = e.getEntity();
        HabitsApi.PerkHit h = U.hit(p, OVERFORGE);
        if (h == null || !U.roll(p, h.value())) return;
        ItemStack real = Crafting.taken(p, e.getOutput());
        if (real != null) overcharge(p, real);
    }

    // ------------------------------------------------------------------ grindstone

    @SubscribeEvent
    static void onGrindTake(GrindstoneEvent.OnTakeItem e) {
        Player p = e.getPlayer();
        if (!U.ok(p)) return;
        U.fire(p, "grindstone_take");
        HabitsApi.PerkHit h = U.hit(p, UNBINDER);
        if (h == null) return;
        e.setXp((int) Math.round(e.getXp() * (1 + h.value() / 100)));
        if (!U.roll(p, U.param(h, "book", 2, 30))) return;
        List<Object2> pool = new ArrayList<>();
        for (ItemStack s : new ItemStack[]{e.getTopItem(), e.getBottomItem()}) {
            if (s.isEmpty() || s.is(Items.ENCHANTED_BOOK)) continue;
            for (var en : s.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).entrySet()) {
                if (!en.getKey().is(EnchantmentTags.CURSE)) pool.add(new Object2(en.getKey(), en.getIntValue()));
            }
        }
        if (pool.isEmpty()) return;
        Object2 pick = pool.get(p.getRandom().nextInt(pool.size()));
        U.give(p, net.minecraft.world.item.EnchantedBookItem.createForEnchantment(new EnchantmentInstance(pick.ench, pick.level)));
    }

    private record Object2(Holder<Enchantment> ench, int level) {}

    @SubscribeEvent
    static void onGrindPlace(GrindstoneEvent.OnPlaceItem e) {
        ItemStack top = e.getTopItem(), bottom = e.getBottomItem();
        if (top.isEmpty() == bottom.isEmpty()) return; // one item only
        ItemStack in = top.isEmpty() ? bottom : top;
        if (in.is(Items.ENCHANTED_BOOK) || !hasCurse(in)) return;
        Player p = grindstoneUser(top, bottom);
        HabitsApi.PerkHit h = p == null ? null : U.hit(p, POLISH);
        if (h == null) return;
        // stable roll per player and item, so taking the item out and back in does not reroll
        long seed = p.getUUID().getMostSignificantBits() ^ Objects.hash(in.getItem(), in.get(DataComponents.ENCHANTMENTS), in.getDamageValue());
        if (RandomSource.create(seed).nextDouble() * 100 >= h.value()) return;
        ItemStack out = in.copyWithCount(1);
        out.set(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        out.remove(DataComponents.REPAIR_COST);
        if (h.level() >= U.param(h, "repair_from", 10, 10)) out.setDamageValue(0);
        e.setOutput(out);
    }

    static boolean hasCurse(ItemStack s) {
        for (var en : s.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).keySet()) if (en.is(EnchantmentTags.CURSE)) return true;
        return false;
    }

    @Nullable
    private static Player grindstoneUser(ItemStack top, ItemStack bottom) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof GrindstoneMenu g && (g.getSlot(0).getItem() == top || g.getSlot(1).getItem() == bottom)) return p;
        }
        for (ServerPlayer p : GRIND_TEST) if (p.containerMenu instanceof GrindstoneMenu g && (g.getSlot(0).getItem() == top || g.getSlot(1).getItem() == bottom)) return p;
        return null;
    }

    /** Test players are not in the player list. */
    static final List<ServerPlayer> GRIND_TEST = new ArrayList<>();

    // ------------------------------------------------------------------ enchanting table

    @SubscribeEvent
    static void onEnchant(PlayerEnchantItemEvent e) {
        Player p = e.getEntity();
        if (!U.ok(p)) return;
        HabitsApi.PerkHit h = U.hit(p, OVERCHARGE);
        if (h != null && U.roll(p, h.value())) overcharge(p, e.getEnchantedItem());
        // overforge on top: overcharge only lifts enchants sitting at max, so the two never stack past +1
        h = U.hit(p, OVERFORGE);
        if (h != null && U.roll(p, h.value())) overcharge(p, e.getEnchantedItem());
        h = U.hit(p, ARCANE);
        if (h != null && p.containerMenu instanceof EnchantmentMenu menu) {
            ItemStack lapis = menu.getSlot(1).getItem();
            // the row's lapis (row + 1) is consumed right after this event: pre-pay what we save
            int rowCost = lapisFor(menu, p);
            int saved = 0;
            for (int i = 0; i < rowCost; i++) if (U.roll(p, h.value())) saved++;
            if (saved > 0 && !lapis.isEmpty()) lapis.grow(saved);
            int refund = Math.min(rowCost - 1, (int) U.param(h, "levels", 0, 2));
            if (refund > 0) p.giveExperienceLevels(refund);
        }
    }

    /** Levels (= lapis) the click just paid: vanilla takes row + 1; recover the row from the level drop. */
    private static int lapisFor(EnchantmentMenu menu, Player p) {
        Integer before = LEVELS_BEFORE.get(p.getUUID());
        if (before == null) return 1;
        return Math.max(1, Math.min(3, before - p.experienceLevel));
    }

    private static final Map<UUID, Integer> LEVELS_BEFORE = U.playerMap();

    @SubscribeEvent
    static void onTickLevels(PlayerTickEvent.Pre e) {
        if (e.getEntity().containerMenu instanceof EnchantmentMenu && !e.getEntity().level().isClientSide) {
            LEVELS_BEFORE.put(e.getEntity().getUUID(), e.getEntity().experienceLevel);
        }
    }

    /** bookworm: the table's data slot reaches a remote client cut to a short, so send the full seed the hints are rolled from. */
    private static final Map<UUID, Integer> SEED_SENT = U.playerMap();

    @SubscribeEvent
    static void onTickSeed(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || !(sp.containerMenu instanceof EnchantmentMenu m) || U.hit(sp, BOOKWORM) == null) return;
        int seed = m.getEnchantmentSeed();
        Integer sent = SEED_SENT.put(sp.getUUID(), seed);
        if (sent == null || sent != seed) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new UtilityNet.TableSeed(seed));
    }

    static void overcharge(Player p, ItemStack s) {
        List<Holder<Enchantment>> atMax = new ArrayList<>();
        EnchantmentHelper.getEnchantmentsForCrafting(s).entrySet().forEach(en -> {
            if (en.getIntValue() == en.getKey().value().getMaxLevel() && en.getKey().value().getMaxLevel() > 1) atMax.add(en.getKey());
        });
        if (atMax.isEmpty()) return;
        Holder<Enchantment> pick = atMax.get(p.getRandom().nextInt(atMax.size()));
        EnchantmentHelper.updateEnchantments(s, m -> m.set(pick, pick.value().getMaxLevel() + 1));
    }

    private static final Map<UUID, Long> REROLL_CD = U.playerMap();

    @SubscribeEvent
    static void onClickTable(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (!p.isShiftKeyDown() || !p.getMainHandItem().isEmpty() || !p.level().getBlockState(e.getPos()).is(Blocks.ENCHANTING_TABLE)) return;
        HabitsApi.PerkHit h = U.hit(p, BOOKWORM);
        if (h == null) return;
        long now = p.level().getGameTime();
        if (now < REROLL_CD.getOrDefault(p.getUUID(), 0L)) {
            p.displayClientMessage(net.minecraft.network.chat.Component.translatable("habits.utility.cooldown"), true);
            return;
        }
        REROLL_CD.put(p.getUUID(), now + (long) (h.value() * 20));
        p.onEnchantmentPerformed(ItemStack.EMPTY, 0); // new seed, no levels taken
        p.displayClientMessage(net.minecraft.network.chat.Component.translatable("habits.utility.bookworm"), true);
        p.level().playSound(null, e.getPos(), net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, net.minecraft.sounds.SoundSource.BLOCKS, 1, 1);
        e.setCanceled(true);
    }

    @SubscribeEvent
    static void onLevelSet(EnchantmentLevelSetEvent e) {
        Level level = e.getLevel();
        if (level.isClientSide) return;
        Player p = level.getNearestPlayer(e.getPos().getX() + 0.5, e.getPos().getY() + 0.5, e.getPos().getZ() + 0.5, 8,
                pl -> ((Player) pl).containerMenu instanceof EnchantmentMenu);
        HabitsApi.PerkHit h = p == null ? null : U.hit(p, LIBRARIAN);
        if (h == null || e.getOriginalLevel() <= 0) return;
        int extra = extraPower(level, e.getPos(), (int) h.value(), h.level() >= U.param(h, "double_from", 10, 10));
        int power = Math.min(15, e.getPower() + extra);
        if (power <= e.getPower()) return;
        RandomSource r = RandomSource.create(e.getPos().asLong() * 31 + e.getEnchantRow() * 7L + e.getOriginalLevel());
        int lvl = EnchantmentHelper.getEnchantmentCost(r, e.getEnchantRow(), power, e.getItem());
        if (lvl > e.getEnchantLevel()) e.setEnchantLevel(lvl);
    }

    /** Bookshelf power from shelves 3..(2 + reach) blocks out, outside the vanilla ring. Only on placing an item. */
    static int extraPower(Level level, BlockPos table, int reach, boolean doubled) {
        int r = 2 + reach;
        float power = 0;
        for (BlockPos b : BlockPos.betweenClosed(table.offset(-r, 0, -r), table.offset(r, 1, r))) {
            int dx = Math.abs(b.getX() - table.getX()), dz = Math.abs(b.getZ() - table.getZ());
            if (Math.max(dx, dz) <= 2 || !level.isLoaded(b)) continue;
            power += level.getBlockState(b).getEnchantPowerBonus(level, b);
        }
        // shelves in the vanilla ring count double at max level
        if (doubled) for (BlockPos off : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
            if (EnchantingTableBlock.isValidBookShelf(level, table, off)) power += level.getBlockState(table.offset(off)).getEnchantPowerBonus(level, table.offset(off));
        }
        return (int) power;
    }
}
