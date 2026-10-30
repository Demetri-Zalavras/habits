package com.habits.perk.survival;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.PerkMechanic;
import com.habits.core.PerkMechanics;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Every survival mechanic, one per skill. Mechanic id {@code habits:survival/<name>}. Each mechanic's primary value is the
 * skill's perk value; extra numbers come from perk params, read with {@link #p} ("a..b" = linear Lv1..Lv10, "a,b,...,j" =
 * one per level, plain number = constant).
 * <p>Reads go through a per-player snapshot rebuilt only when the collection or the skill registry changes, so a damage
 * handler costs one weak-map lookup plus an array read, with no allocation.
 */
public enum Sk {
    RETURN_TO_SENDER("chance in % a mob projectile bounces back"),
    PINCUSHION("chance in % to catch an arrow"),
    ROCKET_JUMPER("own explosion damage cut in %"),
    LAVA_WALKER("lava damage cut in %"),
    WALL_PHASER("suffocation damage cut in %"),
    THUNDERSTRUCK("lightning damage cut in %"),
    VOID_BOUNCE("cooldown in minutes"),
    LAST_STAND("cooldown in minutes"),
    NINTH_LIFE("cooldown in minutes"),
    PHYLACTERY("lich shields"),
    BLINK_SNACKER("chance in % chorus lands where you look"),
    BITTER_TASTE("chance in % a bad potion flips"),
    FEATHERFALL("fall damage cut in %"),
    BAD_DREAMS("bed/anchor blast damage cut in %"),
    CACTUS_SKIN("cactus damage cut in %"),
    CAMP_COOK("campfire damage cut in %"),
    ELBOW_ROOM("cramming damage cut in %"),
    BREATH_BOTTLER("dragon breath damage cut in %"),
    GILLS("air lasts this many times longer"),
    BLAST_BRACED("explosion damage cut in %"),
    HARD_HAT("falling block damage cut in %"),
    DRIP_DODGER("stalactite damage cut in %"),
    SPIKE_LANDER("stalagmite damage cut in %"),
    GRAND_FINALE("firework damage cut in %"),
    CRUMPLE_ZONE("wall crash damage cut in %"),
    SNOW_BORN("freeze damage cut in %"),
    MAGMA_SOLES("hot floor damage cut in %"),
    FLAME_DANCER("fire block damage cut in %"),
    EMBER_SKIN("burn time cut in %"),
    HEX_WARD("indirect magic damage cut in %"),
    ANTIVENOM("poison time cut in %"),
    THICK_HIDE("chance in % to shrug off a mob hit"),
    GOAT_FOOTING("goat ram knockback cut in %"),
    BULLET_SURFER("levitation time cut in %"),
    EDGE_WALKER("world border damage cut in %"),
    SOUND_PROOF("sonic boom damage cut in %"),
    SPIT_SHINE("llama spit damage cut in %"),
    IRON_STOMACH("health starving stops at"),
    BEEKEEPERS_HIDE("sting damage cut in %"),
    BRAMBLE_WALKER("berry bush damage cut in %"),
    THORN_PROOF("thorns damage cut in %"),
    SNOWBALL_CATCHER("chance in % to catch a snowball or egg"),
    TRIDENT_CATCHER("chance in % to keep a trident"),
    WIND_RIDER("wind blast launch multiplier"),
    WITHER_WARD("wither time cut in %"),
    SKULL_JUGGLER("chance in % a wither skull bounces back"),
    SECOND_WIND("cooldown in seconds"),
    NATURAL_HEALER("natural regen faster by %"),
    BIG_EATER("extra saturation in %"),
    SLOW_BURN("exhaustion cut in %"),
    THICK_BLOOD("extra hearts"),
    ABSORBER("absorption lasts % longer"),
    HARDY("extra hurt immunity ticks"),
    PAIN_FUEL("% of damage taken stored as rage"),
    BUTCHERS_BELLY("strength seconds"),
    RAW_DEAL("chance in % raw food skips Hunger"),
    FISHERMANS_DIET("water breathing seconds"),
    PUFFER_PROOF("pufferfish poison cut in %"),
    GHOUL_GUT("chance in % rotten flesh skips Hunger"),
    SPIDER_SENSE("sense radius in blocks"),
    BAD_POTATO("chance in % poisonous potato is safe"),
    BREAD_WINNER("health healed by bread"),
    ROOT_CELLAR("haste seconds"),
    GOLDEN_CARROT_EYES("night vision seconds"),
    ORCHARD_SNACKER("next eat time cut in %"),
    GOLDEN_GLUTTON("extra absorption hearts"),
    NOTCHS_BLESSING("insurance seconds"),
    STEW_SIPPER("regeneration seconds"),
    SUS_CONNOISSEUR("stew effect lasts % longer"),
    SWEET_TOOTH("speed seconds"),
    HONEY_CURE("chance in % honey cures each extra effect"),
    SEA_SNACK("kelp eat time cut in %"),
    MILK_DRINKER("chance in % a good effect survives milk"),
    LONG_BREW("drunk potions last % longer"),
    BOTTOMLESS_FLASK("chance in % a potion is not used up"),
    QUICK_QUAFF("drink time cut in %"),
    HEALING_SURGE("heal share radius in blocks"),
    STEADY_PULSE("knockback cut in % while regenerating"),
    HEAVY_HANDS("stagger seconds"),
    AFTERIMAGE("dodge chance in % while swift"),
    SPRING_HEELS("double jump boost"),
    SALAMANDER("lava fog cut in %"),
    DEEP_DIVER("underwater mining penalty removed in %"),
    OWL_EYES("ore glow radius in blocks"),
    VANISHING_ACT("detection range cut in % while invisible"),
    FEATHER_DRIFT("glide strength"),
    SHELLBACK("slowness levels removed"),
    GUST_GUTS("launch height in blocks"),
    WEB_SPINNER("web slowdown cut in %"),
    SLIME_HEART("seconds your slimes fight for you"),
    HIVE_MIND("seconds your silverfish fight for you"),
    CLEAR_HEAD("nausea wobble cut in %"),
    IRON_WILL("highest mining fatigue level kept (0 = none)"),
    BAT_EARS("ping radius in blocks"),
    GRAVITY_ANCHOR("levitation time cut in %"),
    UNBOWED("weakness effect cut in %"),
    STIFF_JOINTS("slowness time cut in %"),
    HUNGER_PROOF("hunger effect cut in %"),
    OMEN_READER("chance in % a captain drops extra emeralds"),
    SPOTLIGHT("glow radius in blocks"),
    VILLAGE_DARLING("hero lasts % longer"),
    SEA_BLESSED("water buff linger seconds"),
    DEEP_SLEEPER("regeneration seconds on waking"),
    LIGHT_SLEEPER("monster check radius in blocks"),
    POWER_NAP("nap buff seconds"),
    PHOENIX("respawn buff seconds"),
    GRAVE_MAP("extra minutes your dropped items last"),
    SOUL_KEEPER("% of XP kept on death"),
    ANCHOR_BOUND("chance in % an anchor keeps its charge"),
    SHIELD_WALL("% of blocked damage reflected"),
    ARROW_WALL("chance in % a blocked shot ricochets"),
    QUICK_GUARD("shield raise delay cut in ticks"),
    AXE_CATCHER("shield disable time cut in %"),
    TOUGH_PLATE("armor wear cut in %"),
    LEATHER_LOVER("extra armor with full leather"),
    CHAIN_MAIL_GRACE("arrow dodge chance in % per chainmail piece"),
    GOLD_STANDARD("chance in % a barter pays double"),
    DIAMOND_SPINE("knockback resistance in %"),
    TURTLE_SHELL("extra turtle breathing seconds"),
    PUMPKIN_HEAD("pumpkin view block cut in %"),
    TOTEM_SAINT("totem buffs last % longer"),
    ARMOR_TRIMMER("chance in % armor stops at 1 durability"),
    GRUDGE_BEARER("player attack damage cut in %"),
    ODD_BRUISE("generic damage cut in %"),
    BACK_FOR_MORE("resistance seconds after a /kill"),
    // guardian spec batch: skills live in habits_skills/guardian/, code in Guard
    PATCH_UP("% of blocked damage healed", true),
    POTLUCK("share radius in blocks", true),
    HEARTY_MEAL("health healed by food while hurt", true),
    RESTED_GUARD("first hit cut in % after a quiet spell", true),
    BODYGUARD("% of ally damage you take instead", true),
    PROVOKE("taunt radius in blocks", true),
    SHELL_REST("health healed per second behind a raised shield", true),
    TRANSFUSION("health given to an ally", true),
    IRON_RATIONS("eat time cut in % right after a hit", true),
    SHIELD_RUSH("lunge strength", true),
    EMERGENCY_FLASK("cooldown in seconds", true),
    SHOCK_ABSORBER("% of max health a hit takes before the cut", true),
    PHALANX("cover radius in blocks", true),
    GUARDIAN_ANGEL("cooldown in minutes", true),
    RALLYING_CRY("rally radius in blocks", true),
    AEGIS("cooldown in seconds", true);

    private static final Sk[] VALUES = values();
    private static final Map<ResourceLocation, Sk> BY_ID = new HashMap<>();
    static {
        for (Sk s : VALUES) BY_ID.put(s.id, s);
    }

    public final ResourceLocation id;
    /** The skill that carries this mechanic: {@code habits:<name>}, or {@code habits:guardian/<name>} for the spec batch. */
    public final ResourceLocation skill;
    public final String meaning;

    Sk(String meaning) {
        this(meaning, false);
    }

    Sk(String meaning, boolean guardian) {
        String n = name().toLowerCase(Locale.ROOT);
        this.id = Habits.id("survival/" + n);
        this.skill = Habits.id(guardian ? "guardian/" + n : n);
        this.meaning = meaning;
    }

    static void registerAll() {
        for (Sk s : VALUES) {
            String m = s.meaning;
            PerkMechanics.register(s.id, (PerkMechanic) () -> m);
        }
    }

    // ------------------------------------------------------------------ snapshot cache

    private record Snap(HabitsCollection col, Collection<SkillDef> reg, HabitsApi.PerkHit[] hits, boolean any) {}

    // weak keys: a player object that is gone drops out by itself; logout and server stop also clear (see SurvivalMechanics)
    private static final Map<Player, Snap> SERVER = new WeakHashMap<>();
    private static final Map<Player, Snap> CLIENT = new WeakHashMap<>();

    static void forget(Player p) {
        synchronized (SERVER) { SERVER.remove(p); }
    }

    static void clearServer() {
        synchronized (SERVER) { SERVER.clear(); }
    }

    private static Snap snap(Player p) {
        Map<Player, Snap> cache = p.level().isClientSide ? CLIENT : SERVER;
        HabitsCollection col = HabitsApi.collection(p);
        Collection<SkillDef> reg = SkillRegistry.all();
        synchronized (cache) {
            Snap s = cache.get(p);
            if (s != null && s.col == col && s.reg == reg) return s;
            HabitsApi.PerkHit[] hits = new HabitsApi.PerkHit[VALUES.length];
            boolean any = false;
            for (var e : col.skills().entrySet()) {
                var prog = e.getValue();
                if (prog.level() <= 0 || !prog.enabled()) continue;
                SkillDef def = com.habits.progression.Effects.def(p, SkillRegistry.get(e.getKey())); // progression: sigil/charm boosts
                if (def == null || def.perk().mechanic().isEmpty() || !com.habits.core.Specs.allows(p, def)) continue;
                Sk k = BY_ID.get(def.perk().mechanic().get());
                if (k == null) continue;
                double v = def.perkValue(prog.level());
                HabitsApi.PerkHit old = hits[k.ordinal()];
                if (old == null || v > old.value()) hits[k.ordinal()] = new HabitsApi.PerkHit(def, prog.level(), v);
                any = true;
            }
            s = new Snap(col, reg, hits, any);
            cache.put(p, s);
            return s;
        }
    }

    /** True when the player has any active survival mechanic (cheap early exit). */
    static boolean anyActive(Player p) {
        return snap(p).any;
    }

    /** Best active hit for this mechanic, or null. Same result as {@link HabitsApi#mechanicHit}. */
    @Nullable
    public HabitsApi.PerkHit hit(Player p) {
        return snap(p).hits[ordinal()];
    }

    /** True at Lv10: "at max" perks key off the level, since Potent can push the value past its Lv10 number early. */
    public boolean maxed(Player p) {
        return maxed(hit(p));
    }

    public static boolean maxed(@Nullable HabitsApi.PerkHit h) {
        return h != null && h.level() >= com.habits.core.Leveling.MAX_LEVEL;
    }

    /** Primary value, 0 when not active. */
    public double v(Player p) {
        HabitsApi.PerkHit h = hit(p);
        return h == null ? 0 : h.value();
    }

    /** A param at the hit's level: "a..b" linear Lv1..Lv10, "a,b,..." one per level, or a constant. 0 when absent. */
    public static double p(HabitsApi.PerkHit h, String key) {
        return parse(h.skill().perk().param(key, "0"), h.level());
    }

    static double parse(String s, int level) {
        int l = Math.max(1, Math.min(10, level));
        try {
            int dots = s.indexOf("..");
            if (dots > 0) {
                double a = Double.parseDouble(s.substring(0, dots)), b = Double.parseDouble(s.substring(dots + 2));
                return a + (b - a) * (l - 1) / 9.0;
            }
            if (s.indexOf(',') >= 0) {
                String[] parts = s.split(",");
                return Double.parseDouble(parts[Math.min(l, parts.length) - 1].trim());
            }
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Percent roll with the player's random. */
    static boolean roll(Player p, double pct) {
        return pct > 0 && (pct >= 100 || p.getRandom().nextDouble() * 100 < pct);
    }

    /** The player's survival scratch data (saved with the player; not copied on death unless we do it). */
    static CompoundTag data(Player p) {
        CompoundTag root = p.getPersistentData();
        if (!root.contains("habits_survival")) root.put("habits_survival", new CompoundTag());
        return root.getCompound("habits_survival");
    }
}
