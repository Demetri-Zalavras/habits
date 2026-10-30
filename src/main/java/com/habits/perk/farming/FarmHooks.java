package com.habits.perk.farming;

import com.habits.core.HabitsApi.PerkHit;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * What the farming mixins ({@code com.habits.perk.farming.mixin}) call. Every hook returns at once when no player with
 * the skill is involved; docs/mechanics/farming-nature.md lists each one with its per-call cost.
 */
public final class FarmHooks {
    private FarmHooks() {}

    // ---------------------------------------------------------------- lava angler

    /** FishingHook.tick asks "is this water?": lava says yes for a lava angler's bobber. */
    public static boolean lavaFloats(FishingHook hook, FluidState f) {
        return f.is(FluidTags.LAVA) && Farm.hit(hook.getPlayerOwner(), "lava_angler") != null;
    }

    // ---------------------------------------------------------------- leash master

    /** Mob.leashTooFarBehaviour (past vanilla's 10 blocks): within the skill's range keep pulling instead of snapping. */
    public static boolean keepLeash(Mob m) {
        if (!(m.getLeashHolder() instanceof Player p)) return false;
        PerkHit h = Farm.hit(p, "leash_master");
        if (h == null) return false;
        float d = m.distanceTo(p);
        if (d > h.value()) return false;
        m.elasticRangeLeashBehaviour(p, d);
        m.checkSlowFallDistance();
        return true;
    }

    // ---------------------------------------------------------------- cat person

    /** Phantom swoop goal: within range of a cat person the phantom breaks off, like vanilla does near a cat. */
    public static boolean phantomScared(Phantom ph, @Nullable LivingEntity target) {
        if (!(target instanceof Player p)) return false;
        PerkHit h = Farm.hit(p, "cat_person");
        return h != null && ph.distanceTo(p) <= h.value();
    }

    // ---------------------------------------------------------------- beekeeper

    /** A bee just delivered nectar to the hive at pos: the best beekeeper in range adds honey levels on top. */
    public static void beeReturned(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel l)) return;
        double fill = 0;
        for (Player p : l.players()) {
            PerkHit h = Farm.hit(p, "beekeeper");
            if (h != null && p.blockPosition().distSqr(pos) <= h.value() * h.value()) fill = Math.max(fill, Farm.lerp(h, "fill", 0.25, 2));
        }
        if (fill <= 0) return;
        BlockState s = l.getBlockState(pos);
        if (!s.hasProperty(BeehiveBlock.HONEY_LEVEL)) return;
        int honey = s.getValue(BeehiveBlock.HONEY_LEVEL), add = Farm.round(l, fill);
        if (add > 0 && honey < BeehiveBlock.MAX_HONEY_LEVELS)
            l.setBlockAndUpdate(pos, s.setValue(BeehiveBlock.HONEY_LEVEL, Math.min(BeehiveBlock.MAX_HONEY_LEVELS, honey + add)));
    }

    // ---------------------------------------------------------------- sneeze harvest

    static final String SNEEZE = "habits_sneeze", SNEEZE_RICH = "habits_sneeze_rich", SNEEZE_UNTIL = "habits_sneeze_until";

    /** Set by the owner's tick on pandas in range, read by the panda hooks (a hash lookup, no player search). */
    static void stampPanda(Panda pd, PerkHit h) {
        CompoundTag t = pd.getPersistentData();
        if (t.getLong(SNEEZE_UNTIL) > pd.level().getGameTime() && t.getDouble(SNEEZE) > h.value()) return; // a better owner is near
        t.putDouble(SNEEZE, h.value());
        t.putDouble(SNEEZE_RICH, Farm.lerp(h, "rich", 0, 100));
        t.putLong(SNEEZE_UNTIL, pd.level().getGameTime() + 40);
    }

    @Nullable
    static CompoundTag stamp(Panda pd) {
        CompoundTag t = pd.getPersistentData();
        return t.getLong(SNEEZE_UNTIL) > pd.level().getGameTime() ? t : null;
    }

    /**
     * PandaSneezeGoal.canUse said no: near a sneeze harvester, roll vanilla's baby chance (1 in 3000 goal ticks) times the
     * multiplier, minus the roll vanilla already made. Adults sneeze too, at the full multiplied rate.
     */
    public static boolean extraSneeze(Panda pd) {
        CompoundTag t = stamp(pd);
        if (t == null || !pd.canPerformAction()) return false;
        double extra = t.getDouble(SNEEZE) - (pd.isBaby() ? 1 : 0);
        return pd.getRandom().nextDouble() * 3000 < extra;
    }

    /** Panda.afterSneeze: goodies from our tables (the rich table by chance). */
    public static void sneezed(Panda pd) {
        if (!(pd.level() instanceof ServerLevel l)) return;
        CompoundTag t = stamp(pd);
        if (t == null) return;
        boolean rich = Farm.roll(l, t.getDouble(SNEEZE_RICH));
        for (ItemStack it : Farm.loot(l, Farm.table(rich ? "farming/panda_sneeze_rich" : "farming/panda_sneeze"), pd.position(), null))
            pd.spawnAtLocation(it);
    }

    // ---------------------------------------------------------------- ghost rider

    static int ghostTier(Entity mount) {
        if (!(mount.getControllingPassenger() instanceof Player p)) return 0;
        PerkHit h = Farm.hit(p, "ghost_rider");
        return h == null ? 0 : Farm.tier(h);
    }

    /** LivingEntity.canStandOnFluid: a ridden mount stands on still water (tier 2) and lava (tier 3), like a strider. */
    public static boolean standsOn(LivingEntity mount, FluidState f) {
        if (f.isEmpty()) return false;
        int tier = ghostTier(mount);
        return tier >= 2 && f.is(FluidTags.WATER) || tier >= 3 && f.is(FluidTags.LAVA);
    }

    /** Entity.lavaHurt: a lava angler's bobber and a tier 3 mount do not burn (the mount pays 1 damage per 2 seconds in its tick). */
    public static boolean lavaProof(Entity e) {
        if (e instanceof FishingHook hook) return Farm.hit(hook.getPlayerOwner(), "lava_angler") != null;
        return ghostTier(e) >= 3;
    }

    // ---------------------------------------------------------------- shoulder lookout (client)

    /** Client only: entity id -> game time the outline ends. The server refreshes it every 10 ticks. */
    static final java.util.Map<Integer, Long> OUTLINED = new java.util.HashMap<>();

    static void outline(List<Integer> ids, long now) {
        OUTLINED.values().removeIf(t -> t < now);
        for (int id : ids) OUTLINED.put(id, now + 15);
    }

    /** Minecraft.shouldEntityAppearGlowing: true for mobs the lookout outlined for us (other players never see it). */
    public static boolean lookoutGlow(Entity e) {
        if (OUTLINED.isEmpty()) return false;
        Long until = OUTLINED.get(e.getId());
        return until != null && until >= e.level().getGameTime();
    }

    /** On logout: the next world's game time can be lower, so stale ids would outline unrelated mobs there. */
    public static void clearLookout() {
        OUTLINED.clear();
    }

    // ---------------------------------------------------------------- allay pal

    /** Fetch range multiplier for an allay whose liked player has the skill, 1 otherwise. */
    public static double allayRange(Mob m) {
        if (!(m instanceof Allay a) || !(a.level() instanceof ServerLevel l)) return 1;
        var liked = a.getBrain().getMemory(MemoryModuleType.LIKED_PLAYER);
        if (liked.isEmpty()) return 1;
        PerkHit h = Farm.hit(l.getPlayerByUUID(liked.get()), "allay_pal");
        return h == null ? 1 : Farm.lerp(h, "range", 1.5, 3);
    }

    /** NearestItemSensor for a pal's allay: vanilla's search (32 x 16 blocks) times the range multiplier. */
    public static boolean allaySense(ServerLevel l, Mob m) {
        double r = allayRange(m);
        if (r <= 1) return false;
        List<ItemEntity> list = l.getEntitiesOfClass(ItemEntity.class, m.getBoundingBox().inflate(32 * r, 16 * r, 32 * r), e -> true);
        list.sort(Comparator.comparingDouble(m::distanceToSqr));
        m.getBrain().setMemory(MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM, list.stream()
                .filter(e -> m.wantsToPickUp(e.getItem()))
                .filter(e -> e.closerThan(m, 32 * r))
                .filter(m::hasLineOfSight)
                .findFirst());
        return true;
    }

    /** Most an allay can walk to an item: vanilla 32 times the Lv10 multiplier (the sensor already limits normal allays to 32). */
    public static final int ALLAY_MAX_WALK = 96;
}
