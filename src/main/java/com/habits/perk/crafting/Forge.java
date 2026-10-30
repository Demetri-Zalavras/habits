package com.habits.perk.crafting;

import com.habits.core.HabitsApi;
import com.habits.trigger.Triggers;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;


/** Furnace and smithing table perks, plus the station pockets (fuel and lapis loaded for you). */
final class Forge {
    private Forge() {}

    static final ResourceLocation BYPRODUCTS = C.mech("byproducts", "% chance per smelted item of a byproduct");
    static final ResourceLocation KILN = C.mech("kiln_lore", "% chance per fired item of 1 XP");
    static final ResourceLocation TRANSMUTER = C.mech("transmuter", "% chance per ingot of the next metal");
    static final ResourceLocation FORGE_RHYTHM = C.mech("forge_rhythm", "seconds of Haste after a furnace take");
    static final ResourceLocation SMITHS_ARM = C.mech("smiths_arm", "seconds of Strength after smithing");
    static final ResourceLocation ANCIENT_THRIFT = C.mech("ancient_thrift", "% chance to keep the netherite ingot");
    static final ResourceLocation QUICK_COLLECT = C.mech("quick_collect", "collect radius in blocks");
    static final ResourceLocation STOKERS_POCKET = C.mech("stokers_pocket", "fuel loaded from your bag");
    static final ResourceLocation LAPIS_POCKET = C.mech("lapis_pocket", "lapis loaded from your bag");
    static final ResourceLocation HEARTHFIRE = C.mech("hearthfire", "warmth radius in blocks");

    static void init() {}

    // ------------------------------------------------------------------ takes (exact counts from the crafted stat)

    @SubscribeEvent
    static void onStat(StatAwardEvent e) {
        if (e.getStat().getType() != Stats.ITEM_CRAFTED || !(e.getEntity() instanceof ServerPlayer p) || !C.ok(p)) return;
        if (!(e.getStat().getValue() instanceof Item item)) return;
        int amount = Triggers.statDelta(e);
        if (amount <= 0) return;
        if (p.containerMenu instanceof AbstractFurnaceMenu) furnaceTake(p, item, amount);
        else if (p.containerMenu instanceof SmithingMenu sm) smithingTake(p, sm);
    }

    static void furnaceTake(Player p, Item item, int amount) {
        HabitsApi.PerkHit h;
        if ((h = C.hit(p, BYPRODUCTS)) != null) {
            Item by = C.table(h, "table", "minecraft:iron_ingot=minecraft:iron_nugget,minecraft:gold_ingot=minecraft:gold_nugget,"
                    + "minecraft:copper_ingot=minecraft:raw_copper,minecraft:glass=minecraft:sand,minecraft:netherite_scrap=minecraft:gold_nugget").get(item);
            int n = by == null ? 0 : C.carry(p, amount * h.value() / 100);
            if (n > 0) C.give(p, new ItemStack(by, n));
        }
        if ((h = C.hit(p, KILN)) != null && C.matches(p, h, "items", "minecraft:brick,minecraft:nether_brick,#minecraft:terracotta,minecraft:smooth_stone,minecraft:stone", new ItemStack(item))) {
            int xp = C.carry(p, amount * h.value() / 100);
            if (xp > 0) p.giveExperiencePoints(xp);
        }
        if ((h = C.hit(p, TRANSMUTER)) != null) {
            Item up = C.table(h, "table", "minecraft:copper_ingot=minecraft:iron_ingot,minecraft:iron_ingot=minecraft:gold_ingot,minecraft:gold_ingot=minecraft:diamond").get(item);
            int n = 0;
            if (up != null) for (int i = 0; i < amount; i++) if (C.roll(p, h.value())) n++;
            if (n > 0) C.give(p, new ItemStack(up, n));
        }
        if ((h = C.hit(p, FORGE_RHYTHM)) != null) C.effect(p, MobEffects.DIG_SPEED, (int) C.param(h, "amp", 0, 1), (int) (h.value() * 20));
    }

    static void smithingTake(Player p, SmithingMenu sm) {
        HabitsApi.PerkHit h;
        ItemStack add = sm.getSlot(SmithingMenu.ADDITIONAL_SLOT).getItem();
        // inputs are still in their slots here: vanilla shrinks them right after the stat
        if ((h = C.hit(p, ANCIENT_THRIFT)) != null && add.is(Items.NETHERITE_INGOT) && C.roll(p, h.value())) C.give(p, new ItemStack(Items.NETHERITE_INGOT));
        if ((h = C.hit(p, SMITHS_ARM)) != null) C.effect(p, MobEffects.DAMAGE_BOOST, (int) C.param(h, "amp", 0, 1), (int) (h.value() * 20));
    }

    // ------------------------------------------------------------------ pockets: fuel and lapis loaded on open

    @SubscribeEvent
    static void onOpen(PlayerContainerEvent.Open e) {
        Player p = e.getEntity();
        HabitsApi.PerkHit h;
        if (e.getContainer() instanceof AbstractFurnaceMenu m && (h = C.hit(p, STOKERS_POCKET)) != null) {
            load(p, m.getSlot(1), (int) h.value(), s -> s.getMaxStackSize() > 1 && s.getBurnTime(RecipeType.SMELTING) > 0);
            m.broadcastChanges();
        } else if (e.getContainer() instanceof EnchantmentMenu m && (h = C.hit(p, LAPIS_POCKET)) != null) {
            load(p, m.getSlot(1), (int) h.value(), s -> s.is(Items.LAPIS_LAZULI));
            m.broadcastChanges();
        }
    }

    /** Move up to {@code max} of the first matching bag stack into an empty or same-item slot. Counts are conserved. */
    static void load(Player p, Slot slot, int max, java.util.function.Predicate<ItemStack> ok) {
        ItemStack cur = slot.getItem();
        var inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || !ok.test(s) || !cur.isEmpty() && !ItemStack.isSameItemSameComponents(cur, s)) continue;
            int room = Math.min(max - cur.getCount(), slot.getMaxStackSize(s) - cur.getCount());
            if (room <= 0) return;
            int n = Math.min(room, s.getCount());
            if (cur.isEmpty()) slot.set(s.split(n));
            else {
                cur.grow(n);
                s.shrink(n);
                slot.setChanged();
            }
            return;
        }
    }

    // ------------------------------------------------------------------ quick collect: sneak-use a furnace with an empty hand

    @SubscribeEvent
    static void onClick(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (!p.isShiftKeyDown() || !p.getMainHandItem().isEmpty() || e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!(p.level().getBlockState(e.getPos()).getBlock() instanceof AbstractFurnaceBlock)) return;
        HabitsApi.PerkHit h = C.hit(p, QUICK_COLLECT);
        if (h == null) return;
        if (collect(p, e.getPos(), h.value()) > 0) e.setCanceled(true);
    }

    /** Pull every furnace result within {@code radius} into the bag, with its smelting XP. Returns items moved. */
    static int collect(Player p, BlockPos at, double radius) {
        Level level = p.level();
        AABB box = new AABB(at).inflate(radius);
        int moved = 0;
        for (int cx = ((int) box.minX) >> 4; cx <= ((int) box.maxX) >> 4; cx++) {
            for (int cz = ((int) box.minZ) >> 4; cz <= ((int) box.maxZ) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (var be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof AbstractFurnaceBlockEntity f) || !box.contains(be.getBlockPos().getCenter())) continue;
                    ItemStack out = f.getItem(2);
                    if (out.isEmpty()) continue;
                    ItemStack take = out.copy();
                    f.setItem(2, ItemStack.EMPTY);
                    moved += take.getCount();
                    // the vanilla take path: stat (so takes still count), crafted-by hooks, stored XP
                    take.onCraftedBy(level, p, take.getCount());
                    C.give(p, take);
                    if (p instanceof ServerPlayer sp) f.awardUsedRecipesAndPopExperience(sp);
                    f.setChanged();
                }
            }
        }
        return moved;
    }

    // ------------------------------------------------------------------ hearthfire: regeneration near a lit furnace

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.level().isClientSide || p.tickCount % 40 != 0) return;
        HabitsApi.PerkHit h = C.hit(p, HEARTHFIRE);
        if (h != null && nearHearth(p, (int) h.value())) C.effect(p, MobEffects.REGENERATION, (int) C.param(h, "amp", 0, 0), 60);
    }

    static boolean nearHearth(Player p, int r) {
        BlockPos c = p.blockPosition();
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
            if (!p.level().isLoaded(q)) continue;
            BlockState s = p.level().getBlockState(q);
            if ((s.getBlock() instanceof AbstractFurnaceBlock || s.is(BlockTags.CAMPFIRES))
                    && s.getOptionalValue(BlockStateProperties.LIT).orElse(false)) return true;
        }
        return false;
    }
}
