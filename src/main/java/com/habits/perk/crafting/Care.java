package com.habits.perk.crafting;

import com.habits.core.HabitsApi;
import com.habits.perk.Later;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;

/** Gear care: slow self-repair, quenching in a cauldron, salvage from broken gear, and the heirloom save. */
final class Care {
    private Care() {}

    static final ResourceLocation TOOL_CARE = C.mech("tool_care", "seconds per point of durability mended");
    static final ResourceLocation QUENCH = C.mech("quench", "% of durability mended per quench");
    static final ResourceLocation SALVAGER = C.mech("salvager", "materials back from broken gear");
    static final ResourceLocation HEIRLOOM = C.mech("heirloom", "% durability a breaking item comes back with");

    private static final Map<UUID, Long> MEND = C.playerMap();
    private static final Map<UUID, Long> QUENCH_CD = C.playerMap();
    private static final Map<UUID, Long> HEIRLOOM_CD = C.playerMap();

    static void init() {}

    // ------------------------------------------------------------------ tool care: the held item mends itself

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.level().isClientSide || p.tickCount % 20 != 0) return;
        HabitsApi.PerkHit h = C.hit(p, TOOL_CARE);
        if (h != null) mendTick(p, h.value());
    }

    static void mendTick(Player p, double seconds) {
        ItemStack s = p.getMainHandItem();
        if (!s.isDamageableItem() || !s.isDamaged()) return;
        if (C.ready(MEND, p, seconds)) s.setDamageValue(s.getDamageValue() - 1);
    }

    // ------------------------------------------------------------------ quench: sneak-use a damaged item on a water cauldron

    @SubscribeEvent
    static void onClick(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (!p.isShiftKeyDown() || e.getHand() != InteractionHand.MAIN_HAND) return;
        BlockState st = p.level().getBlockState(e.getPos());
        if (!st.is(Blocks.WATER_CAULDRON)) return;
        HabitsApi.PerkHit h = C.hit(p, QUENCH);
        ItemStack s = p.getMainHandItem();
        if (h == null || !s.isDamageableItem() || !s.isDamaged()) return;
        e.setCanceled(true);
        if (!C.ready(QUENCH_CD, p, C.param(h, "cooldown", 30, 5))) return;
        s.setDamageValue(Math.max(0, s.getDamageValue() - (int) Math.ceil(s.getMaxDamage() * h.value() / 100)));
        LayeredCauldronBlock.lowerFillLevel(st, p.level(), e.getPos());
        p.level().playSound(null, e.getPos(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.6f, 1.2f);
    }

    // ------------------------------------------------------------------ broken gear: heirloom first, else salvage

    @SubscribeEvent
    static void onStat(StatAwardEvent e) {
        if (e.getStat().getType() != Stats.ITEM_BROKEN || !(e.getEntity() instanceof ServerPlayer p) || !C.ok(p)) return;
        if (!(e.getStat().getValue() instanceof Item item)) return;
        broke(p, item);
    }

    static void broke(ServerPlayer p, Item item) {
        HabitsApi.PerkHit h;
        ItemStack gone = brokenStack(p, item);
        if ((h = C.hit(p, HEIRLOOM)) != null && gone != null && C.ready(HEIRLOOM_CD, p, C.param(h, "cooldown", 600, 120))) {
            gone.setDamageValue((int) (gone.getMaxDamage() * (1 - Math.min(99, h.value()) / 100)));
            // next tick: some callers clear the hand slot right after the break
            Later.run((ServerLevel) p.level(), 1, () -> C.give(p, gone));
            return;
        }
        if ((h = C.hit(p, SALVAGER)) != null) {
            Ingredient mat = material(item);
            ItemStack[] opts = mat == null ? new ItemStack[0] : mat.getItems();
            int n = C.carry(p, h.value());
            if (opts.length > 0 && n > 0) C.give(p, opts[0].copyWithCount(n));
        }
    }

    /** A copy of the stack that just broke: vanilla shrinks it to 0 in its slot, components intact. */
    @org.jetbrains.annotations.Nullable
    static ItemStack brokenStack(Player p, Item item) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = p.getItemBySlot(slot);
            if (s == ItemStack.EMPTY || s.getCount() != 0) continue;
            s.setCount(1);
            ItemStack copy = s.is(item) ? s.copy() : null;
            s.setCount(0);
            if (copy != null) return copy;
        }
        return null;
    }

    @org.jetbrains.annotations.Nullable
    static Ingredient material(Item item) {
        if (item instanceof TieredItem t) return t.getTier().getRepairIngredient();
        if (item instanceof ArmorItem a) return a.getMaterial().value().repairIngredient().get();
        return null;
    }
}
