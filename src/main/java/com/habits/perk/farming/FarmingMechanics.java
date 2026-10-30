package com.habits.perk.farming;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.PerkMechanic;
import com.habits.core.PerkMechanics;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * Farming and nature perk mechanics (owner: PERKS-FARMING). Every mechanic id is the id of the one skill that uses it.
 * docs/mechanics/farming-nature.md lists what each value means and the custom trigger events fired here.
 */
public final class FarmingMechanics {
    private FarmingMechanics() {}

    record M(String valueMeaning, @Nullable BiConsumer<Player, HabitsApi.PerkHit> ticker) implements PerkMechanic {
        @Override
        public void tick(Player player, HabitsApi.PerkHit hit) {
            if (ticker != null && Farm.ok(player)) ticker.accept(player, hit);
        }
    }

    static void reg(String id, String meaning) {
        reg(id, meaning, null);
    }

    static void reg(String id, String meaning, @Nullable BiConsumer<Player, HabitsApi.PerkHit> tick) {
        PerkMechanics.register(Habits.id(id), new M(meaning, tick));
    }

    public static void init(IEventBus modBus) {
        // crops and soil
        reg("green_thumb", "harvest radius in blocks");
        reg("breadbasket", "replant chance %");
        reg("carrot_top", "golden carrot chance % per carrot");
        reg("hot_potato", "baked chance % per potato");
        reg("seed_spreader", "planting radius in blocks");
        reg("melon_smash", "glistering slice chance %");
        reg("scarecrow", "spawn-block radius in blocks");
        reg("stalk_cutter", "extra stalk radius in blocks");
        reg("kelp_lung", "seconds of air stored per kelp", Crops::kelpTick);
        reg("cocoa_climber", "climb speed in blocks per second", Crops::cocoaClimb);
        reg("wart_warden", "bonus ingredient chance %");
        reg("tiller", "patch width in blocks");
        reg("sower", "patch width in blocks");
        reg("light_step", "hydration radius in blocks", Crops::lightStepTick);
        reg("fertilizer", "splash radius in blocks");
        reg("rot_pocket", "compostables per bone meal", Crops::rotTick);
        reg("grass_cutter", "bonus seed chance %");
        reg("growth_aura", "extra growth ticks per second", Crops::growthAuraTick);
        reg("harvest_moon", "growth stages at midnight", Crops::harvestMoonTick);
        reg("ancient_gardener", "growth boost %");
        reg("flower_power", "effect seconds");
        reg("flower_trail", "flower chance % per block walked", Crops::flowerTrail);
        reg("death_bloom", "wither seconds on monsters");
        reg("photosynthesis", "seconds of sun per food point", Crops::photosynthesis);
        reg("edens_touch", "golden crop chance % per harvest");
        // trees
        reg("timber", "max logs felled");
        reg("leaf_blower", "decay radius in blocks");
        reg("forester", "self-plant chance %", Trees::foresterTick);
        reg("apple_shaker", "shake chance % per punch");
        reg("sap_tap", "honey chance % per strip");
        reg("treespeak", "cooldown in seconds");
        reg("topiary", "cut radius in blocks");
        reg("shroom_feller", "max mushroom blocks felled");
        // animals
        reg("matchmaker", "twin chance %");
        reg("nursery", "baby growth speed multiplier", Animals::nursery);
        reg("pied_piper", "follow radius in blocks", Animals::piedPiper);
        reg("pheromones", "love radius in blocks", Animals::pheromones);
        reg("stew_herder", "stew stack size", Animals::stewTick);
        reg("shearer", "shear radius in blocks", Animals::shearerTick);
        reg("egg_collector", "laying speed multiplier", Animals::eggTick);
        reg("whistle", "call range in blocks");
        reg("stud_book", "foal stat bonus %");
        reg("leash_master", "leash hold range in blocks");
        reg("spit_guard", "llamas that spit", Animals::spitTick);
        reg("pack_alpha", "wolf damage % per wolf");
        reg("armor_groomer", "wolf armor repaired per 30 seconds", Animals::groomTick);
        reg("cat_person", "scare radius in blocks");
        reg("shoulder_lookout", "warning range in blocks", Animals::lookoutTick);
        reg("fetch", "fetch radius in blocks");
        reg("play_dead", "health threshold in hearts");
        reg("bucket_anything", "critter tier (1 rabbits, 2 small animals, 3 any small passive mob)");
        reg("sneeze_harvest", "sneeze rate multiplier", Animals::sneezeTick);
        reg("ram", "knockback strength", Animals::ramTick);
        reg("frog_tongue", "tongue range in blocks");
        reg("beekeeper", "hive radius in blocks", Animals::beekeeperTick);
        reg("swarm", "bees in your swarm", Animals::swarmTick);
        reg("sniff", "dig cooldown in seconds", Animals::sniffTick);
        reg("hoglin_rancher", "hoglins that follow you", Animals::hoglinTick);
        reg("pod_swimmer", "tow speed in blocks per second", Animals::podTick);
        reg("allay_pal", "allay speed bonus %", Animals::allayTick);
        reg("pet_lover", "pet respawn delay in seconds", Animals::petTick);
        reg("ghost_rider", "water walking tier (1 sink slowly, 2 walk on water, 3 also lava)", Animals::ghostRide);
        // fishing
        reg("angler", "auto-hook chance % per bite", Fishing::anglerTick);
        reg("big_bite", "second item chance %");
        reg("treasure_hunter", "treasure upgrade chance %");
        reg("junk_recycler", "junk re-roll chance %");
        reg("puffer_up", "puff chance % when hit");
        reg("fresh_grill", "cooked fish chance %");
        reg("storm_angler", "wait time reduction %", Fishing::stormTick);
        reg("lava_angler", "lava bite speed % of normal", Fishing::lavaTick);
        reg("ice_hole", "double catch chance % in the cold", Fishing::iceTick);

        FarmSpec.init();
        Piaro.init();

        for (Class<?> c : new Class<?>[]{BlockJobs.class, Crops.class, Trees.class, Animals.class, Fishing.class, FarmTriggers.class, Piaro.class, Dish.class})
            NeoForge.EVENT_BUS.register(c);
        NeoForge.EVENT_BUS.addListener(Farm::keepOnClone);
        FarmNet.init(modBus);
        modBus.addListener(Animals::dispenser);
    }
}
