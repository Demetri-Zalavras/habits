package com.habits.progression;

import com.google.common.collect.MapMaker;
import com.habits.Habits;
import com.habits.core.Category;
import com.habits.core.HabitsApi;
import com.habits.core.Leveling;
import com.habits.core.PerkMechanic;
import com.habits.core.PerkMechanics;
import com.habits.core.PerkSpec;
import com.habits.core.SkillDef;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * What sigils and charms do. Perk reads go through {@link #def}: the skill as this player sees it (value times Potent
 * plus charm perks, cooldown params and wait values shortened by Swift, radius and range params grown by Reach). Core's perk read index
 * and the perk packages' own snapshots call it when they rebuild, so every mechanic gets the boost. A change to sockets
 * or charms makes core rebuild those caches ({@link HabitsApi#refreshPerks}).
 */
public final class Effects {
    private Effects() {}

    static void init(IEventBus bus) {
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> { if (e.getEntity() instanceof ServerPlayer sp) applyAttributes(sp); });
        bus.addListener((PlayerEvent.PlayerRespawnEvent e) -> { if (e.getEntity() instanceof ServerPlayer sp) applyAttributes(sp); });
    }

    public static ProgressionData data(Player p) {
        if (!com.habits.core.HabitsApi.connected(p)) // see HabitsApi.collection: no attachment creation before login
            return p.getExistingDataOrNull(ProgRegistries.DATA) instanceof ProgressionData d ? d : ProgressionData.EMPTY;
        return p.getData(ProgRegistries.DATA);
    }

    /** Per data instance: charm sums by category and the boosted defs built so far. Weak identity keys. */
    private static final class Derived {
        final double[] catPerks = new double[Category.values().length];
        final double[] catDrops = new double[Category.values().length];
        /** registry def -> boosted def (identity of the registry def guards against reloads) */
        final Map<ResourceLocation, SkillDef[]> defs = new ConcurrentHashMap<>();
        /** The picks this was built for: charms outside them do nothing. */
        final com.habits.core.Specs specs;

        Derived(ProgressionData d, Player p) {
            specs = com.habits.core.Specs.of(p);
            for (ProgressionData.Equipped e : d.charms()) {
                if (!com.habits.core.Specs.allows(p, e.charm().category())) continue;
                int c = e.charm().category().ordinal();
                catPerks[c] += e.charm().sum(CharmData.LineKind.PERKS);
                catDrops[c] += e.charm().sum(CharmData.LineKind.DROPS);
            }
        }
    }

    private static final Map<ProgressionData, Derived> DERIVED = new MapMaker().weakKeys().makeMap();

    @Nullable
    private static Derived derived(Player p) {
        ProgressionData d = data(p);
        if (d.isEmpty()) return null;
        Derived x = DERIVED.get(d);
        if (x == null || !x.specs.equals(com.habits.core.Specs.of(p))) DERIVED.put(d, x = new Derived(d, p));
        return x;
    }

    /** Summed percent of one sigil type on a card. */
    public static double sigilPercent(ProgressionData d, ResourceLocation skill, SigilType type) {
        double s = 0;
        for (ProgressionData.Socketed x : d.socketed(skill)) if (x.sigil().type() == type) s += x.sigil().percent();
        return s;
    }

    // ------------------------------------------------------------------ multipliers (both sides)

    /** Perk value multiplier: 1 + (Potent % + charm category perks %) / 100. */
    public static double perkMultiplier(Player p, SkillDef def) {
        Derived x = derived(p);
        if (x == null) return 1;
        return 1 + (sigilPercent(data(p), def.id(), SigilType.POTENT) + x.catPerks[def.category().ordinal()]) / 100.0;
    }

    /** Cooldown param factor from Swift (0.7 = 30% shorter), capped by config. */
    public static double cooldownMultiplier(Player p, SkillDef def) {
        ProgressionData d = data(p);
        return d.isEmpty() ? 1 : PMath.cooldownFactor(sigilPercent(d, def.id(), SigilType.SWIFT), ProgressionInit.SWIFT_CAP.get());
    }

    /** Radius and range param factor from Reach. */
    public static double rangeMultiplier(Player p, SkillDef def) {
        ProgressionData d = data(p);
        return d.isEmpty() ? 1 : 1 + sigilPercent(d, def.id(), SigilType.REACH) / 100.0;
    }

    /** Card drop chance multiplier: Lucky on that card times charm "category card drops". Runs per trigger roll, so the
     * common case (nothing socketed or equipped) is one attachment read. */
    public static double dropMultiplier(Player p, SkillDef def) {
        Derived x = derived(p);
        if (x == null) return 1;
        return (1 + sigilPercent(data(p), def.id(), SigilType.LUCKY) / 100.0) * (1 + x.catDrops[def.category().ordinal()] / 100.0);
    }

    /** The skill as this player's perks see it; {@code def} itself when nothing changes it. Null in, null out. */
    public static SkillDef def(Player p, @Nullable SkillDef def) {
        def = com.habits.core.Forks.apply(p, def); // core: the player's fork options first, so boosts scale the forked values
        if (def == null) return null;
        Derived x = derived(p);
        if (x == null) return def;
        SkillDef[] cached = x.defs.get(def.id());
        if (cached != null && cached[0] == def) return cached[1];
        SkillDef boosted = boost(def, perkMultiplier(p, def), cooldownMultiplier(p, def), rangeMultiplier(p, def));
        x.defs.put(def.id(), new SkillDef[]{def, boosted});
        return boosted;
    }

    /** A hit with the boosted def and value (mining's direct fast path). Null in, null out. */
    @Nullable
    public static HabitsApi.PerkHit hit(Player p, @Nullable HabitsApi.PerkHit h) {
        if (h == null) return null;
        SkillDef d = def(p, h.skill());
        return d == h.skill() ? h : new HabitsApi.PerkHit(d, h.level(), d.perkValue(h.level()));
    }

    static SkillDef boost(SkillDef def, double perk, double cooldown, double range) {
        if (perk == 1 && cooldown == 1 && range == 1) return def;
        PerkSpec s = def.perk();
        Map<String, String> params = s.params();
        if (cooldown != 1 || range != 1) {
            params = new LinkedHashMap<>(params);
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (cooldown != 1 && PMath.isCooldownKey(e.getKey())) e.setValue(PMath.scaleParam(e.getValue(), cooldown));
                else if (range != 1 && PMath.isRangeKey(e.getKey())) e.setValue(PMath.scaleParam(e.getValue(), range));
            }
        }
        // values that fall with level (cooldowns, costs) are better lower, so Potent shrinks them instead; Swift also
        // shrinks a value that is itself a wait (last_stand's minutes), so both shorten it
        double f = (lowerBetter(s) ? 1 / perk : perk) * (timeValue(def) ? cooldown : 1);
        // skill JSON "value_max": the most a boosted value may reach (slot counts, % cuts that break at 100)
        String maxParam = s.params().get("value_max");
        double max = maxParam == null ? Double.POSITIVE_INFINITY : Double.parseDouble(maxParam);
        Optional<List<Double>> values = maxParam == null ? s.values().map(l -> l.stream().map(v -> v * f).toList())
                : Optional.of(java.util.stream.IntStream.rangeClosed(1, Leveling.MAX_LEVEL).mapToObj(l -> Math.min(max, s.valueAt(l) * f)).toList());
        PerkSpec spec = new PerkSpec(s.type(), s.mechanic(), params, s.base() * f, s.perLevel() * f, values);
        return def.withPerk(spec);
    }

    private static boolean lowerBetter(PerkSpec s) {
        return s.values().map(l -> l.size() > 1 && l.get(l.size() - 1) < l.get(0)).orElse(s.perLevel() < 0);
    }

    private static final Pattern TIME = Pattern.compile("cooldown|second|minute");

    /** True when the perk value is a wait Swift should shorten: it falls with level and its mechanic's meaning is a
     * time ("cooldown in minutes", "seconds between recalls"). Falling costs and counts ("hits per gust") are not. */
    static boolean timeValue(SkillDef def) {
        PerkSpec s = def.perk();
        if (!lowerBetter(s)) return false;
        PerkMechanic m = s.mechanic().map(PerkMechanics::get).orElse(null);
        return m != null && TIME.matcher(m.valueMeaning()).find();
    }

    // ------------------------------------------------------------------ charm stats (server)

    private static final ResourceLocation[] SLOT_IDS = new ResourceLocation[5];

    static {
        for (int i = 0; i < SLOT_IDS.length; i++) SLOT_IDS[i] = Habits.id("charm_slot_" + i);
    }

    /** Re-applies every equipped charm's stat line as transient modifiers keyed by slot. */
    static void applyAttributes(ServerPlayer p) {
        java.util.Set<Holder<Attribute>> all = new java.util.HashSet<>();
        for (Category c : Category.values()) all.add(CharmData.statAttribute(c));
        for (Holder<Attribute> a : all) {
            AttributeInstance inst = p.getAttribute(a);
            if (inst != null) for (ResourceLocation id : SLOT_IDS) inst.removeModifier(id);
        }
        for (ProgressionData.Equipped e : data(p).charms()) {
            if (e.slot() < 0 || e.slot() >= SLOT_IDS.length) continue;
            double v = e.charm().sum(CharmData.LineKind.STAT);
            Category c = e.charm().category();
            if (v <= 0 || !com.habits.core.Specs.allows(p, c)) continue;
            AttributeInstance inst = p.getAttribute(CharmData.statAttribute(c));
            if (inst != null) inst.addOrUpdateTransientModifier(new AttributeModifier(SLOT_IDS[e.slot()],
                    CharmData.statAmount(c, v), CharmData.statOperation(c)));
        }
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    /** After any socket or charm change: rebuild perk caches and charm stats. */
    static void changed(ServerPlayer p) {
        HabitsApi.refreshPerks(p);
        applyAttributes(p);
    }
}
