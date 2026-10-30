package com.habits.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All skills, loaded from {@code data/<ns>/habits_skills/*.json} on server start and /reload, then synced to clients
 * (login + reload). Same API on both sides. Lists are sorted by rarity, then id (binder order; core may refine); treat them as read-only.
 */
public final class SkillRegistry {
    private static final Logger LOG = LogUtils.getLogger();
    public static final String DIRECTORY = "habits_skills";

    private static volatile Map<ResourceLocation, SkillDef> byId = Map.of();
    private static volatile Map<Category, List<SkillDef>> byCategory = Map.of();
    private static volatile Map<TriggerType, List<SkillDef>> byTrigger = Map.of();
    /** CUSTOM / CUSTOM_UNITS skills by event id (each list also holds that type's catch-all skills); key null = catch-alls only. */
    private static volatile Map<TriggerType, Map<ResourceLocation, List<SkillDef>>> byEvent = Map.of();

    private SkillRegistry() {}

    /** Bumped whenever {@link #get} can answer differently (reload, sync, test fixture): drops cached perk indexes. */
    private static volatile int generation;

    static int generation() {
        return generation;
    }

    /** Hand-made defs for GameTests only: resolvable by {@link #get}, never listed (all/byCategory/byTrigger), never
     * rolled, never synced. Survives /reload. */
    private static final Map<ResourceLocation, SkillDef> TEST_FIXTURES = new java.util.concurrent.ConcurrentHashMap<>();

    @Nullable
    public static SkillDef get(ResourceLocation id) {
        SkillDef d = byId.get(id);
        return d != null || TEST_FIXTURES.isEmpty() ? d : TEST_FIXTURES.get(id);
    }

    /** GameTests only: make {@code def} resolvable by id (see {@link #TEST_FIXTURES}). Returns it. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static SkillDef testFixture(SkillDef def) {
        TEST_FIXTURES.put(def.id(), def);
        generation++;
        return def;
    }

    public static Collection<SkillDef> all() {
        return byId.values();
    }

    public static List<SkillDef> byCategory(Category category) {
        return byCategory.getOrDefault(category, List.of());
    }

    public static List<SkillDef> byTrigger(TriggerType type) {
        return byTrigger.getOrDefault(type, List.of());
    }

    /** The skills worth rolling for this event: for CUSTOM types only those listening to {@code target} (plus skills
     * with no event filter or a tag filter), otherwise {@link #byTrigger}. No allocation. */
    public static List<SkillDef> candidates(TriggerType type, @Nullable ResourceLocation target) {
        Map<ResourceLocation, List<SkillDef>> events = byEvent.get(type);
        if (events == null) return byTrigger(type);
        List<SkillDef> l = target == null ? null : events.get(target);
        return l != null ? l : events.getOrDefault(null, List.of());
    }

    /** Replace every definition (loader on the server, sync payload on the client). */
    static void replaceAll(Collection<SkillDef> defs) {
        List<SkillDef> sorted = new ArrayList<>(defs);
        sorted.sort(Comparator.comparing(SkillDef::rarity).thenComparing(d -> d.id().toString()));
        Map<ResourceLocation, SkillDef> ids = new LinkedHashMap<>();
        Map<Category, List<SkillDef>> cats = new EnumMap<>(Category.class);
        Map<TriggerType, List<SkillDef>> trig = new EnumMap<>(TriggerType.class);
        for (SkillDef d : sorted) {
            ids.put(d.id(), d);
            cats.computeIfAbsent(d.category(), c -> new ArrayList<>()).add(d);
            trig.computeIfAbsent(d.trigger().type(), t -> new ArrayList<>()).add(d);
        }
        Map<TriggerType, Map<ResourceLocation, List<SkillDef>>> events = new EnumMap<>(TriggerType.class);
        for (TriggerType t : List.of(TriggerType.CUSTOM, TriggerType.CUSTOM_UNITS)) {
            List<SkillDef> catchAll = new ArrayList<>();
            Map<ResourceLocation, List<SkillDef>> m = new java.util.HashMap<>();
            for (SkillDef d : trig.getOrDefault(t, List.of())) {
                var match = d.trigger().match();
                if (match.isEmpty() || !match.get().tags().isEmpty()) catchAll.add(d);
                else for (ResourceLocation id : match.get().ids()) m.computeIfAbsent(id, k -> new ArrayList<>()).add(d);
            }
            m.values().forEach(l -> l.addAll(catchAll));
            m.put(null, catchAll);
            events.put(t, m);
        }
        byId = ids;
        byCategory = cats;
        byTrigger = trig;
        byEvent = events;
        generation++;
    }

    static void addReloadListener(AddReloadListenerEvent e) {
        e.addListener(new Loader());
    }

    static void sync(OnDatapackSyncEvent e) {
        var payload = new Payloads.SyncSkills(List.copyOf(all()));
        e.getRelevantPlayers().forEach(p -> PacketDistributor.sendToPlayer(p, payload));
    }

    private static final class Loader extends SimpleJsonResourceReloadListener {
        Loader() {
            super(new Gson(), DIRECTORY);
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
            List<SkillDef> defs = new ArrayList<>();
            files.forEach((id, json) -> {
                if (!json.isJsonObject()) { LOG.error("[habits] skill {}: not a JSON object", id); return; }
                JsonObject o = json.getAsJsonObject().deepCopy();
                o.addProperty("id", id.toString());
                SkillDef.CODEC.parse(JsonOps.INSTANCE, o)
                        .resultOrPartial(err -> LOG.error("[habits] skill {}: {}", id, err))
                        .ifPresent(d -> {
                            d.perk().mechanic().filter(m -> PerkMechanics.get(m) == null)
                                    .ifPresent(m -> LOG.warn("[habits] skill {}: unknown perk mechanic {} (kept, does nothing yet)", id, m));
                            defs.add(d);
                        });
            });
            replaceAll(defs);
            LOG.info("[habits] loaded {} skills", defs.size());
        }
    }
}
