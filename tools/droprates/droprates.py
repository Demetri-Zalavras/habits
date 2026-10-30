"""Turn the measured drop data (DropHarness, HABITS_DROPS=1) into minutes per drop and a ranked report.

    python tools/droprates/droprates.py [run/drops/measured.csv]

Reads the harness CSV, applies the ASSUMED activity rates below (edit them and re-run; no game needed once
docs/droprates_measured.csv exists), writes docs/droprates.csv and docs/DROPRATES.md.

Every rate means: how many of this skill's events (or units) a player makes per minute WHILE DOING that action on
purpose. None = a one-off milestone, where minutes mean nothing (reported by events per drop only).
"""
import csv
import io
import math
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# minutes of doing the action per drop, by rarity (user decision 2026-10-08: doubled, half the card pace). calibrate.py sets every chance from this.
TARGET_MIN = {"common": 10, "uncommon": 16, "rare": 30, "epic": 60, "legendary": 120, "mythical": 240}

# ---------------------------------------------------------------- per trigger type (units or events per minute)
TYPE_RATE = {
    "walk_distance": (259, "blocks walked (4.32 b/s)"),
    "sprint_distance": (337, "blocks sprinted (5.6 b/s)"),
    "sneak_distance": (78, "blocks sneaked (1.3 b/s)"),
    "swim_distance": (180, "blocks swum (3 b/s)"),
    "climb_distance": (141, "blocks climbed (2.35 b/s)"),
    "elytra_distance": (1800, "blocks glided (30 b/s)"),
    "boat_distance": (480, "blocks rowed (8 b/s)"),
    "jump": (60, "jumps (one a second while hopping)"),
    "xp_pickup": (40, "xp points picked up (normal mining and fighting)"),
    "place_block": (30, "blocks placed while building"),
    "harvest_crop": (40, "grown crops broken while harvesting a field"),
    "plant": (40, "seeds planted"),
    "till": (40, "blocks tilled"),
    "bonemeal": (15, "bone meal uses"),
    "interact_block": (6, "block right-clicks"),
    "open_container": (4, "chests opened"),
    "kill_entity": (3, "kills of that mob"),
    "damage_dealt": (6, "hits of that kind"),
    "damage_taken": (6, "hits taken of that kind"),
    "shield_block": (6, "hits blocked in a fight"),
    "shoot_projectile": (10, "shots"),
    "die": (0.1, "deaths (one per 10 min of risky play)"),
    "eat": (1, "foods eaten"),
    "drink_potion": (1, "potions drunk"),
    "use_item": (6, "item uses"),
    "bucket_fill": (2, "buckets filled"),
    "bucket_empty": (4, "buckets emptied"),
    "item_break": (0.1, "tools worn out"),
    "pickup_item": (30, "item pickups"),
    "level_up_xp": (0.2, "level-ups at level 10+"),
    "craft": (6, "craft takes"),
    "smelt": (4, "furnace takes"),
    "brew": (6, "potions taken from a stand"),
    "enchant": (0.5, "enchants"),
    "anvil": (1, "anvil uses"),
    "trade": (6, "trades"),
    "fish": (4, "catches (about Lure II)"),
    "breed": (4, "breeds"),
    "tame": (1, "tames"),
    "shear": (6, "sheep sheared"),
    "interact_entity": (6, "mob right-clicks"),
    "fall": (1, "big falls"),
    "sleep": (0.05, "nights slept (one per 20 min day)"),
    "enter_biome": (0.5, "entries into that biome while exploring"),
    "enter_dimension": (0.2, "portal trips"),
    "enter_structure": (0.1, "entries into that structure while exploring"),
    "advancement": (None, "one-off"),
    "custom": (2, "default for a mod event (see EVENT_RATE)"),
    "custom_units": (60, "default for a mod unit event"),
    "stat_increment": (6, "default for a count stat (see STAT_RATE)"),
    "ride_distance": (480, "blocks ridden (see RIDE_RATE)"),
}

RIDE_RATE = {"minecraft:horse": 480, "minecraft:camel": 330, "minecraft:donkey": 400, "minecraft:pig": 180,
             "minecraft:strider": 200, "minecraft:minecart": 480}

# stat_increment: units are blocks for *_one_cm, seconds for *_time, else counts
STAT_RATE = {
    "minecraft:open_barrel": 3, "minecraft:bell_ring": 20, "minecraft:drop": 6, "minecraft:eat_cake_slice": 1,
    "minecraft:sneak_time": 60, "minecraft:play_record": 0.5, "minecraft:play_noteblock": 60,
    "minecraft:swim_one_cm": 180, "minecraft:open_enderchest": 3, "minecraft:fly_one_cm": 240,
    "minecraft:pot_flower": 4, "minecraft:talked_to_villager": 6, "minecraft:walk_on_water_one_cm": 120,
    "minecraft:interact_with_lectern": 3, "minecraft:tune_noteblock": 30, "minecraft:raid_win": 0.067,
    "minecraft:walk_under_water_one_cm": 100, "minecraft:open_shulker_box": 3, "minecraft:climb_one_cm": 141,
    "minecraft:target_hit": 10, "minecraft:clean_armor": 3, "minecraft:fill_cauldron": 6,
    "minecraft:interact_with_loom": 3,
}

# per skill, where the target changes the pace a lot (blocks mined, foods, mobs, biomes, structures...)
SKILL_RATE = {
    # break_block: breaks per minute while mining that block with the right tool
    "stonebreaker": 50, "deep_miner": 35, "hellraker": 80, "excavator": 60, "sifter": 60, "snowplow": 80,
    "timber": 20, "leaf_blower": 60, "grass_cutter": 60, "flower_power": 20, "kelp_lung": 30, "stalk_cutter": 40,
    "melon_smash": 20, "shroom_feller": 30, "soul_digger": 60, "mesa_gold": 40, "telekinesis": 40, "ice_carver": 40,
    "icicle_drop": 20, "gentle_hands": 30, "lantern_bearer": 20, "bastion_breaker": 30, "muffled_tools": 20,
    "resonance": 6, "obsidian_breaker": 6, "web_cutter": 15, "bug_hunter": 2, "compactor": 2, "death_bloom": 1,
    "ancient_gardener": 2, "spawner_breaker": 0.1, "debris_diver": 0.5, "torchlight": 6, "static_pick": 5,
    "hot_pick": 4, "gold_rush": 2, "lapis_scholar": 2, "ore_pulse": 3, "diamond_dowsing": 1, "emerald_echo": 0.3,
    "crystal_splinter": 10, "golden_truce": 6,
    # place_block
    "clockwork": 1, "dispenser_tinkerer": 2, "drain": 2, "hopper_hand": 2, "librarians_nose": 5, "piston_power": 3,
    "standard_bearer": 1, "scribe": 3, "candlelight": 4, "torch_toss": 6, "redstone_sight": 15, "wireless": 4,
    "rail_layer": 20,
    # harvest / plant / till
    "scarecrow": 20, "cocoa_climber": 20, "forester": 10, "bark_peeler": 20, "sap_tap": 20,
    # interact_block
    "doorkeeper": 10, "blaze_saver": 1, "campfire_chef": 3, "fletchers_eye": 1, "fuel_saver": 4, "kindler": 3,
    "lodestone_link": 0.5, "rot_pocket": 20,
    # kills and hits
    "breeze_breaker": 2, "trident_thief": 2, "wither_bane": 0.25, "echo_shot": 6, "shulker_sniper": 6,
    "firework_artillery": 4, "frost_pelter": 10, "bramble": 10,
    # damage taken, per minute of exposure (damage-over-time types hit every 0.5 to 2 s)
    "featherfall": 2, "ember_skin": 60, "flame_dancer": 120, "hot_hands": 120, "lava_walker": 120, "gills": 60,
    "snow_born": 30, "magma_soles": 120, "iron_stomach": 15, "wither_ward": 30, "edge_walker": 120,
    "elbow_room": 120, "wall_phaser": 120, "bramble_walker": 120, "cactus_skin": 120, "camp_cook": 120,
    "antivenom": 48, "thick_hide": 6, "hardy": 6, "grudge_bearer": 6, "pincushion": 4, "snowball_catcher": 4,
    "trident_catcher": 2, "wind_rider": 4, "goat_footing": 0.5, "goat_simulator": 0.5, "return_to_sender": 3,
    "skull_juggler": 6, "sound_proof": 0.5, "thunderstruck": 0.02, "spit_shine": 2, "spike_lander": 0.2,
    "drip_dodger": 0.3, "anvil_rain": 0.2, "hard_hat": 0.2, "bad_dreams": 0.05, "hex_ward": 3,
    "rocket_jumper": 2, "blast_braced": 1, "grand_finale": 3, "odd_bruise": 0.2, "beekeepers_hide": 3,
    "breath_bottler": 2, "bullet_surfer": 4, "crumple_zone": 0.5, "wall_kisser": 0.5, "thorn_proof": 6,
    "arrow_wall": 4,
    # die
    "back_for_more": 0.05,
    # eat
    "sea_snack": 3, "golden_glutton": 0.5, "notchs_blessing": 0.1, "blink_snacker": 3, "puffer_proof": 0.2,
    "sus_connoisseur": 0.5,
    # use_item
    "bottle_pop": 30, "bottled_wisdom": 30, "bundle_up": 10, "cartographer": 1, "fire_pitcher": 6,
    "firework_show": 6, "milk_drinker": 1, "paperwork": 1, "pearl_pitcher": 4, "tailors_dummy": 2, "curator": 6,
    # buckets, items
    "play_dead": 1, "armor_trimmer": 0.02, "egg_collector": 2,
    # crafting
    "tinkerer": 2, "overcharge": 0.5,
    # fish
    "fresh_grill": 2.4, "big_bite": 0.5, "junk_recycler": 0.4, "treasure_hunter": 0.2,
    # animals
    "fetch": 1, "hard_shell": 0.5, "hoglin_rancher": 2, "pheromones": 1, "stud_book": 1, "swarm": 3,
    "cat_person": 0.5, "pack_alpha": 1, "shoulder_lookout": 0.5, "spit_guard": 0.5, "whistle": 0.5,
    "allay_pal": 2, "armor_groomer": 1, "armor_stand_buddy": 3, "dye_hard": 3, "leash_master": 3,
    "pod_swimmer": 2, "ram": 2, "sneeze_harvest": 3, "stew_herder": 2, "sticky_name": 0.5,
    # melee kills filtered to one mob: kills of that mob per minute while hunting it
    "abyssal_eye": 0.2, "batter_up": 1, "bonebreaker": 4, "butcher": 4, "charge_breaker": 2, "deflector": 2,
    "ember_heart": 3, "exterminator": 3, "fang_strike": 0.5, "grave_caller": 4, "heave": 1, "hex_breaker": 1,
    "holy_strike": 4, "hush": 3, "ink_blade": 2, "juggernaut": 0.5, "levitate_lock": 2, "pinned": 2, "rout": 3,
    "sky_swatter": 2, "sonic_blade": 0.2, "soul_harvest": 0.33, "sticky_blade": 4, "volatile": 2, "wither_hand": 3,
    # falls
    "glider_kick": 0.5, "ground_slam": 1,
    # biomes: rare or far-off ones are entered less often
    "deep_dark_delver": 0.1, "mushroom_pilgrim": 0.05, "end_islander": 0.3, "mesa_runner": 0.2,
    "crimson_wanderer": 0.5, "delta_walker": 0.5, "cave_dweller": 0.5,
    # structures
    "village_guide": 0.1, "mine_rat": 0.2, "portal_restorer": 0.2, "shipwrecked": 0.1, "mansion_raider": 0.005,
    "ancient_echo": 0.02, "stronghold_seeker": 0.01, "monument_diver": 0.02, "piglin_disguise": 0.05,
    "fortress_crawler": 0.1, "trial_taker": 0.03, "tomb_raider": 0.05, "outpost_scout": 0.05, "igloo_finder": 0.02,
    "witch_hunter": 0.02, "fossil_finder": 0.2, "ruin_reader": 0.1,
}

# custom / custom_units mod events (habits:<id>), per minute while doing that thing
EVENT_RATE = {
    # melee
    "melee_hit": 40, "melee_kill": 4, "crit": 15, "sweep": 8, "armored_hit": 30, "multi_hit": 6, "sprint_hit": 10,
    "shield_hit": 40, "dual_wield_hit": 20, "underwater_hit": 20, "mounted_hit": 10, "backstab": 3, "one_shot": 3,
    "overkill": 3, "one_shot_kill": 2, "low_hp_kill": 0.5, "duel_kill": 2, "crowd_kill": 1, "kill_streak": 0.5,
    "full_charge_kill": 3, "disarm": 5, "enchanted_kill/looting": 3, "enchanted_kill/sharpness": 3, "frost_kill": 1,
    "storm_kill": 0.3, "channel_kill": 0.3, "sneak_kill": 1, "pet_kill": 1, "wet_kill": 1, "harming_kill": 1,
    "improvised_kill": 1, "smash_kill": 1, "mace_smash": 3, "tnt_kill": 0.3, "riposte": 2, "perfect_block": 2,
    "quick_followup": 3, "gale": 2, "burst_damage": 0.5, "flawless_streak": 0.3, "weapon_break": 0.05,
    "shield_disabled": 0.5, "shield_raise": 6, "stomp": 0.5, "wildfire": 20, "wall_slam": 4, "dragonslayer": 20,
    # ranged
    "arrow_hit_hostile": 6, "arrow_hit_close": 3, "arrow_hit_30": 1, "arrow_hit_crowd": 0.5, "arrow_hit_near_wall": 1,
    "arrow_hit_stone": 6, "arrow_pickup": 6, "arrow_streak_5": 0.5, "arrow_through_cover": 0.3, "airborne_hit": 1,
    "headshot": 1, "overdrawn_hit": 2, "spectral_hit": 3, "tipped_arrow_hit": 3, "steep_arrow_hit": 1,
    "unaware_hit": 2, "underwater_arrow_hit": 1, "moving_hit": 4, "trickshot": 0.3, "target_bullseye": 2,
    "crossbow_loaded": 6, "multishot_double": 1, "bolt_pierce_two": 1, "trident_aquatic_hit": 3, "trident_caught": 3,
    "trident_long_hit": 1, "trident_wet_hit": 3, "gliding_hit": 0.5, "pearl_hit_mob": 0.5, "egg_hit": 4,
    "splash_hit": 2, "lingering_hit": 2, "heal_splash_ally": 1, "water_splash_hurt": 1, "crystal_shot": 0.2,
    "tnt_flame_arrow": 0.5, "reel_mob": 3, "reel_ground": 3, "far_pearl": 1,
    # mining and building
    "bare_hand_break": 30, "dark_break": 20, "dig_down": 30, "dig_up": 20, "low_tier_break": 40, "midair_break": 5,
    "underwater_break": 15, "lava_side_break": 2, "silk_break": 20, "fortune_ore": 3, "ore_streak": 1,
    "quick_swap_break": 5, "break_near_spawner": 10, "generator_stone": 30, "sand_column": 30, "copper_work": 3,
    "gold_tool_break": 50, "stack_used_up": 1, "quick_undo": 2, "hit_in_heavy_armor": 6, "swap_block": 5, "edge_bridge": 15,
    "jump_pillar": 20, "fall_place": 1, "special_rail": 30, "tnt_lit": 3, "tnt_big_blast": 0.5, "vault_unlock": 0.3,
    # farming and animals
    "shear_plant": 20, "apple_from_leaves": 0.5, "mega_tree": 0.1, "make_path": 20, "trample": 2, "crop_grew_near": 20,
    "sun_planting": 10, "full_moon_harvest": 40, "hive_harvest": 1, "feed_animal": 10, "feed_baby": 3,
    "froglight": 0.2, "sniffer_find": 0.2, "villager_born": 0.5, "name_pet": 0.3, "build_golem": 0.2,
    "brush_done": 2, "cured_villager": 0.05, "puffer_poisoned": 0.5, "cold_catch": 2, "lava_catch": 2,
    "thunder_catch": 3, "mount": 2, "skeleton_horse_mount": 0.01, "trade_wandering": 2,
    # crafting and utility
    "big_craft": 2, "mass_craft": 0.5, "shift_craft": 4, "grindstone_take": 2, "smithing_take": 1, "smithing_trim": 1,
    "stonecutter_take": 6, "chat": 2, "polite_chat": 0.5, "swap_hands": 10, "long_reach": 10, "pickup_full": 1,
    "loot_chest_first": 0.3, "bell_in_raid": 0.1, "card_dropped": 1.1, "easter_egg_name": 0.1,
    # survival (effects fire once per new effect)
    "effect_hunger": 1, "effect_slowness": 1, "effect_weakness": 0.5, "effect_nausea": 0.5, "effect_glowing": 0.3,
    "effect_absorption": 0.5, "effect_levitation": 1, "effect_mining_fatigue": 0.2, "effect_blindness": 1,
    "effect_conduit_power": 0.5, "effect_bad_omen": 0.05, "effect_hero_of_the_village": 0.05,
    "hunger_refill": 1, "low_hp_survived": 0.3, "survive_1hp": 0.1, "totem_pop": 0.02, "new_death_type": 0.05,
    "close_fall": 0.5, "high_dive": 0.3, "deep_dive": 0.5, "long_dive": 0.5, "sleep_blocked": 0.1, "day_nap": 2,
    "lightning_rod_strike": 0.02, "anchor_spawn": 0.1, "recovery_compass": 0.05, "lost": 0.05, "buried_treasure": 0.05,
    "void_fall": 0.01, "homecoming": 0.02, "three_sleepless_days": 0.017,
    # timers: fire once per that much time doing it
    "afk_5min": 0.2, "stood_still_minute": 1, "nether_minute": 1, "end_minute": 1, "pumpkin_enderman_minute": 1,
    "turtle_underwater_minute": 1, "chainmail_5min": 0.2, "gold_armor_5min": 0.2, "full_leather_5min": 0.2,
    "hoarder_10min": 0.1, "minimalist_10min": 0.1,
    # movement
    "wind_burst": 1, "wind_self_launch": 3, "slime_bounce": 6, "edge_peek": 3, "jump_frenzy": 1, "long_jump": 4,
    "wall_jump": 10, "thermal_glide": 3, "glide_rocket": 6, "long_flight": 0.2, "long_ride": 0.1, "long_sprint": 1,
    "long_voyage": 0.1, "long_walk": 0.25, "sky_high": 0.2, "gateway_jump": 0.5, "new_chunk": 20, "marathon": 0.03,
    # custom_units (units per minute)
    "bubble_ride": 120, "compass_walk": 259, "damage_taken_hp": 10, "diamond_armor_damage": 10, "forest_walk": 259,
    "honey_slide": 30, "hunger_point_lost": 2, "ice_boat": 2400, "ice_walk": 259, "mud_walk": 259,
    "natural_regen_hp": 10, "night_walk": 259, "rain_swim": 180, "river_swim": 180, "sand_walk": 259,
    "scaffold_climb": 120, "scope_seconds": 60, "soul_walk": 259, "sprint_new_biome": 337, "storm_walk": 259,
}
NEVER_WHY = {}

# the run before calibrate.py (2026-10-08, chances as first written), judged against the same targets and rates:
# rarity, skills ranked, median minutes per drop, under half the target, over 2x the target
BEFORE = [("common", 224, 6.38, 25, 52), ("uncommon", 215, 5.8, 63, 30), ("rare", 111, 6.01, 59, 13),
          ("epic", 40, 13.2, 20, 4), ("legendary", 6, 54, 3, 2)]
# Mythical feats (2026-10-13): one-offs, judged by events per drop
MILESTONES = {"biomes_20", "far_1000", "world_edge", "skill_milestone", "beacon_builder", "ore_rainbow", "legendary_card", "overforge"}


def rate_for(row):
    """(events or units per minute, where the number came from); rate None = one-off."""
    skill, trig, target = row["skill"], row["trigger"], row["target"]
    if skill in SKILL_RATE:
        return SKILL_RATE[skill], "skill"
    if trig.startswith("custom"):
        ev = target.split(":", 1)[1] if ":" in target else target
        if ev in MILESTONES:
            return None, "milestone"
        if ev in EVENT_RATE:
            return EVENT_RATE[ev], "event"
        return TYPE_RATE[trig][0], "type default"
    if trig == "stat_increment":
        return STAT_RATE.get(target, TYPE_RATE[trig][0]), "stat" if target in STAT_RATE else "type default"
    if trig == "ride_distance":
        return RIDE_RATE.get(target, 480), "ride"
    if skill in MILESTONES:
        return None, "milestone"
    return TYPE_RATE[trig][0], "type"


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "run", "drops", "measured.csv")
    measured = os.path.join(ROOT, "docs", "droprates_measured.csv")
    if os.path.abspath(src) != os.path.abspath(measured):
        shutil.copyfile(src, measured)
    lines = open(measured, encoding="utf-8").read().splitlines()
    header = lines[0] if lines[0].startswith("#") else ""
    rows = list(csv.DictReader(io.StringIO("\n".join(l for l in lines if not l.startswith("#")))))
    assert len(rows) == 614 or "--any" in sys.argv, f"expected 614 skills, got {len(rows)}"

    out = []
    for r in rows:
        units, drops = float(r["units"]), int(r["drops"])
        per_drop = units / drops if drops else math.inf
        rate, src_kind = rate_for(r)
        minutes = per_drop / rate if rate else None
        target = TARGET_MIN[r["rarity"]]
        # nominal chance in the measured unit: cm stats are measured per block, tick stats per second
        nominal = float(r["nominal_chance_per_unit"])
        if r["trigger"] == "stat_increment" and r["unit"] in ("block", "second"):
            nominal = 1 - (1 - nominal) ** (100 if r["unit"] == "block" else 20)
        out.append({
            "skill": r["skill"], "rarity": r["rarity"], "trigger": r["trigger"], "target": r["target"],
            "item": r["item"], "entity": r["entity"], "path": r["path"], "unit": r["unit"],
            "events_or_units_per_drop": per_drop, "measured_chance_per_unit": drops / units if units else 0,
            "nominal_chance_per_unit": nominal, "drops": drops, "assumed_per_min": rate, "rate_source": src_kind,
            "minutes_per_drop": minutes, "target_minutes": target,
            "ratio_vs_target": minutes / target if minutes is not None else None,
            "ratio_vs_5min": minutes / 5 if minutes is not None else None,
        })

    csv_path = os.path.join(ROOT, "docs", "droprates.csv")
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0].keys()))
        w.writeheader()
        for o in sorted(out, key=lambda o: (o["ratio_vs_target"] is None, o["ratio_vs_target"] or 0)):
            w.writerow({k: fmt(v) for k, v in o.items()})
    write_md(out, header)


def fmt(v):
    if v is None:
        return ""
    if isinstance(v, float):
        return "inf" if math.isinf(v) else f"{v:.4g}"
    return v


def num(v, digits=3):
    if v is None:
        return "-"
    if math.isinf(v):
        return "never"
    return f"{v:.{digits}g}" if v < 1000 else f"{v:,.0f}"


def write_md(out, header):
    sys.dont_write_bytecode = True  # no __pycache__ next to the tables
    from calibrate import FEATS  # calibrate imports this module, so not at the top
    ranked = [o for o in out if o["ratio_vs_target"] is not None and not math.isinf(o["ratio_vs_target"])]
    capped = sorted((o for o in ranked if o["nominal_chance_per_unit"] >= 0.999 and o["skill"] not in FEATS),
                    key=lambda o: -o["ratio_vs_target"])
    never = [o for o in out if o["drops"] == 0]
    one_off = [o for o in out if o["assumed_per_min"] is None]
    over = sorted(ranked, key=lambda o: o["ratio_vs_target"])[:20]
    under = sorted(ranked, key=lambda o: -o["ratio_vs_target"])[:20]
    gaps = [(abs(o["measured_chance_per_unit"] / o["nominal_chance_per_unit"] - 1), o["drops"]) for o in out if o["drops"]]
    worst_gap = max(g for g, _ in gaps)
    zs = [g * math.sqrt(n) for g, n in gaps]  # relative error of a mean over n drops is about 1/sqrt(n)
    over3 = sum(1 for z in zs if z > 3)

    def table(rows):
        t = ["| # | skill | rarity | trigger (target) | per drop | assumed /min | min per drop | target | x target |",
             "|---|---|---|---|---|---|---|---|---|"]
        for i, o in enumerate(rows, 1):
            tgt = o["target"].replace("minecraft:", "").replace("habits:", "")
            t.append(f"| {i} | {o['skill']} | {o['rarity']} | {o['trigger']} ({tgt}) | "
                     f"{num(o['events_or_units_per_drop'])} {o['unit']} | {num(o['assumed_per_min'])} | "
                     f"{num(o['minutes_per_drop'])} | {o['target_minutes']} | {num(o['ratio_vs_target'], 2)} |")
        return "\n".join(t)

    by_rarity = []
    for rar in TARGET_MIN:
        ms = sorted(o["minutes_per_drop"] for o in ranked if o["rarity"] == rar and o["skill"] not in FEATS)
        if not ms:
            continue
        med = ms[len(ms) // 2]
        fast = sum(1 for m in ms if m < TARGET_MIN[rar] / 2)
        slow = sum(1 for m in ms if m > TARGET_MIN[rar] * 2)
        by_rarity.append(f"| {rar} | {len(ms)} | {TARGET_MIN[rar]} | {num(med)} | {num(ms[0])} | {num(ms[-1])} | {fast} | {slow} |")

    stats = "\n".join(f"| {k} | {v} |" for k, v in sorted(STAT_RATE.items()))
    types = "\n".join(f"| {k} | {v[0] if v[0] is not None else 'one-off'} | {v[1]} |" for k, v in TYPE_RATE.items())
    rides = ", ".join(f"{k.split(':')[1]} {v}" for k, v in RIDE_RATE.items())
    skill_rates = ", ".join(f"{k} {v}" for k, v in sorted(SKILL_RATE.items()))
    event_rates = ", ".join(f"{k} {v}" for k, v in sorted(EVENT_RATE.items()))

    md = f"""# Card drop rates (measured)

Generated by `tools/droprates/droprates.py` from the `DropHarness` run (`HABITS_DROPS=1`). Do not edit by hand: change
the assumption tables in the script and re-run it. Full data: `docs/droprates.csv` (one row per skill, sorted from most
over-frequent to most under-frequent) and the raw harness output `docs/droprates_measured.csv`.

Run: `{header.lstrip('# ')}`

## How it was measured
- `src/main/java/com/habits/perf/DropHarness.java` runs on `runGameTestServer` when `HABITS_DROPS=1`. For every one of the
  {len(out)} skills it repeats the skill's action on a fake survival player until 300 of that skill's cards have landed in the
  inventory, then counts events (or units) per card. It counts real cards, so the trigger gate, the catalog matchers,
  the per-unit math and the `dropRate` config all count.
- Paths, most real first:
  - **stat**: vanilla `StatsCounter.increment`. This fires the real `StatAwardEvent`, the trigger listener and its
    once-a-second flush. Covers movement, `stat_increment`, item use, item break and pickups. One iteration is one
    second of movement, or one action.
  - **xp**: a real `PlayerXpEvent.PickupXp` with a 3-point orb, then the flush.
  - **break**: a real survival `destroyBlock` with the tool in hand (the listed tool, else the right diamond tool).
    Crops are set to their grown state first.
  - **fire**: everything else. It calls `Triggers.fire` with the context the listener or perk package builds: the
    first listed target, item and entity (or a tag's first member), and the amount the real site passes.
- 300 drops per skill gives about plus or minus 6% (1 sigma) on events per drop. For every skill that dropped at all,
  the measured chance matches the JSON chance (times `dropRate`) within noise: largest gap {worst_gap:.0%}, largest
  {max(zs):.1f} sigma, {over3} of {len(zs)} beyond 3 sigma (about 1 is expected by chance). So **the roll itself does what
  the JSON says**. The gaps
  below come from the chances in the JSON compared with how often players do things.
- Minutes per drop = (events per drop) / (assumed events per minute while doing that action). The assumptions are
  below. They are guesses meant to be challenged; every row of the CSV shows the rate it used and where it came from.

## Never drops ({len(never)})
""" + "\n".join(f"- **{o['skill']}** ({o['rarity']}, `{o['trigger']}`): 0 cards in the full run. {NEVER_WHY.get(o['skill'], '')}"
                 for o in never) + """

## Target per rarity
User decision (2026-10-08): minutes of doing the skill's own action per card, Common 5, Uncommon 8, Rare 15, Epic 30,
Legendary 60. Boss kills and big feats drop about 1 in 3 per feat instead. `tools/droprates/calibrate.py` sets every
chance from `TARGET_MIN` and the rate tables below (`chance = 1 / (events per minute x target minutes)`), so re-tune
by editing a table and re-running it. Feats are left out of this table (see "Feats").

| rarity | skills ranked | target min | median min | fastest | slowest | under 1/2 target | over 2x target |
|---|---|---|---|---|---|---|---|
""" + "\n".join(by_rarity) + """

Before calibration (the earlier harness run, judged against the same targets and rates):

| rarity | skills ranked | target min | median min | under 1/2 target | over 2x target |
|---|---|---|---|---|---|
""" + "\n".join(f"| {r} | {n} | {TARGET_MIN[r]} | {m} | {f} | {sl} |" for r, n, m, f, sl in BEFORE) + f"""

## Capped at 1.0 ({len(capped)}: the action is too rare to reach the target)
Each drops on every event and still takes longer than its target at the assumed rate:
""" + "\n".join(f"- {o['skill']} ({o['rarity']}, `{o['trigger']}` {o['target'].replace('minecraft:', '').replace('habits:', '')}): "
                 f"{num(o['minutes_per_drop'])} min per card, target {o['target_minutes']}" for o in capped) + """

## Feats (about 1 in 3 per feat)
""" + "\n".join(f"- {o['skill']} ({o['rarity']}): {num(o['events_or_units_per_drop'])} {'event' if o['unit'] == 'count' else o['unit']}s per card, about "
                 f"{FEATS[o['skill']]} per feat" for o in out if o["skill"] in FEATS) + f"""

## 20 most over-frequent (drop much sooner than their rarity target)
{table(over)}

## 20 most under-frequent (take much longer than their rarity target)
{table(under)}

## One-off milestones (not ranked: minutes mean nothing)
""" + "\n".join(f"- {o['skill']} ({o['rarity']}): {num(o['events_or_units_per_drop'])} events per drop" for o in one_off) + f"""

## Assumed rates

### Per trigger type (events or units per minute while doing it)
| trigger | per min | what |
|---|---|---|
{types}

### Stats (`stat_increment`; blocks for *_one_cm, seconds for *_time, else counts)
| stat | per min |
|---|---|
{stats}

### Riding (blocks per minute)
{rides}

### Per skill (overrides the trigger default)
{skill_rates}

### Mod events (`custom` per event, `custom_units` per unit)
{event_rates}

## Limits
- `fire`-path skills skip the vanilla event that would build the context, and the mod events (`custom`) are rolled once
  per call. If a perk site fires an event more or less often than its name says, the real rate differs by that factor.
  The harness does not measure how often perk packages fire their own events.
- Damage-taken rates are per minute of exposure. Damage-over-time types roll on every damage tick (fire and drowning
  once a second; lava, cactus, berry bushes, magma, campfires, walls and the border twice a second; freezing and wither
  every 2 s; starving every 4 s), and the rates use those tick rates, so a card takes minutes of standing in it.
- Boss and big-feat skills are calibrated per feat, not per minute (see "Feats"), and left out of the rarity table.
- Skills without a `match` used a stand-in target (stone, zombie, cow, sharpness, diamond sword...); see the CSV.
- One skill per action: a single action can roll several skills (one stone break rolls every stone skill). The table
  is per skill, not per action.
"""
    open(os.path.join(ROOT, "docs", "DROPRATES.md"), "w", encoding="utf-8").write(md)
    print(f"{len(out)} skills, {len(ranked)} ranked, {len(never)} never drop, {len(one_off)} one-off")


if __name__ == "__main__":
    main()
