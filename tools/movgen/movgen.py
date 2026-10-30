"""Generates the movement-exploration skill JSONs + lang part. Run from the worktree root."""
import json, os

SK = "src/main/resources/data/habits/habits_skills"
LANG = "src/main/parts/assets/habits/lang/en_us/movement.json"


def lin(a, b, nd=2):
    return [round(a + (b - a) * i / 9, nd) for i in range(10)]


def ints(a, b):
    return [int(round(a + (b - a) * i / 9)) for i in range(10)]


def T(type_, chance, match=None, item=None, entity=None, mn=None):
    t = {"type": type_}
    if match: t["match"] = match
    if item: t["item"] = item
    if entity: t["entity"] = entity
    if mn is not None: t["min"] = mn
    t["chance"] = round(chance, 7)
    return t


def C(event, chance):
    return T("custom", chance, match="habits:" + event)


def CU(event, chance):
    return T("custom_units", chance, match="habits:" + event)


def STAT(stat, chance_per_unit):
    return T("stat_increment", chance_per_unit, match="minecraft:" + stat)


def BIOME(match, chance):
    return T("enter_biome", chance, match=match)


def STRUCT(match, chance):
    return T("enter_structure", chance, match=match)


S = []


def sk(id, rarity, cat, trig, icon, art, values, name, found, perk, params=None):
    S.append(dict(id=id, rarity=rarity, cat=cat, trig=trig, icon=icon, art=art, values=values,
                  name=name, found=found, perk=perk, params=params or {}))


M, E = "movement", "exploration"
# ---------------------------------------------------------------- top picks
# values = the real ground distance (HABITS_SHOTS=dashlab); push = the ground burst (/2.2 blocks/tick) that produces it.
# A burst is capped at 3.9 blocks/tick total (vanilla's motion packet cap), so Lv9-10 go further with a second burst
# of "boost" blocks/tick one tick later (tuned with dashlab).
sk("dash", "uncommon", M, C("long_sprint", 1 / 6), "minecraft:sugar", "minecraft:smooth_stone",
   # ponytail: Lv10's 13.0 is predicted from the boost fit (2.2 -> +0.0, 2.5 -> +1.12), not yet measured; re-run dashlab
   [3.5, 4.6, 5.7, 6.7, 7.8, 8.9, 10.0, 11.0, 12.0, 13.0],
   "Dash", "sprinting 30 blocks in a straight line", "Double-tap forward to dash %s blocks",
   {"cooldown": "8..2", "iframes": "0.2..0.5", "push": ",".join(str(v) for v in lin(3, 10, 1)),
    "boost": "0,0,0,0,0,0,0,0,2.41,2.67"})
sk("double_jump", "rare", M, C("jump_frenzy", 1 / 4), "minecraft:rabbit_foot", "minecraft:slime_block",
   [1, 1, 1, 1, 1, 1, 2, 2, 2, 2], "Double Jump", "jumping 50 times in a minute", "Jump again in midair (%s extra)",
   {"strength": "0.6..1.0"})
sk("wall_jump", "epic", M, C("wall_jump", 1 / 80), "minecraft:cobblestone_wall", "minecraft:bricks", ints(50, 100),
   "Wall Jump", "jumping off walls", "Jump off walls in midair (%s%% power)",
   {"kicks": "1,1,2,2,3,3,4,5,6,99", "cooldown": "0.4"})
sk("spider_climb", "common", M, STAT("climb_one_cm", 1 / 16000), "minecraft:ladder", "minecraft:jungle_planks",
   ints(20, 200), "Spider Climb", "climbing ladders and vines", "Climb %s%% faster; Lv5+ sneak into any wall to climb it",
   {"wall_level": "5", "stamina": "0,0,0,0,3,4,6,8,12,999"})
sk("glider_kick", "uncommon", M, T("fall", 0.2, mn=20), "minecraft:phantom_membrane", "minecraft:white_wool",
   ints(30, 85), "Glider Kick", "falling 20 blocks", "Hold jump while falling to glide (%s%% slower fall)",
   {"drift": "0.1..0.5"})
sk("ground_slam", "uncommon", M, T("fall", 1 / 6, mn=8), "minecraft:anvil", "minecraft:cracked_stone_bricks",
   lin(2, 7, 1), "Ground Slam", "landing from 8 blocks up", "Big landings slam mobs within %s blocks",
   {"dmg": "0.5..1.5", "cap": "6..30", "min_fall": "4"})
sk("jesus_walk", "rare", M, STAT("walk_on_water_one_cm", 1 / 50000), "minecraft:lily_pad", "minecraft:prismarine",
   [1, 1, 1, 2, 2, 2, 2, 2, 2, 3], "Water Strider", "walking on lily pads and shallow water",
   "Walk on water, tier %s of 3 (sprint, walk, stand)")
sk("wings_of_wind", "legendary", M, C("wind_burst", 1 / 3), "minecraft:wind_charge", "minecraft:tuff_bricks",
   ints(8, 40), "Wings of Wind", "getting blasted by a wind charge",
   "Hold sneak 1 s on the ground with an elytra to launch %s blocks up",
   {"cooldown": "30..3", "food": "3..1", "hold": "1"})
sk("pathfinder", "common", E, CU("compass_walk", 1 / 600), "minecraft:compass", "minecraft:spruce_planks",
   [0, 0, 0, 0, 0, 2, 4, 6, 8, 10], "Pathfinder", "walking with a compass in hand",
   "Compass bar with coords; bed at Lv3, last death at Lv5; %s saved waypoints", {"bed_level": "3", "death_level": "5"})
# ---------------------------------------------------------------- movement
sk("wanderer", "common", M, T("walk_distance", 1 / 1300), "minecraft:leather_boots", "minecraft:gravel", ints(5, 50),
   "Wanderer", "walking", "+%s%% speed on paths, gravel, slabs and stairs; step up blocks from Lv5",
   {"step": "0,0,0,0,0.5,0.5,0.5,0.5,0.5,0.5"})
sk("runners_lungs", "common", M, T("sprint_distance", 1 / 1700), "minecraft:golden_carrot", "minecraft:hay_block",
   ints(2, 20), "Runner's Lungs", "sprinting", "The first %s s of every sprint cost no hunger; Lv6+ run on an empty belly",
   {"any_food_level": "6"})
sk("trailblazer", "uncommon", M, CU("sprint_new_biome", 1 / 400), "minecraft:blaze_powder", "minecraft:moss_block",
   ints(5, 30), "Trailblazer", "sprinting through a biome you just found", "Sprinting leaves a speed trail that lasts %s s",
   {"amp": "0,0,0,0,0,1,1,1,1,1"})
sk("ghost_step", "common", M, T("sneak_distance", 1 / 600), "minecraft:sculk_sensor", "minecraft:black_wool",
   ints(10, 80), "Ghost Step", "sneaking around", "Sneaking: mobs spot you %s%% closer; Lv4+ sculk ignores you",
   {"sculk_level": "4"})
sk("edge_sense", "uncommon", M, C("edge_peek", 1 / 8), "minecraft:scaffolding", "minecraft:stone_bricks",
   lin(0.3, 1.0), "Edge Sense", "sneaking at the edge of a big drop", "Sneaking, you catch ledges up to %s blocks away")
sk("cat_feet", "common", M, STAT("sneak_time", 1 / 24000), "minecraft:string", "minecraft:white_wool", ints(36, 90),
   "Cat Feet", "sneaking for a long time", "Sneak at %s%% of walking speed")
sk("long_legs", "rare", M, C("long_walk", 1 / 3), "minecraft:iron_boots", "minecraft:coarse_dirt", lin(1.0, 2.5, 1),
   "Long Legs", "walking 1000 blocks without getting hurt", "Step up %s blocks without jumping")
sk("marathon", "epic", M, C("marathon", 1), "minecraft:diamond_boots", "minecraft:sandstone", ints(10, 60),
   "Marathon", "walking and running 10 km", "Keep moving to build up to +%s%% speed", {"rate": "1..5"})
sk("ice_skater", "common", M, CU("ice_walk", 1 / 800), "minecraft:ice", "minecraft:packed_ice", ints(10, 80),
   "Ice Skater", "walking on ice", "+%s%% speed on ice; full control from Lv3", {"control_level": "3"})
sk("soul_runner", "uncommon", M, CU("soul_walk", 1 / 600), "minecraft:soul_soil", "minecraft:soul_sand", ints(10, 70),
   "Soul Runner", "walking on soul sand", "Soul sand never slows you, +%s%% speed on it")
sk("mud_wader", "common", M, CU("mud_walk", 1 / 1000), "minecraft:mud", "minecraft:mud", ints(20, 100),
   "Mud Wader", "wading through mud and swamps", "Webs, berry bushes, honey and powder snow slow you %s%% less")
sk("sand_strider", "common", M, CU("sand_walk", 1 / 1000), "minecraft:sand", "minecraft:sand", ints(5, 40),
   "Sand Strider", "walking on sand", "+%s%% speed on sand; step up sand from Lv4",
   {"step": "0,0,0,0.4,0.4,0.4,0.4,0.4,0.4,0.4"})
sk("slime_rider", "uncommon", M, C("slime_bounce", 1 / 15), "minecraft:slime_ball", "minecraft:slime_block",
   ints(20, 70), "Slime Rider", "bouncing on slime blocks", "Big landings bounce you back up %s%% of the height, sneak to stop",
   {"min_fall": "6..3"})
sk("honey_glider", "uncommon", M, CU("honey_slide", 1 / 30), "minecraft:honey_bottle", "minecraft:honey_block",
   lin(4, 1, 1), "Honey Glider", "sliding down honey blocks", "Hold toward a wall while falling to slide at %s blocks/s")
sk("bubble_elevator", "uncommon", M, CU("bubble_ride", 1 / 50), "minecraft:prismarine_shard", "minecraft:sea_lantern",
   ints(4, 16), "Bubble Elevator", "riding bubble columns", "Underwater, hold jump to rise and sneak to sink at %s blocks/s")
sk("parkour", "uncommon", M, C("long_jump", 1 / 20), "minecraft:golden_boots", "minecraft:oak_planks", ints(5, 40),
   "Parkour", "landing long sprint jumps", "Sprint jumps go %s%% farther and you grab ledges you barely miss",
   {"grab": "0.2..0.6"})
sk("pathless", "common", M, CU("forest_walk", 1 / 1000), "minecraft:oak_leaves", "minecraft:oak_leaves",
   [1, 1, 1, 1, 1, 2, 2, 2, 2, 2], "Pathless", "walking through forests and jungles",
   "Pass through leaves and bamboo, tier %s of 2 (sprinting, any pace)")
sk("bunny_hop", "common", M, T("jump", 1 / 300), "minecraft:carrot", "minecraft:moss_block", lin(0.1, 1.0, 1),
   "Bunny Hop", "jumping", "Jump %s blocks higher; chained hops keep your speed", {"keep": "30..100"})
sk("scaffold_monkey", "uncommon", M, CU("scaffold_climb", 1 / 120), "minecraft:scaffolding", "minecraft:bamboo_block",
   lin(1.5, 4, 1), "Scaffold Monkey", "climbing scaffolding", "Scaffolding and ladders: climb and drop x%s faster")
sk("cat_landing", "rare", M, C("close_fall", 1 / 2), "minecraft:cod", "minecraft:white_terracotta", ints(3, 25),
   "Cat Landing", "surviving a big fall on low health", "Fall %s more blocks safely; Lv3+ sneak as you land to roll",
   {"roll_level": "3"})
sk("leap_of_faith", "epic", M, C("high_dive", 1 / 3), "minecraft:water_bucket", "minecraft:hay_block", ints(120, 10),
   "Leap of Faith", "diving into water from 50 blocks up",
   "Hay, slime, beds, snow and webs always catch you; any landing is soft every %s s")
sk("dolphin_kick", "common", M, STAT("swim_one_cm", 1 / 90000), "minecraft:tropical_fish", "minecraft:brain_coral_block",
   ints(10, 120), "Dolphin Kick", "swimming", "Swim %s%% faster; Lv5+ leap out of the water", {"leap_level": "5"})
sk("seabed_walker", "common", M, STAT("walk_under_water_one_cm", 1 / 60000), "minecraft:turtle_helmet", "minecraft:gravel",
   ints(40, 100), "Seabed Walker", "walking on the sea floor", "Walk underwater at %s%% of land speed; Lv5+ jump normally",
   {"jump_level": "5"})
sk("deep_breath", "uncommon", M, C("long_dive", 1 / 4), "minecraft:pufferfish", "minecraft:tube_coral_block",
   ints(1, 10), "Deep Breath", "staying underwater for a minute", "+%s extra air; Lv5+ bubble columns refill your air",
   {"bubble_level": "5"})
sk("riptide_rider", "rare", M, CU("rain_swim", 1 / 600), "minecraft:trident", "minecraft:light_blue_concrete",
   ints(4, 16), "Riptide Rider", "swimming in the rain", "In water or rain, double-tap forward to lunge %s blocks",
   {"cooldown": "10..2"})
sk("current_surfer", "common", M, CU("river_swim", 1 / 1200), "minecraft:salmon", "minecraft:clay", lin(1.2, 3, 1),
   "Current Surfer", "swimming down rivers", "Currents push you x%s harder and barely hold you back",
   {"resist": "20..90"})
sk("pearl_diver", "rare", M, C("deep_dive", 1 / 3), "minecraft:nautilus_shell", "minecraft:dark_prismarine",
   ints(8, 64), "Pearl Diver", "diving deep in the ocean", "Night vision underwater; ocean loot within %s blocks glows")
sk("sky_glider", "rare", M, T("elytra_distance", 1 / 3000), "minecraft:elytra", "minecraft:end_stone_bricks",
   ints(5, 50), "Sky Glider", "flying with an elytra", "Elytra loses %s%% less speed; Lv10 glide level forever",
   {"level_glide_level": "10"})
sk("rocket_saver", "uncommon", M, C("glide_rocket", 1 / 40), "minecraft:firework_rocket", "minecraft:purpur_block",
   ints(10, 60), "Rocket Saver", "boosting with rockets", "%s%% chance a rocket boost is free; boosts last longer",
   {"duration": "10..80"})
sk("thermal_rider", "epic", M, C("thermal_glide", 1 / 60), "minecraft:campfire", "minecraft:magma_block",
   lin(0.4, 3, 1), "Thermal Rider", "gliding over fire and lava",
   "Gliding over heat, water or rain lifts you %s blocks/s; Lv10 at dawn and dusk anywhere", {"anywhere_level": "10"})
sk("flight_deck", "epic", M, C("long_flight", 1 / 2), "minecraft:phantom_membrane", "minecraft:purpur_pillar",
   lin(0.5, 5, 1), "Flight Deck", "gliding 1000 blocks in one flight",
   "Walking repairs your worn elytra, %s points per 100 blocks")
sk("feather_body", "uncommon", M, STAT("fly_one_cm", 1 / 100000), "minecraft:feather", "minecraft:quartz_block",
   ints(5, 50), "Feather Body", "hanging in the air", "Gravity -%s%%")
sk("horse_whisperer", "common", M, T("ride_distance", 1 / 2000, match="minecraft:horse"), "minecraft:saddle",
   "minecraft:hay_block", ints(5, 60), "Horse Whisperer", "riding horses", "Your horse runs and jumps %s%% better")
sk("pack_mule", "uncommon", M, T("ride_distance", 1 / 800,
                                 match=["minecraft:donkey", "minecraft:mule", "minecraft:llama", "minecraft:trader_llama"]),
   "minecraft:chest", "minecraft:barrel", ints(8, 64), "Pack Mule", "riding donkeys, mules and llamas",
   "Mount key: call your chested mount from %s blocks and open it")
sk("camel_caravan", "uncommon", M, T("ride_distance", 1 / 800, match="minecraft:camel"), "minecraft:cactus",
   "minecraft:smooth_sandstone", ints(10, 80), "Camel Caravan", "riding camels",
   "Camel dash recharges %s%% faster and your passengers take no damage")
sk("pig_jockey", "uncommon", M, T("ride_distance", 1 / 300, match="minecraft:pig"), "minecraft:carrot_on_a_stick",
   "minecraft:pink_wool", ints(20, 200), "Pig Jockey", "riding pigs", "Pigs you ride go %s%% faster; your stick wears slower",
   {"stick": "10..100"})
sk("lava_strider", "rare", M, T("ride_distance", 1 / 300, match="minecraft:strider"),
   "minecraft:warped_fungus_on_a_stick", "minecraft:netherrack", ints(20, 150), "Lava Strider", "riding striders",
   "Striders go %s%% faster; Lv6+ you both ignore fire", {"fire_level": "6"})
sk("saddle_up", "common", M, C("mount", 1 / 50), "minecraft:lead", "minecraft:oak_log", ints(5, 50), "Saddle Up",
   "mounting animals", "Mounts you ride heal and take %s%% less damage; Lv10 whistle them over",
   {"regen": "0.5..4", "whistle": "0,0,0,0,0,0,0,0,0,64"})
sk("mounted_explorer", "rare", M, C("long_ride", 1 / 3), "minecraft:leather_horse_armor", "minecraft:birch_planks",
   ints(16, 96), "Mounted Explorer", "riding 1000 blocks in one go",
   "Riding: night vision, ores and structures within %s blocks show on your compass bar")
sk("oarsman", "common", M, T("boat_distance", 1 / 2400), "minecraft:oak_boat", "minecraft:stripped_oak_log",
   ints(10, 100), "Oarsman", "rowing boats", "Boats go %s%% faster; Lv4+ drive up onto 1-block shores",
   {"shore_level": "4"})
sk("ice_road", "uncommon", M, CU("ice_boat", 1 / 6000), "minecraft:blue_ice", "minecraft:blue_ice", lin(0.5, 1.5, 1),
   "Ice Road", "boating on ice", "On ice your boat grips instead of spinning out; press jump to hop %s blocks",
   {"cooldown": "3..0.5"})
sk("rail_baron", "common", M, T("ride_distance", 1 / 3000, match="minecraft:minecart"), "minecraft:minecart",
   "minecraft:iron_block", lin(1.2, 2.5, 2), "Rail Baron", "riding minecarts",
   "Minecarts you ride reach x%s top speed; Lv10 plain track never slows you", {"coast_level": "10"})
sk("chest_cart_hauler", "uncommon", M, C("special_rail", 1 / 600), "minecraft:chest_minecart", "minecraft:redstone_block",
   lin(2, 10, 1), "Cart Hauler", "riding over powered and detector rails", "In a minecart, items within %s blocks fly to you")
sk("captain", "rare", M, C("long_voyage", 1 / 2), "minecraft:dark_oak_boat", "minecraft:dark_oak_planks",
   ints(20, 100), "Captain", "sailing 1000 blocks across the ocean",
   "Drowned and guardians ignore you %s%% of the time in a boat; your passengers breathe water")
# ---------------------------------------------------------------- exploration
sk("portal_hopper", "uncommon", E, T("enter_dimension", 1 / 6, match="minecraft:the_nether"),
   "minecraft:flint_and_steel", "minecraft:obsidian", lin(4, 0.2, 1), "Portal Hopper", "travelling to the Nether",
   "Nether portals take %s s; Lv10 no portal nausea and a short Resistance on arrival",
   {"resistance": "0,0,0,0,0,0,0,0,0,5", "calm_level": "10"})
sk("nether_native", "common", E, C("nether_minute", 1 / 5), "minecraft:netherrack", "minecraft:netherrack",
   ints(5, 50), "Nether Native", "spending time in the Nether", "+%s%% speed on Nether ground; ghasts lose you sooner",
   {"ghast": "10..70"})
sk("endwalker", "uncommon", E, C("end_minute", 1 / 4), "minecraft:ender_pearl", "minecraft:end_stone", ints(4, 16),
   "Endwalker", "spending time in the End", "Endermen ignore your stare; sneak and jump to blink %s blocks",
   {"cooldown": "20..2"})
sk("overworlder", "common", E, C("homecoming", 1), "minecraft:red_bed", "minecraft:oak_planks",
   [1] * 10, "Overworlder", "coming home after a long trip",
   "Your compass bar points home; distance from Lv5, last death at Lv10", {"distance_level": "5", "death_level": "10"})
sk("long_haul", "rare", E, T("custom", 1, match=["habits:far_1000", "habits:far_5000", "habits:far_10000", "habits:far_30000"]),
   "minecraft:map", "minecraft:mossy_cobblestone", ints(10, 60), "Long Haul", "travelling far from spawn",
   "Up to +%s%% speed the farther you are from spawn", {"rate": "1..5"})
sk("gateway_jumper", "epic", E, C("gateway_jump", 1 / 2), "minecraft:end_crystal", "minecraft:purpur_block",
   ints(1, 20), "Gateway Jumper", "jumping through an End gateway",
   "Sneak into an End gateway to go to one of %s remembered gateways instead")
sk("world_edge", "legendary", E, C("world_edge", 1), "minecraft:chorus_fruit", "minecraft:bedrock", ints(120, 10),
   "World's Edge", "reaching the world border", "Travel key: warp to a structure you've visited, every %s min",
   {"cross_level": "10"})
sk("forest_ranger", "common", E, BIOME(["#minecraft:is_forest", "minecraft:cherry_grove", "minecraft:old_growth_pine_taiga",
                                        "minecraft:old_growth_spruce_taiga"], 0.2), "minecraft:oak_sapling", "minecraft:oak_log",
   ints(20, 100), "Forest Ranger", "exploring forests", "Falls onto leaves hurt %s%% less")
sk("desert_nomad", "common", E, BIOME("minecraft:desert", 0.2), "minecraft:dead_bush", "minecraft:sandstone",
   ints(10, 100), "Desert Nomad", "crossing deserts",
   "Sprinting in hot places costs %s%% less hunger; husks lose you; Lv10 Speed in desert rain",
   {"husk": "10..70", "storm_level": "10"})
sk("mesa_runner", "uncommon", E, BIOME("#minecraft:is_badlands", 0.25), "minecraft:raw_gold", "minecraft:terracotta",
   ints(8, 48), "Mesa Runner", "exploring badlands", "Gold ore within %s blocks shows on your compass bar")
sk("jungle_explorer", "common", E, BIOME("#minecraft:is_jungle", 0.2), "minecraft:vine", "minecraft:jungle_log",
   lin(2, 10, 1), "Jungle Explorer", "exploring jungles", "Jump off vines to fling %s blocks")
sk("taiga_tracker", "common", E, BIOME("#minecraft:is_taiga", 0.2), "minecraft:sweet_berries", "minecraft:spruce_log",
   ints(5, 60), "Taiga Tracker", "exploring taigas", "Mobs you hit leave footprints for %s s")
sk("savanna_stalker", "common", E, BIOME("#minecraft:is_savanna", 0.2), "minecraft:acacia_sapling", "minecraft:acacia_log",
   lin(2, 10, 1), "Savanna Stalker", "exploring savannas", "Zoom key: x%s zoom; Lv5+ attack while zoomed to mark mobs",
   {"mark": "0,0,0,0,5,10,15,20,25,30"})
sk("peak_climber", "uncommon", E, BIOME(["#minecraft:is_mountain", "minecraft:stony_peaks"], 0.25), "minecraft:goat_horn",
   "minecraft:calcite", ints(10, 80), "Peak Climber", "climbing mountains",
   "Above y 100 you jump %s%% higher and fall softer; goats barely push you", {"goat": "20..100", "min_y": "100"})
sk("snow_scout", "common", E, BIOME(["#minecraft:spawns_snow_foxes", "#minecraft:spawns_white_rabbits"], 0.2),
   "minecraft:snowball", "minecraft:snow_block", ints(10, 100), "Snow Scout", "exploring snowy places",
   "Powder snow freezes you %s%% slower")
sk("mushroom_pilgrim", "rare", E, BIOME("minecraft:mushroom_fields", 0.5), "minecraft:red_mushroom",
   "minecraft:red_mushroom_block", ints(4, 24), "Mushroom Pilgrim", "finding mushroom fields",
   "Stand still 5 s and no monsters spawn within %s blocks")
sk("swamp_wader", "common", E, BIOME(["minecraft:swamp", "minecraft:mangrove_swamp"], 0.2), "minecraft:mangrove_propagule",
   "minecraft:mud_bricks", ints(10, 70), "Swamp Wader", "wading through swamps",
   "Poison and slowness from mobs wear off %s%% sooner")
sk("beachcomber", "common", E, BIOME(["#minecraft:is_beach", "minecraft:stony_shore"], 0.2), "minecraft:prismarine_shard",
   "minecraft:sand", lin(1.7, 10, 1), "Beachcomber", "walking along beaches",
   "Beaches: about %s finds every 1000 blocks walked",
   {"items": "minecraft:nautilus_shell,minecraft:prismarine_shard,minecraft:prismarine_crystals,minecraft:kelp,minecraft:sea_pickle,minecraft:ink_sac"})
sk("cave_dweller", "common", E, BIOME(["minecraft:dripstone_caves", "minecraft:lush_caves"], 0.2),
   "minecraft:pointed_dripstone", "minecraft:dripstone_block", ints(8, 32), "Cave Dweller", "exploring caves",
   "Double-tap sneak to see mobs within %s blocks through walls", {"cooldown": "10..2"})
sk("deep_dark_delver", "epic", E, BIOME("minecraft:deep_dark", 0.5), "minecraft:sculk_shrieker", "minecraft:sculk",
   ints(10, 80), "Deep Dark Delver", "entering the deep dark",
   "Sculk shriekers ignore you; wardens hear you from %s%% less far")
sk("crimson_wanderer", "uncommon", E, BIOME(["minecraft:crimson_forest", "minecraft:warped_forest"], 0.25),
   "minecraft:crimson_fungus", "minecraft:crimson_stem", ints(10, 80), "Crimson Wanderer", "exploring Nether forests",
   "Piglins and hoglins spot you %s%% closer; endermen stay calm in warped forests")
sk("delta_walker", "uncommon", E, BIOME(["minecraft:basalt_deltas", "minecraft:soul_sand_valley"], 0.25),
   "minecraft:magma_cream", "minecraft:blackstone", ints(20, 100), "Delta Walker", "crossing basalt deltas",
   "Magma cubes knock you back %s%% less")
sk("end_islander", "rare", E, BIOME(["minecraft:end_highlands", "minecraft:end_midlands", "minecraft:small_end_islands",
                                     "minecraft:end_barrens"], 1 / 3), "minecraft:chorus_flower", "minecraft:end_stone",
   ints(6, 30), "End Islander", "exploring the outer End islands",
   "Jump off the edge of an End island to arc %s blocks forward", {"cooldown": "30..5"})
sk("river_runner", "common", E, BIOME("#minecraft:is_river", 0.2), "minecraft:cod_bucket", "minecraft:gravel",
   ints(5, 40), "River Runner", "following rivers", "By rivers: +%s%% speed and Dolphin's Grace in the water")
sk("meadow_breeze", "common", E, BIOME(["minecraft:meadow", "minecraft:flower_forest", "minecraft:sunflower_plains",
                                        "minecraft:cherry_grove"], 0.2), "minecraft:honeycomb", "minecraft:honeycomb_block",
   lin(1, 3, 1), "Meadow Breeze", "exploring meadows", "Bees leave you alone; walking over flowers gives Speed for %s s",
   {"amp": "0,0,0,0,0,1,1,1,1,1"})
sk("atlas", "legendary", E, T("custom", 1, match=["habits:biomes_20", "habits:biomes_35", "habits:biomes_50", "habits:biomes_64"]),
   "minecraft:globe_banner_pattern", "minecraft:bookshelf", ints(10, 100), "Atlas", "visiting lots of biomes",
   "Biome perks work everywhere at %s%% strength")
sk("village_guide", "common", E, STRUCT(["minecraft:village_plains", "minecraft:village_desert", "minecraft:village_savanna",
                                         "minecraft:village_snowy", "minecraft:village_taiga"], 0.25), "minecraft:emerald",
   "minecraft:cobblestone", [0, 0, 0, 0, 1, 1, 1, 1, 1, 2], "Village Guide", "visiting villages",
   "Iron golems never turn on you; Hero of the Village level %s near villagers")
sk("outpost_scout", "uncommon", E, STRUCT("minecraft:pillager_outpost", 1 / 3), "minecraft:crossbow",
   "minecraft:dark_oak_log", ints(32, 160), "Outpost Scout", "finding pillager outposts",
   "Raiders within %s blocks show on your compass bar")
sk("tomb_raider", "uncommon", E, STRUCT(["minecraft:desert_pyramid", "minecraft:jungle_pyramid"], 1 / 3),
   "minecraft:tripwire_hook", "minecraft:chiseled_sandstone", ints(4, 24), "Tomb Raider", "entering temples",
   "Traps within %s blocks glow; Lv5+ the world's own pressure plates and tripwires ignore you", {"immune_level": "5"})
sk("witch_hunter", "uncommon", E, STRUCT("minecraft:swamp_hut", 1 / 3), "minecraft:splash_potion", "minecraft:spruce_planks",
   ints(10, 70), "Witch Hunter", "finding a witch hut", "Witch potions miss you %s%% of the time")
sk("igloo_finder", "rare", E, STRUCT("minecraft:igloo", 1 / 2), "minecraft:snow", "minecraft:snow_block", lin(0.5, 2, 1),
   "Igloo Finder", "finding an igloo", "Standing still in snowy places heals %s HP every few seconds; you never freeze",
   {"interval": "10..3"})
sk("shipwrecked", "common", E, STRUCT(["minecraft:shipwreck", "minecraft:shipwreck_beached"], 1 / 4),
   "minecraft:spruce_boat", "minecraft:stripped_spruce_log", ints(64, 512), "Shipwrecked", "exploring shipwrecks",
   "Shipwrecks and ocean ruins within %s blocks show on your compass bar")
sk("ruin_reader", "uncommon", E, STRUCT(["minecraft:ocean_ruin_cold", "minecraft:ocean_ruin_warm", "minecraft:trail_ruins"], 1 / 3),
   "minecraft:brush", "minecraft:suspicious_sand", ints(4, 32), "Ruin Reader", "exploring ruins",
   "Suspicious sand and gravel within %s blocks glow")
sk("x_marks_the_spot", "rare", E, C("buried_treasure", 1), "minecraft:heart_of_the_sea", "minecraft:sand",
   ints(64, 1024), "X Marks the Spot", "digging up buried treasure",
   "Hold a map to mark buried treasure within %s blocks on your compass bar")
sk("mine_rat", "common", E, STRUCT(["minecraft:mineshaft", "minecraft:mineshaft_mesa"], 1 / 4), "minecraft:rail",
   "minecraft:oak_planks", ints(10, 60), "Mine Rat", "exploring mineshafts",
   "+%s%% speed on rails and planks in mineshafts; Lv6+ no poison there", {"poison_level": "6"})
sk("monument_diver", "epic", E, STRUCT("minecraft:monument", 1 / 2), "minecraft:prismarine_crystals",
   "minecraft:prismarine_bricks", [1, 1, 1, 1, 2, 2, 2, 2, 2, 3], "Monument Diver", "raiding ocean monuments",
   "Mining Fatigue on you is %s levels weaker and guardian beams charge slower; Lv10 the fatigue turns into Haste II",
   {"charge": "20..150"})
sk("mansion_raider", "epic", E, STRUCT("minecraft:mansion", 1 / 2), "minecraft:totem_of_undying",
   "minecraft:dark_oak_planks", ints(8, 48), "Mansion Raider", "exploring a woodland mansion",
   "Secret rooms within %s blocks show as outlines")
sk("fortress_crawler", "rare", E, STRUCT("minecraft:fortress", 1 / 2), "minecraft:blaze_rod", "minecraft:nether_bricks",
   ints(20, 100), "Fortress Crawler", "exploring Nether fortresses",
   "On nether brick you take %s%% less knockback; blazes lose you out of sight", {"blaze": "5..0.5"})
sk("piglin_disguise", "epic", E, STRUCT("minecraft:bastion_remnant", 1 / 2), "minecraft:gold_ingot",
   "minecraft:gilded_blackstone", lin(0.5, 4, 1), "Piglin Disguise", "exploring bastions",
   "Piglins treat you like you wear gold; brutes wait %s s before attacking")
sk("fossil_finder", "uncommon", E, STRUCT("minecraft:nether_fossil", 1 / 3), "minecraft:bone", "minecraft:bone_block",
   ints(8, 48), "Fossil Finder", "finding Nether fossils", "Skeletons within %s blocks show through walls")
sk("portal_restorer", "common", E, STRUCT("#minecraft:ruined_portal", 1 / 4), "minecraft:crying_obsidian",
   "minecraft:crying_obsidian", ints(1, 6), "Portal Restorer", "finding ruined portals",
   "Lighting a broken portal shows its gaps and fills up to %s of them", {"cooldown": "10"})
sk("stronghold_seeker", "epic", E, STRUCT("minecraft:stronghold", 1 / 2), "minecraft:ender_eye", "minecraft:stone_bricks",
   ints(10, 100), "Stronghold Seeker", "finding a stronghold",
   "Thrown eyes of ender break %s%% less often and fly farther", {"distance": "1.5..4"})
sk("ancient_echo", "legendary", E, STRUCT("minecraft:ancient_city", 1 / 3), "minecraft:echo_shard",
   "minecraft:deepslate_tiles", ints(10, 100), "Ancient Echo", "finding an ancient city",
   "%s%% of your sounds go unheard; Darkness fades faster", {"darkness": "20..100"})
sk("trial_taker", "rare", E, STRUCT("minecraft:trial_chambers", 1 / 2), "minecraft:trial_key", "minecraft:chiseled_tuff",
   ints(20, 150), "Trial Taker", "entering trial chambers",
   "Wind charges launch you %s%% higher with no fall damage; Lv5+ trial and ominous vaults give you one extra item",
   {"vault_level": "5"})
sk("lodestone_link", "rare", E, T("interact_block", 1 / 3, match="minecraft:lodestone", item="minecraft:compass"),
   "minecraft:lodestone", "minecraft:chiseled_stone_bricks", lin(10, 2, 1), "Lodestone Link",
   "linking a compass to a lodestone", "Sneak and use a lodestone compass to warp to it after %s s",
   {"cooldown": "30..2"})
sk("recovery_run", "rare", E, C("recovery_compass", 1 / 2), "minecraft:recovery_compass", "minecraft:soul_soil",
   [1, 1, 1, 2, 2, 2, 2, 3, 3, 3], "Recovery Run", "using a recovery compass",
   "After respawning: Speed %s until you reach your death spot; Lv10 your items never vanish",
   {"duration": "30..600", "keep_level": "10"})
sk("spyglass_scout", "common", E, CU("scope_seconds", 1 / 240), "minecraft:spyglass", "minecraft:copper_block",
   ints(1, 10), "Spyglass Scout", "looking through a spyglass",
   "Attack while using a spyglass to drop up to %s markers on your compass bar", {"seconds": "30..600"})
sk("lost_and_found", "uncommon", E, C("lost", 1 / 2), "minecraft:glow_berries", "minecraft:mossy_stone_bricks",
   ints(64, 512), "Lost and Found", "getting lost far from home",
   "Lost far from home? A glowing trail of your last %s blocks leads back", {"delay": "10..0"})
sk("night_owl", "common", E, CU("night_walk", 1 / 1000), "minecraft:glow_ink_sac", "minecraft:black_concrete",
   ints(10, 80), "Night Owl", "walking outside at night",
   "+%s%% brightness under the night sky; Lv5+ mobs spawning near you glow", {"glow_level": "5", "glow": "8..32"})
sk("storm_chaser", "rare", E, CU("storm_walk", 1 / 600), "minecraft:lightning_rod", "minecraft:oxidized_copper",
   [1, 1, 1, 2, 2, 2, 2, 3, 3, 3], "Storm Chaser", "walking through thunderstorms",
   "Speed %s in thunderstorms; Lv5+ lightning can't hit you", {"redirect_level": "5"})
sk("explorers_eye", "rare", E, C("new_chunk", 1 / 30), "minecraft:filled_map", "minecraft:rooted_dirt", ints(5, 40),
   "Explorer's Eye", "walking into brand new land", "+%s%% speed in fresh land, with a chance of a small find",
   {"pouch": "200..20", "items": "minecraft:emerald,minecraft:iron_nugget,minecraft:gold_nugget,minecraft:amethyst_shard,minecraft:bread"})
sk("sky_watcher", "epic", E, C("sky_high", 1 / 4), "minecraft:light_gray_stained_glass", "minecraft:white_concrete",
   ints(50, 90), "Sky Watcher", "standing at the top of the world",
   "Falling from high up: look up to float (%s%% slower), look down to dive", {"min_y": "100..-64"})
sk("key_turner", "rare", E, C("vault_unlock", 1 / 3), "minecraft:ominous_trial_key", "minecraft:copper_block",
   ints(10, 100), "Key Turner", "unlocking vaults", "Vaults you open have a %s%% chance to pay out again",
   {"heavy_core": "0,0,0,0,0,0,0,0,0,5"})

# singular perk lines, used when the level value is exactly 1
ONE = {
    "edge_sense": "Sneaking, you catch ledges up to %s block away",
    "long_legs": "Step up %s block without jumping",
    "honey_glider": "Hold toward a wall while falling to slide at %s block/s",
    "bunny_hop": "Jump %s block higher; chained hops keep your speed",
    "thermal_rider": "Gliding over heat, water or rain lifts you %s block/s; Lv10 at dawn and dusk anywhere",
    "flight_deck": "Walking repairs your worn elytra, %s point per 100 blocks",
    "monument_diver": "Mining Fatigue on you is %s level weaker and guardian beams charge slower; Lv10 the fatigue turns into Haste II",
    "spyglass_scout": "Attack while using a spyglass to drop up to %s marker on your compass bar",
}

if __name__ == "__main__":
    lang = {}
    # drop chances come from tools/droprates/calibrate.py (edit its rate tables, not the numbers above)
    CHANCES = json.load(open("tools/droprates/chances.json", encoding="utf-8"))
    for s in S:
        perk = {"mechanic": "habits:movement/" + s["id"]}
        if s["params"]: perk["params"] = s["params"]
        perk["values"] = s["values"]
        trig = {**s["trig"], "chance": CHANCES.get(s["id"], s["trig"]["chance"])}
        j = {"rarity": s["rarity"], "category": s["cat"], "trigger": trig, "icon": s["icon"], "art": s["art"], "perk": perk}
        with open(os.path.join(SK, s["id"] + ".json"), "w", newline="\n") as f:
            json.dump(j, f, indent=2)
            f.write("\n")
        lang["skill.habits." + s["id"]] = s["name"]
        lang["skill.habits." + s["id"] + ".found_by"] = s["found"]
        lang["mechanic.habits.movement." + s["id"]] = s["perk"]
        if s["id"] in ONE: lang["mechanic.habits.movement." + s["id"] + ".one"] = ONE[s["id"]]
    lang.update(json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "extra_lang.json"))))
    for k, v in lang.items():
        assert "—" not in v, k
    with open(LANG, "w", newline="\n") as f:
        json.dump(lang, f, indent=2, ensure_ascii=True)
        f.write("\n")
    print(len(S), "skills")
    print(",".join('"' + s["id"] + '"' for s in S))
