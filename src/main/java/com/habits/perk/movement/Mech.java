package com.habits.perk.movement;

import com.habits.Habits;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Every movement-exploration mechanic. Mechanic id = {@code habits:movement/<skill id>}, one skill each. */
public enum Mech {
    DASH("dash distance in blocks"), DOUBLE_JUMP("extra midair jumps"), WALL_JUMP("wall kick power %"),
    SPIDER_CLIMB("climb speed %"), GLIDER_KICK("fall speed reduction %"), GROUND_SLAM("slam radius in blocks"),
    JESUS_WALK("water walk tier 1-3"), WINGS_OF_WIND("launch height in blocks"), PATHFINDER("saved waypoints"),
    WANDERER("path speed %"), RUNNERS_LUNGS("free sprint seconds"), TRAILBLAZER("trail seconds"),
    GHOST_STEP("sneak detection reduction %"), EDGE_SENSE("ledge catch reach in blocks"), CAT_FEET("sneak speed % of walk"),
    LONG_LEGS("step height in blocks"), MARATHON("momentum cap %"), ICE_SKATER("ice speed %"), SOUL_RUNNER("soul sand speed %"),
    MUD_WADER("slow removed %"), SAND_STRIDER("sand speed %"), SLIME_RIDER("bounce % of fall"), HONEY_GLIDER("slide blocks/s"),
    BUBBLE_ELEVATOR("rise blocks/s"), PARKOUR("sprint jump distance %"), PATHLESS("leaf step height"), BUNNY_HOP("extra jump blocks"),
    SCAFFOLD_MONKEY("climb multiplier"), CAT_LANDING("extra safe fall blocks"), LEAP_OF_FAITH("soft landing cooldown s"),
    DOLPHIN_KICK("swim speed %"), SEABED_WALKER("underwater walk % of land"), DEEP_BREATH("oxygen bonus"),
    RIPTIDE_RIDER("lunge blocks"), CURRENT_SURFER("current push multiplier"), PEARL_DIVER("loot glow radius"),
    SKY_GLIDER("elytra drag reduction %"), ROCKET_SAVER("free rocket chance %"), THERMAL_RIDER("lift blocks/s"),
    FLIGHT_DECK("elytra repair per 100 blocks"), FEATHER_BODY("gravity reduction %"), HORSE_WHISPERER("horse speed/jump %"),
    PACK_MULE("mount call radius"), CAMEL_CARAVAN("dash recharge %"), PIG_JOCKEY("pig speed %"), LAVA_STRIDER("strider speed %"),
    SADDLE_UP("mount damage reduction %"), MOUNTED_EXPLORER("scan radius"), OARSMAN("boat speed %"), ICE_ROAD("boat hop blocks"),
    RAIL_BARON("minecart speed multiplier"), CHEST_CART_HAULER("item pull radius"), CAPTAIN("ignore chance %"),
    PORTAL_HOPPER("portal wait seconds"), NETHER_NATIVE("nether speed %"), ENDWALKER("blink blocks"), OVERWORLDER("home marker on"),
    LONG_HAUL("distance speed cap %"), GATEWAY_JUMPER("remembered gateways"), WORLD_EDGE("fast travel cooldown minutes"),
    FOREST_RANGER("leaf fall damage reduction %"), DESERT_NOMAD("hot sprint hunger saved %"), MESA_RUNNER("gold scan radius"),
    JUNGLE_EXPLORER("vine fling blocks"), TAIGA_TRACKER("footprint seconds"), SAVANNA_STALKER("zoom factor"),
    PEAK_CLIMBER("high jump %"), SNOW_SCOUT("freeze reduction %"), MUSHROOM_PILGRIM("no-spawn radius"),
    SWAMP_WADER("debuff time reduction %"), BEACHCOMBER("finds per 1000 blocks"), CAVE_DWELLER("ping radius"),
    DEEP_DARK_DELVER("muted sounds % near wardens"), CRIMSON_WANDERER("piglin notice reduction %"),
    DELTA_WALKER("magma knockback reduction %"), END_ISLANDER("arc jump blocks"), RIVER_RUNNER("river speed %"),
    MEADOW_BREEZE("flower speed seconds"), ATLAS("biome perk strength %"), VILLAGE_GUIDE("hero level"),
    OUTPOST_SCOUT("raider radar radius"), TOMB_RAIDER("trap glow radius"), WITCH_HUNTER("potion miss %"),
    IGLOO_FINDER("heal per pulse"), SHIPWRECKED("wreck radar radius"), RUIN_READER("suspicious block radius"),
    X_MARKS_THE_SPOT("treasure radius"), MINE_RAT("mineshaft speed %"), MONUMENT_DIVER("fatigue levels removed"),
    MANSION_RAIDER("secret room radius"), FORTRESS_CRAWLER("knockback reduction %"), PIGLIN_DISGUISE("brute delay seconds"),
    FOSSIL_FINDER("skeleton reveal radius"), PORTAL_RESTORER("portal blocks filled"), STRONGHOLD_SEEKER("eye save %"),
    ANCIENT_ECHO("muted vibration %"), TRIAL_TAKER("wind launch bonus %"), LODESTONE_LINK("warp channel seconds"),
    RECOVERY_RUN("speed level"), SPYGLASS_SCOUT("marker count"), LOST_AND_FOUND("trail length blocks"),
    NIGHT_OWL("night brightness %"), STORM_CHASER("storm speed level"), EXPLORERS_EYE("fresh land speed %"),
    SKY_WATCHER("float fall reduction %"), KEY_TURNER("vault repeat chance %"),
    BLINK("blink blocks through walls"), FRONTIER_LORD("zone damage scaling cut %"),
    // spec batch (2026-10-13): skills live at habits:movement/<skill>, logic in SpecMoves / SpecEvents
    ADRENALINE("speed seconds after a big landing", true), FLYING_DISMOUNT("% of the ride's speed kept", true),
    FIREMAN_POLE("ladder slide blocks/s", true), POWER_SLIDE("slide blocks", true), SNOWSHOES("snow speed %", true),
    DOWNHILL("downhill speed %", true), SURE_HOOVES("mount fall damage cut %", true), STEADY_STRIDE("walk speed % kept using items", true),
    RAIL_RUNNER("speed % on rails", true), STARTING_BLOCK("speed seconds when you set off", true), IRON_GRIP("climbing knockback cut %", true),
    CRAWL_SPACE("crawl speed %", true), WALKIES("pet pace radius", true), GIDDY_UP("mount speed seconds", true), DOORBUSTER("door tier 1-3", true),
    CANNONBALL("splash radius", true), PEARL_MOMENTUM("% of pearl speed kept", true), TRAMPLE_CHARGE("trample knockback", true),
    TORPEDO("swim ram damage", true), COILED_SPRING("extra jump blocks", true), CHAIN_CLIMBER("thin climb tier 1-3", true),
    SHOULDER_CHECK("sprint bump knockback", true), AIR_BRAKE("glide speed shed %", true), RUNNERS_HIGH("heal per 100 blocks sprinted", true),
    KICK_OFF("ladder kick blocks", true), EVASION("speed seconds when hit", true), PEDAL_POWER("cart push % of top speed a second", true),
    SWIM_RHYTHM("swim momentum cap %", true), FENCE_HOPPER("fence jump extra blocks", true), CHIMNEY_CLIMBER("shaft brace seconds", true),
    RUBBER_BAND("pearl snap back seconds", true), SLIPSTREAM("drafting speed %", true), STORM_SURGE("riptide bonus %", true),
    INK_JET("ink jet blocks", true), SALMON_RUN("waterfall swim blocks/s", true), ESCAPE_ARTIST("chased speed %", true),
    FLAP("flap blocks/s", true), PEREGRINE("dive damage", true), MANTLE("vault height blocks", true), QUICK_WINGS("auto elytra fall blocks", true),
    PEARL_RIDER("mount pearl range", true), SHARED_STRIDE("effects shared with your mount", true), THUNDER_SPIN("dry riptide cooldown s", true),
    PEGASUS("mount glide fall cut %", true), LAST_LEDGE("fatal fall save cooldown min", true), PACE_SETTER("pace aura radius", true),
    SIDESTEP("projectile dodge chance %", true),
    // class passives (2026-10-13): skills at habits:class/movement/<name>, logic in ClassMoves and Mounts
    FREERUNNER("sprint speed and jump %", "class/movement/freerunner"), OUTRIDER("mount speed and jump %", "class/movement/outrider"),
    STORMSTRIDER("speed %", "class/movement/stormstrider");

    public static final Mech[] ALL = values();
    public static final int N = ALL.length;
    private static final Map<ResourceLocation, Mech> BY_ID = new HashMap<>();

    static {
        for (Mech m : ALL) BY_ID.put(m.id, m);
    }

    public final String skill = name().toLowerCase(Locale.ROOT);
    /** Mechanic id, also used as this mechanic's attribute modifier id. */
    public final ResourceLocation id = Habits.id("movement/" + skill);
    /** {@code habits:<skill>}, {@code habits:movement/<skill>} for the spec batch, or the class skill path. */
    public final ResourceLocation skillId;
    public final String meaning;

    Mech(String meaning) {
        this(meaning, false);
    }

    Mech(String meaning, boolean spec) {
        this.meaning = meaning;
        this.skillId = Habits.id(spec ? "movement/" + skill : skill);
    }

    /** A mechanic whose skill lives somewhere else ({@code habits:<skillPath>}). */
    Mech(String meaning, String skillPath) {
        this.meaning = meaning;
        this.skillId = Habits.id(skillPath);
    }

    public static Mech byId(ResourceLocation id) {
        return BY_ID.get(id);
    }
}
