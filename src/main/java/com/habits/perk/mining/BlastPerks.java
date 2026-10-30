package com.habits.perk.mining;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** TNT you light: shaped tunnels, bigger blasts, cluster charges. Data rides on the primed TNT entity. */
public final class BlastPerks {
    private BlastPerks() {}

    static final String SHAPED = "habits_shaped", MINI = "habits_mini";
    /** Live mini charges per player are capped by counting nearby ones before splitting. */
    static final int MAX_MINIS_NEARBY = 24;

    /** Our own re-explosions must not be re-handled. */
    private static boolean reentry;

    @SubscribeEvent
    public static void onTntJoin(EntityJoinLevelEvent e) {
        if (!(e.getEntity() instanceof PrimedTnt tnt) || e.getLevel().isClientSide() || tnt.getPersistentData().getBoolean(MINI)) return;
        if (!(tnt.getOwner() instanceof ServerPlayer p) || !MiningMechanics.allowed(p) || e.loadedFromDisk()) return;
        BreakPerks.fire(p, "tnt_lit", tnt.blockPosition());
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "shaped_charge");
        if (h != null && p.isShiftKeyDown()) {
            var tag = tnt.getPersistentData();
            tag.putInt(SHAPED, Direction.getNearest(p.getLookAngle()).get3DDataValue());
            tag.putInt(SHAPED + "_depth", (int) h.value());
            tag.putInt(SHAPED + "_width", (int) Math.round(MiningMechanics.lerp(h, "width", 2, 3)));
        }
    }

    @SubscribeEvent
    public static void onStart(ExplosionEvent.Start e) {
        if (reentry || !(e.getExplosion().getDirectSourceEntity() instanceof PrimedTnt tnt) || !(e.getLevel() instanceof ServerLevel level)) return;
        float power;
        if (tnt.getPersistentData().getBoolean(MINI)) {
            power = tnt.getPersistentData().getFloat(MINI + "_power");
        } else {
            if (!(tnt.getOwner() instanceof ServerPlayer p) || tnt.getPersistentData().contains(SHAPED)) return;
            HabitsApi.PerkHit h = MiningMechanics.hit(p, "demolitionist");
            if (h == null || h.value() <= e.getExplosion().radius()) return;
            power = (float) h.value();
        }
        e.setCanceled(true);
        Vec3 c = e.getExplosion().center();
        reentry = true;
        try {
            level.explode(tnt, Explosion.getDefaultDamageSource(level, tnt), null, c.x, c.y, c.z, power, false, Level.ExplosionInteraction.TNT);
        } finally {
            reentry = false;
        }
    }

    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate e) {
        if (!(e.getExplosion().getDirectSourceEntity() instanceof PrimedTnt tnt) || !(e.getLevel() instanceof ServerLevel level)) return;
        var tag = tnt.getPersistentData();
        if (tag.contains(SHAPED)) {
            List<BlockPos> tunnel = tunnel(level, tnt.blockPosition(), Direction.from3DDataValue(tag.getInt(SHAPED)),
                    tag.getInt(SHAPED + "_width"), tag.getInt(SHAPED + "_depth"));
            e.getAffectedBlocks().clear();
            e.getAffectedBlocks().addAll(tunnel);
        }
        if (!(tnt.getOwner() instanceof ServerPlayer p) || tag.getBoolean(MINI)) return;
        long broken = e.getAffectedBlocks().stream().filter(b -> !level.getBlockState(b).isAir()).count();
        if (broken >= 20) BreakPerks.fire(p, "tnt_big_blast", tnt.blockPosition());
        HabitsApi.PerkHit h = MiningMechanics.hit(p, "cluster_bomb");
        if (h == null) return;
        int nearby = level.getEntitiesOfClass(PrimedTnt.class, tnt.getBoundingBox().inflate(32), t -> t.getPersistentData().getBoolean(MINI)).size();
        int n = Math.min((int) h.value(), MAX_MINIS_NEARBY - nearby);
        float power = (float) MiningMechanics.lerp(h, "mini_power", 1.5, 1.5);
        for (int i = 0; i < n; i++) {
            PrimedTnt mini = new PrimedTnt(level, tnt.getX(), tnt.getY() + 0.5, tnt.getZ(), p);
            double a = level.getRandom().nextDouble() * Math.PI * 2;
            mini.setDeltaMovement(Math.cos(a) * 0.5, 0.5 + level.getRandom().nextDouble() * 0.3, Math.sin(a) * 0.5);
            mini.setFuse(15 + level.getRandom().nextInt(15));
            mini.getPersistentData().putBoolean(MINI, true);
            mini.getPersistentData().putFloat(MINI + "_power", power);
            level.addFreshEntity(mini);
        }
    }

    /** A width x width square tunnel running {@code depth} blocks from {@code start} toward {@code dir}. Skips air,
     * fluids and anything as hard as obsidian or unbreakable. */
    static List<BlockPos> tunnel(ServerLevel level, BlockPos start, Direction dir, int width, int depth) {
        Set<BlockPos> out = new LinkedHashSet<>();
        Direction.Axis ax = dir.getAxis();
        int lo = -(width - 1) / 2, hi = width / 2;
        for (int d = 0; d < depth; d++) {
            BlockPos c = start.relative(dir, d);
            for (int a = lo; a <= hi; a++)
                for (int b = lo; b <= hi; b++) {
                    BlockPos q = switch (ax) {
                        case X -> c.offset(0, a, b);
                        case Y -> c.offset(a, 0, b);
                        case Z -> c.offset(a, b, 0);
                    };
                    if (!level.isLoaded(q)) continue;
                    BlockState s = level.getBlockState(q);
                    if (BreakPerks.boreable(level, s) || (!s.isAir() && s.getFluidState().isEmpty() && s.getBlock().getExplosionResistance() < 1200
                            && s.getDestroySpeed(level, q) >= 0)) out.add(q);
                }
        }
        return new ArrayList<>(out);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        var src = e.getSource();
        if (!src.is(DamageTypeTags.IS_EXPLOSION) || !(src.getEntity() instanceof ServerPlayer p)) return;
        if (src.getDirectEntity() instanceof PrimedTnt || src.getDirectEntity() instanceof MinecartTNT)
            BreakPerks.fire(p, "tnt_kill", e.getEntity().blockPosition());
    }
}
