package com.habits.perk.mining;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** BlockDropsEvent mechanics, in one fixed chain: re-rolls, smelting, self-drops, extras, doubling, then telekinesis
 * last so it carries whatever the others made. */
public final class DropPerks {
    private DropPerks() {}

    static final String SPAWNER_MOB = "habits_spawner_mob";

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDrops(BlockDropsEvent e) {
        if (!(e.getBreaker() instanceof ServerPlayer p) || !MiningMechanics.allowed(p) || p.isCreative()) return;
        ServerLevel level = e.getLevel();
        BlockPos pos = e.getPos();
        BlockState s = e.getState();
        RandomSource r = p.getRandom();
        ItemStack tool = e.getTool();
        List<ItemStack> drops = new ArrayList<>();
        for (ItemEntity ie : e.getDrops()) drops.add(ie.getItem());
        boolean changed = false;
        HabitsApi.PerkHit h;
        boolean ore = s.is(Tags.Blocks.ORES);
        boolean silk = BreakPerks.level(p, tool, Enchantments.SILK_TOUCH) > 0;

        // re-rolls
        if (p.isShiftKeyDown() && !silk && !s.is(Blocks.SPAWNER) && !s.is(Blocks.BUDDING_AMETHYST)
                && (h = MiningMechanics.hit(p, "feather_pick")) != null && r.nextDouble() * 100 < h.value()) {
            ItemStack t = tool.isEmpty() ? (h.level() >= 10 && (s.is(BlockTags.ICE) || s.is(Tags.Blocks.GLASS_BLOCKS) || s.is(Tags.Blocks.GLASS_PANES))
                    ? new ItemStack(Items.WOODEN_PICKAXE) : ItemStack.EMPTY) : tool.copy();
            if (!t.isEmpty()) {
                t.enchant(holder(p, Enchantments.SILK_TOUCH), 1);
                drops = new ArrayList<>(Block.getDrops(s, level, pos, e.getBlockEntity(), p, t));
                silk = true;
                changed = true;
            }
        } else if (ore && !silk && (h = MiningMechanics.hit(p, "lucky_strike")) != null) {
            int extra = MiningMechanics.roll(h.value(), r);
            if (extra > 0) {
                ItemStack t = tool.isEmpty() ? new ItemStack(Items.WOODEN_PICKAXE) : tool.copy();
                t.enchant(holder(p, Enchantments.FORTUNE), BreakPerks.level(p, tool, Enchantments.FORTUNE) + extra);
                drops = new ArrayList<>(Block.getDrops(s, level, pos, e.getBlockEntity(), p, t));
                changed = true;
            }
        }

        // philosopher's pick: the next ore up (before hot pick, so a transmuted raw iron or gold can still smelt)
        if (ore && !silk && (h = MiningMechanics.hit(p, "philosophers_pick")) != null) {
            List<ItemStack> up = PhilosophersPick.drops(p, h, level, pos, s, tool, drops);
            if (up != null) { drops = up; changed = true; }
        }

        // hot pick: raw ore drops come out smelted, with the furnace xp
        if (ore && !silk && (h = MiningMechanics.hit(p, "hot_pick")) != null) {
            double bonus = MiningMechanics.lerp(h, "bonus", 0, 30);
            for (int i = 0; i < drops.size(); i++) {
                ItemStack d = drops.get(i);
                if (!(d.is(Items.RAW_IRON) || d.is(Items.RAW_GOLD) || d.is(Items.RAW_COPPER)) || r.nextDouble() * 100 >= h.value()) continue;
                var recipe = level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(d), level);
                if (recipe.isEmpty()) continue;
                ItemStack out = recipe.get().value().getResultItem(level.registryAccess()).copyWithCount(d.getCount());
                if (r.nextDouble() * 100 < bonus) out.grow(1);
                e.setDroppedExperience(e.getDroppedExperience() + MiningMechanics.roll(recipe.get().value().getExperience() * d.getCount(), r));
                drops.set(i, out);
                changed = true;
            }
        }

        // blocks that drop themselves
        if ((s.is(Tags.Blocks.GLASS_BLOCKS) || s.is(Tags.Blocks.GLASS_PANES) || s.is(Blocks.SEA_LANTERN) || s.is(Blocks.BOOKSHELF)
                || s.is(Blocks.CHISELED_BOOKSHELF)) && !silk && (h = MiningMechanics.hit(p, "gentle_hands")) != null && r.nextDouble() * 100 < h.value()) {
            drops = new ArrayList<>(List.of(new ItemStack(s.getBlock())));
            changed = true;
        }
        if (s.is(BlockTags.ICE) && !silk && drops.isEmpty() && (h = MiningMechanics.hit(p, "ice_carver")) != null && r.nextDouble() * 100 < h.value()) {
            drops.add(new ItemStack(s.getBlock()));
            changed = true;
        }
        if (s.is(Blocks.SPAWNER) && (h = MiningMechanics.hit(p, "spawner_breaker")) != null && r.nextDouble() * 100 < h.value()
                && e.getBlockEntity() instanceof SpawnerBlockEntity sp) {
            drops.add(spawnerItem(sp, level));
            changed = true;
        }
        if ((h = MiningMechanics.hit(p, "resonance")) != null) {
            if (s.is(Blocks.BUDDING_AMETHYST) && r.nextDouble() * 100 < h.value()) { drops.add(new ItemStack(Items.BUDDING_AMETHYST)); changed = true; }
            if (s.is(Blocks.AMETHYST_CLUSTER) && !silk) {
                drops.add(new ItemStack(Items.AMETHYST_SHARD, (int) Math.round(MiningMechanics.lerp(h, "shards", 1, 4))));
                changed = true;
            }
        }

        // extras
        if ((s.is(Blocks.GRAVEL) || s.is(BlockTags.SAND) || s.is(Blocks.CLAY)) && (h = MiningMechanics.hit(p, "sifter")) != null
                && r.nextDouble() * 100 < h.value()) {
            drops.add(sift(h, r));
            changed = true;
        }
        if ((s.is(BlockTags.TERRACOTTA) || s.is(Tags.Blocks.GLAZED_TERRACOTTAS) || s.is(Blocks.TERRACOTTA))
                && (h = MiningMechanics.hit(p, "mesa_gold")) != null && r.nextDouble() * 100 < h.value()) {
            drops.add(new ItemStack(Items.GOLD_NUGGET, 1 + r.nextInt((int) Math.round(MiningMechanics.lerp(h, "count", 1, 3)))));
            changed = true;
        }
        if ((s.is(Blocks.POLISHED_BLACKSTONE_BRICKS) || s.is(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS) || s.is(Blocks.CHISELED_POLISHED_BLACKSTONE))
                && (h = MiningMechanics.hit(p, "bastion_breaker")) != null && r.nextDouble() * 100 < h.value()) {
            drops.add(new ItemStack(Items.GOLD_NUGGET, 1 + r.nextInt((int) Math.round(MiningMechanics.lerp(h, "count", 1, 3)))));
            changed = true;
        }
        if ((s.is(Blocks.COBBLESTONE) || s.is(Blocks.STONE)) && Generators.isGenerated(level, pos)
                && (h = MiningMechanics.hit(p, "stone_alchemy")) != null && r.nextDouble() * 100 < h.value() && alchemyBudget(p, h)) {
            drops = new ArrayList<>(List.of(alchemyOre(h, r)));
            changed = true;
        }

        // goldsmith: golden tools get fortune-like luck
        if (tool.getItem() instanceof TieredItem t && t.getTier() == Tiers.GOLD && (h = MiningMechanics.hit(p, "goldsmith")) != null
                && r.nextDouble() * 100 < MiningMechanics.lerp(h, "bonus_drop", 5, 40)) {
            List<ItemStack> more = new ArrayList<>();
            for (ItemStack d : drops) if (!d.is(s.getBlock().asItem())) more.add(d.copy());
            drops.addAll(more);
            changed |= !more.isEmpty();
        }

        // lapis scholar: more experience from ores
        if (ore && (h = MiningMechanics.hit(p, "lapis_scholar")) != null && e.getDroppedExperience() > 0)
            e.setDroppedExperience(MiningMechanics.roll(e.getDroppedExperience() * h.value(), r));

        // telekinesis: last, carries everything into the inventory
        if ((h = MiningMechanics.hit(p, "telekinesis")) != null && r.nextDouble() * 100 < h.value()) {
            List<ItemStack> left = new ArrayList<>();
            for (ItemStack d : drops) {
                ItemStack c = d.copy();
                p.getInventory().add(c);
                if (!c.isEmpty()) left.add(c);
            }
            drops = left;
            changed = true;
            Compactor.pack(p);
        }

        if (changed) set(e, drops);
    }

    static net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> holder(ServerPlayer p,
            net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
        return p.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getHolderOrThrow(key);
    }

    private static void set(BlockDropsEvent e, List<ItemStack> drops) {
        e.getDrops().clear();
        BlockPos pos = e.getPos();
        for (ItemStack d : drops) {
            if (d.isEmpty()) continue;
            ItemEntity ie = new ItemEntity(e.getLevel(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, d);
            ie.setDefaultPickUpDelay();
            e.getDrops().add(ie);
        }
    }

    /** A spawner item that remembers its mob. Vanilla only lets ops place block entity data, so the mob id rides in
     * custom data and {@link BuildPerks} restores it on place. */
    static ItemStack spawnerItem(SpawnerBlockEntity sp, ServerLevel level) {
        ItemStack st = new ItemStack(Items.SPAWNER);
        CompoundTag tag = sp.getSpawner().save(new CompoundTag());
        String mob = tag.getCompound("SpawnData").getCompound("entity").getString("id");
        if (!mob.isEmpty()) {
            CompoundTag c = new CompoundTag();
            c.putString(SPAWNER_MOB, mob);
            st.set(DataComponents.CUSTOM_DATA, CustomData.of(c));
        }
        return st;
    }

    private static ItemStack sift(HabitsApi.PerkHit h, RandomSource r) {
        int lvl = h.level();
        if (lvl >= (int) MiningMechanics.lerp(h, "diamond_level", 10, 10) && r.nextDouble() * 100 < MiningMechanics.lerp(h, "diamond_chance", 0.1, 0.1))
            return new ItemStack(Items.DIAMOND);
        List<Item> pool = new ArrayList<>(List.of(Items.FLINT, Items.CLAY_BALL));
        if (lvl >= (int) MiningMechanics.lerp(h, "nugget_level", 4, 4)) Collections.addAll(pool, Items.IRON_NUGGET, Items.GOLD_NUGGET);
        return new ItemStack(pool.get(r.nextInt(pool.size())));
    }

    private static boolean alchemyBudget(ServerPlayer p, HabitsApi.PerkHit h) {
        var tag = p.getPersistentData();
        long minute = p.level().getGameTime() / 1200;
        if (tag.getLong("habits_alchemy_min") != minute) { tag.putLong("habits_alchemy_min", minute); tag.putInt("habits_alchemy_n", 0); }
        int n = tag.getInt("habits_alchemy_n");
        if (n >= (int) MiningMechanics.lerp(h, "cap_per_min", 20, 20)) return false;
        tag.putInt("habits_alchemy_n", n + 1);
        return true;
    }

    private static ItemStack alchemyOre(HabitsApi.PerkHit h, RandomSource r) {
        int lvl = h.level();
        if (lvl >= MiningMechanics.lerp(h, "diamond_level", 10, 10) && r.nextDouble() * 100 < MiningMechanics.lerp(h, "diamond_chance", 2, 2)) return new ItemStack(Items.DIAMOND);
        List<Item> pool = new ArrayList<>(List.of(Items.COAL, Items.RAW_COPPER));
        if (lvl >= MiningMechanics.lerp(h, "iron_level", 4, 4)) Collections.addAll(pool, Items.RAW_IRON, Items.RAW_GOLD);
        if (lvl >= MiningMechanics.lerp(h, "redstone_level", 7, 7)) Collections.addAll(pool, Items.REDSTONE, Items.LAPIS_LAZULI);
        return new ItemStack(pool.get(r.nextInt(pool.size())));
    }

    // ---------------------------------------------------------------- pickup hooks (lapis scholar repair: MiningHooks.lapisRepair)

    @SubscribeEvent
    public static void onPickup(ItemEntityPickupEvent.Post e) {
        if (e.getPlayer() instanceof ServerPlayer p && e.getOriginalStack().getCount() != e.getCurrentStack().getCount()) Compactor.pack(p);
    }

    /** Compactor: 9 of an ore item pack into its block, using the crafting recipes. */
    static final class Compactor {
        /** Packing order; the skill value is how many entries from the front are allowed. */
        static final List<Item> ORDER = List.of(Items.RAW_IRON, Items.RAW_COPPER, Items.RAW_GOLD, Items.COAL, Items.REDSTONE,
                Items.LAPIS_LAZULI, Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT, Items.DIAMOND, Items.EMERALD,
                Items.IRON_NUGGET, Items.GOLD_NUGGET, Items.AMETHYST_SHARD, Items.QUARTZ, Items.NETHERITE_INGOT);

        /** 3x3 recipe result per item, EMPTY when none (quartz and amethyst only pack 2x2, so without the cache every
         * pickup re-ran the recipe search). Rebuilt when the recipe manager changes (/reload). */
        private static final java.util.Map<Item, ItemStack> PACKED = new java.util.IdentityHashMap<>();
        private static java.lang.ref.WeakReference<Object> packedFor = new java.lang.ref.WeakReference<>(null);

        static ItemStack packed(ServerPlayer p, Item item) {
            var rm = p.serverLevel().getRecipeManager();
            if (packedFor.get() != rm) { PACKED.clear(); packedFor = new java.lang.ref.WeakReference<>(rm); }
            ItemStack hit = PACKED.get(item);
            if (hit != null) return hit;
            List<ItemStack> grid = Collections.nCopies(9, new ItemStack(item));
            ItemStack out = rm.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, grid), p.serverLevel())
                    .map(r -> r.value().getResultItem(p.registryAccess()).copy()).orElse(ItemStack.EMPTY);
            PACKED.put(item, out);
            return out;
        }

        static void pack(ServerPlayer p) {
            HabitsApi.PerkHit h = MiningMechanics.hit(p, "compactor");
            if (h == null) return;
            int kinds = (int) Math.min(ORDER.size(), h.value());
            var inv = p.getInventory();
            for (int k = 0; k < kinds; k++) {
                Item item = ORDER.get(k);
                int have = 0;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack st = inv.getItem(i);
                    if (st.is(item) && !st.has(DataComponents.CUSTOM_NAME)) have += st.getCount();
                }
                if (have < 9) continue;
                ItemStack block = packed(p, item);
                if (block.isEmpty() || block.getCount() != 1) continue;
                int packs = have / 9;
                int toRemove = packs * 9;
                for (int i = 0; i < inv.getContainerSize() && toRemove > 0; i++) {
                    ItemStack st = inv.getItem(i);
                    if (!st.is(item) || st.has(DataComponents.CUSTOM_NAME)) continue;
                    int take = Math.min(toRemove, st.getCount());
                    st.shrink(take);
                    toRemove -= take;
                }
                for (int left = packs; left > 0; left -= block.getMaxStackSize()) Work.give(p, block.copyWithCount(Math.min(left, block.getMaxStackSize())));
            }
        }
    }
}
