package com.habits.perk.utility;

import com.habits.core.HabitsApi;
import net.minecraft.Util;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownExperienceBottle;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Experience perks. */
final class Xp {
    private Xp() {}

    static final ResourceLocation WISE = U.mech("wise", "% more XP from orbs");
    static final ResourceLocation BOTTLED = U.mech("bottled_wisdom", "% of the XP kept when bottled");
    static final ResourceLocation MENDING_TOUCH = U.mech("mending_touch", "XP per durability point repaired");
    static final ResourceLocation LECTURER = U.mech("lecturer", "% more orb XP near a lectern");
    static final ResourceLocation AFK = U.mech("afk_pro", "XP per minute while idle");

    static final String TAG_XP = "habits_xp";

    static void init() {}

    // ------------------------------------------------------------------ orbs: wise, lecturer

    private static final Map<UUID, Boolean> NEAR_LECTERN = U.playerMap();

    @SubscribeEvent
    static void onOrb(PlayerXpEvent.PickupXp e) {
        Player p = e.getEntity();
        if (!U.ok(p)) return;
        double pct = U.v(p, WISE);
        HabitsApi.PerkHit lec = U.hit(p, LECTURER);
        if (lec != null && NEAR_LECTERN.getOrDefault(p.getUUID(), false)) pct += lec.value();
        int extra = U.carry(p, e.getOrb().getValue() * pct / 100);
        if (extra > 0) p.giveExperiencePoints(extra);
    }

    // ------------------------------------------------------------------ mending touch: sneak + hold use pours XP into gear

    private static final Map<UUID, Long> LAST_MEND = U.playerMap();

    /** One pulse from the client while sneak + use is held: repair the held item, or with an empty hand the most damaged
     * worn armor piece, by up to rate_pct% of its max durability, paid from the XP bar. */
    static void mendPulse(ServerPlayer p) {
        HabitsApi.PerkHit h = U.hit(p, MENDING_TOUCH);
        long now = p.level().getGameTime();
        if (h == null || !p.isShiftKeyDown() || now - LAST_MEND.getOrDefault(p.getUUID(), 0L) < 2) return;
        LAST_MEND.put(p.getUUID(), now);
        ItemStack s = p.getMainHandItem();
        if (s.isEmpty()) {
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack a = p.getItemBySlot(slot);
                if (a.isDamaged() && (!s.isDamaged() || a.getDamageValue() > s.getDamageValue())) s = a;
            }
        }
        if (mend(p, s, h.value(), Math.max(1, (int) Math.ceil(s.getMaxDamage() * U.param(h, "rate_pct", 2.5, 2.5) / 100))) > 0
                && now % 6 == 0)
            p.level().playSound(null, p.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.3f, 1.4f);
    }

    /** Repair up to {@code max} points of {@code s}, paying perPoint XP each from the bar; returns points repaired. */
    static int mend(Player p, ItemStack s, double perPoint, int max) {
        if (!s.isDamaged()) return 0;
        int points = Math.min(Math.min(s.getDamageValue(), max), (int) (totalXp(p) / perPoint));
        if (points <= 0) return 0;
        s.setDamageValue(s.getDamageValue() - points);
        p.giveExperiencePoints(-(int) Math.ceil(points * perPoint));
        return points;
    }

    // ------------------------------------------------------------------ bottled wisdom

    @SubscribeEvent
    static void onBottle(PlayerInteractEvent.RightClickItem e) {
        Player p = e.getEntity();
        if (!p.isShiftKeyDown() || !e.getItemStack().is(Items.GLASS_BOTTLE)) return;
        HabitsApi.PerkHit h = U.hit(p, BOTTLED);
        if (h == null) return;
        BlockHitResult hit = p.level().clip(new ClipContext(p.getEyePosition(), p.getEyePosition().add(p.getLookAngle().scale(5)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
        if (hit.getType() == HitResult.Type.BLOCK && !p.level().getFluidState(hit.getBlockPos()).isEmpty()) return; // filling water
        if (bottle(p, e.getItemStack(), h)) e.setCanceled(true);
    }

    /** Take {@code points} XP, store points * value% in one bottle (capped at 100%: never more out than in). */
    static boolean bottle(Player p, ItemStack bottles, HabitsApi.PerkHit h) {
        int points = (int) U.param(h, "points", 10, 10);
        if (totalXp(p) < points) return false;
        int stored = (int) Math.floor(points * Math.min(100, h.value()) / 100);
        p.giveExperiencePoints(-points);
        bottles.consume(1, p);
        ItemStack out = new ItemStack(Items.EXPERIENCE_BOTTLE);
        CustomData.update(DataComponents.CUSTOM_DATA, out, t -> t.putInt(TAG_XP, stored));
        out.set(DataComponents.LORE, new ItemLore(List.of(Component.translatable("habits.utility.bottled_lore", stored)
                .setStyle(Style.EMPTY.withItalic(false).withColor(0x9FE07A)))));
        U.give(p, out);
        p.level().playSound(null, p.blockPosition(), SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 0.8f, 1.3f);
        return true;
    }

    static int totalXp(Player p) {
        int lvl = p.experienceLevel, sum = 0;
        for (int l = 0; l < lvl; l++) sum += l >= 30 ? 112 + (l - 30) * 9 : l >= 15 ? 37 + (l - 15) * 5 : 7 + l * 2;
        return sum + Math.round(p.experienceProgress * p.getXpNeededForNextLevel());
    }

    /** Your own bottled XP goes straight back to the thrower (no orbs, so orb bonuses never stack onto it). Unowned: lost. */
    @SubscribeEvent
    static void onImpact(ProjectileImpactEvent e) {
        if (!(e.getProjectile() instanceof ThrownExperienceBottle b) || b.level().isClientSide) return;
        CustomData cd = b.getItem().get(DataComponents.CUSTOM_DATA);
        if (cd == null || !cd.contains(TAG_XP)) return;
        e.setCanceled(true);
        int xp = cd.copyTag().getInt(TAG_XP);
        if (b.getOwner() instanceof Player p && p.isAlive()) p.giveExperiencePoints(xp);
        b.level().levelEvent(2002, b.blockPosition(), 0x7FFF00);
        b.discard();
    }

    // ------------------------------------------------------------------ ticks: lectern check, AFK

    private static final Map<UUID, Integer> IDLE_FIRED = U.playerMap();
    private static final Map<UUID, Boolean> AFK_NOW = U.playerMap();

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !U.ok(p)) return;
        if (AFK_NOW.getOrDefault(p.getUUID(), false)) p.getFoodData().setExhaustion(0);
        if (p.tickCount % 20 != 0) return;
        long idle = Util.getMillis() - p.getLastActionTime();
        if (idle >= 300_000) {
            if (IDLE_FIRED.putIfAbsent(p.getUUID(), 1) == null) U.fire(p, "afk_5min");
        } else IDLE_FIRED.remove(p.getUUID());
        HabitsApi.PerkHit h = U.hit(p, AFK);
        boolean afk = h != null && idle >= U.param(h, "idle_seconds", 60, 60) * 1000;
        AFK_NOW.put(p.getUUID(), afk);
        if (afk) afkXp(p, h.value(), 1);
        if (p.tickCount % 100 == 0) {
            HabitsApi.PerkHit lec = U.hit(p, LECTURER);
            NEAR_LECTERN.put(p.getUUID(), lec != null && nearLectern(p, (int) U.param(lec, "radius", 8, 24)));
        }
    }

    static void afkXp(Player p, double perMinute, int seconds) {
        int xp = U.carry(p, perMinute * seconds / 60);
        if (xp > 0) p.giveExperiencePoints(xp);
    }

    /** Lecterns are librarian job sites, so the POI index finds them without a block scan. */
    static boolean nearLectern(Player p, int radius) {
        return p.level() instanceof ServerLevel sl && sl.getPoiManager()
                .findClosest(t -> t.is(PoiTypes.LIBRARIAN), p.blockPosition(), radius, PoiManager.Occupancy.ANY).isPresent();
    }
}
