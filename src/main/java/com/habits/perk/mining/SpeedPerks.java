package com.habits.perk.mining;

import com.habits.core.HabitsApi;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Break speed and harvest checks. Both sides read the same synced collection, so client and server agree on break
 * time (momentum stacks come from {@link MiningNet.Momentum} on the client). */
public final class SpeedPerks {
    private SpeedPerks() {}

    /** Client copy of the local player's momentum stacks. */
    public static int clientMomentum;

    @SubscribeEvent
    public static void onSpeed(PlayerEvent.BreakSpeed e) {
        Player p = e.getEntity();
        if (!MiningMechanics.allowed(p)) return;
        BlockState s = e.getState();
        float speed = e.getNewSpeed();
        ItemStack tool = p.getMainHandItem();
        HabitsApi.PerkHit h;

        if ((h = MiningMechanics.hit(p, "stonebreaker")) != null) {
            int stacks = p.level().isClientSide() ? clientMomentum : BreakPerks.momentumStacks(p);
            speed *= (float) (1 + stacks * h.value() / 100);
        }
        if (s.is(Blocks.ANCIENT_DEBRIS) && (h = MiningMechanics.hit(p, "debris_diver")) != null)
            speed *= (float) MiningMechanics.lerp(h, "speed", 2, 50);
        if ((s.is(Blocks.OBSIDIAN) || s.is(Blocks.CRYING_OBSIDIAN) || s.is(Blocks.RESPAWN_ANCHOR)) && (h = MiningMechanics.hit(p, "obsidian_breaker")) != null)
            speed /= (float) Math.max(0.02, 1 - h.value() / 100);
        if (!p.onGround() && !p.isInWater() && (h = MiningMechanics.hit(p, "midair_miner")) != null)
            speed *= (float) (1 + 4 * h.value() / 100);
        if ((h = MiningMechanics.hit(p, "punch_above")) != null && tool.getItem() instanceof TieredItem t
                && (lowTier(t.getTier()) || h.level() >= (int) MiningMechanics.lerp(h, "all_level", 10, 10)))
            speed *= (float) (1 + h.value() / 100);
        if (MiningMechanics.bareHand(tool) && (h = MiningMechanics.hit(p, "bare_knuckle")) != null && handMineable(s))
            speed = Math.max(speed, (float) h.value());
        if (tool.isEmpty()) {
            if ((h = MiningMechanics.hit(p, "gentle_hands")) != null && h.level() >= 10
                    && (s.is(Tags.Blocks.GLASS_BLOCKS) || s.is(Tags.Blocks.GLASS_PANES) || s.is(Blocks.SEA_LANTERN) || s.is(Blocks.BOOKSHELF)))
                speed = Math.max(speed, 100f);
        }
        e.setNewSpeed(speed);
    }

    @SubscribeEvent
    public static void onHarvest(PlayerEvent.HarvestCheck e) {
        if (e.canHarvest()) return;
        Player p = e.getEntity();
        if (!MiningMechanics.allowed(p)) return;
        BlockState s = e.getTargetBlock();
        ItemStack tool = p.getMainHandItem();
        HabitsApi.PerkHit h;
        if ((s.is(Blocks.OBSIDIAN) || s.is(Blocks.CRYING_OBSIDIAN) || s.is(Blocks.RESPAWN_ANCHOR))
                && (h = MiningMechanics.hit(p, "obsidian_breaker")) != null && h.level() >= (int) MiningMechanics.lerp(h, "iron_level", 5, 5)
                && tool.getItem() instanceof TieredItem t && t.getTier() == Tiers.IRON && tool.canPerformAction(ItemAbilities.PICKAXE_DIG)) {
            e.setCanHarvest(true);
            return;
        }
        if ((h = MiningMechanics.hit(p, "punch_above")) != null && tool.getItem() instanceof TieredItem t
                && (lowTier(t.getTier()) || h.level() >= (int) MiningMechanics.lerp(h, "all_level", 10, 10))
                && tool.getDestroySpeed(s) > 1 && !s.is(next(t.getTier()).getIncorrectBlocksForDrops())) {
            e.setCanHarvest(true);
            return;
        }
        if (MiningMechanics.bareHand(tool) && (h = MiningMechanics.hit(p, "bare_knuckle")) != null && handMineable(s)
                && !s.is(handTier(h.value()).getIncorrectBlocksForDrops())) {
            e.setCanHarvest(true);
            return;
        }
        if (tool.isEmpty() && s.is(Tags.Blocks.GLAZED_TERRACOTTAS) && (h = MiningMechanics.hit(p, "mesa_gold")) != null
                && h.level() >= (int) MiningMechanics.lerp(h, "hand_level", 6, 6))
            e.setCanHarvest(true);
    }

    static boolean lowTier(Tier t) {
        return t == Tiers.WOOD || t == Tiers.STONE || t == Tiers.GOLD;
    }

    static Tier next(Tier t) {
        if (t == Tiers.WOOD || t == Tiers.GOLD) return Tiers.STONE;
        if (t == Tiers.STONE) return Tiers.IRON;
        if (t == Tiers.IRON) return Tiers.DIAMOND;
        return Tiers.NETHERITE;
    }

    static boolean handMineable(BlockState s) {
        return s.is(BlockTags.MINEABLE_WITH_PICKAXE) || s.is(BlockTags.MINEABLE_WITH_AXE) || s.is(BlockTags.MINEABLE_WITH_SHOVEL);
    }

    /** Bare knuckle value is a dig speed: 2 = wood, 4 = stone, 6 = iron (the vanilla tier speeds). */
    static Tier handTier(double speed) {
        return speed >= 6 ? Tiers.IRON : speed >= 4 ? Tiers.STONE : Tiers.WOOD;
    }
}
