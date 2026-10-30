package com.habits.perk.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.vault.VaultConfig;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * What the movement mixins ({@code com.habits.perk.movement.mixin}) call. Every hook returns the vanilla value unless
 * the entity is a player who owns the skill: an instanceof check, then one map read ({@link MoveState#of}).
 */
public final class MoveHooks {
    private MoveHooks() {}

    /** Night Owl gamma bonus for the local player: set once per client tick by {@link Physics}, read by the lightmap. */
    public static float nightGamma;

    /** Top of a still water source (8/9 of a block, the visible surface). */
    private static final VoxelShape WATER_TOP = Shapes.box(0, 0, 0, 1, 8 / 9.0, 1);

    @Nullable
    static MoveState owner(@Nullable Entity e, Mech m) {
        if (!(e instanceof Player p) || !Mv.ok(p)) return null;
        MoveState s = MoveState.of(p);
        return s.has(m) ? s : null;
    }

    @Nullable
    private static Player player(CollisionContext ctx) {
        return ctx instanceof EntityCollisionContext ec && ec.getEntity() instanceof Player p ? p : null;
    }

    // ---- Spider Climb: LivingEntity.onClimbable, both sides

    public static boolean climbable(LivingEntity e, boolean vanilla) {
        if (vanilla || !(e instanceof Player p) || !Mv.ok(p)) return vanilla;
        MoveState s = MoveState.of(p);
        return wallClimb(p, s) || SpecMoves.thinClimb(p, s) || chimney(p, s);
    }

    /** Chimney Climber: holding jump in midair inside a 1-wide shaft braces you like a ladder while stamina lasts. */
    static boolean chimney(Player p, MoveState s) {
        return s.has(Mech.CHIMNEY_CLIMBER) && !p.onGround() && (s.keys & MovePayloads.K_JUMP) != 0 && !p.getAbilities().flying
                && !p.isFallFlying() && s.shaftTicks < s.v(Mech.CHIMNEY_CLIMBER) * 20 && SpecMoves.shaft(p);
    }

    // ---- Fireman's Pole: LivingEntity.handleOnClimbable return, both sides

    /** Looking down past the pitch on a ladder or vine, not pushing forward, jumping or sneaking: slide at the skill's speed. */
    public static Vec3 pole(LivingEntity e, Vec3 v) {
        if (!(e instanceof Player p) || !p.onClimbable()) return v;
        MoveState s = owner(p, Mech.FIREMAN_POLE);
        if (s == null || p.isShiftKeyDown() || p.getXRot() < s.p(Mech.FIREMAN_POLE, "pitch")
                || (s.keys & (MovePayloads.K_FORWARD | MovePayloads.K_JUMP)) != 0) return v;
        return new Vec3(v.x, -s.v(Mech.FIREMAN_POLE) / 20, v.z);
    }

    // ---- Snowshoes: PowderSnowBlock.canEntityWalkOnPowderSnow return, both sides

    public static boolean snowWalk(Entity e, boolean vanilla) {
        if (vanilla) return true;
        MoveState s = owner(e, Mech.SNOWSHOES);
        return s != null && s.lv(Mech.SNOWSHOES) >= s.p(Mech.SNOWSHOES, "powder_level");
    }

    // ---- Steady Stride: the 0.2 input scale while using an item in LocalPlayer.aiStep, client

    public static float useSlow(Player p, float vanilla) {
        MoveState s = owner(p, Mech.STEADY_STRIDE);
        return s == null ? vanilla : (float) Math.max(vanilla, s.v(Mech.STEADY_STRIDE) / 100);
    }

    // ---- Thunder Spin: Player.isInWaterOrRain() in TridentItem.use / releaseUsing, both sides

    /** Riptide counts as wet for an owner; a dry launch (server) puts tridents on the skill's cooldown, which also
     * blocks the next use on both sides (vanilla item cooldowns are synced). */
    public static boolean dryRiptide(LivingEntity e, boolean vanilla, boolean launch) {
        if (vanilla) return true;
        MoveState s = owner(e, Mech.THUNDER_SPIN);
        if (s == null) return false;
        if (launch && e instanceof ServerPlayer p)
            p.getCooldowns().addCooldown(net.minecraft.world.item.Items.TRIDENT, (int) Math.round(s.v(Mech.THUNDER_SPIN) * 20));
        return true;
    }

    /** Lv5+: sneaking into a wall makes it climbable while stamina lasts (vanilla then climbs at 0.2 b/t). */
    static boolean wallClimb(Player p, MoveState s) {
        return s.has(Mech.SPIDER_CLIMB) && p.horizontalCollision && p.isShiftKeyDown() && !p.getAbilities().flying
                && s.lv(Mech.SPIDER_CLIMB) >= s.p(Mech.SPIDER_CLIMB, "wall_level")
                && s.climbStamina < s.p(Mech.SPIDER_CLIMB, "stamina") * 20;
    }

    // ---- Water Strider: BlockStateBase.getCollisionShape, both sides (the server's move check sees the same floor)

    /** A water source with open air above becomes a floor at its visible top: plain water, kelp, seagrass, bubble
     * columns and waterlogged blocks alike. Tier 1 sprinting, tier 2 moving, tier 3 always. Sneak or fall from more
     * than 3 blocks ({@link #fellThrough}) to go in. */
    public static VoxelShape waterFloor(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx, VoxelShape vanilla) {
        if (state.getFluidState().getType() != Fluids.WATER) return vanilla; // the hot path: one compare for every other block
        Player p = player(ctx);
        if (p == null || !level.getFluidState(pos.above()).isEmpty() || !ctx.isAbove(WATER_TOP, pos, true)) return vanilla;
        MoveState s = owner(p, Mech.JESUS_WALK);
        // no isFallFlying() check: an elytra glide that comes down on water lands on it (landing ends the flight)
        if (s == null || p.isShiftKeyDown() || fellThrough(p, s) || p.getAbilities().flying) return vanilla;
        int tier = (int) s.v(Mech.JESUS_WALK);
        boolean ok = tier >= 3 || p.isSprinting() || (tier >= 2 && (s.keys & MovePayloads.K_FORWARD) != 0);
        return !ok ? vanilla : vanilla.isEmpty() ? WATER_TOP : Shapes.or(vanilla, WATER_TOP);
    }

    /** True when {@link #waterFloor} holds this player on the surface of this block, so a bubble column under them
     * leaves them alone. A player in the water (below the surface) fails the floor's own above-the-top check. */
    public static boolean surfaceHeld(Entity e, BlockState state, BlockGetter level, BlockPos pos) {
        return e instanceof Player p && waterFloor(state, level, pos, CollisionContext.of(p), Shapes.empty()) == WATER_TOP;
    }

    /** Highest point since the player last stood, swam, climbed or glided. Both sides call this once per player tick,
     * after the tick's move, from the same positions (the server's are the client's packets) and the same synced keys,
     * so they agree on it. Vanilla fallDistance does not: the server updates it at packet time. */
    static void trackFall(Player p, MoveState s) {
        double y = p.getY(), dy = y - s.fallLastY;
        s.fallLastY = y;
        boolean reset = p.onGround() || p.isInWater() || p.onClimbable() || p.isFallFlying() || perkGlide(p, s, dy);
        s.fallTop = reset ? y : Math.max(s.fallTop, y);
    }

    /** Glider Kick, Honey Glider or Sky Watcher holding a slow descent ({@code dy} = y moved last tick). Also the
     * server's rule for cancelling fall damage, so a glide that saves you from a fall also keeps you on the water. */
    static boolean perkGlide(Player p, MoveState s, double dy) {
        if (p.onGround() || !(dy < 0) || p.isFallFlying() || p.isInWater() || p.isPassenger()) return false;
        return (s.has(Mech.GLIDER_KICK) && (s.keys & MovePayloads.K_JUMP) != 0 && dy > -(ServerTick.glideMaxFall(s) + 0.15))
                || (s.has(Mech.HONEY_GLIDER) && (s.keys & MovePayloads.K_FORWARD) != 0
                    && dy > -(s.v(Mech.HONEY_GLIDER) / 20 + 0.15) && Mv.wall(p) != null)
                || (s.has(Mech.SKY_WATCHER) && p.getXRot() < -20 && p.getY() >= s.p(Mech.SKY_WATCHER, "min_y")
                    && dy > -(ServerTick.skyFloatMaxFall(s) + 0.15));
    }

    /** The one sink rule for both sides: dropped more than 3 blocks since {@link #trackFall}'s high point. */
    static boolean fellThrough(Player p, MoveState s) {
        return s.fallTop - p.getY() > 3;
    }

    // ---- Ice Skater: LivingEntity.travel ground friction (runs for the local player)

    /** From control_level, ice grips like normal ground (friction 0.6); the speed bonus is the attribute. */
    public static float friction(LivingEntity e, float vanilla) {
        if (vanilla <= 0.6f || !(e instanceof Player)) return vanilla;
        MoveState s = owner(e, Mech.ICE_SKATER);
        return s != null && s.lv(Mech.ICE_SKATER) >= s.p(Mech.ICE_SKATER, "control_level")
                && e.level().getBlockState(e.getBlockPosBelowThatAffectsMyMovement()).is(BlockTags.ICE) ? 0.6f : vanilla;
    }

    // ---- Mud Wader: Entity.makeStuckInBlock, both sides

    /** Moves the web/bush/powder snow slow {@code v%} of the way to none; at 100% you are not held at all. */
    public static Vec3 stuck(Entity e, Vec3 m) {
        MoveState s = owner(e, Mech.MUD_WADER);
        if (s == null) return m;
        double f = s.v(Mech.MUD_WADER) / 100;
        if (f >= 1) return Vec3.ZERO; // a zero multiplier means "not stuck": momentum is kept too
        return new Vec3(m.x + (1 - m.x) * f, m.y + (1 - m.y) * f, m.z + (1 - m.z) * f);
    }

    // ---- Pathless: BlockStateBase.getCollisionShape for leaves and bamboo, both sides

    public static boolean leafy(Block b) {
        return b instanceof LeavesBlock || b instanceof BambooStalkBlock;
    }

    /** Tier 1 (Lv1-5) only while sprinting, tier 2 at any pace. You still stand on top of a canopy. */
    public static VoxelShape leafPass(BlockPos pos, CollisionContext ctx, VoxelShape vanilla) {
        Player p = player(ctx);
        if (p == null || vanilla.isEmpty()) return vanilla;
        MoveState s = owner(p, Mech.PATHLESS);
        if (s == null || (!p.isSprinting() && s.v(Mech.PATHLESS) < 2) || ctx.isAbove(vanilla, pos, false)) return vanilla;
        return Shapes.empty();
    }

    // ---- Tomb Raider: Entity.isIgnoringBlockTriggers (pressure plates and tripwires), server

    /** The plate or tripwire whose checkPressed is running (server thread only), so the hook knows which block asks. */
    @Nullable
    private static BlockPos trapAt;

    public static void checkingTrap(@Nullable BlockPos pos) {
        trapAt = pos;
    }

    /** Only world traps (temples) ignore you: plates and tripwires any player placed still fire. */
    public static boolean ignoresTraps(Entity e, boolean vanilla) {
        if (vanilla) return true;
        MoveState s = owner(e, Mech.TOMB_RAIDER);
        return s != null && s.lv(Mech.TOMB_RAIDER) >= s.p(Mech.TOMB_RAIDER, "immune_level")
                && !(trapAt != null && placedTrap(e.level(), trapAt));
    }

    /** Tripwire hooks are never stepped on, so the string decides; a player's tripwire always has player string. */
    public static boolean trap(BlockState state) {
        Block b = state.getBlock();
        return b instanceof BasePressurePlateBlock || b instanceof TripWireBlock;
    }

    static boolean placedTrap(Level level, BlockPos pos) {
        LevelChunk c = level.getChunkAt(pos);
        return c.hasData(MoveData.PLACED_TRAPS) && c.getData(MoveData.PLACED_TRAPS).contains(pos.asLong());
    }

    static void markTrap(Level level, BlockPos pos, boolean placed) {
        LevelChunk c = level.getChunkAt(pos);
        if (placed ? c.getData(MoveData.PLACED_TRAPS).add(pos.asLong())
                : c.hasData(MoveData.PLACED_TRAPS) && c.getData(MoveData.PLACED_TRAPS).remove(pos.asLong()))
            c.setUnsaved(true);
    }

    /** BlockStateBase.onRemove (server): a trap block left this spot (break, explosion, piston, /setblock), so forget the
     * mark. Pistons can't move plates or tripwire (push reaction DESTROY), so this covers them. Hot path: one instanceof pair. */
    public static void trapRemoved(BlockState old, Level level, BlockPos pos, BlockState now) {
        if (trap(old) && !now.is(old.getBlock())) markTrap(level, pos, false);
    }

    // ---- Rail Baron: AbstractMinecart rail speed cap and coasting, server (carts are server driven)

    public static double cartCap(@Nullable Entity rider, double vanilla) {
        MoveState s = owner(rider, Mech.RAIL_BARON);
        return s == null ? vanilla : vanilla * s.v(Mech.RAIL_BARON);
    }

    /** From coast_level a ridden cart loses no speed on plain track (water still slows it). Returns true when it
     * coasts, having done the one other thing the skipped slowdown does: zero the vertical speed. */
    public static boolean coasts(AbstractMinecart cart) {
        if (!coaster(cart.getFirstPassenger()) || cart.isInWater()) return false;
        Vec3 v = cart.getDeltaMovement();
        cart.setDeltaMovement(v.x, 0, v.z);
        return true;
    }

    static boolean coaster(@Nullable Entity rider) {
        MoveState s = owner(rider, Mech.RAIL_BARON);
        return s != null && s.lv(Mech.RAIL_BARON) >= s.p(Mech.RAIL_BARON, "coast_level");
    }

    // ---- Pig Jockey: FoodOnAStickItem.use skips the stick wear, server

    public static boolean stickSaved(LivingEntity user) {
        return user.getVehicle() instanceof Pig && saves(user);
    }

    static boolean saves(LivingEntity user) {
        MoveState s = owner(user, Mech.PIG_JOCKEY);
        return s != null && user.getRandom().nextDouble() * 100 < s.p(Mech.PIG_JOCKEY, "stick");
    }

    // ---- Portal Hopper: NetherPortalBlock.getPortalTransitionTime (server) and the client portal nausea

    /** Value is the wait in seconds against vanilla's default 4 s, so a changed gamerule scales the same way. */
    public static int portalTime(Entity e, int vanilla) {
        MoveState s = owner(e, Mech.PORTAL_HOPPER);
        return s == null ? vanilla : Math.max(1, (int) Math.round(vanilla * s.v(Mech.PORTAL_HOPPER) / 4.0));
    }

    public static boolean portalNausea(Player p, boolean vanilla) {
        if (!vanilla) return false;
        MoveState s = owner(p, Mech.PORTAL_HOPPER);
        return s == null || s.lv(Mech.PORTAL_HOPPER) < s.p(Mech.PORTAL_HOPPER, "calm_level");
    }

    // ---- Piglin Disguise: PiglinAi.isWearingGold, server

    public static boolean wearsGold(LivingEntity e, boolean vanilla) {
        return vanilla || owner(e, Mech.PIGLIN_DISGUISE) != null;
    }

    // ---- Monument Diver: guardian beam charge time, both sides (server hit timing, client beam animation)

    public static int beamTime(@Nullable LivingEntity target, int vanilla) {
        MoveState s = owner(target, Mech.MONUMENT_DIVER);
        return s == null ? vanilla : (int) Math.round(vanilla * (1 + s.p(Mech.MONUMENT_DIVER, "charge") / 100));
    }

    // ---- Trial Taker: VaultBlockEntity.Server.resolveItemsToEject, server

    /** From vault_level a trial or ominous vault rolls its loot table once more for an owner and adds one item of that
     * roll. Same loot params as vanilla's roll. Runs once per key the vault accepts. */
    public static List<ItemStack> vaultLoot(ServerLevel level, VaultConfig config, BlockPos pos, Player player, List<ItemStack> vanilla) {
        MoveState s = owner(player, Mech.TRIAL_TAKER);
        if (vanilla.isEmpty() || s == null || s.lv(Mech.TRIAL_TAKER) < s.p(Mech.TRIAL_TAKER, "vault_level")) return vanilla;
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withLuck(player.getLuck()).withParameter(LootContextParams.THIS_ENTITY, player).create(LootContextParamSets.VAULT);
        List<ItemStack> extra = level.getServer().reloadableRegistries().getLootTable(config.lootTable()).getRandomItems(params);
        extra.removeIf(ItemStack::isEmpty);
        if (extra.isEmpty()) return vanilla;
        List<ItemStack> out = new ArrayList<>(vanilla);
        out.add(extra.get(level.getRandom().nextInt(extra.size())));
        return out;
    }

    // ---- Deep Dark Delver: warden hearing range and shrieker targeting, server

    /** A warden only hears an owner's vibration within {@code (100 - v)%} of its listening radius. */
    public static boolean wardenHears(PositionSource warden, ServerLevel level, BlockPos at, GameEvent.Context ctx, int radius) {
        MoveState s = owner(ctx.sourceEntity(), Mech.DEEP_DARK_DELVER);
        if (s == null) return true;
        double max = radius * (1 - s.v(Mech.DEEP_DARK_DELVER) / 100);
        return warden.getPosition(level).map(w -> w.distanceToSqr(Vec3.atCenterOf(at)) <= max * max).orElse(true);
    }

    @Nullable
    public static ServerPlayer shriekerTarget(@Nullable ServerPlayer vanilla) {
        return vanilla != null && owner(vanilla, Mech.DEEP_DARK_DELVER) != null ? null : vanilla;
    }

    // ---- Runner's Lungs: LocalPlayer.hasEnoughFoodToStartSprinting, client

    public static boolean sprintAnyFood(Player p, boolean vanilla) {
        if (vanilla) return true;
        MoveState s = owner(p, Mech.RUNNERS_LUNGS);
        return s != null && s.lv(Mech.RUNNERS_LUNGS) >= s.p(Mech.RUNNERS_LUNGS, "any_food_level");
    }
}
