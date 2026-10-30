package com.habits.perk.mining;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.PerkMechanic;
import com.habits.core.PerkMechanics;
import com.habits.trigger.Triggers;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Mining and building perk mechanics. Every mechanic id equals its skill id (skill ids are unique across the catalog,
 * so ids never clash with other domains). Secondary numbers that scale with level live in the skill JSON params as
 * {@code "a..b"} (Lv1..Lv10, linear), read with {@link #lerp}. docs/mechanics/mining-building.md lists every id.
 */
public final class MiningMechanics {
    private MiningMechanics() {}

    /** id -> value meaning. Tick mechanics are attached in {@link TickPerks}. */
    static final Map<String, String> MEANINGS = new LinkedHashMap<>();

    static {
        m("stonebreaker", "mining speed % per momentum stack");
        m("deep_miner", "% chance to bore through deepslate behind");
        m("ore_pulse", "ore glow radius in blocks");
        m("debris_diver", "ancient debris glow radius in blocks");
        m("vein_miner", "extra connected ore blocks broken");
        m("masons_wand", "extra blocks placed on the surface");
        m("auto_bridge", "blocks placed per second while bridging");
        m("candlelight", "spawn-proof radius around your lit candles");
        m("shaped_charge", "tunnel depth in blocks");
        m("torchlight", "light level at or below which a torch is placed");
        m("static_pick", "max stored charges");
        m("hot_pick", "% chance raw ore drops come out smelted");
        m("gold_rush", "haste duration in seconds");
        m("lapis_scholar", "ore experience multiplier");
        m("diamond_dowsing", "dowsing radius in blocks");
        m("emerald_echo", "hero of the village seconds");
        m("crystal_splinter", "shard damage");
        m("golden_truce", "minutes between piglin gifts");
        m("resonance", "% chance budding amethyst drops itself");
        m("compactor", "how many ore kinds get packed");
        m("lantern_bearer", "light level you give off");
        m("obsidian_breaker", "% less break time on obsidian");
        m("stone_alchemy", "% chance generator stone drops an ore");
        m("philosophers_pick", "% chance an ore drops the next ore up");
        m("sandman", "max column blocks collected");
        m("sifter", "% chance of a sifting bonus");
        m("excavator", "blocks dug per shovel break");
        m("snowplow", "snow clearing radius");
        m("ice_carver", "% chance ice drops itself");
        m("hellraker", "nether stone blocks per break");
        m("bastion_breaker", "% chance of gold nuggets");
        m("telekinesis", "% chance drops go straight to you");
        m("muffled_tools", "radius in which sculk does not hear your mining and building");
        m("gentle_hands", "% chance fragile blocks drop themselves");
        m("aqua_digger", "underwater mining speed bonus");
        m("spawner_breaker", "% chance a spawner drops itself");
        m("bug_hunter", "infested block reveal radius");
        m("icicle_drop", "stalactite damage multiplier");
        m("soul_digger", "soul speed level");
        m("mesa_gold", "% chance terracotta drops gold nuggets");
        m("treasure_sense", "container glow radius in blocks");
        m("dark_sight", "darkness night vision level (1 deep only, 10 anywhere)");
        m("shaft_sinker", "blocks checked below when digging down");
        m("gravel_guard", "falling block catch radius");
        m("midair_miner", "% of the airborne mining penalty removed");
        m("magma_miner", "lava cooling radius");
        m("tireless_tools", "% durability restored when a tool would break");
        m("auto_tool", "auto tool level (6+ searches the whole inventory)");
        m("punch_above", "% faster mining with low tier tools");
        m("goldsmith", "golden tool durability multiplier");
        m("bare_knuckle", "bare hand dig speed");
        m("lucky_strike", "extra fortune levels");
        m("feather_pick", "% chance of silk touch while sneaking");
        m("archaeologist", "brushing speed multiplier");
        m("path_maker", "path radius");
        m("bark_peeler", "max logs stripped at once");
        m("copper_keeper", "max copper blocks waxed or scraped at once");
        m("web_cutter", "% less cobweb slow");
        m("pillar_up", "extra blocks per jump place");
        m("scaffolder", "scaffolding tower height");
        m("half_measures", "slab skill level");
        m("wrench", "wrench level (what can be rotated)");
        m("fence_line", "max gap filled");
        m("doorkeeper", "seconds before your doors close");
        m("torch_toss", "torch throw range");
        m("ladder_layer", "ladders placed per click");
        m("rail_layer", "rails laid per placement");
        m("oops", "blocks you can undo");
        m("block_swapper", "blocks swapped at once");
        m("air_place", "air place distance");
        m("concrete_mixer", "water search radius (99 = always)");
        m("drain", "sponge radius");
        m("plumber", "% chance the water bucket stays full");
        m("beacon_builder", "extra beacon range in blocks");
        m("kindler", "light-up radius");
        m("scribe", "max sign waypoints");
        m("standard_bearer", "banner aura radius");
        m("curator", "curator level");
        m("tailors_dummy", "armor stand swap level");
        m("redstone_sight", "redstone power reading range");
        m("wireless", "max bound levers");
        m("dispenser_tinkerer", "dispenser level (5+ breaks blocks)");
        m("hopper_hand", "hopper speed multiplier");
        m("piston_power", "piston push limit");
        m("demolitionist", "tnt blast radius");
        m("cluster_bomb", "mini charges per tnt");
    }

    private static void m(String id, String meaning) {
        MEANINGS.put(id, meaning);
    }

    public static void init(IEventBus modBus) {
        Map<String, BiConsumer<Player, HabitsApi.PerkHit>> ticks = TickPerks.ticks();
        DeepPerks.meanings(MiningMechanics::m);
        DeepPerks.ticks(ticks);
        MEANINGS.forEach((id, meaning) -> {
            BiConsumer<Player, HabitsApi.PerkHit> t = ticks.get(id);
            PerkMechanics.register(Habits.id(id), new PerkMechanic() {
                @Override public String valueMeaning() { return meaning; }
                @Override public void tick(Player player, HabitsApi.PerkHit hit) {
                    if (t != null && allowed(player)) t.accept(player, hit);
                }
            });
        });
        modBus.addListener(MiningNet::register);
        modBus.addListener(DispenserPerks::setup);
        Doors.init(modBus);
        NeoForge.EVENT_BUS.register(Work.class);
        NeoForge.EVENT_BUS.register(Scanner.class);
        NeoForge.EVENT_BUS.register(Generators.class);
        NeoForge.EVENT_BUS.register(BreakPerks.class);
        NeoForge.EVENT_BUS.register(DropPerks.class);
        NeoForge.EVENT_BUS.register(PhilosophersPick.class);
        NeoForge.EVENT_BUS.register(SpeedPerks.class);
        NeoForge.EVENT_BUS.register(BuildPerks.class);
        NeoForge.EVENT_BUS.register(TickPerks.class);
        NeoForge.EVENT_BUS.register(BlastPerks.class);
        NeoForge.EVENT_BUS.register(DeepPerks.class);
        NeoForge.EVENT_BUS.addListener(MiningMechanics::keepOnClone);
        if (FMLEnvironment.dist.isClient()) com.habits.perk.mining.client.MiningClient.init(modBus);
    }

    /** Death and End exit build a new player without this data; these keys carry over (levers, waypoints, Tireless cooldown). */
    static final String[] KEPT = {Remote.KEY, Scribe.PENDING, Scribe.WAYPOINTS, "habits_tireless_ready"};

    static void keepOnClone(net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone e) {
        var from = e.getOriginal().getPersistentData();
        var to = e.getEntity().getPersistentData();
        for (String k : KEPT) if (from.contains(k)) to.put(k, from.get(k).copy());
    }

    // ---------------------------------------------------------------- shared reads

    /** Alive, not spectating, and not a fake player (GameTest fakes let through by the trigger gate are allowed). */
    static boolean allowed(@Nullable Player p) {
        return p != null && p.isAlive() && !p.isSpectator()
                && (!(p instanceof FakePlayer) || Triggers.TEST_PLAYERS.contains(p.getUUID()));
    }

    /** The player's best active hit for a mining mechanic, or null (also null when not {@link #allowed}). */
    @Nullable
    /** Bare Knuckle's "bare hand": empty, or anything that is not a tool or weapon (a block, food, a stick). */
    static boolean bareHand(net.minecraft.world.item.ItemStack s) {
        if (s.isEmpty()) return true;
        if (s.has(net.minecraft.core.component.DataComponents.TOOL) || s.getItem() instanceof net.minecraft.world.item.TieredItem) return false;
        var mods = s.getAttributeModifiers().modifiers();
        for (int i = 0; i < mods.size(); i++)
            if (mods.get(i).attribute().is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) return false;
        return true;
    }

    static HabitsApi.PerkHit hit(@Nullable Player p, String mechanic) {
        return allowed(p) ? HabitsApi.mechanicHit(p, Habits.id(mechanic)) : null;
    }

    /** O(1) gate for mixin hooks (both sides): the skill with this id if owned and on, built straight from its
     * progress entry (mechanic id == skill id), so non-owners cost one map read and no collection scan. */
    @Nullable
    static HabitsApi.PerkHit fast(@Nullable net.minecraft.world.entity.Entity e, net.minecraft.resources.ResourceLocation skill) {
        if (!(e instanceof Player p)) return null;
        var sp = HabitsApi.collection(p).get(skill);
        // progression: sigil/charm boosts (the stored-level path in at() has no player and stays unboosted)
        if (sp.level() <= 0 || !sp.enabled() || !allowed(p)) return null;
        var def = com.habits.core.SkillRegistry.get(skill);
        return def != null && !com.habits.core.Specs.allows(p, def) ? null : com.habits.progression.Effects.hit(p, at(skill, sp.level()));
    }

    /** The hit a skill gives at a stored level (owned blocks remember the placer's level), or null if unknown. */
    @Nullable
    static HabitsApi.PerkHit at(net.minecraft.resources.ResourceLocation skill, int level) {
        var def = level <= 0 ? null : com.habits.core.SkillRegistry.get(skill);
        return def == null ? null : new HabitsApi.PerkHit(def, level, def.perkValue(level));
    }

    /** Value of a mining mechanic, 0 when not active. */
    static double val(@Nullable Player p, String mechanic) {
        HabitsApi.PerkHit h = hit(p, mechanic);
        return h == null ? 0 : h.value();
    }

    /** A level-scaled param {@code "a..b"} (Lv1 = a, Lv10 = b, linear), or a plain number; {@code a..b} default. */
    static double lerp(HabitsApi.PerkHit hit, String key, double a, double b) {
        String s = hit.skill().perk().params().get(key);
        if (s != null) {
            try {
                int i = s.indexOf("..");
                if (i < 0) return Double.parseDouble(s.trim());
                a = Double.parseDouble(s.substring(0, i).trim());
                b = Double.parseDouble(s.substring(i + 2).trim());
            } catch (NumberFormatException ignored) {
                // bad JSON param: fall back to the defaults
            }
        }
        return a + (b - a) * (Math.min(10, Math.max(1, hit.level())) - 1) / 9.0;
    }

    /** Whole-number chance: 2.3 means 2, plus 1 more 30% of the time. */
    static int roll(double v, net.minecraft.util.RandomSource r) {
        int n = (int) Math.floor(v);
        return n + (r.nextDouble() < v - n ? 1 : 0);
    }
}
