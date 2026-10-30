package com.habits.perk;

import com.habits.core.PerkType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Event-driven stat {@link PerkType}s. Ticking ones live in {@link StatTicks}, mixin-driven ones in {@link StatHooks}. */
final class StatEvents {
    private StatEvents() {}

    /** Work to run once the current server tick ends (after vanilla finished the interaction). Server thread only. */
    private static final List<Runnable> END_OF_TICK = new ArrayList<>();

    static void init() {
        var bus = NeoForge.EVENT_BUS;
        bus.addListener((ServerTickEvent.Post e) -> drain());
        bus.addListener((ServerStoppedEvent e) -> END_OF_TICK.clear());
        bus.addListener(StatEvents::breakSpeed);
        bus.addListener(StatEvents::incomingDamage);
        bus.addListener(StatEvents::damagePost);
        bus.addListener(StatEvents::crit);
        bus.addListener(StatEvents::blockDrops);
        bus.addListener(StatEvents::livingDrops);
        bus.addListener(StatEvents::xpPickup);
        bus.addListener(StatEvents::eat);
        bus.addListener(StatEvents::smelt);
        bus.addListener(StatEvents::breed);
        bus.addListener(StatEvents::tameAttempt);
        bus.addListener(StatEvents::enchantCost);
        bus.addListener(StatEvents::fishingHook);
    }

    static ResourceLocation blockId(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock());
    }

    static ResourceLocation itemId(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem());
    }

    static ResourceLocation entityId(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
    }

    static ResourceLocation damageId(DamageSource s) {
        return s.typeHolder().unwrapKey().map(k -> k.location()).orElse(null);
    }

    // MINING_SPEED (both sides)
    static void breakSpeed(PlayerEvent.BreakSpeed e) {
        Player p = e.getEntity();
        double v = Stats.sum(p, PerkType.MINING_SPEED, h -> Stats.accepts(h, "block", blockId(e.getState()), Registries.BLOCK, p));
        if (v != 0) e.setNewSpeed((float) (e.getNewSpeed() * (1 + v / 100)));
    }

    // DAMAGE_BONUS (attacker) and DAMAGE_TYPE_RESIST (victim)
    static void incomingDamage(LivingIncomingDamageEvent e) {
        DamageSource src = e.getSource();
        ResourceLocation type = damageId(src);
        float amount = e.getAmount();
        if (src.getEntity() instanceof Player p && p != e.getEntity()) {
            ItemStack weapon = src.getWeaponItem() != null ? src.getWeaponItem() : p.getMainHandItem();
            double v = Stats.sum(p, PerkType.DAMAGE_BONUS, h -> Stats.accepts(h, "damage", type, Registries.DAMAGE_TYPE, p)
                    && Stats.accepts(h, "entity", entityId(e.getEntity()), Registries.ENTITY_TYPE, p)
                    && Stats.accepts(h, "item", itemId(weapon), Registries.ITEM, p));
            amount *= (float) (1 + v / 100);
        }
        if (e.getEntity() instanceof Player p) {
            double v = Stats.sum(p, PerkType.DAMAGE_TYPE_RESIST, h -> Stats.accepts(h, "damage", type, Registries.DAMAGE_TYPE, p));
            amount *= (float) (1 - Math.min(v, PerkInit.RESIST_CAP.get()) / 100);
        }
        if (amount != e.getAmount()) e.setAmount(amount);
    }

    // LIFESTEAL (attacker) and THORNS (victim), melee only (the attacker is the direct entity)
    static void damagePost(LivingDamageEvent.Post e) {
        DamageSource src = e.getSource();
        float dealt = e.getNewDamage();
        if (dealt <= 0 || src.is(DamageTypes.THORNS) || src.getEntity() == null || src.getDirectEntity() != src.getEntity()) return;
        if (src.getEntity() instanceof Player p && p.isAlive()) {
            double v = Stats.sum(p, PerkType.LIFESTEAL);
            if (v > 0) p.heal((float) (dealt * v / 100));
        }
        if (e.getEntity() instanceof Player p && src.getEntity() instanceof LivingEntity attacker && attacker != p) {
            double v = Stats.sum(p, PerkType.THORNS);
            if (v > 0) attacker.hurt(p.damageSources().thorns(p), (float) (dealt * v / 100));
        }
    }

    // CRIT_DAMAGE
    static void crit(CriticalHitEvent e) {
        if (!e.isCriticalHit()) return;
        double v = Stats.sum(e.getEntity(), PerkType.CRIT_DAMAGE);
        if (v != 0) e.setDamageMultiplier((float) (e.getDamageMultiplier() * (1 + v / 100)));
    }

    // AUTO_REPLANT and DROP_BONUS (blocks)
    static void blockDrops(BlockDropsEvent e) {
        if (!(e.getBreaker() instanceof Player p)) return;
        ResourceLocation block = blockId(e.getState());
        if (e.getState().getBlock() instanceof CropBlock crop && crop.isMaxAge(e.getState())) {
            double v = Stats.sum(p, PerkType.AUTO_REPLANT, h -> Stats.accepts(h, "block", block, Registries.BLOCK, p));
            if (v > 0 && p.getRandom().nextDouble() * 100 < v && takeSeed(e.getDrops(), crop)) {
                e.getLevel().setBlock(e.getPos(), crop.getStateForAge(0), 3);
            }
        }
        double v = Stats.sum(p, PerkType.DROP_BONUS, h -> !(Stats.has(h, "entity") && !Stats.has(h, "block"))
                && Stats.accepts(h, "block", block, Registries.BLOCK, p));
        duplicate(e.getDrops(), v, p);
    }

    private static boolean takeSeed(List<ItemEntity> drops, CropBlock crop) {
        for (ItemEntity d : drops) {
            if (d.getItem().is(crop.asItem())) { d.getItem().shrink(1); return true; }
        }
        return false;
    }

    // DROP_BONUS (mobs)
    static void livingDrops(LivingDropsEvent e) {
        if (!(e.getSource().getEntity() instanceof Player p) || e.getEntity() instanceof Player) return;
        ResourceLocation victim = entityId(e.getEntity());
        double v = Stats.sum(p, PerkType.DROP_BONUS, h -> !(Stats.has(h, "block") && !Stats.has(h, "entity"))
                && Stats.accepts(h, "entity", victim, Registries.ENTITY_TYPE, p));
        duplicate(e.getDrops(), v, p);
    }

    /** {@code percent}% chance to add another copy of every drop (over 100 = guaranteed copies plus a chance of one more). */
    static void duplicate(Collection<ItemEntity> drops, double percent, Player p) {
        if (percent <= 0 || drops.isEmpty()) return;
        int copies = Stats.roundRandom(percent / 100, p.getRandom());
        List<ItemEntity> extra = new ArrayList<>();
        for (int c = 0; c < copies; c++) {
            for (ItemEntity d : drops) {
                if (d.getItem().isEmpty()) continue;
                ItemEntity copy = new ItemEntity(d.level(), d.getX(), d.getY(), d.getZ(), d.getItem().copy());
                copy.setDefaultPickUpDelay();
                extra.add(copy);
            }
        }
        drops.addAll(extra);
    }

    // XP_BONUS
    static void xpPickup(PlayerXpEvent.PickupXp e) {
        double v = Stats.sum(e.getEntity(), PerkType.XP_BONUS);
        if (v != 0) e.getOrb().value = Math.max(0, Stats.roundRandom(e.getOrb().value * (1 + v / 100), e.getEntity().getRandom()));
    }

    // FOOD_BONUS
    static void eat(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide) return;
        FoodProperties food = e.getItem().getFoodProperties(p);
        if (food == null) return;
        double v = Stats.sum(p, PerkType.FOOD_BONUS, h -> Stats.accepts(h, "item", itemId(e.getItem()), Registries.ITEM, p));
        if (v <= 0) return;
        var fd = p.getFoodData();
        fd.setFoodLevel(Math.min(20, fd.getFoodLevel() + Stats.roundRandom(food.nutrition() * v / 100, p.getRandom())));
        fd.setSaturation((float) Math.min(fd.getFoodLevel(), fd.getSaturationLevel() + food.saturation() * v / 100));
    }

    // SMELT_BONUS (fired when the result is taken out of a furnace)
    static void smelt(PlayerEvent.ItemSmeltedEvent e) {
        Player p = e.getEntity();
        ItemStack out = e.getSmelting();
        if (p.level().isClientSide || out.isEmpty()) return;
        double v = Stats.sum(p, PerkType.SMELT_BONUS, h -> Stats.accepts(h, "item", itemId(out), Registries.ITEM, p));
        if (v <= 0) return;
        int extra = 0;
        for (int i = 0; i < out.getCount(); i++) extra += Stats.roundRandom(v / 100, p.getRandom());
        if (extra > 0) {
            ItemStack bonus = out.copyWithCount(extra);
            if (!p.getInventory().add(bonus)) p.drop(bonus, false);
        }
    }

    // BREED_TWINS
    static void breed(BabyEntitySpawnEvent e) {
        Player p = e.getCausedByPlayer();
        AgeableMob child = e.getChild();
        if (p != null && child != null && e.getParentA() instanceof Animal a && e.getParentB() instanceof AgeableMob b) twin(p, a, b, child);
    }

    static void twin(Player p, Animal a, AgeableMob b, AgeableMob child) {
        if (!(a.level() instanceof ServerLevel level)) return;
        double v = Stats.sum(p, PerkType.BREED_TWINS, h -> Stats.accepts(h, "entity", entityId(child), Registries.ENTITY_TYPE, p));
        if (v <= 0 || p.getRandom().nextDouble() * 100 >= v) return;
        AgeableMob twin = a.getBreedOffspring(level, b);
        if (twin == null) return;
        twin.setBaby(true);
        twin.moveTo(a.getX(), a.getY(), a.getZ(), 0, 0);
        level.addFreshEntityWithPassengers(twin);
    }

    static void drain() {
        if (END_OF_TICK.isEmpty()) return;
        List<Runnable> now = new ArrayList<>(END_OF_TICK);
        END_OF_TICK.clear();
        now.forEach(Runnable::run);
    }

    // TAME_CHANCE: at the end of the tick, after a taming attempt that used the item and left the animal wild, roll the bonus.
    // ponytail: TamableAnimal only (wolf, cat, parrot); horses tame by temper and are not covered.
    static void tameAttempt(PlayerInteractEvent.EntityInteract e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getTarget() instanceof TamableAnimal animal) || animal.isTame()) return;
        ItemStack held = p.getItemInHand(e.getHand());
        int before = held.getCount();
        if (before == 0) return;
        double v = Stats.sum(p, PerkType.TAME_CHANCE, h -> Stats.accepts(h, "entity", entityId(animal), Registries.ENTITY_TYPE, p));
        if (v <= 0) return;
        END_OF_TICK.add(() -> {
            if (animal.isAlive() && !animal.isTame() && held.getCount() < before && p.getRandom().nextDouble() * 100 < v) tame(animal, p);
        });
    }

    static void tame(TamableAnimal animal, Player p) {
        animal.tame(p);
        animal.getNavigation().stop();
        animal.setTarget(null);
        animal.setOrderedToSit(true);
        animal.level().broadcastEntityEvent(animal, (byte) 7);
    }

    // ENCHANT_DISCOUNT: the enchanting table takes its levels through giveExperienceLevels(-cost)
    static void enchantCost(PlayerXpEvent.LevelChange e) {
        Player p = e.getEntity();
        if (e.getLevels() >= 0 || !(p.containerMenu instanceof EnchantmentMenu)) return;
        double v = Math.min(100, Stats.sum(p, PerkType.ENCHANT_DISCOUNT));
        int cost = -e.getLevels();
        if (v > 0) e.setLevels(-(cost - Stats.roundRandom(cost * v / 100, p.getRandom())));
    }

    // FISHING_LUCK / FISHING_SPEED (fields opened by the access transformer)
    static void fishingHook(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide || !(e.getEntity() instanceof FishingHook hook) || hook.getPlayerOwner() == null) return;
        Player p = hook.getPlayerOwner();
        hook.luck += (int) Math.round(Stats.sum(p, PerkType.FISHING_LUCK));
        hook.lureSpeed += (int) Math.round(Stats.sum(p, PerkType.FISHING_SPEED) * 100); // one Lure level = 100 ticks
    }
}
