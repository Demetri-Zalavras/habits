package com.habits.perk.combat;

import com.habits.core.HabitsApi;
import com.habits.core.TriggerContext;
import com.habits.core.TriggerType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
import net.neoforged.neoforge.event.entity.player.PlayerDestroyItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.SweepAttackEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Every game event the melee mechanics listen to. Each handler bails out in O(1) for players without melee skills. */
public final class Hooks {
    private Hooks() {}

    /** Knockback multipliers for mobs hit this tick (home run, lancer, wall slam arrows). Cleared every tick. */
    static final Map<LivingEntity, Float> KB = new IdentityHashMap<>();
    /** Players who swung this tick; their swing is summed up at the end of the tick (sweep trigger). */
    static final List<ServerPlayer> SWUNG = new ArrayList<>();

    static long now(Entity e) {
        return e.level().getGameTime();
    }

    // ---------------------------------------------------------------- attack phase

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onAttack(AttackEntityEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !M.active(p) || e.isCanceled()) return;
        if (Special.throwCarried(p)) {
            e.setCanceled(true);
            return;
        }
        PS s = PS.of(p);
        long now = now(p);
        Entity t = e.getTarget();
        s.attackTick = now;
        s.mainTarget = t.getId();
        s.strength = p.getAttackStrengthScale(0.5f);
        s.sprintHit = p.isSprinting() && s.strength > 0.9f;
        s.swingHits = 0;
        s.swingKills = 0;
        s.wideArc = false;
        if (!SWUNG.contains(p)) SWUNG.add(p);
        if (now - s.blockTime <= 20 && t instanceof LivingEntity) M.fire(p, "riposte", 1, p.getMainHandItem(), t);
        if (s.momentumUntil > 0) Tracked.later(() -> Special.endMomentum(p, s));
        if (t instanceof Projectile pr) {
            Special.swat(p, pr, e);
            return;
        }
        if (p.isShiftKeyDown() && t instanceof LivingEntity le) Tracked.later(() -> Special.sneakAttack(p, le));
    }

    /** Core's CUSTOM {@code habits:swing_air} (a swing at nothing, rate-limited to the attack cadence): a miss breaks
     * the Swordplay combo, Heave throws what you carry, a sneak swing spins (Whirlwind) or bashes (Shield Bash). */
    static void onSwingAir(ServerPlayer p, TriggerType type, TriggerContext ctx) {
        if (type != TriggerType.CUSTOM || !SWING_AIR.equals(ctx.target())) return;
        PS s = PS.ALL.get(p.getUUID());
        if (s != null) s.comboCount = 0;
        if (Special.throwCarried(p)) return;
        if (p.isShiftKeyDown()) Special.sneakAttack(p, null);
        else Warrior.lunge(p);
    }

    static final net.minecraft.resources.ResourceLocation SWING_AIR = com.habits.Habits.id("swing_air");

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onCrit(CriticalHitEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !M.active(p)) return;
        PS s = PS.of(p);
        long now = now(p);
        HabitsApi.PerkHit r = M.hit(p, Mech.RIPOSTE);
        if (r != null && e.getTarget() instanceof LivingEntity && now - s.blockTime <= M.ticks(M.p(r, "window"))) {
            e.setCriticalHit(true);
            e.setDamageMultiplier(1.5f * (float) (1 + r.value() / 100));
            s.blockTime = -1000;
        }
        if (e.isCriticalHit()) {
            s.critTick = now;
            s.critTarget = e.getTarget().getId();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onSweep(SweepAttackEvent e) {
        if (!e.isSweeping() || !(e.getEntity() instanceof ServerPlayer p)) return;
        HabitsApi.PerkHit h = M.hit(p, Mech.WIDE_ARC);
        if (h == null) return;
        e.setSweeping(false);
        PS.of(p).wideArc = true;
        Entity t = e.getTarget();
        Tracked.later(() -> Special.wideArc(p, t, h));
    }

    // ---------------------------------------------------------------- damage phase

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onIncoming(LivingIncomingDamageEvent e) {
        LivingEntity v = e.getEntity();
        if (v.level().isClientSide) return;
        DamageSource src = e.getSource();
        Entity owner = src.getEntity();
        if (owner != null && (v instanceof Player && Allies.isAlly(owner) || owner instanceof Player && Allies.isAlly(v))) {
            e.setCanceled(true);
            return;
        }
        if (v instanceof ServerPlayer pv && M.active(pv)) {
            Defense.incoming(pv, e);
            if (e.isCanceled()) return;
        }
        float amt = Damage.scale(v, src, owner, e.getAmount());
        e.setAmount(amt);
    }

    /** {@link com.habits.perk.PerkInit#BOSS_HIT_CAP}: last in the damage phase, so other mods' bonuses count toward it too. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void bossHitCap(LivingIncomingDamageEvent e) {
        double cap = com.habits.perk.PerkInit.BOSS_HIT_CAP.get();
        if (cap > 0 && e.getSource().getEntity() instanceof Player && M.boss(e.getEntity()))
            e.setAmount(Math.min(e.getAmount(), (float) (e.getEntity().getMaxHealth() * cap / 100)));
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onKnock(LivingKnockBackEvent e) {
        if (KB.isEmpty()) return;
        Float m = KB.get(e.getEntity());
        if (m != null) e.setStrength(e.getStrength() * m);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPre(LivingDamageEvent.Pre e) {
        if (e.getEntity() instanceof ServerPlayer pv && M.active(pv)) Warrior.lethal(pv, e);
        ServerPlayer p = M.melee(e.getSource());
        if (p == null) return;
        LivingEntity v = e.getEntity();
        float d = e.getNewDamage(), hp = v.getHealth();
        if (d < hp) return;
        PS s = PS.of(p);
        s.overflow = d - hp;
        s.overflowTarget = v.getId();
        if (d >= 2 * hp) M.fire(p, "overkill", 1, p.getMainHandItem(), v);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPost(LivingDamageEvent.Post e) {
        LivingEntity v = e.getEntity();
        if (v.level().isClientSide) return;
        DamageSource src = e.getSource();
        float dmg = e.getNewDamage();
        if (v instanceof ServerPlayer pv && M.active(pv) && dmg > 0) Defense.hurt(pv, src, dmg);
        if (src.getEntity() instanceof ServerPlayer op && op != v && src.getDirectEntity() instanceof Projectile pr && M.active(op)) {
            Special.shot(op, v, pr);
            if (src.is(DamageTypes.WIND_CHARGE) && op.distanceToSqr(v) <= 16) M.fire(op, "gale", 1, op.getMainHandItem(), v);
        }
        ServerPlayer p = M.melee(src);
        if (p == null || dmg <= 0 || p == v) return;
        OnHit.hit(p, v, dmg);
    }

    /** A weapon broke on a mob: Player.attack fires this right after the hit that used it up. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDestroy(PlayerDestroyItemEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && M.active(p) && e.getHand() == InteractionHand.MAIN_HAND && PS.of(p).attackTick == now(p))
            M.fire(p, "weapon_break", 1, e.getOriginal(), null);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onBlock(LivingShieldBlockEvent e) {
        if (!e.getBlocked() || !(e.getEntity() instanceof ServerPlayer p) || !M.active(p)) return;
        Defense.blocked(p, e);
    }

    // ---------------------------------------------------------------- death phase

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDeath(LivingDeathEvent e) {
        LivingEntity v = e.getEntity();
        if (v.level().isClientSide || e.isCanceled()) return;
        DamageSource src = e.getSource();
        if (src.getEntity() instanceof TamableAnimal pet && pet.getOwner() instanceof ServerPlayer o && M.active(o))
            M.fire(o, "pet_kill", 1, null, v);
        Special.indirectDeath(v, src);
        ServerPlayer p = M.melee(src);
        if (p == null || p == v) return;
        OnKill.kill(p, v);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDrops(LivingDropsEvent e) {
        LivingEntity v = e.getEntity();
        if (Allies.isAlly(v)) {
            e.setCanceled(true);
            return;
        }
        ServerPlayer p = M.melee(e.getSource());
        if (p != null) OnKill.drops(p, v, e);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onXp(LivingExperienceDropEvent e) {
        LivingEntity v = e.getEntity();
        if (Allies.isAlly(v)) {
            e.setDroppedExperience(0);
            return;
        }
        if (e.getAttackingPlayer() instanceof ServerPlayer p && M.active(p) && v.getTags().contains(OnKill.MELEE_KILL))
            e.setDroppedExperience(OnKill.xp(p, v, e.getDroppedExperience()));
    }

    // ---------------------------------------------------------------- mixin hooks (com.habits.perk.combat.mixin)

    /** Mob.serverAiStep: a dazed mob skips its AI step (goals, brain, navigation) and stops walking. O(1): one
     * identity-map lookup, none while nothing is tracked. */
    public static boolean dazed(Mob m) {
        if (Tracked.size() == 0 || !Tracked.has(m, OnHit.DAZE)) return false;
        m.setXxa(0);
        m.setYya(0);
        m.setZza(0);
        m.setJumping(false);
        return true;
    }

    /** ItemStack.postHurtEnemy: Improviser's non-weapons take no wear from hitting. */
    public static boolean noHitWear(ItemStack s, Player attacker) {
        return attacker instanceof ServerPlayer p && !Damage.weapon(s) && M.hit(p, Mech.IMPROVISER) != null;
    }

    /** FishingHook.pullEntity: reeling in a mob that holds an item fires Disarm's second trigger (server only). */
    public static void reeled(FishingHook hook, Entity e) {
        if (e instanceof Mob m && !m.getMainHandItem().isEmpty() && hook.getPlayerOwner() instanceof ServerPlayer o && M.active(o))
            M.fire(o, "disarm", 1, o.getMainHandItem(), m);
    }

    // ---------------------------------------------------------------- other

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFall(LivingFallEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && M.active(p)) Special.fall(p, e);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getLevel().isClientSide || e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (Special.pickUp(p, e.getTarget())) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** Heave keeps the main hand empty to grab mobs, but vanilla skips the block on a sneak-click whenever the offhand
     * holds anything; for Heave owners a sneak-click with an empty main hand still opens a container. Both sides, so the
     * client does not fall through to using the offhand item. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (e.getHand() == InteractionHand.MAIN_HAND && p.isShiftKeyDown() && p.getMainHandItem().isEmpty() && !p.getOffhandItem().isEmpty()
                && !p.isSpectator() && e.getLevel().getBlockState(e.getPos()).getMenuProvider(e.getLevel(), e.getPos()) != null
                && HabitsApi.mechanicHit(p, Mech.HEAVE.id) != null)
            e.setUseBlock(net.neoforged.neoforge.common.util.TriState.TRUE);
    }

    /** Hatchet Throw: sneak + right-click the air with an axe. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        if (e.getLevel().isClientSide || !(e.getEntity() instanceof ServerPlayer p) || !M.active(p)) return;
        if (Warrior.hatchet(p, e.getHand(), e.getItemStack())) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onImpact(ProjectileImpactEvent e) {
        if (!(e.getRayTraceResult() instanceof EntityHitResult eh) || e.getProjectile().level().isClientSide) return;
        Projectile pr = e.getProjectile();
        if (eh.getEntity() instanceof ServerPlayer p && pr.getOwner() != p && !(pr instanceof ThrownTrident && pr.getOwner() instanceof Player)
                && !(pr instanceof FishingHook) && Special.deflect(p, pr)) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onEnderTeleport(EntityTeleportEvent.EnderEntity e) {
        LivingEntity v = e.getEntityLiving();
        if (v.getTags().contains(OnHit.PINNED) && v.getPersistentData().getLong(OnHit.PINNED) > now(v)) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onTarget(LivingChangeTargetEvent e) {
        LivingEntity nt = e.getNewAboutToBeSetTarget();
        if (nt == null) return;
        LivingEntity v = e.getEntity();
        if ((Allies.isAlly(v) && !(nt instanceof net.minecraft.world.entity.monster.Enemy)) || Tracked.has(v, OnHit.LOST)) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide) return;
        if (!e.loadedFromDisk()) {
            Special.onProjectileJoin(e);
            return;
        }
        Entity en = e.getEntity();
        if (en.getTags().contains(Allies.TAG)) e.setCanceled(true); // allies never outlive a restart
        else if (en.getTags().contains(Special.CARRIED)) Special.release(en);
    }

    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) Special.dropCarried(p);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (e.getEntity() instanceof ServerPlayer p) Ticks.tick(p);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        endTick();
    }

    /** End of a server tick: sweep triggers, queued work, tracked mobs, homing projectiles. Tests call it directly. */
    static void endTick() {
        KB.clear();
        for (ServerPlayer p : SWUNG) {
            PS s = PS.ALL.get(p.getUUID());
            if (s != null && !s.wideArc && s.swingHits >= 2) M.fire(p, TriggerType.CUSTOM, "sweep", s.swingHits, p.getMainHandItem(), null);
            if (s != null && s.swingKills >= 2) M.fire(p, TriggerType.CUSTOM, "double_kill", s.swingKills, p.getMainHandItem(), null);
        }
        SWUNG.clear();
        Tracked.tick();
        Special.tickHoming();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) Special.dropCarried(p);
        forget(e.getEntity());
    }

    static void forget(Player p) {
        PS.ALL.remove(p.getUUID());
        M.SNAPS.remove(p.getUUID());
        SWUNG.remove(p);
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent e) {
        PS.ALL.clear();
        M.SNAPS.clear();
        KB.clear();
        SWUNG.clear();
        Tracked.clear();
        Special.HOMING.clear();
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent e) {
        M.SNAPS.clear();
    }

    /** True for melee-style damage types that count as "a mob you shot died from the environment". */
    static boolean environmental(DamageSource src) {
        return src.is(DamageTypes.FALL) || src.is(DamageTypes.LAVA) || src.is(DamageTypes.FELL_OUT_OF_WORLD) || src.is(DamageTypes.FLY_INTO_WALL);
    }

    static boolean isDragon(Entity e) {
        return e instanceof EnderDragon;
    }

    static boolean isArrow(Entity e) {
        return e instanceof AbstractArrow;
    }
}
