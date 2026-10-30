package com.habits.perk.crafting;

import com.habits.core.HabitsApi;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.brewing.PlayerBrewedPotionEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Brewing and potion perks: drink faster, keep the bottle, bonus effects, share a draught, longer clouds, stacking. */
final class Alchemy {
    private Alchemy() {}

    static final ResourceLocation QUICK_SIP = C.mech("quick_sip", "% faster drinking");
    static final ResourceLocation BOTTOMLESS = C.mech("bottomless_flask", "% chance a potion is not used up");
    static final ResourceLocation MIXOLOGIST = C.mech("mixologist", "% chance of a bonus effect");
    static final ResourceLocation SHARED = C.mech("shared_draught", "share radius in blocks");
    static final ResourceLocation LONG_LINGER = C.mech("long_linger", "% longer potion clouds");
    static final ResourceLocation DISTILLER = C.mech("distillers_pay", "XP per brewed bottle");
    static final ResourceLocation BREW_RUSH = C.mech("brew_rush", "seconds of brewing skipped");
    static final ResourceLocation POTION_BELT = C.mech("potion_belt", "potions per stack");
    static final ResourceLocation COMFORT = C.mech("comfort_food", "seconds of Regeneration");

    private static final Map<UUID, Long> RUSH_CD = C.playerMap();

    static void init() {}

    private static boolean drinkable(ItemStack s) {
        return s.is(Items.POTION);
    }

    // ------------------------------------------------------------------ drinking

    @SubscribeEvent
    static void onStart(LivingEntityUseItemEvent.Start e) {
        if (!(e.getEntity() instanceof Player p) || !drinkable(e.getItem())) return;
        HabitsApi.PerkHit h = C.hitAnySide(p, QUICK_SIP);
        if (h != null) e.setDuration(Math.max(1, (int) Math.round(e.getDuration() * (1 - Math.min(90, h.value()) / 100))));
    }

    @SubscribeEvent
    static void onFinish(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof Player p) || !C.ok(p)) return;
        ItemStack used = e.getItem();
        HabitsApi.PerkHit h;
        if ((h = C.hit(p, COMFORT)) != null && used.has(DataComponents.FOOD) && C.matches(p, h, "items",
                "minecraft:bread,minecraft:cake,minecraft:cookie,minecraft:pumpkin_pie,minecraft:mushroom_stew,minecraft:rabbit_stew,"
                        + "minecraft:beetroot_soup,minecraft:suspicious_stew,minecraft:golden_carrot", used)) {
            C.effect(p, MobEffects.REGENERATION, (int) C.param(h, "amp", 0, 1), (int) (h.value() * 20));
        }
        if (!drinkable(used)) return;
        PotionContents pc = used.get(DataComponents.POTION_CONTENTS);
        if ((h = C.hit(p, MIXOLOGIST)) != null && C.roll(p, h.value())) bonusEffect(p, h);
        if ((h = C.hit(p, SHARED)) != null && pc != null) share(p, pc, h.value());
        if ((h = C.hit(p, BOTTOMLESS)) != null && C.roll(p, h.value())) keep(p, e, used);
    }

    /** Hand the potion back: either the bottle result becomes the potion, or a stacked potion grows back and its bottle goes. */
    private static void keep(Player p, LivingEntityUseItemEvent.Finish e, ItemStack used) {
        ItemStack result = e.getResultStack();
        if (result.is(Items.GLASS_BOTTLE)) {
            e.setResultStack(used.copyWithCount(1));
        } else if (ItemStack.isSameItemSameComponents(result, used)) {
            result.grow(1);
            p.getInventory().clearOrCountMatchingItems(s -> s.is(Items.GLASS_BOTTLE), 1, p.inventoryMenu.getCraftSlots());
        }
    }

    private static void bonusEffect(Player p, HabitsApi.PerkHit h) {
        List<Holder<MobEffect>> pool = new ArrayList<>();
        for (String id : C.str(h, "effects", "minecraft:speed,minecraft:haste,minecraft:resistance,minecraft:regeneration,"
                + "minecraft:absorption,minecraft:luck,minecraft:night_vision,minecraft:jump_boost").split(",")) {
            ResourceLocation rl = ResourceLocation.tryParse(id.trim());
            if (rl != null) BuiltInRegistries.MOB_EFFECT.getHolder(rl).ifPresent(pool::add);
        }
        if (pool.isEmpty()) return;
        p.addEffect(new MobEffectInstance(pool.get(p.getRandom().nextInt(pool.size())), (int) (C.param(h, "seconds", 20, 60) * 20), 0));
    }

    /** Copy the drunk effects onto other players and your own pets within {@code radius}. */
    static int share(Player p, PotionContents pc, double radius) {
        int n = 0;
        for (LivingEntity t : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(radius),
                t -> t != p && t.isAlive() && (t instanceof Player o && !o.isSpectator() || t instanceof TamableAnimal a && p.getUUID().equals(a.getOwnerUUID())))) {
            for (MobEffectInstance i : pc.getAllEffects()) {
                if (i.getEffect().value().isInstantenous()) i.getEffect().value().applyInstantenousEffect(p, p, t, i.getAmplifier(), 1.0);
                else t.addEffect(new MobEffectInstance(i));
            }
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ lingering clouds last longer

    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide || !(e.getEntity() instanceof AreaEffectCloud cloud) || !(cloud.getOwner() instanceof Player p)) return;
        HabitsApi.PerkHit h = C.hit(p, LONG_LINGER);
        if (h != null) cloud.setDuration((int) (cloud.getDuration() * (1 + h.value() / 100)));
    }

    // ------------------------------------------------------------------ brewing stand: pay, stacking, rush

    @SubscribeEvent
    static void onBrewTake(PlayerBrewedPotionEvent e) {
        Player p = e.getEntity();
        HabitsApi.PerkHit h;
        if ((h = C.hit(p, DISTILLER)) != null) {
            int xp = C.carry(p, h.value() * Math.max(1, e.getStack().getCount()));
            if (xp > 0) p.giveExperiencePoints(xp);
        }
        if ((h = C.hit(p, POTION_BELT)) != null) {
            ItemStack real = C.taken(p, e.getStack());
            if (real != null && real.has(DataComponents.POTION_CONTENTS)) real.set(DataComponents.MAX_STACK_SIZE, Math.max(1, Math.min(99, (int) h.value())));
        }
    }

    @SubscribeEvent
    static void onClick(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (!(p.level().getBlockEntity(e.getPos()) instanceof BrewingStandBlockEntity stand)) return;
        HabitsApi.PerkHit h = C.hit(p, BREW_RUSH);
        if (h != null) rush(p, stand, h);
    }

    static boolean rush(Player p, BrewingStandBlockEntity stand, HabitsApi.PerkHit h) {
        int left = stand.dataAccess.get(0);
        if (left <= 1 || !C.ready(RUSH_CD, p, C.param(h, "cooldown", 10, 10))) return false;
        stand.dataAccess.set(0, Math.max(1, left - (int) (h.value() * 20)));
        stand.setChanged();
        return true;
    }
}
