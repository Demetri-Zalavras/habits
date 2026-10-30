package com.habits.perk.survival;

import com.habits.core.HabitsApi;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;
import java.util.Map;

/** Eating, drinking, chorus fruit. */
public final class Food {
    private Food() {}

    static final TagKey<Item> COOKED_MEAT = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "foods/cooked_meat"));
    static final Map<Item, Item> RAW_TO_COOKED = Map.of(Items.BEEF, Items.COOKED_BEEF, Items.PORKCHOP, Items.COOKED_PORKCHOP,
            Items.MUTTON, Items.COOKED_MUTTON, Items.CHICKEN, Items.COOKED_CHICKEN, Items.RABBIT, Items.COOKED_RABBIT);
    static final Map<Holder<Potion>, Holder<Potion>> FLIP = Map.of(
            Potions.POISON, Potions.REGENERATION, Potions.LONG_POISON, Potions.LONG_REGENERATION, Potions.STRONG_POISON, Potions.STRONG_REGENERATION,
            Potions.WEAKNESS, Potions.STRENGTH, Potions.LONG_WEAKNESS, Potions.LONG_STRENGTH,
            Potions.SLOWNESS, Potions.SWIFTNESS, Potions.LONG_SLOWNESS, Potions.LONG_SWIFTNESS, Potions.STRONG_SLOWNESS, Potions.STRONG_SWIFTNESS,
            Potions.HARMING, Potions.HEALING, Potions.STRONG_HARMING, Potions.STRONG_HEALING);
    static final List<Holder<MobEffect>> GOOD = List.of(MobEffects.MOVEMENT_SPEED, MobEffects.DIG_SPEED, MobEffects.DAMAGE_BOOST,
            MobEffects.REGENERATION, MobEffects.DAMAGE_RESISTANCE, MobEffects.JUMP, MobEffects.FIRE_RESISTANCE, MobEffects.NIGHT_VISION);

    @SubscribeEvent
    public static void start(LivingEntityUseItemEvent.Start e) {
        if (!(e.getEntity() instanceof Player p) || !Sk.anyActive(p) && p.level().isClientSide) return;
        ItemStack s = e.getItem();
        boolean server = !p.level().isClientSide;
        if (server && s.is(Items.SHIELD)) Fx.fire(p, "shield_raise");
        if (server && s.has(DataComponents.FOOD)) Sk.data(p).putInt("foodBefore", p.getFoodData().getFoodLevel());
        if (!Sk.anyActive(p)) return;
        if (s.is(Items.POTION)) {
            if (server) flipBadPotion(p, s);
            cutDuration(e, Sk.QUICK_QUAFF.v(p));
        } else if (s.is(Items.DRIED_KELP)) {
            cutDuration(e, Sk.SEA_SNACK.v(p));
        } else if (s.is(Items.SHIELD)) {
            e.setDuration(e.getDuration() - (int) Sk.QUICK_GUARD.v(p));
        } else if (server && s.is(Items.MILK_BUCKET) && Sk.MILK_DRINKER.hit(p) != null) {
            ListTag kept = new ListTag();
            for (MobEffectInstance i : p.getActiveEffects()) if (i.getEffect().value().isBeneficial()) kept.add(i.save());
            Sk.data(p).put("milkKeep", kept);
        }
        if (server && s.has(DataComponents.FOOD) && Sk.data(p).getBoolean("fastNext")) {
            Sk.data(p).putBoolean("fastNext", false);
            cutDuration(e, Sk.ORCHARD_SNACKER.v(p));
        }
    }

    static void cutDuration(LivingEntityUseItemEvent.Start e, double pct) {
        if (pct > 0) e.setDuration(Math.max(1, (int) Math.round(e.getDuration() * (1 - pct / 100))));
    }

    /** Bitter Taste: one roll per potion bottle (marked so restarting the drink can't reroll it). */
    static void flipBadPotion(Player p, ItemStack s) {
        PotionContents c = s.get(DataComponents.POTION_CONTENTS);
        if (c == null || c.potion().isEmpty()) return;
        Holder<Potion> good = FLIP.get(c.potion().get());
        if (good == null) return;
        CustomData data = s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        if (data.contains("habits_rolled")) return;
        CustomData.update(DataComponents.CUSTOM_DATA, s, t -> t.putBoolean("habits_rolled", true));
        if (Sk.roll(p, Sk.BITTER_TASTE.v(p))) s.set(DataComponents.POTION_CONTENTS, new PotionContents(good));
    }

    @SubscribeEvent
    public static void finish(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !Fx.acts(p)) return;
        ItemStack s = e.getItem();
        FoodProperties food = s.get(DataComponents.FOOD);
        CompoundTag d = Sk.data(p);
        if (food != null && d.getInt("foodBefore") < 6 && p.getFoodData().getFoodLevel() >= 20) Fx.fire(p, "hunger_refill");
        if (!Sk.anyActive(p)) return;
        if (food != null) ate(p, s, food, d);
        if (s.is(Items.POTION)) drank(e, p, s);
        if (s.is(Items.MILK_BUCKET) && d.contains("milkKeep")) {
            double keep = Sk.MILK_DRINKER.v(p);
            for (Tag t : d.getList("milkKeep", Tag.TAG_COMPOUND)) {
                MobEffectInstance i = MobEffectInstance.load((CompoundTag) t);
                if (i != null && Sk.roll(p, keep)) p.addEffect(i);
            }
            d.remove("milkKeep");
        }
    }

    static void ate(Player p, ItemStack s, FoodProperties food, CompoundTag d) {
        var fd = p.getFoodData();
        HabitsApi.PerkHit h;
        if ((h = Sk.BIG_EATER.hit(p)) != null) {
            fd.setSaturation((float) Math.min(fd.getFoodLevel(), fd.getSaturationLevel() + food.saturation() * h.value() / 100));
            int over = d.getInt("foodBefore") + food.nutrition() - 20;
            if (over > 0) d.putInt("reserve", (int) Math.min(Sk.p(h, "reserve"), d.getInt("reserve") + over));
        }
        if (s.is(COOKED_MEAT) && (h = Sk.BUTCHERS_BELLY.hit(p)) != null)
            Fx.effect(p, MobEffects.DAMAGE_BOOST, h.value(), Sk.roll(p, Sk.p(h, "strong_chance")) ? 1 : 0);
        Item cooked = RAW_TO_COOKED.get(s.getItem());
        if (cooked != null && (h = Sk.RAW_DEAL.hit(p)) != null && Sk.p(h, "cooked") > 0) {
            FoodProperties c = cooked.components().get(DataComponents.FOOD);
            int dn = c == null ? 0 : c.nutrition() - food.nutrition();
            if (dn > 0) fd.eat(dn, (c.saturation() - food.saturation()) / (2f * dn));
        }
        if ((s.is(Items.COD) || s.is(Items.SALMON) || s.is(Items.COOKED_COD) || s.is(Items.COOKED_SALMON) || s.is(Items.TROPICAL_FISH))
                && (h = Sk.FISHERMANS_DIET.hit(p)) != null) {
            Fx.effect(p, MobEffects.WATER_BREATHING, h.value(), 0);
            Fx.effect(p, MobEffects.DOLPHINS_GRACE, Sk.p(h, "dolphin_seconds"), 0);
        }
        if (s.is(Items.PUFFERFISH) && (h = Sk.PUFFER_PROOF.hit(p)) != null)
            Fx.effect(p, MobEffects.REGENERATION, Sk.p(h, "regen_seconds"), Sk.maxed(h) ? 1 : 0);
        if (s.is(Items.ROTTEN_FLESH) && (h = Sk.GHOUL_GUT.hit(p)) != null)
            d.putLong("ghoulUntil", p.level().getGameTime() + (long) (Sk.p(h, "ignore_seconds") * 20));
        if (s.is(Items.SPIDER_EYE) && (h = Sk.SPIDER_SENSE.hit(p)) != null)
            d.putLong("senseUntil", p.level().getGameTime() + (long) (Sk.p(h, "seconds") * 20));
        if (s.is(Items.POISONOUS_POTATO) && (h = Sk.BAD_POTATO.hit(p)) != null && !p.hasEffect(MobEffects.POISON))
            Fx.effect(p, GOOD.get(p.getRandom().nextInt(GOOD.size())), Sk.p(h, "buff_seconds"), 0);
        if (s.is(Items.BREAD)) p.heal((float) Sk.BREAD_WINNER.v(p));
        if ((s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BAKED_POTATO) || s.is(Items.BEETROOT)) && (h = Sk.ROOT_CELLAR.hit(p)) != null)
            Fx.effect(p, MobEffects.DIG_SPEED, h.value(), Sk.p(h, "amp"));
        if (s.is(Items.GOLDEN_CARROT)) Fx.effect(p, MobEffects.NIGHT_VISION, Sk.GOLDEN_CARROT_EYES.v(p), 0);
        if ((s.is(Items.APPLE) || s.is(Items.MELON_SLICE) || s.is(Items.SWEET_BERRIES)) && (h = Sk.ORCHARD_SNACKER.hit(p)) != null) {
            fd.setSaturation((float) Math.min(fd.getFoodLevel(), fd.getSaturationLevel() + Sk.p(h, "saturation")));
            d.putBoolean("fastNext", true);
        }
        if (s.is(Items.GOLDEN_APPLE) && (h = Sk.GOLDEN_GLUTTON.hit(p)) != null)
            Fx.effect(p, MobEffects.DAMAGE_RESISTANCE, Sk.p(h, "resist_seconds"), 0);
        if (s.is(Items.ENCHANTED_GOLDEN_APPLE) && (h = Sk.NOTCHS_BLESSING.hit(p)) != null)
            d.putLong("insuredUntil", p.level().getGameTime() + (long) (h.value() * 20));
        if ((s.is(Items.MUSHROOM_STEW) || s.is(Items.RABBIT_STEW) || s.is(Items.BEETROOT_SOUP)) && (h = Sk.STEW_SIPPER.hit(p)) != null)
            Fx.effect(p, MobEffects.REGENERATION, h.value(), Sk.p(h, "amp"));
        if ((s.is(Items.COOKIE) || s.is(Items.PUMPKIN_PIE) || s.is(Items.HONEY_BOTTLE)) && (h = Sk.SWEET_TOOTH.hit(p)) != null)
            Fx.effect(p, MobEffects.MOVEMENT_SPEED, h.value(), Sk.p(h, "amp"));
        if (s.is(Items.HONEY_BOTTLE) && (h = Sk.HONEY_CURE.hit(p)) != null) {
            for (var eff : List.of(MobEffects.WITHER, MobEffects.HUNGER, MobEffects.MOVEMENT_SLOWDOWN))
                if (p.hasEffect(eff) && Sk.roll(p, h.value())) p.removeEffect(eff);
        }
        if (s.is(Items.DRIED_KELP) && (h = Sk.SEA_SNACK.hit(p)) != null)
            p.setAirSupply(Math.min(p.getMaxAirSupply(), p.getAirSupply() + (int) (Sk.p(h, "bubbles") * 30)));
    }

    /** Stew Sipper bowls: put the bowl on the bowl stack instead of in the hand. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOW)
    public static void stewBowl(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide || !e.getResultStack().is(Items.BOWL)) return;
        if (Sk.STEW_SIPPER.hit(p) == null) return;
        ItemStack bowl = e.getResultStack().copy();
        e.setResultStack(ItemStack.EMPTY);
        Fx.give(p, bowl);
    }

    static void drank(LivingEntityUseItemEvent.Finish e, Player p, ItemStack potion) {
        if (Sk.roll(p, Sk.BOTTOMLESS_FLASK.v(p))) e.setResultStack(potion.copyWithCount(1));
        HabitsApi.PerkHit h = Sk.HEALING_SURGE.hit(p);
        PotionContents c = potion.get(DataComponents.POTION_CONTENTS);
        if (h == null || c == null || !(c.is(Potions.HEALING) || c.is(Potions.STRONG_HEALING))) return;
        float amount = c.is(Potions.STRONG_HEALING) ? 8 : 4;
        p.heal(amount * (float) (Sk.p(h, "bonus") / 100));
        if (h.value() > 0)
            for (Player o : p.level().getEntitiesOfClass(Player.class, p.getBoundingBox().inflate(h.value()), o -> o != p && Fx.acts(o)))
                o.heal(amount);
    }

    @SubscribeEvent
    public static void cake(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (p.level().isClientSide || !p.getFoodData().needsFood()) return;
        if (!p.level().getBlockState(e.getPos()).is(BlockTags.CANDLE_CAKES) && !p.level().getBlockState(e.getPos()).is(net.minecraft.world.level.block.Blocks.CAKE)) return;
        HabitsApi.PerkHit h = Sk.SWEET_TOOTH.hit(p);
        if (h != null) Fx.effect(p, MobEffects.MOVEMENT_SPEED, h.value(), Sk.p(h, "amp"));
    }

    @SubscribeEvent
    public static void chorus(EntityTeleportEvent.ChorusFruit e) {
        if (!(e.getEntityLiving() instanceof Player p) || p.level().isClientSide) return;
        HabitsApi.PerkHit h = Sk.BLINK_SNACKER.hit(p);
        if (h == null) return;
        CompoundTag d = Sk.data(p);
        long now = p.level().getGameTime();
        if (d.getLong("chorusTick") == now) return; // only the first of vanilla's 16 tries; the rest stay random
        d.putLong("chorusTick", now);
        if (!Sk.roll(p, h.value())) return;
        Vec3 target = aim(p, Sk.p(h, "range"));
        e.setTargetX(target.x);
        e.setTargetY(target.y);
        e.setTargetZ(target.z);
    }

    /** Where the player is looking, backed off the hit face so the teleport lands in open space. */
    static Vec3 aim(Player p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(range));
        BlockHitResult hit = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (hit.getType() == HitResult.Type.MISS) return end;
        return Vec3.atBottomCenterOf(hit.getBlockPos().relative(hit.getDirection()));
    }
}
