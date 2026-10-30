package com.habits.perk.nature;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsApi.PerkHit;
import com.habits.core.IdMatch;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import com.habits.trigger.Triggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/** Shared helpers for the nature mechanics. Mechanic ids are {@code habits:nature/<skill>}. */
final class Nat {
    private Nat() {}

    /** Alive, not spectating, and a real player (GameTest fakes are let through like the trigger gate does). */
    static boolean ok(@Nullable Player p) {
        return p != null && p.isAlive() && !p.isSpectator()
                && (!(p instanceof FakePlayer) || Triggers.TEST_PLAYERS.contains(p.getUUID()));
    }

    static ResourceLocation id(String m) {
        return Habits.id("nature/" + m);
    }

    @Nullable
    static PerkHit hit(@Nullable Player p, String m) {
        return ok(p) ? HabitsApi.mechanicHit(p, id(m)) : null;
    }

    /** A secondary number that scales Lv1..Lv10 from the skill param {@code key} ("lv1,lv10" or "lv1..lv10"; one number = flat). */
    static double lerp(PerkHit h, String key, double lo, double hi) {
        String s = h.skill().perk().param(key, null);
        if (s != null) {
            String[] a = s.contains("..") ? s.split("\\.\\.") : s.split(",");
            try {
                lo = Double.parseDouble(a[0].trim());
                hi = Double.parseDouble(a[a.length - 1].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return lo + (hi - lo) * (Math.max(1, Math.min(10, h.level())) - 1) / 9.0;
    }

    static boolean roll(Level l, double percent) {
        return l.random.nextDouble() * 100 < percent;
    }

    /** Whole part plus a chance for the fraction. */
    static int round(Level l, double v) {
        int n = (int) Math.floor(v);
        return n + (l.random.nextDouble() < v - n ? 1 : 0);
    }

    static void fire(Player p, String event, @Nullable Vec3 pos) {
        Triggers.fire(p, TriggerType.CUSTOM, TriggerContext.of(Habits.id(event)).withPos(pos));
    }

    static void fireUnits(Player p, String event, double units) {
        Triggers.fire(p, TriggerType.CUSTOM_UNITS, TriggerContext.of(Habits.id(event)).withAmount(units));
    }

    static void give(Player p, ItemStack s) {
        if (!s.isEmpty() && !p.getInventory().add(s)) p.drop(s, false);
    }

    static Optional<Holder.Reference<MobEffect>> effect(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id.trim());
        return rl == null ? Optional.empty() : BuiltInRegistries.MOB_EFFECT.getHolder(ResourceKey.create(Registries.MOB_EFFECT, rl));
    }

    static void effect(net.minecraft.world.entity.LivingEntity e, Holder<MobEffect> eff, int ticks, int amp) {
        e.addEffect(new MobEffectInstance(eff, ticks, Math.max(0, amp), true, false, true));
    }

    /** Survives death (NeoForge copies the PlayerPersisted tag to the new player). */
    static CompoundTag data(Player p) {
        CompoundTag root = p.getPersistentData();
        if (!root.contains(Player.PERSISTED_NBT_TAG)) root.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }

    /** One entry of a weighted list "id*weight,id,..." (weight 1 when left out); EMPTY when nothing resolves. */
    static ItemStack pick(Level l, String list) {
        String[] parts = list.split(",");
        double total = 0;
        double[] w = new double[parts.length];
        Item[] items = new Item[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String[] iw = parts[i].trim().split("\\*");
            ResourceLocation rl = ResourceLocation.tryParse(iw[0].trim());
            items[i] = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
            try {
                w[i] = items[i] == null ? 0 : iw.length > 1 ? Double.parseDouble(iw[1].trim()) : 1;
            } catch (NumberFormatException e) {
                w[i] = 0;
            }
            total += w[i];
        }
        double r = l.random.nextDouble() * total;
        for (int i = 0; i < parts.length; i++) if (w[i] > 0 && (r -= w[i]) < 0) return new ItemStack(items[i]);
        return ItemStack.EMPTY;
    }

    static boolean matches(Level l, String spec, BlockPos pos) {
        IdMatch m = IdMatch.parse(spec);
        return m == null || l.getBiome(pos).unwrapKey().map(k -> m.test(k.location(), Registries.BIOME, l.registryAccess())).orElse(false);
    }

    static boolean blockMatches(Level l, String spec, net.minecraft.world.level.block.state.BlockState s) {
        IdMatch m = IdMatch.parse(spec);
        return m != null && m.test(BuiltInRegistries.BLOCK.getKey(s.getBlock()), Registries.BLOCK, l.registryAccess());
    }

    /** Stable per-entity roll that changes every {@code period} ticks, so a mob is calm or not, not flickering each tick. */
    static boolean steadyRoll(Entity e, Player p, double percent, long period) {
        long seed = e.getUUID().getLeastSignificantBits() ^ p.getUUID().getMostSignificantBits() ^ (e.level().getGameTime() / period) * 0x9E3779B97F4A7C15L;
        return Math.floorMod(Long.hashCode(seed * 0xBF58476D1CE4E5B9L), 100) < percent;
    }
}
