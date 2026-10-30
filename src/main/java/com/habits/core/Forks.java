package com.habits.core;

import com.google.common.collect.MapMaker;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Skill forks ({@link SkillDef.ForkStep}): from a step's level the player may pick one of its options (free to swap
 * in the book); until they do, the skill works unforked. Choices live in {@link CoreRegistries#FORKS} as {@code "<skill>@<level>" -> option id}.
 * {@link #apply} folds the chosen options into the def every perk read sees (progression's {@code Effects.def} calls it
 * first, so sigils and charms boost the forked values).
 */
public final class Forks {
    private Forks() {}

    /** Per player: skill id -> {registry def, choices map, level, forked def}. Weak keys: dies with the player. */
    private static final Map<Player, Map<ResourceLocation, Object[]>> CACHE = new MapMaker().weakKeys().makeMap();

    static String key(ResourceLocation skill, int level) {
        return skill + "@" + level;
    }

    public static Map<String, String> choices(Player p) {
        if (!HabitsApi.connected(p)) return p.getExistingDataOrNull(CoreRegistries.FORKS) instanceof Map<String, String> m ? m : Map.of();
        return p.getData(CoreRegistries.FORKS);
    }

    /** The option picked for a step, or null while none is (the skill then works unforked). */
    @Nullable
    public static SkillDef.ForkOption chosen(Player p, SkillDef def, SkillDef.ForkStep step) {
        String id = choices(p).get(key(def.id(), step.level()));
        for (SkillDef.ForkOption o : step.options()) if (o.id().equals(id)) return o;
        return null;
    }

    /** {@code def} with every reached step's option applied (values scaled, params overridden); {@code def} itself when
     * it has no forks or none is reached yet. */
    public static SkillDef apply(Player p, @Nullable SkillDef def) {
        if (def == null || def.forks().isEmpty()) return def;
        int level = HabitsApi.collection(p).get(def.id()).level();
        Map<String, String> ch = choices(p);
        Map<ResourceLocation, Object[]> mine = CACHE.computeIfAbsent(p, k -> new java.util.concurrent.ConcurrentHashMap<>());
        Object[] c = mine.get(def.id());
        if (c != null && c[0] == def && c[1] == ch && (int) c[2] == level) return (SkillDef) c[3];
        double scale = 1;
        Map<String, String> params = null;
        for (SkillDef.ForkStep step : def.forks()) {
            if (level < step.level() || step.options().isEmpty()) continue;
            SkillDef.ForkOption o = chosen(p, def, step);
            if (o == null) continue;
            scale *= o.scale();
            if (!o.params().isEmpty()) {
                if (params == null) params = new LinkedHashMap<>(def.perk().params());
                params.putAll(o.params());
            }
        }
        SkillDef out = def;
        if (scale != 1 || params != null) {
            PerkSpec s = def.perk();
            double f = scale;
            out = def.withPerk(new PerkSpec(s.type(), s.mechanic(), params == null ? s.params() : params, s.base() * f, s.perLevel() * f,
                    s.values().map(l -> l.stream().map(v -> v * f).toList())));
        }
        mine.put(def.id(), new Object[]{def, ch, level, out});
        return out;
    }

    /** {@link Payloads.SetFork}: picks {@code option} for the step at {@code level} of an owned skill that reached it. */
    static boolean set(ServerPlayer p, ResourceLocation skill, int level, String option) {
        SkillDef def = SkillRegistry.get(skill);
        if (def == null || HabitsApi.collection(p).get(skill).level() < level) return false;
        Optional<SkillDef.ForkStep> step = def.forks().stream().filter(s -> s.level() == level).findFirst();
        if (step.isEmpty() || step.get().options().stream().noneMatch(o -> o.id().equals(option))) return false;
        Map<String, String> m = new HashMap<>(choices(p));
        m.put(key(skill, level), option);
        p.setData(CoreRegistries.FORKS, Map.copyOf(m));
        HabitsApi.refreshPerks(p);
        return true;
    }
}
