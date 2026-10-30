package com.habits.perk.movement;

import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.PerkSpec;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player, per-side transient state. One pass over the collection fills {@link #val}/{@link #lvl} (refreshed when
 * the collection object changes, or every 2 s for /reload), so every mechanic reads its value as an array lookup.
 */
public final class MoveState {
    // separate maps: in singleplayer the client and the integrated server share statics and the same player UUID
    static final Map<UUID, MoveState> SERVER = new ConcurrentHashMap<>();
    static final Map<UUID, MoveState> CLIENT = new ConcurrentHashMap<>();

    final double[] val = new double[Mech.N];
    final int[] lvl = new int[Mech.N];
    /** The unboosted value (before sigils/charms), for mechanics that scale a param by the boost. */
    final double[] raw = new double[Mech.N];
    final PerkSpec[] spec = new PerkSpec[Mech.N];
    @SuppressWarnings("unchecked")
    private final Map<String, Double>[] params = new Map[Mech.N];
    /** Owns at least one active movement mechanic. */
    boolean any;
    private Object coll;
    private long refreshedAt = Long.MIN_VALUE;

    // ---- shared scratch state used by mechanics (all transient)
    long tick;                  // local tick counter
    double lastX, lastY, lastZ;  // position last tick (server: from client packets)
    double dy;                  // y moved last tick
    boolean wasOnGround, wasInWater;
    float lastFall;
    double fallTop = Double.NEGATIVE_INFINITY; // water walk: high point since last ground (MoveHooks.trackFall)
    double fallLastY = Double.NaN;             // trackFall's own last y, so both sides measure dy the same way
    int airJumps, wallKicks;
    long nextDash, nextDashNag, nextWallKick, nextBlink, nextHop, nextLunge, nextLaunch, nextPing, nextIslandHop;
    long iframesUntil;
    byte keys;                  // held keys from the client (MovePayloads.K_*)
    int sneakHeld;              // ticks of ground sneak with elytra (wings of wind)
    long launchedAt = -1;       // wings of wind: when to open the elytra
    int sprintTicks;            // runner's lungs
    float lastExhaustion;
    int lastSprintCm, lastJumps; // stat counters: what this tick's sprint cost was
    double momentum;            // marathon %
    boolean hadAttrs;           // some attribute modifier may still be on the player
    @Nullable Holder<Biome> biome;
    long climbStamina;          // spider climb wall ticks used this climb
    boolean windLaunched;       // trial taker: next landing is free
    @Nullable Vec3 jumpStart;   // parkour tracker
    // trackers
    double sprintRun; @Nullable Vec3 sprintDir, sprintFrom;
    int jumpsInWindow; long jumpWindowStart;
    double walkClean; int stillTicks; long lastHurt;
    double flightDist, rideDist, voyageDist;
    int diveTicks; boolean deepDiveFired, edgeFired;
    /** Last tick at the world top / near the border: each fires again only after 60 s away (no bouncing on the line). */
    long skyHighAt = -Trackers.AWAY_TICKS, worldEdgeAt = -Trackers.AWAY_TICKS;
    double sampleX, sampleY, sampleZ; boolean sampled;
    long leftOverworldAt = -1;
    long firstEnteredBiomeAt;   // trailblazer: sprinting soon after a first visit
    // misc mechanic state
    long channelUntil; @Nullable BlockPos channelTarget; @Nullable Vec3 channelFrom;
    @Nullable BlockPos recoverTarget; long recoverUntil;
    int lostTicks;
    long lastChunk = Long.MIN_VALUE;
    int gatewayIndex;
    @Nullable UUID mountUuid;   // last ridden mount (saddle up whistle)
    @Nullable java.lang.ref.WeakReference<net.minecraft.world.entity.Entity> buffedMount;
    long lastMoveTick;
    final java.util.ArrayDeque<long[]> trail = new java.util.ArrayDeque<>(); // trailblazer: {x,y,z packed, expireTick}
    final java.util.ArrayDeque<BlockPos> crumbs = new java.util.ArrayDeque<>(); // lost and found
    record Tracked(java.lang.ref.WeakReference<net.minecraft.world.entity.LivingEntity> ref, long until) {}
    final java.util.ArrayDeque<Tracked> tracked = new java.util.ArrayDeque<>(); // taiga tracker, cap 20
    boolean inMineshaft;
    boolean jumpEdge, grabbed, dolphinLeapt, launchSeen; // client physics
    @Nullable Vec3 lastGround; long landTick; double preLandSpeed; long nextBoatHop;
    @Nullable BlockPos vaultPos; int vaultKeys; boolean vaultOminous; // key turner pending unlock
    net.minecraft.world.InteractionHand vaultHand = net.minecraft.world.InteractionHand.MAIN_HAND;
    double walkedForRepair, secondDist;
    boolean inFresh;
    long lastRail;
    final java.util.List<long[]> spyMarks = new java.util.ArrayList<>(); // {packed pos, expireTick}
    int markerHash;
    // spec batch (SpecMoves / SpecEvents)
    long nextSlide, nextCheck, nextBrake, nextFlap, nextTorpedo, nextEvade, nextInk, nextMantle, nextGiddy, nextStart, nextTrample, nextDive;
    boolean prevShift, prevJumpKey, wasSprinting, wasSpin, wasGliding, steepFired, startFired, chased, drafting;
    long sprintSeen, myLastMove, startedAt, softUntil, bandUntil, pearlRideAt;
    int coil, shaftTicks;
    double y5, secY, highDist, swimMom, sprintJumpY = Double.NaN;
    @Nullable Vec3 vehLast, pearlFrom, bandFrom;
    Vec3 vehVel = Vec3.ZERO;
    @Nullable net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> pearlDim, bandDim;
    @Nullable java.lang.ref.WeakReference<net.minecraft.world.entity.Entity> pearlMount;
    int wingsSlot = -1;                        // quick wings: inventory slot holding the chest item we swapped out
    @Nullable net.minecraft.world.item.ItemStack wingsOld;

    private MoveState() {}

    static MoveState of(Player p) {
        MoveState s = (p.level().isClientSide() ? CLIENT : SERVER).computeIfAbsent(p.getUUID(), u -> new MoveState());
        s.refresh(p);
        return s;
    }

    static void forget(UUID id) {
        SERVER.remove(id);
        CLIENT.remove(id);
    }

    private void refresh(Player p) {
        HabitsCollection c = HabitsApi.collection(p);
        long now = p.level().getGameTime();
        if (c == coll && now - refreshedAt < 40 && now >= refreshedAt) return;
        coll = c;
        refreshedAt = now;
        java.util.Arrays.fill(val, 0);
        java.util.Arrays.fill(lvl, 0);
        java.util.Arrays.fill(raw, 0);
        java.util.Arrays.fill(spec, null);
        java.util.Arrays.fill(params, null);
        boolean found = false;
        for (Map.Entry<net.minecraft.resources.ResourceLocation, SkillProgress> e : c.skills().entrySet()) {
            SkillProgress prog = e.getValue();
            if (prog.level() <= 0 || !prog.enabled()) continue;
            SkillDef base = SkillRegistry.get(e.getKey());
            SkillDef def = com.habits.progression.Effects.def(p, base); // progression: sigil/charm boosts
            if (def == null || def.perk().mechanic().isEmpty() || !com.habits.core.Specs.allows(p, def)) continue;
            Mech m = Mech.byId(def.perk().mechanic().get());
            if (m == null) continue;
            double v = def.perkValue(prog.level());
            int i = m.ordinal();
            if (spec[i] == null || v > val[i]) {
                val[i] = v;
                lvl[i] = prog.level();
                raw[i] = base.perkValue(prog.level());
                spec[i] = def.perk();
            }
            found = true;
        }
        any = found;
    }

    boolean has(Mech m) {
        return lvl[m.ordinal()] > 0;
    }

    double v(Mech m) {
        return val[m.ordinal()];
    }

    /** Boosted value over the unboosted one (1 without a boost). */
    double boost(Mech m) {
        int i = m.ordinal();
        return raw[i] > 0 ? val[i] / raw[i] : 1;
    }

    int lv(Mech m) {
        return lvl[m.ordinal()];
    }

    /** A param of the owned skill at its level: {@code "a..b"} lerps Lv1..Lv10, a 10-item list picks by level, a number is constant. */
    double p(Mech m, String key) {
        int i = m.ordinal();
        if (spec[i] == null) return 0;
        Map<String, Double> cache = params[i];
        if (cache == null) params[i] = cache = new HashMap<>(4);
        Double d = cache.get(key);
        if (d == null) {
            d = parse(spec[i].param(key, "0"), lvl[i]);
            cache.put(key, d);
        }
        return d;
    }

    /** A raw string param of the owned skill ("" when not owned). */
    String ps(Mech m, String key) {
        PerkSpec sp = spec[m.ordinal()];
        return sp == null ? "" : sp.param(key, "");
    }

    static double parse(String s, int level) {
        try {
            int range = s.indexOf("..");
            if (range > 0) {
                double a = Double.parseDouble(s.substring(0, range)), b = Double.parseDouble(s.substring(range + 2));
                return a + (b - a) * (Math.max(1, Math.min(10, level)) - 1) / 9.0;
            }
            if (s.indexOf(',') >= 0) {
                String[] parts = s.split(",");
                return Double.parseDouble(parts[Math.max(0, Math.min(parts.length - 1, level - 1))].trim());
            }
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
