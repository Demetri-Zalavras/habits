package com.habits.perk.building;

import com.habits.core.HabitsApi;
import com.habits.core.PerkMechanic;
import com.habits.core.PerkMechanics;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

/** Entry point of package perk.building (Building specialization mechanics). Owner: PERKS-BUILDING. Called once from PerkInit.
 * Mechanic ids are {@code habits:building/<name>}, equal to their skill ids; docs/mechanics/building.md lists them. */
public final class BuildingMechanics {
    private BuildingMechanics() {}

    /** name -> value meaning (the binder's tooltip line); every one is registered as {@code habits:building/<name>}. */
    static final Map<String, String> MEANINGS = new LinkedHashMap<>();

    static {
        // placing
        m("endless_supply", "% chance a placed block costs nothing");
        m("palette_brush", "% chance your offhand block is placed instead");
        m("sod_layer", "% chance placed dirt turns to grass");
        m("flow_state", "seconds of haste after a building burst");
        m("quick_hands", "ticks between block placements (vanilla 4)");
        m("carpet_roll", "carpets rolled out per placement");
        m("glazier", "panes filled per placement");
        m("chandelier", "chains hung per placement");
        m("vine_drape", "vines draped per placement");
        m("flower_bed", "flowers planted around per placement");
        m("wire_puller", "extra redstone dust laid ahead");
        m("tripwire_spool", "max string span between hooks");
        m("pool_builder", "water blocks filled per bucket");
        m("backfill", "max blocks filled in a pit");
        m("symmetry", "mirror distance in blocks");
        m("supply_line", "supply chest range in blocks");
        m("fill_wand", "max blocks filled per box");
        m("builders_wage", "experience per 64 blocks placed");
        m("stock_count", "chest count radius (0 = carried only)");
        // removing and terraforming
        m("dismantle", "recent placements that break fast");
        m("demolition_hammer", "extra matching blocks broken");
        m("slab_splitter", "% chance a split costs no durability");
        m("moving_day", "max stacks carried inside a container");
        m("brush_clearer", "plant clearing radius");
        m("grader", "grading radius in blocks");
        m("dry_up", "water blocks dried per scoop");
        // decor and tools
        m("painter", "blocks recolored per use");
        m("chisel", "blocks chiseled per use");
        m("weathering", "blocks aged per use");
        m("patina", "copper blocks aged per bottle");
        m("mud_mixer", "dirt blocks turned to mud per bottle");
        m("measuring_tape", "measuring range in blocks");
        m("double_doors", "extra doors opened in a row");
        m("iron_hands", "iron hands level");
        m("take_a_seat", "health healed per minute while seated");
        m("gallery", "gallery level");
        m("hidden_light", "light level of hidden lights");
        m("builders_stick", "builder's stick level");
        m("glue_gun", "% chance the slime ball is kept");
        m("poser", "armor stand poses");
        m("offcuts", "% chance a cut piece craft gives half again");
        m("sawmill", "% chance a planks craft gives extra");
        m("cozy_home", "seconds of regeneration after a cozy night");
        // access and movement around a build
        m("safety_net", "seconds between safety blocks");
        m("hover", "seconds of hover per jump");
        m("sky_builder", "seconds of flight per landing");
        m("elevator", "elevator reach in blocks");
        m("spawn_proofer", "dark spot reveal radius");
        m("pole_climber", "% of ladder speed on poles");
    }

    private static void m(String name, String meaning) {
        MEANINGS.put(name, meaning);
    }

    // ---------------------------------------------------------------- config (see CONTRACT Requests: defineConfig wiring)

    static IntSupplier HIGH_PLACE_Y = () -> 100;
    static IntSupplier STREAK_BLOCKS = () -> 64;
    static IntSupplier STREAK_SECONDS = () -> 60;
    static IntSupplier PALETTE_KINDS = () -> 8;
    static IntSupplier PALETTE_SECONDS = () -> 60;

    /** Own section values; core calls this once it is wired (until then the defaults above hold). */
    public static void defineConfig(ModConfigSpec.Builder b) {
        var high = b.comment("habits:building/high_place fires for a block placed at or above this y").defineInRange("high_place_y", 100, -64, 320);
        var streak = b.comment("habits:building/build_streak: blocks placed within streak_seconds").defineInRange("streak_blocks", 64, 2, 10000);
        var streakS = b.defineInRange("streak_seconds", 60, 1, 3600);
        var kinds = b.comment("habits:building/palette: different blocks placed within palette_seconds").defineInRange("palette_kinds", 8, 2, 64);
        var kindsS = b.defineInRange("palette_seconds", 60, 1, 3600);
        HIGH_PLACE_Y = high::get;
        STREAK_BLOCKS = streak::get;
        STREAK_SECONDS = streakS::get;
        PALETTE_KINDS = kinds::get;
        PALETTE_SECONDS = kindsS::get;
        Statue.defineConfig(b);
    }

    public static void init(IEventBus modBus) {
        Map<String, BiConsumer<Player, HabitsApi.PerkHit>> ticks = Moving.ticks();
        MEANINGS.forEach((name, meaning) -> {
            BiConsumer<Player, HabitsApi.PerkHit> t = ticks.get(name);
            PerkMechanics.register(Bld.id(name), new PerkMechanic() {
                @Override public String valueMeaning() { return meaning; }
                @Override public void tick(Player player, HabitsApi.PerkHit hit) {
                    if (t != null && Bld.allowed(player)) t.accept(player, hit);
                }
            });
        });
        NeoForge.EVENT_BUS.register(Bld.class);
        NeoForge.EVENT_BUS.register(Placing.class);
        NeoForge.EVENT_BUS.register(Using.class);
        NeoForge.EVENT_BUS.register(Breaking.class);
        NeoForge.EVENT_BUS.register(Moving.class);
        Statue.register(modBus);
    }
}
