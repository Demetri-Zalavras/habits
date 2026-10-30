package com.habits.danger;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a spot sits on the danger scale. Dimensions listed in {@code dimensionMultipliers} are flat (Nether 5x/3x, End
 * 8x/4x by default); every other dimension ramps with flat distance from world spawn, from {@code safeRadius} to
 * {@code fullStrengthDistance}. Rank orders zones for the action bar and indexes the per-tier config lists:
 * 0 = Safe, 1..n = Frontier tiers, then the listed dimensions in order.
 */
public final class Zones {
    private Zones() {}

    /** {@code dim} is set for a listed (flat) dimension. */
    public record Zone(int rank, double health, double damage, @Nullable ResourceLocation dim) {
        public Component name() {
            if (dim != null) return Component.translatableWithFallback("danger.habits.dim." + dim.getNamespace() + "." + dim.getPath(), dim.toString());
            return Component.translatable("danger.habits.tier." + Math.min(rank, 3));
        }
    }

    private record Flat(ResourceLocation dim, double health, double damage) {}

    private static List<? extends String> parsedFrom;
    private static List<Flat> flats = List.of();

    private static synchronized List<Flat> flats() {
        List<? extends String> src = DangerInit.DIMENSIONS.get();
        if (src != parsedFrom) {
            List<Flat> out = new ArrayList<>();
            for (String s : src) {
                String[] kv = s.split("=");
                String[] hd = kv.length == 2 ? kv[1].split(",") : new String[0];
                ResourceLocation id = ResourceLocation.tryParse(kv[0].trim());
                if (id == null || hd.length != 2) continue;
                try {
                    out.add(new Flat(id, Double.parseDouble(hd[0].trim()), Double.parseDouble(hd[1].trim())));
                } catch (NumberFormatException ignored) {}
            }
            flats = out;
            parsedFrom = src;
        }
        return flats;
    }

    public static Zone at(ServerLevel level, double x, double z) {
        BlockPos spawn = level.getSharedSpawnPos();
        return of(level.dimension().location(), Math.sqrt((x - spawn.getX()) * (x - spawn.getX()) + (z - spawn.getZ()) * (z - spawn.getZ())));
    }

    /** {@code dist} = flat blocks from world spawn (ignored for listed dimensions). */
    public static Zone of(ResourceLocation dim, double dist) {
        List<? extends Number> starts = DangerInit.TIER_STARTS.get();
        List<Flat> f = flats();
        for (int i = 0; i < f.size(); i++)
            if (f.get(i).dim.equals(dim)) return new Zone(starts.size() + 1 + i, f.get(i).health, f.get(i).damage, dim);
        double t = ZoneMath.ramp(dist, DangerInit.SAFE_RADIUS.get(), DangerInit.RAMP_END.get());
        return new Zone(ZoneMath.tier(dist, starts), ZoneMath.mult(DangerInit.MAX_HEALTH.get(), t), ZoneMath.mult(DangerInit.MAX_DAMAGE.get(), t), null);
    }
}
