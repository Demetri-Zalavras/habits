package com.habits.perk.farming;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import com.habits.trigger.TriggerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Shared helpers for the farming-nature mechanics. Every mechanic id equals its skill id. */
public final class Farm {
    private Farm() {}

    /** True while our own code breaks blocks, so our BreakEvent listeners don't chain off themselves. */
    public static boolean busy;

    /** Alive, not spectating, and not a fake player (GameTest fakes are let through like the trigger gate does). */
    static boolean ok(@Nullable Player p) {
        return p != null && p.isAlive() && !p.isSpectator()
                && (!(p instanceof FakePlayer) || Triggers.TEST_PLAYERS.contains(p.getUUID()));
    }

    @Nullable
    static HabitsApi.PerkHit hit(@Nullable Player p, String mechanic) {
        return ok(p) ? HabitsApi.mechanicHit(p, Habits.id(mechanic)) : null;
    }

    /** A tier skill's tier from its unboosted value, so Potent can't unlock the next tier early. */
    static int tier(HabitsApi.PerkHit h) {
        var raw = com.habits.core.SkillRegistry.get(h.skill().id());
        return (int) Math.min(h.value(), raw == null ? h.value() : raw.perkValue(h.level()));
    }

    /** A secondary number that scales Lv1..Lv10. The skill JSON param {@code key} = "lv1,lv10" overrides the defaults. */
    static double lerp(HabitsApi.PerkHit h, String key, double lo, double hi) {
        String s = h.skill().perk().param(key, null);
        if (s != null) {
            String[] a = s.split(",");
            try {
                lo = Double.parseDouble(a[0].trim());
                hi = Double.parseDouble(a[a.length - 1].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return lo + (hi - lo) * (Math.max(1, h.level()) - 1) / 9.0;
    }

    /** {@code percent} out of 100. */
    static boolean roll(Level l, double percent) {
        return l.random.nextDouble() * 100 < percent;
    }

    /** Whole part plus a chance for the fraction (2.3 -> 2, or 3 three times in ten). */
    static int round(Level l, double v) {
        int n = (int) Math.floor(v);
        return n + (l.random.nextDouble() < v - n ? 1 : 0);
    }

    /** Break a block as the player: fires BreakEvent, uses the held tool's durability, drops loot, respects protection. */
    static boolean breakAs(ServerPlayer p, BlockPos pos) {
        BlockState s = p.level().getBlockState(pos);
        ResourceLocation tool = TriggerEvents.itemOrNull(p.getMainHandItem());
        boolean was = busy, broke;
        busy = true;
        try {
            broke = p.gameMode.destroyBlock(pos);
        } finally {
            busy = was;
        }
        // every extra block a perk breaks rolls cards like a hand-broken one (user call, 2026-10-08)
        if (broke) TriggerEvents.rollBreak(p, s, pos, tool);
        return broke;
    }

    /** Loaded check that never loads a chunk. */
    static boolean loaded(Level l, BlockPos pos) {
        return l.hasChunkAt(pos);
    }

    static void give(Player p, ItemStack s) {
        if (!s.isEmpty() && !p.getInventory().add(s)) p.drop(s, false);
    }

    static ItemEntity drop(Level l, Vec3 at, ItemStack s) {
        ItemEntity e = new ItemEntity(l, at.x, at.y, at.z, s);
        e.setDefaultPickUpDelay();
        l.addFreshEntity(e);
        return e;
    }

    /** Fire one of our CUSTOM trigger events, {@code habits:<event>}. */
    static void fire(Player p, String event, @Nullable Vec3 pos) {
        Triggers.fire(p, TriggerType.CUSTOM, TriggerContext.of(Habits.id(event)).withPos(pos));
    }

    static boolean mature(BlockState s) {
        if (s.getBlock() instanceof CropBlock c) return c.isMaxAge(s);
        if (s.getBlock() instanceof NetherWartBlock) return s.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
        if (s.getBlock() instanceof CocoaBlock) return s.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
        return false;
    }

    /** Per-player saved state (survives relogs; NeoForge keeps it on the player). */
    static CompoundTag data(Player p) {
        return p.getPersistentData();
    }

    /** Death and End exit build a new player without this data; these keys carry over (pet revives, air bank, mount). */
    static final String[] KEPT = {Animals.PETS, "habits_air", "habits_mount"};

    static void keepOnClone(net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone e) {
        CompoundTag from = data(e.getOriginal()), to = data(e.getEntity());
        for (String k : KEPT) if (from.contains(k)) to.put(k, from.get(k).copy());
    }

    /** Cooldown stored as a game-time stamp. Returns true (and starts it) when ready. */
    static boolean cooldown(Player p, String key, double seconds) {
        long now = p.level().getGameTime();
        CompoundTag d = data(p);
        if (now < d.getLong(key)) return false;
        d.putLong(key, now + (long) (seconds * 20));
        return true;
    }

    static ResourceKey<LootTable> table(String path) {
        return ResourceKey.create(Registries.LOOT_TABLE, Habits.id(path));
    }

    /** Roll a loot table that needs only an origin (our tables, chests, gifts). */
    static List<ItemStack> loot(ServerLevel l, ResourceKey<LootTable> key, Vec3 at, @Nullable Player p) {
        LootParams.Builder b = new LootParams.Builder(l).withParameter(LootContextParams.ORIGIN, at);
        if (p != null) b.withParameter(LootContextParams.THIS_ENTITY, p).withLuck(p.getLuck());
        return l.getServer().reloadableRegistries().getLootTable(key).getRandomItems(b.create(LootContextParamSets.CHEST));
    }
}
