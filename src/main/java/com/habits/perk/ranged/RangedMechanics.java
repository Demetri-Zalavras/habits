package com.habits.perk.ranged;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.PerkMechanic;
import com.habits.core.PerkMechanics;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ranged perk mechanics (bows, crossbows, tridents, throwables, potions, fishing rods). Every mechanic is an event
 * listener in this package that reads its value through {@link HabitsApi}; ids and value meanings are listed here and
 * in docs/mechanics/ranged.md.
 */
public final class RangedMechanics {
    private RangedMechanics() {}

    /** mechanic id path -> what its level value means. */
    static final Map<String, String> MEANINGS = new LinkedHashMap<>();

    static {
        m("homing_arrows", "bend radius in blocks (params: turn deg/tick, lock range)");
        m("steady_draw", "bow draw speed bonus %");
        m("quartermaster", "% chance to refund the arrow");
        m("long_shot", "% damage per block flown past 15");
        m("point_blank", "knockback strength on close hits");
        m("headshot", "% headshot damage");
        m("ricochet", "max bounces off stone");
        m("split_shot", "shards per hit");
        m("rain_of_arrows", "arrows per volley");
        m("pinning_shot", "pin time in seconds");
        m("overdraw", "% damage per second held past full draw");
        m("trackers_mark", "mark time in seconds");
        m("venom_fletcher", "% longer tipped effects");
        m("skeet_shooter", "% lead on moving targets");
        m("frost_pelter", "snowball damage");
        m("snow_volley", "snowballs per throw");
        m("egg_toss", "% hatch chance");
        m("yolk_splatter", "blindness seconds");
        m("pearl_pitcher", "% less pearl damage");
        m("pearl_swap", "stun seconds");
        m("far_pearl", "% pearl speed");
        m("gale_thrower", "% wind blast radius");
        m("wind_jumper", "% self-launch boost");
        m("breeze_breaker", "% chance a hit can't be dodged");
        m("quick_loader", "% crossbow load time cut");
        m("fan_of_bolts", "extra multishot bolts");
        m("skewer", "% damage per mob already pierced");
        m("firework_artillery", "rocket homing radius in blocks");
        m("loyal_return", "% trident return speed");
        m("javelin", "mobs a thrown trident pierces");
        m("tidecaller", "wave radius in blocks");
        m("stormcaller", "% chance to call lightning");
        m("harpoon", "blocks pulled toward you");
        m("trident_thief", "% trident drop chance");
        m("splash_zone", "% splash radius");
        m("harming_echo", "% chance to splash again");
        m("lingering_mist", "% longer clouds");
        m("field_medic", "% stronger good potions");
        m("water_balloon", "damage to water-hating mobs");
        m("bottle_pop", "% more bottle XP");
        m("fire_pitcher", "fireball damage");
        m("yank", "reel pull multiplier");
        m("grapple_hook", "grapple range in blocks");
        m("hunters_instinct", "% of meat cooked");
        m("shulker_sniper", "% levitation chance");
        m("crystal_popper", "% creeper detonation chance");
        m("demolition_shot", "% flame arrow burst chance");
        m("echo_shot", "reveal radius in blocks");
        m("wither_bane", "% of arrows that become wither skulls");
        m("bullseye", "path preview length in blocks");
        m("trickshot", "block tier (1 levers/doors, 2 glass/ice/leaves, 3 item fetch)");
        m("hot_streak", "% damage per hit in a row");
        m("quickdraw", "% chance of an instant draw after a hit");
        m("wall_bang", "cover blocks an arrow passes");
        m("silent_archer", "% sneak shot damage on unaware mobs");
        m("run_and_gun", "% of the draw slowdown removed");
        m("horse_archer", "% mounted arrow damage");
        m("sky_gunner", "% arrow speed while gliding");
        m("tide_archer", "% of water drag removed");
        m("finisher", "% health below which arrows execute");
        m("target_practice", "% center hit damage");
        m(FallingStar.ID, "star damage every few full-draw shots");
        // ranger spec batch (Ranger.java)
        m("ranged/fletcher", "% chance crafted arrows come with extras");
        m("ranged/scavenger", "% chance an arrow kill drops arrows");
        m("ranged/hobble", "slow seconds on leg shots");
        m("ranged/low_profile", "% less mob sight range while sneaking");
        m("ranged/potion_sling", "% throw speed for potions");
        m("ranged/kindling", "fire spread radius from burning arrows");
        m("ranged/quick_throw", "% faster trident wind-up");
        m("ranged/gust_pack", "% wind charge cooldown cut");
        m("ranged/spotter", "spyglass mark seconds");
        m("ranged/snap_shot", "% of missing draw power restored");
        m("ranged/swift_fletch", "% arrow speed");
        m("ranged/barbed", "bleed damage per second");
        m("ranged/porcupine", "% arrow damage per stuck arrow");
        m("ranged/high_ground", "% arrow damage per block above");
        m("ranged/sidestep", "% chance to dodge projectiles");
        m("ranged/nimble", "% chance to dodge melee holding a ranged weapon");
        m("ranged/hunters_high", "speed seconds after an arrow kill");
        m("ranged/lanyard", "seconds before a stuck trident comes back");
        m("ranged/riptide", "% riptide launch");
        m("ranged/ghillie", "invisibility seconds after leaving cover");
        m("ranged/shadow_shot", "% arrow damage from the dark");
        m("ranged/leech", "% of arrow damage healed");
        m("ranged/smoke_bomb", "smoke radius");
        m("ranged/hair_trigger", "% chance a crossbow kill reloads");
        m("ranged/two_birds", "% chance of one more pierce");
        m("ranged/unseen", "% arrow damage while invisible");
        m("ranged/bolt_burst", "blast power on crossbow kills");
        m("ranged/chain_shot", "chain range in blocks");
        m("ranged/fang_arrows", "% chance of evoker fangs");
        m("ranged/deadeye", "blocks of flat flight");
        m("ranged/whirlpool", "pull radius in blocks");
        m("ranged/dragonfire", "dragon breath damage");
        m("ranged/sonic_shot", "sonic beam damage");
        m("ranged/twin_tide", "% chance of a twin trident");
        // ranger classes (habits_skills/class/ranger)
        m("ranged/ambush", "% arrow damage to mobs not targeting you");
        m("ranged/galeborn", "% arrow damage on moving shots");
        m("ranged/heavenpiercer", "% arrow damage, plus pierce");
    }

    private static void m(String id, String meaning) {
        MEANINGS.put(id, meaning);
    }

    public static void init(IEventBus modBus) {
        MEANINGS.forEach((id, meaning) -> PerkMechanics.register(Habits.id(id), id.equals("quick_loader")
                ? new Simple(meaning) {
                    @Override
                    public void tick(Player player, HabitsApi.PerkHit hit) {
                        if (!player.level().isClientSide() && (player.tickCount & 3) == 0) Draw.refill(player);
                    }
                }
                : new Simple(meaning)));
        modBus.addListener(RangedNet::register);
        NeoForge.EVENT_BUS.register(Shots.class);
        NeoForge.EVENT_BUS.register(Hits.class);
        NeoForge.EVENT_BUS.register(Impacts.class);
        NeoForge.EVENT_BUS.register(Throws.class);
        NeoForge.EVENT_BUS.register(Draw.class);
        NeoForge.EVENT_BUS.register(Status.class);
        NeoForge.EVENT_BUS.register(Ranger.class);
    }

    /** A skill param at the hit's level ({@code "a..b"}, ten comma values, or a number). For the client subpackage. */
    public static double param(HabitsApi.PerkHit h, String key) {
        return R.p(h, key);
    }

    static class Simple implements PerkMechanic {
        private final String meaning;

        Simple(String meaning) {
            this.meaning = meaning;
        }

        @Override
        public String valueMeaning() {
            return meaning;
        }
    }
}
