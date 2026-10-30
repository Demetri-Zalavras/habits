package com.habits.perk.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Pufferfish;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Moments vanilla events don't cover, fired as CUSTOM triggers ({@code habits:<event>}); the list lives in
 * docs/mechanics/farming-nature.md. Cheap filters first: most of these bail on the first instanceof.
 */
public final class FarmTriggers {
    private FarmTriggers() {}

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || e.getLevel().isClientSide || !(e.getTarget() instanceof Animal a)) return;
        if (!a.isFood(e.getItemStack())) return;
        Farm.fire(e.getEntity(), a.isBaby() ? "feed_baby" : "feed_animal", a.position());
    }

    @SubscribeEvent
    public static void onEffectAdded(MobEffectEvent.Added e) {
        if (e.getEntity() instanceof ServerPlayer p && e.getEffectInstance().is(MobEffects.POISON) && e.getEffectSource() instanceof Pufferfish pf)
            Farm.fire(p, "puffer_poisoned", pf.position());
    }

    /** Froglights (a frog ate a magma cube) and sniffer finds or hatchlings: players within 8 blocks get the roll. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.loadedFromDisk() || !(e.getLevel() instanceof ServerLevel l)) return;
        String event = null;
        if (e.getEntity() instanceof ItemEntity it) {
            ItemStack s = it.getItem();
            if (s.is(Items.OCHRE_FROGLIGHT) || s.is(Items.VERDANT_FROGLIGHT) || s.is(Items.PEARLESCENT_FROGLIGHT)) event = "froglight";
            else if ((s.is(Items.TORCHFLOWER_SEEDS) || s.is(Items.PITCHER_POD))
                    && !l.getEntitiesOfClass(Sniffer.class, it.getBoundingBox().inflate(4)).isEmpty()) event = "sniffer_find";
        } else if (e.getEntity() instanceof Sniffer sn && sn.isBaby()) event = "sniffer_find";
        if (event == null) return;
        Vec3 at = e.getEntity().position();
        for (ServerPlayer p : l.players()) if (p.distanceToSqr(at) <= 64) Farm.fire(p, event, at);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || e.getLevel().isClientSide) return;
        var s = e.getLevel().getBlockState(e.getPos());
        ItemStack held = e.getItemStack();
        if (s.getBlock() instanceof BeehiveBlock && s.getValue(BeehiveBlock.HONEY_LEVEL) >= BeehiveBlock.MAX_HONEY_LEVELS
                && (held.is(Tags.Items.TOOLS_SHEAR) || held.is(Items.GLASS_BOTTLE)))
            Farm.fire(e.getEntity(), "hive_harvest", Vec3.atCenterOf(e.getPos()));
    }

    /** The 8 crops for eight_crops_day, one bit each. */
    static final Block[] EIGHT = {Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS, Blocks.MELON, Blocks.PUMPKIN, Blocks.COCOA, Blocks.NETHER_WART};

    /** A ripe harvest (Crops.onDrops): all 8 crops within one Minecraft day (overworld clock) fires eight_crops_day once. */
    static void harvested(Player p, ServerLevel l, Block b, Vec3 at) {
        int bit = java.util.Arrays.asList(EIGHT).indexOf(b);
        if (bit < 0) return;
        var d = Farm.data(p);
        long day = l.getServer().overworld().getDayTime() / 24000;
        int mask = d.getLong("habits_eden_day") == day ? d.getInt("habits_eden_crops") : 0;
        mask |= 1 << bit;
        if (mask == 0xFF) {
            Farm.fire(p, "eight_crops_day", at);
            mask |= 0x100; // done for today
        }
        d.putLong("habits_eden_day", day);
        d.putInt("habits_eden_crops", mask);
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent e) {
        if (e.isMounting() && !e.getLevel().isClientSide && e.getEntityMounting() instanceof Player p && e.getEntityBeingMounted() instanceof SkeletonHorse h)
            Farm.fire(p, "skeleton_horse_mount", h.position());
    }

    @SubscribeEvent
    public static void onFished(ItemFishedEvent e) {
        Player p = e.getEntity();
        if (!(p.level() instanceof ServerLevel l)) return;
        Vec3 at = e.getHookEntity().position();
        BlockPos pos = e.getHookEntity().blockPosition();
        if (l.isThundering()) Farm.fire(p, "thunder_catch", at);
        if (nearLava(l, pos)) Farm.fire(p, "lava_catch", at);
        if (l.getBiome(pos).value().coldEnoughToSnow(pos)) Farm.fire(p, "cold_catch", at);
    }

    /** Lava within 5 blocks: samples a sparse 5-block grid (27 reads), not the whole cube. */
    static boolean nearLava(ServerLevel l, BlockPos pos) {
        for (int dx = -5; dx <= 5; dx += 5)
            for (int dy = -5; dy <= 5; dy += 5)
                for (int dz = -5; dz <= 5; dz += 5) {
                    BlockPos q = pos.offset(dx, dy, dz);
                    if (Farm.loaded(l, q) && l.getFluidState(q).is(FluidTags.LAVA)) return true;
                }
        for (BlockPos q : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2)))
            if (l.getFluidState(q).is(FluidTags.LAVA)) return true;
        return false;
    }
}
