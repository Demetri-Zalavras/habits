package com.habits.perk.ranged;

import com.habits.core.HabitsApi;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.ArrowLooseEvent;
import org.jetbrains.annotations.Nullable;

/** Bow and crossbow handling: draw speed (both sides), bow charge capture, instant draws, stored crossbow shots, fans. */
public final class Draw {
    private Draw() {}

    public static final String STORED = "habits_stored";
    static final int MAX_BOLTS = 9;

    // the bow charge of the arrow about to spawn: ArrowLooseEvent fires right before the arrow joins, same tick
    @Nullable private static Player looser;
    private static int looseCharge;
    private static long looseTick;

    static void clear() {
        looser = null;
    }

    static int takeCharge(Player p) {
        if (looser != p || looseTick != p.level().getGameTime()) return 0;
        return looseCharge;
    }

    @SubscribeEvent
    public static void onLoose(ArrowLooseEvent e) {
        Player p = e.getEntity();
        if (p.level().isClientSide()) return;
        ItemStack w = e.getBow();
        if (w.getItem() instanceof BowItem) {
            looser = p;
            looseCharge = e.getCharge();
            looseTick = p.level().getGameTime();
        } else if (w.getItem() instanceof CrossbowItem && p instanceof ServerPlayer sp && R.real(sp)) {
            chain(sp, w);
        }
    }

    /** Draw speed: Steady Draw for bows, Quick Loader for crossbows. Runs on both sides so the animation matches. */
    @SubscribeEvent
    public static void onUseTick(LivingEntityUseItemEvent.Tick e) {
        if (!(e.getEntity() instanceof Player p) || !p.isAlive() || p.isSpectator()) return;
        ItemStack st = e.getItem();
        HabitsApi.PerkHit h;
        double speed;
        if (st.getItem() instanceof BowItem && (h = HabitsApi.mechanicHit(p, R.id("steady_draw"))) != null) speed = h.value() / 100;
        else if (st.getItem() instanceof CrossbowItem && (h = HabitsApi.mechanicHit(p, R.id("quick_loader"))) != null)
            speed = 1 / (1 - Math.min(0.9, h.value() / 100)) - 1;
        else return;
        int n = p.getTicksUsingItem();
        int extra = (int) ((n + 1) * speed) - (int) (n * speed);
        if (extra > 0) e.setDuration(Math.max(1, e.getDuration() - extra));
    }

    /** Quickdraw armed count on the client (set by {@link RangedNet.Quick}); read only on the client side. */
    static volatile int clientArmed;

    /** On logout: the server forgets its armed count, so a kept client count would show a full pull for a partial shot. */
    public static void clientReset() {
        clientArmed = 0;
    }

    /** Quickdraw: an armed instant draw makes this bow pull start already full, on both sides so the animation matches. */
    @SubscribeEvent
    public static void onUseStart(LivingEntityUseItemEvent.Start e) {
        if (!(e.getEntity() instanceof Player p) || !(e.getItem().getItem() instanceof BowItem)) return;
        if (p.level().isClientSide()) {
            if (!p.isLocalPlayer() || clientArmed <= 0 || R.hit(p, "quickdraw") == null) return;
            clientArmed--;
            e.setDuration(e.getDuration() - 20);
            return;
        }
        if (!(p instanceof ServerPlayer sp) || !R.real(sp)) return;
        Status.P st = Status.PLAYERS.get(sp.getUUID());
        if (st == null || st.quickReady <= 0 || R.hit(sp, "quickdraw") == null) return;
        RangedNet.quick(sp, st, st.quickReady - 1);
        e.setDuration(e.getDuration() - 20);
    }

    /** Card event: a crossbow finished loading (release after a full charge with ammo). */
    @SubscribeEvent
    public static void onUseStop(LivingEntityUseItemEvent.Stop e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        ItemStack st = e.getItem();
        if (!(st.getItem() instanceof CrossbowItem) || CrossbowItem.isCharged(st) || p.getProjectile(st).isEmpty()) return;
        int used = st.getUseDuration(p) - e.getDuration();
        if (used >= CrossbowItem.getChargeDuration(st, p)) R.fire(p, "crossbow_loaded", null);
    }

    /** Quick Loader stored shots: the first shot of a fresh load sets how many follow; each refill uses one ammo. */
    static void chain(ServerPlayer p, ItemStack bow) {
        HabitsApi.PerkHit h = R.hit(p, "quick_loader");
        CompoundTag tag = bow.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        int left = tag.contains(STORED) ? tag.getInt(STORED) : (h == null ? 0 : (int) R.p(h, "shots") - 1);
        if (h == null || left <= 0) {
            if (tag.contains(STORED)) { tag.remove(STORED); CustomData.set(DataComponents.CUSTOM_DATA, bow, tag); }
            return;
        }
        tag.putInt(STORED, left);
        CustomData.set(DataComponents.CUSTOM_DATA, bow, tag);
    }

    /** Mechanic tick (server): refill an emptied crossbow that still has stored shots. */
    static void refill(Player player) {
        if (!(player instanceof ServerPlayer p) || !R.real(p)) return;
        for (ItemStack bow : new ItemStack[]{p.getMainHandItem(), p.getOffhandItem()}) {
            if (!(bow.getItem() instanceof CrossbowItem) || CrossbowItem.isCharged(bow)) continue;
            CompoundTag tag = bow.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            int left = tag.getInt(STORED);
            if (left <= 0) continue;
            ItemStack ammo = p.getProjectile(bow);
            if (ammo.isEmpty()) continue;
            ItemStack one = p.hasInfiniteMaterials() ? ammo.copyWithCount(1) : ammo.split(1);
            bow.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(one));
            tag.putInt(STORED, left - 1);
            CustomData.set(DataComponents.CUSTOM_DATA, bow, tag);
        }
    }

    /** Fan of Bolts: a multishot volley gets extra bolts that close in on whatever is under the crosshair. */
    static void fan(ServerPlayer p, AbstractArrow first, ItemStack weapon) {
        ServerLevel level = p.serverLevel();
        if (EnchantmentHelper.processProjectileCount(level, weapon, p, 1) <= 1) return;
        Shots.Shot fs = Shots.get(first);
        long now = level.getGameTime();
        if (fs != null) fs.volley = now;
        Status.P st = Status.of(p);
        if (st.volleyTick == now) return; // only the first bolt of the volley spawns the fan
        st.volleyTick = now;
        st.volleyHits = 0;
        HabitsApi.PerkHit h = R.hit(p, "fan_of_bolts");
        if (h == null) return;
        int extra = (int) Math.min(MAX_BOLTS - 3, h.value());
        Vec3 look = p.getLookAngle();
        Mob target = crosshair(p, R.p(h, "range"));
        double converge = R.p(h, "converge") / 100, angle = Math.toRadians(R.p(h, "angle"));
        double speed = first.getDeltaMovement().length();
        Vec3 up = new Vec3(0, 1, 0), side = look.cross(up).lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : look.cross(up).normalize();
        for (int i = 0; i < extra; i++) {
            double a = angle * ((i / 2) + 2) * (i % 2 == 0 ? 1 : -1);
            Vec3 dir = look.scale(Math.cos(a)).add(side.scale(Math.sin(a))).normalize();
            if (target != null) dir = dir.scale(1 - converge).add(Shots.aimPoint(target).subtract(p.getEyePosition()).normalize().scale(converge)).normalize();
            Arrow bolt = new Arrow(level, p.getX(), p.getEyeY() - 0.1, p.getZ(), first.getPickupItemStackOrigin().copyWithCount(1), weapon);
            bolt.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
            bolt.setBaseDamage(first.getBaseDamage());
            bolt.setDeltaMovement(dir.scale(speed));
            level.addFreshEntity(bolt);
            bolt.setOwner(p);
            Shots.Shot s = Shots.shard(p, bolt);
            s.shard = false; // fan bolts count as real shots for hits and cards
            s.volley = now;
        }
    }

    @Nullable
    static Mob crosshair(Player p, double range) {
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Mob best = null;
        double bestCos = Math.cos(Math.toRadians(5));
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, new AABB(eye, eye.add(look.scale(range))).inflate(3), R::hostile)) {
            double cos = Shots.aimPoint(m).subtract(eye).normalize().dot(look);
            if (cos > bestCos) { bestCos = cos; best = m; }
        }
        return best;
    }
}
