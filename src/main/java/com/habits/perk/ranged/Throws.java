package com.habits.perk.ranged;

import com.habits.core.HabitsApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Right-click throws (snowballs, fire charges), fishing rod reels, and ender pearl landings. */
public final class Throws {
    private Throws() {}

    /** Marker in {@code Shot.charge} for a sneak-thrown fire charge. */
    static final int PITCHED = -7;

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickItem e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !R.real(p)) return;
        ItemStack st = e.getItemStack();
        HabitsApi.PerkHit h;
        if (st.is(Items.SNOWBALL) && (h = R.hit(p, "snow_volley")) != null) volley(p, h);
        else if (st.is(Items.FIRE_CHARGE) && p.isShiftKeyDown() && (h = R.hit(p, "fire_pitcher")) != null) {
            pitch(p, st, e.getHand());
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        } else if (st.getItem() instanceof FishingRodItem && p.fishing != null) reel(p, p.fishing);
    }

    /** Snow Volley: extra snowballs fanned around the one vanilla throws. Only one item is used. */
    static void volley(ServerPlayer p, HabitsApi.PerkHit h) {
        int extra = (int) h.value() - 1;
        double spread = R.p(h, "spread");
        for (int i = 0; i < extra; i++) {
            float yaw = (float) (spread * ((i / 2) + 1) * (i % 2 == 0 ? 1 : -1));
            Snowball sb = new Snowball(p.level(), p.getX(), p.getEyeY() - 0.1, p.getZ());
            sb.shootFromRotation(p, p.getXRot(), p.getYRot() + yaw, 0, 1.5f, 1.0f);
            p.level().addFreshEntity(sb);
            sb.setOwner(p); // after adding, so the extras don't count as throws for cards
            Shots.SHOTS.put(sb, new Shots.Shot(p, sb));
        }
    }

    static void pitch(ServerPlayer p, ItemStack st, net.minecraft.world.InteractionHand hand) {
        Vec3 look = p.getLookAngle();
        SmallFireball fb = new SmallFireball(p.level(), p, look);
        Vec3 eye = p.getEyePosition().add(look.scale(0.6));
        fb.setPos(eye.x, eye.y - 0.1, eye.z);
        fb.setDeltaMovement(look.scale(1.2));
        p.level().addFreshEntity(fb);
        Shots.Shot s = Shots.get(fb);
        if (s != null) s.charge = PITCHED;
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1, 1.2f);
        p.awardStat(Stats.ITEM_USED.get(Items.FIRE_CHARGE));
        st.consume(1, p);
        p.swing(hand, true);
    }

    static void pitchHit(Shots.Shot s, SmallFireball fb, Vec3 at, Entity direct) {
        ServerPlayer p = s.owner;
        HabitsApi.PerkHit h = R.hit(p, "fire_pitcher");
        float dmg = h == null ? 5 : (float) h.value();
        int burn = h == null ? 5 : (int) R.p(h, "burn");
        if (direct instanceof LivingEntity l && l != p && !l.fireImmune()) {
            l.hurt(fb.damageSources().fireball(fb, p), dmg);
            l.igniteForSeconds(burn);
        }
        double splash = h == null ? 0 : R.p(h, "splash");
        if (splash > 0) for (LivingEntity l : fb.level().getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(splash), R::hostile)) {
            if (l == direct || l.fireImmune()) continue;
            l.hurt(fb.damageSources().fireball(fb, p), dmg * 0.5f);
            l.igniteForSeconds(burn);
        }
        R.puff((ServerLevel) fb.level(), net.minecraft.core.particles.ParticleTypes.FLAME, at, 10, 0.3);
        fb.discard(); // never sets blocks on fire
    }

    /** Yank (hooked mob) and Grapple Hook (grounded bobber). Runs before vanilla's own retrieve. */
    static void reel(ServerPlayer p, FishingHook hook) {
        Entity hooked = hook.getHookedIn();
        HabitsApi.PerkHit h;
        if (hooked instanceof LivingEntity l && !(l instanceof Player)) {
            R.fire(p, "reel_mob", l.position());
            if ((h = R.hit(p, "yank")) != null && !R.boss(l)) {
                Vec3 pull = p.position().subtract(hook.position()).scale(0.1 * (h.value() - 1));
                l.setDeltaMovement(l.getDeltaMovement().add(pull));
                l.hurtMarked = true;
                if (R.p(h, "dmg") > 0 && !Impacts.friendly(p, l)) l.hurt(p.damageSources().playerAttack(p), (float) R.p(h, "dmg"));
                if (R.p(h, "slow") > 0) l.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, (int) (R.p(h, "slow") * 20), 1), p);
            }
            return;
        }
        if (hooked != null || !hook.onGround()) return;
        R.fire(p, "reel_ground", hook.position());
        if ((h = R.hit(p, "grapple_hook")) == null) return;
        Vec3 d = hook.position().subtract(p.position());
        double horiz = d.horizontalDistance();
        if (d.length() > h.value() || d.length() < 1.5) return;
        // players keep about 0.91 of horizontal speed per tick in the air, so v ~ distance / 11 covers it
        Vec3 hv = horiz < 1e-3 ? Vec3.ZERO : new Vec3(d.x, 0, d.z).scale(1.1 / 11);
        double vy = d.y > 0 ? Math.sqrt(2 * 0.08 * d.y) * 1.15 + 0.25 : 0.4;
        Vec3 v = hv.add(0, vy, 0);
        if (v.length() > 3.5) v = v.normalize().scale(3.5);
        p.setDeltaMovement(v);
        p.hurtMarked = true;
        p.resetFallDistance();
        Status.P st = Status.of(p);
        st.noFallUntil = p.level().getGameTime() + 40 + (long) (R.p(h, "nofall") * 20);
        st.noFallCut = R.p(h, "nofall") > 0 ? 1 : 0;
        p.getCooldowns().addCooldown(Items.FISHING_ROD, (int) (R.p(h, "cooldown") * 20));
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 1, 0.7f);
    }

    /** Pearl Pitcher (less damage, refunds) and the Far Pearl card event. */
    @SubscribeEvent
    public static void onPearl(EntityTeleportEvent.EnderPearl e) {
        ServerPlayer p = e.getPlayer();
        if (!R.real(p)) return;
        var far = com.habits.core.SkillRegistry.get(com.habits.Habits.id("far_pearl")); // its "far" param is the card's distance
        if (e.getPrev().distanceTo(e.getTarget()) >= (far == null ? 40 : R.param(far.perk().param("far", "40"), 1))) R.fire(p, "far_pearl", e.getTarget());
        HabitsApi.PerkHit h = R.hit(p, "pearl_pitcher");
        if (h == null) return;
        e.setAttackDamage((float) (e.getAttackDamage() * Math.max(0, 1 - h.value() / 100)));
        if (!p.hasInfiniteMaterials() && R.roll(p, R.p(h, "refund"))) {
            ItemStack back = new ItemStack(Items.ENDER_PEARL);
            if (!p.getInventory().add(back)) p.drop(back, false);
        }
    }
}
