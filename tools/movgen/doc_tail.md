
Minute-valued params: `world_edge` value, `lodestone_link.cooldown`, `portal_restorer.cooldown`, `lost_and_found.delay`.

## Custom trigger events

Fired from `Trackers` / `WorldEvents` for every player (owned skills or not). CUSTOM rolls once per event; CUSTOM_UNITS
rolls per unit (blocks, or seconds for `scope_seconds`), batched once a second.

| Event id | Type | Fired when | Skill (chance) |
|---|---|---|---|
| `habits:long_sprint` | custom | 30 blocks of sprinting within 18 degrees of one heading | dash (1/6) |
| `habits:jump_frenzy` | custom | the 50th jump inside a 60 s window | double_jump (1/4) |
| `habits:wall_jump` | custom | jumping while touching a wall (ground or air), and every wall kick | wall_jump (1/80) |
| `habits:wind_burst` | custom | knocked back by a wind charge explosion (player or breeze) | wings_of_wind (1/3) |
| `habits:compass_walk` | units | blocks walked with a compass in either hand | pathfinder (1/600) |
| `habits:sprint_new_biome` | units | blocks sprinted within 2 min of first ever entering a biome | trailblazer (1/400) |
| `habits:edge_peek` | custom | sneaking on the ground at a 10+ block drop, once per sneak | edge_sense (1/8) |
| `habits:long_walk` | custom | 1000 blocks on foot without damage or 5 s standing still | long_legs (1/3) |
| `habits:marathon` | custom | each new 10 km of walk + sprint stats | marathon (1) |
| `habits:ice_walk` / `soul_walk` / `mud_walk` / `sand_walk` | units | blocks walked on ice / soul blocks / mud or in swamps / sand | ice_skater 1/800, soul_runner 1/600, mud_wader 1/1000, sand_strider 1/1000 |
| `habits:forest_walk` | units | blocks walked in forest or jungle biomes | pathless (1/1000) |
| `habits:slime_bounce` | custom | landing on a slime block from 3+ blocks | slime_rider (1/15) |
| `habits:honey_slide` | units | blocks slid down next to a honey block | honey_glider (1/30) |
| `habits:bubble_ride` | units | blocks moved inside a bubble column | bubble_elevator (1/50) |
| `habits:long_jump` | custom | landing a sprint jump 3+ blocks away (not lower than takeoff) | parkour (1/20) |
| `habits:scaffold_climb` | units | blocks climbed inside scaffolding | scaffold_monkey (1/120) |
| `habits:close_fall` | custom | fall damage from 20+ blocks leaves you under 2 hearts | cat_landing (1/2) |
| `habits:high_dive` | custom | hitting water after falling 50+ blocks | leap_of_faith (1/3) |
| `habits:long_dive` | custom | 60 s with your head underwater in one dive | deep_breath (1/4) |
| `habits:rain_swim` | units | blocks swum while it rains on you | riptide_rider (1/600) |
| `habits:river_swim` | units | blocks swum in a river biome | current_surfer (1/1200) |
| `habits:deep_dive` | custom | below y 30 in a deep ocean, once per dive | pearl_diver (1/3) |
| `habits:glide_rocket` | custom | using a firework rocket while gliding | rocket_saver (1/40) |
| `habits:thermal_glide` | custom | each second gliding over lava, fire, a lit campfire or magma | thermal_rider (1/60) |
| `habits:long_flight` | custom | landing after 1000+ blocks of one glide | flight_deck (1/2) |
| `habits:mount` | custom | mounting anything | saddle_up (1/50) |
| `habits:long_ride` | custom | dismounting after 1000+ blocks in one ride | mounted_explorer (1/3) |
| `habits:ice_boat` | units | blocks boated over ice | ice_road (1/6000) |
| `habits:special_rail` | custom | riding a minecart onto a powered, detector or activator rail | chest_cart_hauler (1/600) |
| `habits:long_voyage` | custom | leaving a boat after 1000+ blocks over ocean in one trip | captain (1/2) |
| `habits:nether_minute` / `end_minute` | custom | each minute spent in the Nether / End | nether_native 1/5, endwalker 1/4 |
| `habits:homecoming` | custom | entering the overworld after 30+ min away | overworlder (1) |
| `habits:far_1000` `far_5000` `far_10000` `far_30000` | custom | first time that far from world spawn (saved per player) | long_haul (1) |
| `habits:gateway_jump` | custom | travelling through an End gateway | gateway_jumper (1/2) |
| `habits:world_edge` | custom | coming within 16 blocks of the world border (once per approach) | world_edge (1) |
| `habits:biomes_20` `biomes_35` `biomes_50` `biomes_64` | custom | distinct biomes visited reach that count (saved per player) | atlas (1) |
| `habits:buried_treasure` | custom | opening an unlooted chest inside a buried treasure | x_marks_the_spot (1) |
| `habits:recovery_compass` | custom | using a recovery compass in the dimension you died in | recovery_run (1/2) |
| `habits:scope_seconds` | units | seconds spent looking through a spyglass | spyglass_scout (1/240) |
| `habits:lost` | custom | 10 min 500+ blocks from bed/spawn with no compass or map | lost_and_found (1/2) |
| `habits:night_walk` | units | blocks walked under open sky at night | night_owl (1/1000) |
| `habits:storm_walk` | units | blocks walked under open sky in a thunderstorm | storm_chaser (1/600) |
| `habits:new_chunk` | custom | being the first player to walk into a freshly generated chunk | explorers_eye (1/30) |
| `habits:sky_high` | custom | going above y 256 in the overworld (once per climb) | sky_watcher (1/4) |
| `habits:vault_unlock` | custom | a trial key is used up on a vault | key_turner (1/3) |

Vanilla triggers used directly: `fall` (glider_kick min 20, ground_slam min 8), `stat_increment` (climb, walk on water,
sneak time, swim, walk under water, fly), `walk/sprint/sneak/elytra/boat_distance`, `jump`, `ride_distance` (by mount),
`enter_dimension`, `enter_biome`, `enter_structure`, `interact_block` (compass on lodestone).

## Performance

Measured by `MovementTests.perTickBudgetAllSkillsLv10` (gametest server, one fake player owning all 106 skills at Lv10,
2000 ticks, standing and stepping in place):

| Path | Cost |
|---|---|
| Server tick, all 106 skills at Lv10 (includes the 5/20/100-tick staggered work) | about 9-23 us average, worst single tick about 0.5 ms (the 5 s scan) |
| Server tick, player with no movement skills (card trackers only) | about 0.5-1 us |
| Local-player physics, all skills | about 2-4 us |
| Marker compose (all marker skills) / outline scans | about 50 us / 15 us, once per 5 s per player |

Budget asserted: average under 50 us, worst tick under 3 ms, idle player under 10 us, physics under 20 us.

- Values come from one pass over the collection, cached until the collection object changes (2 s refresh for /reload);
  each mechanic is an array read. Players without movement skills skip everything but the card trackers.
- Every tick: field reads only. Attributes every 5 ticks, effects/mounts/trackers every 20, scans every 100, all
  staggered by entity id.
- Scans read loaded chunks only (`getChunkNow`, palette `maybeHas` skips whole sections), are capped (48 markers, 64
  blocks, 128 glows, 16 structures) and the tick returns early if the player's own chunk isn't loaded, so nothing here
  can load or generate a chunk. Structure lookups stay inside the server view distance.
- Particles: trail 1 per 4 points per second, footprints 20 mobs max, crumbs 32 max, all sent only to the owner or
  nearby players.
- Client: no allocation per frame in the compass bar (text rebuilt only on block change), outlines are a static
  buffer; input is read once per client tick and sends packets only on change.

## Deviations

No mixin config ships with the mod, so mixin-only parts became event or reflection versions (reflection on Mojang names,
each fails safe by switching the effect off):

- **spider_climb** any-wall climbing is "sneak into a wall to climb it" in local physics instead of an `onClimbable` mixin.
- **jesus_walk** holds you on the surface in local physics (snap to the surface and mark on-ground) instead of fluid collision.
- **runners_lungs** Lv6+ "sprint at any hunger" is a +30% speed boost while sprint+forward are held at 6 food or less
  (vanilla won't let the client start sprinting).
- **edge_sense** is a ledge catch: sneaking, slipping off an edge snaps you back onto it within the reach; fence/wall
  overhang needs a mixin.
- **ice_skater** control is steering your speed along your look while holding forward, braking otherwise.
- **mud_wader** scales `Entity.stuckSpeedMultiplier` by reflection (both sides) and uses movement efficiency on honey.
- **pathless** can't make leaves non-solid without a collision mixin: it lets you step up over leaves and bamboo instead.
- **current_surfer** adds or cancels the flow push in local physics (works on flowing water; still river water has no current).
- **sky_glider** scales elytra speed in local physics instead of patching the drag in `travel`.
- **oarsman / ice_road** push the boat in local physics (boats are client-driven); no extra grip/anti-spin on ice.
- **rail_baron** can't raise the cart speed cap (`getMaxSpeed` is protected, NeoForge only lets you lower the cap): carts you
  ride cruise at a share of the rail top speed without powered rails. Values are now 30% -> 100%.
- **camel_caravan** Lv10 double dash dropped; recharge speed and passenger protection kept.
- **pig_jockey** stick wear is refunded after the use instead of skipping it inside `Pig.boost`.
- **portal_hopper** speeds the portal timer by reflection on `PortalProcessor.portalTime`; portal nausea is untouched (client field).
- **pack_mule** the donkey/llama menu closes when the animal is far, so the key calls the mount to you and opens it.
  The llama caravan speed part is dropped.
- **piglin_disguise** cancels piglin targeting unless they're angry at you (no `isWearingGold` mixin); brutes wait the
  value before their first attack.
- **monument_diver** reduces Mining Fatigue (and turns it into Haste II at Lv10); the guardian beam charge time is
  dropped. Values start at -1 level from Lv1.
- **tomb_raider** immunity (Lv5+) fizzles ownerless TNT near you and blocks ownerless arrows; plates and tripwires still click.
- **trial_taker** wind launch + fall cancel only; trial spawner player count needs a mixin.
- **deep_dark_delver** resets your warden warning level every second (shriekers never summon for you) and mutes your
  vibrations near wardens by chance, instead of halving the warden's hearing range.
- **night_owl** gives a hidden Night Vision at night under open sky instead of a partial gamma bump (no lightmap hook);
  the level value is the spawn-glow radius from Lv5.
- **snow_scout** no snowstorm fog change (vanilla has none outside powder snow).
- **swamp_wader** frogs and slimes following you dropped.
- **village_guide** trade-sense label dropped; golems stay calm from Lv1, Hero of the Village from Lv5.
- **ruin_reader** brush speed dropped (mining's area).
- **shipwrecked / x_marks_the_spot / mounted_explorer / mesa_runner** only see loaded chunks (never load or generate one
  to search); X Marks shows the treasure's structure centre.
- **beachcomber** treasure map at Lv10 dropped (making one searches unloaded chunks); finds come from the `items` param.
- **lost_and_found** the glowing trail is your own recent path (crumbs every 8 blocks), shown near you once lost.
- **gateway_jumper** sneak while going through a gateway to land at your next remembered gateway (cycles) instead of a picker.
- **world_edge** remembers structures you enter while owning the skill (no Cartographer list exists).
- **overworlder** the home arrow is a marker on the shared compass bar.
- **stronghold_seeker** eye range scales its flight target.
- **leap_of_faith** cooldown is per session (not saved).
- **atlas** applies to the biome-gated movement perks in this package (river runner, nether native, peak climber, desert
  nomad heat): outside their biome they run at Atlas% of your skill's value (or its Lv1 value if you don't own it).
- Catalog "Also: ..." second triggers (bunny_hop rabbits, shipwrecked ocean travel, monument_diver prismarine,
  stronghold_seeker eye throws) are dropped: a skill JSON has one trigger.
- trailblazer's "biome you have never been in" counts sprinting within 2 minutes of your first visit.

## Requests

- [PERKS-MOVEMENT -> CORE] a mixin config would let these become exact: `LivingEntity.onClimbable` (spider_climb),
  fluid collision for water walking (jesus_walk), `LocalPlayer` sprint food check (runners_lungs), `Player.maybeBackOffFromEdge`
  (edge_sense), `Block.getFriction` for players (ice_skater), leaves collision with entity context (pathless),
  `AbstractMinecart.getMaxSpeed` (rail_baron), `PiglinAi.isWearingGold` (piglin_disguise), guardian attack time
  (monument_diver), `VaultBlockEntity.Server.unlock` (key_turner, currently detected by key use),
  `TrialSpawner` player count (trial_taker).
- [PERKS-MOVEMENT -> CORE] the `## Mechanics` table in CONTRACT.md should list `habits:movement/*` (106 ids, all in the
  table above); I didn't edit CONTRACT.md per the build brief.
- [PERKS-MOVEMENT -> FX/BOOK-UI] the compass bar sits just under the boss bar layer at the top centre; if another HUD
  wants that spot, say so and I'll move it.
