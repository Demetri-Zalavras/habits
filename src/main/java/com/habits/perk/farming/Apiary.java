package com.habits.perk.farming;

import com.habits.core.HabitsApi.PerkHit;
import com.habits.perk.Later;
import com.habits.trigger.TriggerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Spec batch: bees and hives. */
public final class Apiary {
    private Apiary() {}

    static PerkHit hit(Player p, String skill) {
        return Fields.hit(p, skill);
    }

    static final String HIVES = "habits_hives";
    /** Hives remembered for Honey Flow, oldest dropped first. */
    static final int MAX_HIVES = 16;

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getLevel() instanceof ServerLevel l) || !(e.getEntity() instanceof ServerPlayer p) || !Farm.ok(p)) return;
        BlockPos pos = e.getPos().immutable();
        BlockState s = l.getBlockState(pos);
        if (!(s.getBlock() instanceof BeehiveBlock) || s.getValue(BeehiveBlock.HONEY_LEVEL) < BeehiveBlock.MAX_HONEY_LEVELS) return;
        ItemStack held = e.getItemStack();
        boolean shears = held.is(Tags.Items.TOOLS_SHEAR), bottle = held.is(Items.GLASS_BOTTLE);
        if (!shears && !bottle) return;
        Vec3 at = Vec3.atCenterOf(pos);
        remember(p, pos);
        PerkHit h;
        if (shears) {
            Farm.fire(p, "farming/comb_sheared", at);
            if ((h = hit(p, "comb_cutter")) != null) {
                int n = Farm.round(l, h.value());
                if (n > 0) Block.popResource(l, pos, new ItemStack(Items.HONEYCOMB, n));
            }
        } else {
            Farm.fire(p, "farming/honey_bottled", at);
            if ((h = hit(p, "honey_bottler")) != null) moreBottles(p, held, (int) h.value());
        }
        if (CampfireBlock.isSmokeyPos(l, pos)) {
            Farm.fire(p, "farming/smoked_hive", at);
            if ((h = hit(p, "smoke_and_honey")) != null) {
                int keep = Math.min(BeehiveBlock.MAX_HONEY_LEVELS - 1, (int) h.value());
                Later.run(l, 1, () -> {
                    BlockState now = l.getBlockState(pos);
                    if (now.getBlock() instanceof BeehiveBlock && now.getValue(BeehiveBlock.HONEY_LEVEL) == 0)
                        l.setBlock(pos, now.setValue(BeehiveBlock.HONEY_LEVEL, keep), Block.UPDATE_ALL);
                });
            }
        }
    }

    /** Honey Bottler: up to n more glass bottles from the bag become honey (vanilla fills the one in hand). */
    static void moreBottles(ServerPlayer p, ItemStack held, int n) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.items.size() && n > 0; i++) {
            ItemStack s = inv.items.get(i);
            if (!s.is(Items.GLASS_BOTTLE)) continue;
            int spare = s == held ? s.getCount() - 1 : s.getCount(), k = Math.min(n, spare);
            if (k <= 0) continue;
            s.shrink(k);
            n -= k;
            Farm.give(p, new ItemStack(Items.HONEY_BOTTLE, k));
        }
    }

    static void remember(Player p, BlockPos pos) {
        ListTag list = Farm.data(p).getList(HIVES, Tag.TAG_LONG);
        LongTag t = LongTag.valueOf(pos.asLong());
        list.remove(t);
        list.add(t);
        while (list.size() > MAX_HIVES) list.remove(0);
        Farm.data(p).put(HIVES, list);
    }

    /** Honey Flow: every value seconds, each remembered hive within 32 blocks gains a honey level. */
    static void honeyFlowTick(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || (l.getGameTime() + p.getId()) % 20 != 0) return;
        if (!Farm.data(p).contains(HIVES) || !Farm.cooldown(p, "habits_flow", h.value())) return;
        ListTag list = Farm.data(p).getList(HIVES, Tag.TAG_LONG);
        for (int i = list.size() - 1; i >= 0; i--) {
            BlockPos q = BlockPos.of(((LongTag) list.get(i)).getAsLong());
            if (q.distSqr(p.blockPosition()) > 32 * 32 || !Farm.loaded(l, q)) continue;
            BlockState s = l.getBlockState(q);
            if (!(s.getBlock() instanceof BeehiveBlock)) { list.remove(i); continue; }
            int lv = s.getValue(BeehiveBlock.HONEY_LEVEL);
            if (lv < BeehiveBlock.MAX_HONEY_LEVELS) l.setBlock(q, s.setValue(BeehiveBlock.HONEY_LEVEL, lv + 1), Block.UPDATE_ALL);
        }
    }

    /** Pollinator: bees with pollen near you grow the crop under them, rolled every 2 seconds per bee. */
    static void pollinateTick(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || (l.getGameTime() + p.getId()) % 40 != 0) return;
        for (Bee b : Animals.near(p, Bee.class, Farm.lerp(h, "radius", 8, 24), Bee::hasNectar)) {
            if (!Farm.roll(l, h.value())) continue;
            BlockPos at = b.blockPosition();
            for (int dy = 0; dy >= -2; dy--) {
                BlockPos q = at.offset(0, dy, 0);
                if (Crops.maxAge(l.getBlockState(q)) > 0 && Crops.grow(l, q, 1)) {
                    l.levelEvent(2005, q, 0); // bone meal sparkles
                    break;
                }
            }
        }
    }

    /** Bee Mover: the nest or hive comes off whole, bees and honey inside, like silk touch. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent e) {
        if (Farm.busy || !(e.getPlayer() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel l) || !(e.getState().getBlock() instanceof BeehiveBlock)) return;
        PerkHit h = hit(p, "bee_mover");
        ItemStack tool = p.getMainHandItem();
        if (h == null || p.getAbilities().instabuild || !Farm.roll(l, h.value())
                || EnchantmentHelper.getItemEnchantmentLevel(l.registryAccess().holderOrThrow(Enchantments.SILK_TOUCH), tool) > 0) return;
        BlockPos pos = e.getPos();
        BlockState s = e.getState();
        if (!(l.getBlockEntity(pos) instanceof BeehiveBlockEntity be)) return;
        ItemStack item = new ItemStack(s.getBlock());
        item.applyComponents(be.collectComponents());
        item.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(BeehiveBlock.HONEY_LEVEL, s.getValue(BeehiveBlock.HONEY_LEVEL)));
        e.setCanceled(true);
        l.removeBlock(pos, false);
        Block.popResource(l, pos, item);
        l.sendParticles(ParticleTypes.WAX_ON, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 8, 0.4, 0.4, 0.4, 0);
        // a cancelled break skips the trigger listener, so roll the card here
        TriggerEvents.rollBreak(p, s, pos, TriggerEvents.itemOrNull(tool));
    }
}
