package com.habits.perk.ranged.client;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.perk.ranged.Draw;
import com.habits.perk.ranged.Impacts;
import com.habits.perk.ranged.RangedMechanics;
import com.habits.perk.ranged.Trajectory;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import org.joml.Vector3f;

/** Client-only ranged bits for the local player: Run and Gun movement, the Bullseye path preview, the Overdraw hum. */
@EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT)
public final class RangedClient {
    private RangedClient() {}

    private static final DustParticleOptions DOT = new DustParticleOptions(new Vector3f(1f, 0.85f, 0.35f), 0.6f);
    private static final DustParticleOptions MARK = new DustParticleOptions(new Vector3f(1f, 0.3f, 0.2f), 1.2f);
    /** Hard cap: the preview never spawns more dots than this per refresh (and simulates at most 120 steps). */
    private static final int MAX_DOTS = 40;

    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent e) {
        if (!(e.getEntity() instanceof LocalPlayer p) || !p.isUsingItem() || p.isPassenger()) return;
        ItemStack use = p.getUseItem();
        if (!(use.getItem() instanceof BowItem || use.getItem() instanceof CrossbowItem)) return;
        HabitsApi.PerkHit h = HabitsApi.mechanicHit(p, Habits.id("run_and_gun"));
        if (h == null) return;
        // vanilla multiplies by 0.2 right after this event; pre-scale so v% of that slowdown is gone
        float k = (float) (1 + 4 * Math.min(1, h.value() / 100));
        e.getInput().forwardImpulse *= k;
        e.getInput().leftImpulse *= k;
        if (RangedMechanics.param(h, "sprint") > 0 && Minecraft.getInstance().options.keySprint.isDown() && e.getInput().forwardImpulse > 0.8f
                && p.getFoodData().getFoodLevel() > 6) p.setSprinting(true);
    }

    @SubscribeEvent
    public static void loggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) {
        Draw.clientReset();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.isPaused() || !p.isAlive() || p.isSpectator()) return;
        if ((p.tickCount & 1) == 0) preview(p, mc.level);
        hum(p);
    }

    /** Overdraw: a soft rising hum every half second while a bow is held past full draw. */
    private static void hum(LocalPlayer p) {
        if (!p.isUsingItem() || !(p.getUseItem().getItem() instanceof BowItem)) return;
        int t = p.getTicksUsingItem();
        if (t <= 20 || t % 10 != 0 || HabitsApi.mechanicHit(p, Habits.id("overdraw")) == null) return;
        p.level().playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS,
                0.25f, (float) Math.min(2, 0.8 + (t - 20) / 100.0), false);
    }

    /** Bullseye: simulate the shot with vanilla arrow physics ({@link Trajectory}) and dot the path, every other tick. */
    private static void preview(LocalPlayer p, Level level) {
        HabitsApi.PerkHit h = HabitsApi.mechanicHit(p, Habits.id("bullseye"));
        if (h == null) return;
        double speed = launchSpeed(p);
        if (speed <= 0.1) return;
        Vec3 own = p.getDeltaMovement();
        Vec3 v = p.getLookAngle().scale(speed).add(own.x, p.onGround() ? 0 : own.y, own.z);
        Trajectory.Result r = Trajectory.simulate(level, p, p.getEyePosition().subtract(0, 0.1, 0), v, h.value(), 1.5, MAX_DOTS,
                d -> level.addParticle(DOT, d.x, d.y, d.z, 0, 0, 0));
        Entity mob = r.mob();
        if (mob != null && RangedMechanics.param(h, "highlight") > 0) {
            Vec3 c = mob.getBoundingBox().getCenter();
            for (int k = 0; k < 6; k++)
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, c.x + (k % 3 - 1) * mob.getBbWidth() * 0.6,
                        c.y + (k / 3 - 0.5) * mob.getBbHeight() * 0.8, c.z, 0, 0, 0);
        } else if (r.hitBlock() && RangedMechanics.param(h, "marker") > 0) {
            Vec3 e = r.end();
            for (int k = 0; k < 3; k++) level.addParticle(MARK, e.x, e.y, e.z, 0, 0.02 * k, 0);
        }
    }

    private static double launchSpeed(LocalPlayer p) {
        ItemStack main = p.getMainHandItem(), off = p.getOffhandItem();
        if (p.isUsingItem() && p.getUseItem().getItem() instanceof BowItem) return BowItem.getPowerForTime(p.getTicksUsingItem()) * 3.0;
        if (p.isUsingItem() && p.getUseItem().getItem() instanceof TridentItem) return p.getTicksUsingItem() >= 10 ? 2.5 : 0;
        for (ItemStack s : new ItemStack[]{main, off}) {
            if (s.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(s)) {
                var cp = s.get(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES);
                return cp != null && cp.contains(Items.FIREWORK_ROCKET) ? 0 : 3.15;
            }
        }
        return 0;
    }

    /** Quick Loader: show the stored shots waiting in a crossbow. */
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent e) {
        ItemStack st = e.getItemStack();
        if (!(st.getItem() instanceof CrossbowItem)) return;
        CustomData data = st.get(DataComponents.CUSTOM_DATA);
        int n = data == null ? 0 : data.copyTag().getInt(Draw.STORED);
        if (n > 0) e.getToolTip().add(Component.translatable("tooltip.habits.quick_loader.stored", n).withStyle(ChatFormatting.GRAY));
    }

    /** Wall Bang, client copy of the server rule for your own arrows, so they don't stop at cover for a tick. */
    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent e) {
        if (!(e.getProjectile() instanceof AbstractArrow a) || a instanceof ThrownTrident || !a.level().isClientSide()
                || !(e.getRayTraceResult() instanceof BlockHitResult bh)) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || a.getOwner() != p) return;
        HabitsApi.PerkHit h = HabitsApi.mechanicHit(p, Habits.id("wall_bang"));
        int covers = a.getPersistentData().getInt("habits_covers");
        if (h == null || covers >= h.value() || !Impacts.thin(h, a.level().getBlockState(bh.getBlockPos()))) return;
        a.getPersistentData().putInt("habits_covers", covers + 1);
        e.setCanceled(true);
    }
}
