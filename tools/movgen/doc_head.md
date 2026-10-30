# Movement and exploration mechanics

Owner: PERKS-MOVEMENT. Package `com.habits.perk.movement` (common) and `com.habits.client.movement` (client only, wires
itself with `@EventBusSubscriber(value = Dist.CLIENT)`, nothing to call). 106 skills, one mechanic each, id
`habits:movement/<skill id>`. Every value is read through the player's own active skill (toggle off = 0, the
attribute modifiers come off on the next 5-tick pass).

## How it is built

- **Bursts are server-authoritative.** Double jump, wall jump, dash, riptide lunge, blink, End arc jump and the Wings of
  Wind launch are a C2S intent (`MovePayloads.Act`); `Actions` re-checks owner, level, ground/air state, walls,
  cooldowns and counters, then sets the velocity (`hurtMarked`). A client can't ask for more jumps than the server
  counts, and the counters reset only when the server sees the player on the ground. None of them work while flying,
  gliding, swimming, climbing or riding.
- **Continuous feel is local-player physics** (`Physics`, common code, runs only for `isLocalPlayer()`), the same place
  vanilla moves the player: climb speed, glide fall cap, wall slide, bubbles, water walking, ice control, parkour,
  bunny hop, boats. Fall damage is still the server's call: glide, wall slide and sky float only clear fall distance
  while the server itself sees a slow descent with the key held (`ServerTick.everyTick`).
- **Held keys** the server can't see (jump, forward, sprint key) come in `MovePayloads.Keys`, sent only when they change.
- **Stats** are transient attribute modifiers with the mechanic id as modifier id (step height, gravity, jump strength,
  sneak speed, swim speed, oxygen, safe fall, movement efficiency, speed). Step height from several skills uses the
  best one, capped at 2.5.
- **Compass bar** (`client.movement.CompassBar`): Pathfinder, Overworlder, Mesa Runner, Mounted Explorer, Outpost Scout,
  Shipwrecked, X Marks the Spot and Spyglass Scout all feed the one bar. The server composes the marker list every
  5 s (`Scans.compose`) and sends it only when it changed. Keys: Z zoom, B waypoint (sneak+B removes), N call mount,
  M fast travel (all rebindable, "Habits: Movement").
- **Outlines** (`client.movement.Outlines`): block lists (loot, traps, suspicious blocks, secret rooms, portal gaps) are
  baked into one static vertex buffer, rebuilt only when a list arrives or expires. Glowing mobs are drawn as boxes
  (no shared Glowing effect, so other players don't see them).

## Mechanics

Values are Lv1 -> Lv10 from the skill JSON. Params: `a..b` lerps Lv1..Lv10, a 10-item list picks by level, a number is
constant. Cooldowns are seconds unless the name says minutes.

| Id | Skill | Level value means | Lv1 -> Lv10 | Params |
|---|---|---|---|---|
