package com.habits.perk.nature;

import com.habits.core.HabitsApi;
import com.habits.core.PerkMechanic;
import com.habits.core.PerkMechanics;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/** Entry point of package perk.nature (Nature specialization mechanics). Owner: PERKS-NATURE. Called once from PerkInit.
 * Ids are {@code habits:nature/<skill>}; docs/mechanics/nature.md lists values, params and the custom events fired here. */
public final class NatureMechanics {
    private NatureMechanics() {}

    record M(String valueMeaning, @Nullable BiConsumer<Player, HabitsApi.PerkHit> ticker) implements PerkMechanic {
        @Override
        public void tick(Player player, HabitsApi.PerkHit hit) {
            if (ticker != null && Nat.ok(player)) ticker.accept(player, hit);
        }
    }

    static void reg(String id, String meaning) {
        reg(id, meaning, null);
    }

    static void reg(String id, String meaning, @Nullable BiConsumer<Player, HabitsApi.PerkHit> tick) {
        PerkMechanics.register(Nat.id(id), new M(meaning, tick));
    }

    public static void init(IEventBus modBus) {
        // trees and logging
        reg("ring_counter", "xp chance % per log");
        reg("heartwood", "absorption chance % per log");
        reg("chop_rhythm", "highest Haste level");
        reg("squirrel", "climb speed in blocks per second", Woods::squirrel);
        reg("tree_hugger", "health per minute while hugging a tree", Woods::treeHugger);
        reg("sapling_stash", "extra sapling chance %");
        reg("dryad", "chance % a monster loses you under leaves", Wild::hideTick);
        reg("old_growth", "seconds until a planted sapling grows");
        reg("rootbind", "cooldown in seconds");
        reg("forest_heart", "Regeneration level in forests", Woods::forestHeart);
        // flowers and foraging
        reg("bouquet", "extra flower chance %");
        reg("moss_bed", "health per minute on moss", Woods::mossBed);
        reg("flower_bed", "health per minute near flowers", Woods::flowerBed);
        reg("lily_step", "seconds a lily pad lasts", Woods::lilyStep);
        reg("dandelion_wish", "wish chance % per dandelion");
        reg("bloom_burst", "cooldown in seconds");
        reg("berry_picker", "bonus berry chance %");
        reg("desert_forager", "find chance % per block");
        reg("glow_forager", "seconds of Night Vision");
        reg("truffle_snout", "find chance % per block");
        reg("bee_dance", "seconds of Speed and Haste");
        // fishing
        reg("tall_tale", "% more fishing experience");
        reg("chum_bucket", "seconds the chum lasts", Waters::chumTick);
        reg("reel_rhythm", "% shorter wait per catch in a streak", Waters::reelTick);
        reg("message_bottle", "treasure map chance % per catch");
        reg("school", "school radius in blocks");
        reg("old_man", "legendary fish chance % per catch");
        // animals and taming
        reg("hatchery", "hatch chance % per egg");
        reg("fresh_milk", "health healed by milk");
        reg("pet_medic", "extra health when you feed a pet");
        reg("shared_meal", "health your pets regain when you eat");
        reg("calm_aura", "% of neutral animals that stay calm");
        reg("mount_mend", "mount health per minute", Wild::mountMend);
        reg("barding", "% less damage to your mount");
        reg("wingman", "% less fall damage with a parrot on your shoulder");
        reg("cat_nap", "seconds of Regeneration and Absorption");
        reg("guard_dog", "wolf rally range in blocks");
        reg("quick_love", "% shorter breeding wait");
        reg("game_tracker", "glow radius in blocks", Wild::gameTracker);
        reg("pack_hunter", "double loot chance % on pet kills");
        reg("stampede", "trample damage", Wild::stampede);
        reg("apex", "food points per animal hunted");
        reg("beastmaster", "% of damage your pets take for you");
        reg("spirit_animal", "spirit effect level", Wild::spiritAnimal);
        // weather
        reg("cloud_reader", "forecast range in minutes", Skies::cloudReader);
        reg("thunder_child", "Strength and Speed level in storms", Skies::thunderChild);
        reg("rainbow", "seconds of Luck and Regeneration");
        reg("rainmaker", "cooldown in minutes");
        reg("monsoon", "growth ticks per second in the rain", Skies::monsoon);
        reg("eye_of_storm", "lightning strikes per minute", Skies::eyeOfStorm);
        // oceans
        reg("coral_collector", "keep chance % per coral");
        reg("kelp_cover", "chance % a monster loses you in seagrass", Wild::hideTick);
        reg("ink_cloud", "ink chance % when hit in water");
        reg("shell_seeker", "find chance % per block");
        reg("deep_watch", "glow radius in blocks", Waters::deepWatch);
        reg("tide_pool", "fish chance % per bucket");
        reg("siren", "lull radius in blocks", Waters::siren);
        reg("sea_heart", "Conduit Power level", Waters::seaHeart);
        // class passives
        reg("pack_bond", "Strength level of your pets", Callings::packBond);
        reg("stormcall", "lightning chance % per hit in the rain");

        for (Class<?> c : new Class<?>[]{Woods.class, Wild.class, Waters.class, Skies.class, Callings.class}) NeoForge.EVENT_BUS.register(c);
    }
}
