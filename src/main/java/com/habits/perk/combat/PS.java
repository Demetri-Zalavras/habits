package com.habits.perk.combat;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Transient per-player combat state (combos, stacks, cooldowns). Server thread only; removed on logout, cleared on stop. */
final class PS {
    static final Map<UUID, PS> ALL = new HashMap<>();

    static PS of(Player p) {
        return ALL.computeIfAbsent(p.getUUID(), u -> new PS());
    }

    // the swing in progress (set by AttackEntityEvent)
    long attackTick = -1;
    int mainTarget = -1;
    float strength = 1;
    boolean sprintHit;
    int swingHits;
    long critTick = -1;
    int critTarget = -1;
    float lastMainDamage;
    boolean fullHpHit;
    int fullHpTarget = -1;
    float overflow;
    int overflowTarget = -1;

    // combos and stacks
    int comboTarget = -1, comboCount;
    int heat;
    int crits;
    long sonicReady, fangReady, stormReady, whirlReady, abyssReady, roarReady, brambleReady;
    int galeHits;
    int bloodStacks;
    long bloodUntil;
    int rampStacks;
    long rampLast;
    int flowStacks, flawless;
    long blockTime = -1000;
    int punchChain;
    long punchTime;
    int emberHits;
    int quakePunches;
    int soulOrbs;
    long soulTime;
    int lastHitId = -1;
    int packTarget = -1;
    int hostiles = -1;
    long noFallUntil;
    boolean wideArc;
    int crowd = -1;
    long momentumUntil, momentumSet;
    // warrior batch (Warrior.java)
    long lungeReady, splitReady, shadowReady, counterReady, hatchetReady, furyReady, furyUntil = -1;
    int lastAttacker = -1;
    long lastAttackerTick = -1000;
    float veng;
    long vengUntil;
    int juggleTarget = -1, juggleStacks;
    int lastKind;
    long lastKindTick = -1000;
    boolean swapHit;
    int swingKills;
    int blades;
    long bladesUntil;
    // kill streak (5 kills in 10 s) and multi hit (3 mobs in 2 s): tiny rings, no allocation per hit
    final long[] killTimes = new long[5];
    int killIdx;
    final int[] hitIds = new int[3];
    final long[] hitTimes = new long[3];
    int hitIdx;

    // movement samples
    Vec3 lastPos;
    double sprintDist;
    Vec3 lastVehiclePos;
    double vehicleSpeed;
}
