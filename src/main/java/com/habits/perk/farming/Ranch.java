package com.habits.perk.farming;

import com.habits.Habits;
import com.habits.core.HabitsApi.PerkHit;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;

/** Spec batch: livestock (wool, milk, eggs, breeding, the herd). */
public final class Ranch {
    private Ranch() {}

    static PerkHit hit(Player p, String skill) {
        return Fields.hit(p, skill);
    }

    static final String PRIZE = "habits_prize", PLUCKED = "habits_plucked";

    // ================================================================ right clicks on animals

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getLevel() instanceof ServerLevel l) || !(e.getEntity() instanceof ServerPlayer p) || !Farm.ok(p)) return;
        Entity t = e.getTarget();
        ItemStack held = e.getItemStack();
        PerkHit h;
        if (t instanceof Sheep sh && held.is(Tags.Items.TOOLS_SHEAR) && sh.readyForShearing()) shear(p, l, sh);
        if ((t instanceof Cow || t instanceof Goat) && held.is(Items.BUCKET) && !((LivingEntity) t).isBaby() && (h = hit(p, "dairy_farmer")) != null && Farm.roll(l, h.value()))
            secondBucket(p, held);
        if (t instanceof Chicken c && held.isEmpty() && !c.isBaby() && (h = hit(p, "plucker")) != null) {
            long now = l.getGameTime();
            if (now >= c.getPersistentData().getLong(PLUCKED)) {
                c.getPersistentData().putLong(PLUCKED, now + (long) (h.value() * 20));
                c.spawnAtLocation(Items.FEATHER);
                l.playSound(null, c.blockPosition(), SoundEvents.CHICKEN_HURT, SoundSource.NEUTRAL, 0.6f, 1.4f);
                p.swing(InteractionHand.MAIN_HAND, true);
            }
        }
        if (t instanceof Armadillo a && held.is(Items.BRUSH) && !a.isBaby() && (h = hit(p, "brush_up")) != null) {
            int n = Farm.round(l, h.value());
            if (n > 0) a.spawnAtLocation(new ItemStack(Items.ARMADILLO_SCUTE, n));
        }
        if (t instanceof Animal a && a.isFood(held) && !a.isBaby()) {
            if ((h = hit(p, "animal_doctor")) != null) {
                a.heal((float) h.value() * 2);
                for (MobEffectInstance m : List.copyOf(a.getActiveEffects()))
                    if (m.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) a.removeEffect(m.getEffect());
                l.sendParticles(ParticleTypes.HEART, a.getX(), a.getY() + a.getBbHeight(), a.getZ(), 2, 0.3, 0.2, 0.3, 0);
            }
            if ((h = hit(p, "feed_bag")) != null && a.getAge() == 0 && a.canFallInLove()) feedBag(p, a, held, h.value());
        }
    }

    /** Woolly (extra wool), Rainbow Fleece (new color first, so the wool matches), Golden Fleece (gold). */
    static void shear(ServerPlayer p, ServerLevel l, Sheep sh) {
        PerkHit h;
        if ((h = hit(p, "wool_dyer")) != null && Farm.roll(l, h.value())) sh.setColor(DyeColor.byId(l.random.nextInt(16)));
        if ((h = hit(p, "woolly")) != null) {
            int n = Farm.round(l, h.value());
            if (n > 0) sh.spawnAtLocation(new ItemStack(woolOf(sh.getColor()), n), 1);
        }
        if ((h = hit(p, "golden_fleece")) != null && Farm.roll(l, h.value()))
            sh.spawnAtLocation(Farm.roll(l, Farm.lerp(h, "ingot", 0, 10)) ? Items.GOLD_INGOT : Items.GOLD_NUGGET);
    }

    static net.minecraft.world.item.Item woolOf(DyeColor c) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(c.getName() + "_wool"));
    }

    /** Dairy Farmer: one more empty bucket from the bag becomes milk (vanilla fills the one in hand). */
    static void secondBucket(ServerPlayer p, ItemStack held) {
        var inv = p.getInventory();
        ItemStack spare = ItemStack.EMPTY;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.is(Items.BUCKET) && (s != held || s.getCount() > 1)) { spare = s; break; }
        }
        if (spare.isEmpty()) return;
        spare.shrink(1);
        Farm.give(p, new ItemStack(Items.MILK_BUCKET));
    }

    /** Feed Bag: every adult of the same kind around that can breed gets fed too, one food each from the stack. */
    static void feedBag(ServerPlayer p, Animal fed, ItemStack food, double r) {
        boolean free = p.getAbilities().instabuild;
        for (Animal a : Animals.near(p, Animal.class, r, x -> x != fed && x.getType() == fed.getType() && !x.isBaby() && x.getAge() == 0 && x.canFallInLove())) {
            if (!free && food.getCount() <= 1) break; // vanilla still takes one for the clicked animal
            a.setInLove(p);
            if (!free) food.shrink(1);
        }
    }

    // ================================================================ eggs

    @SubscribeEvent
    public static void onImpact(ProjectileImpactEvent e) {
        if (!(e.getProjectile() instanceof ThrownEgg egg) || !(egg.getOwner() instanceof ServerPlayer p) || !(egg.level() instanceof ServerLevel l) || !Farm.ok(p)) return;
        PerkHit h;
        if (e.getRayTraceResult() instanceof EntityHitResult eh && eh.getEntity() instanceof LivingEntity v && v != p && (h = hit(p, "egg_bomb")) != null) {
            v.hurt(l.damageSources().thrown(egg, p), (float) h.value());
            v.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, (int) (Farm.lerp(h, "slow", 1, 4) * 20), 1));
        }
        if ((h = hit(p, "hatchery")) != null && Farm.roll(l, h.value())) {
            Chicken c = EntityType.CHICKEN.create(l);
            if (c != null) {
                c.setAge(-24000);
                c.moveTo(egg.getX(), egg.getY(), egg.getZ(), egg.getYRot(), 0);
                l.addFreshEntity(c);
            }
        }
    }

    /** Laid eggs (an egg item with no thrower right next to a chicken) and chicks hatched from your thrown eggs. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.loadedFromDisk() || !(e.getLevel() instanceof ServerLevel l)) return;
        if (e.getEntity() instanceof ItemEntity it && it.getItem().is(Items.EGG) && it.getOwner() == null
                && !l.getEntitiesOfClass(Chicken.class, it.getBoundingBox().inflate(1), c -> !c.isBaby()).isEmpty()) laid(l, it);
        else if (e.getEntity() instanceof Chicken c && c.isBaby())
            for (ThrownEgg egg : l.getEntitiesOfClass(ThrownEgg.class, c.getBoundingBox().inflate(2)))
                if (egg.getOwner() instanceof ServerPlayer p) { Farm.fire(p, "farming/egg_hatched", c.position()); break; }
    }

    static void laid(ServerLevel l, ItemEntity egg) {
        Vec3 at = egg.position();
        boolean doubled = false, gold = false;
        for (ServerPlayer p : l.players()) {
            if (p.distanceToSqr(at) > 256 || !Farm.ok(p)) continue;
            Farm.fire(p, "farming/egg_laid_near", at);
            PerkHit h;
            if (!doubled && (h = hit(p, "double_yolk")) != null && Farm.roll(l, h.value())) {
                egg.getItem().grow(1);
                doubled = true;
            }
            if (!gold && (h = hit(p, "golden_goose")) != null && Farm.roll(l, h.value())) {
                Farm.drop(l, at, new ItemStack(Farm.roll(l, Farm.lerp(h, "ingot", 0, 10)) ? Items.GOLD_INGOT : Items.GOLD_NUGGET));
                gold = true;
            }
        }
    }

    // ================================================================ breeding

    @SubscribeEvent
    public static void onBaby(BabyEntitySpawnEvent e) {
        AgeableMob child = e.getChild();
        if (child == null || !(e.getCausedByPlayer() instanceof ServerPlayer p) || !(child.level() instanceof ServerLevel l) || !Farm.ok(p)) return;
        var a = e.getParentA();
        var b = e.getParentB();
        PerkHit h;
        if (l.getEntitiesOfClass(Animal.class, a.getBoundingBox().inflate(8), x -> x.getType() == a.getType()).size() >= 12)
            Farm.fire(p, "farming/big_herd", a.position());
        if ((h = hit(p, "quick_breed")) != null) {
            double keep = 1 - h.value() / 100.0;
            later(l, () -> {
                for (var m : new net.minecraft.world.entity.Mob[]{a, b})
                    if (m instanceof AgeableMob am && am.isAlive() && am.getAge() > 0) am.setAge((int) (am.getAge() * keep));
            });
        }
        if ((h = hit(p, "prize_livestock")) != null && Farm.roll(l, h.value())) {
            child.getPersistentData().putBoolean(PRIZE, true);
            l.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, a.getX(), a.getY() + 0.5, a.getZ(), 12, 0.3, 0.3, 0.3, 0.1);
        }
        if (child instanceof Rabbit && (h = hit(p, "lucky_litter")) != null && Farm.roll(l, h.value())) a.spawnAtLocation(Items.RABBIT_FOOT);
        if ((h = hit(p, "noahs_ark")) != null && Farm.roll(l, h.value())) ark(l, a, child.getType(), h.skill().perk().param("animals", "minecraft:cow,minecraft:pig,minecraft:sheep,minecraft:chicken"));
    }

    private static void later(ServerLevel l, Runnable r) {
        com.habits.perk.Later.run(l, 1, r);
    }

    /** Noah's Ark: a baby of another farm animal from the list, next to the parents. */
    static void ark(ServerLevel l, Entity at, EntityType<?> not, String list) {
        List<EntityType<?>> pick = new ArrayList<>();
        for (String id : list.split(",")) {
            ResourceLocation rl = ResourceLocation.tryParse(id.trim());
            if (rl != null) BuiltInRegistries.ENTITY_TYPE.getOptional(rl).filter(t -> t != not).ifPresent(pick::add);
        }
        if (pick.isEmpty()) return;
        Entity made = pick.get(l.random.nextInt(pick.size())).spawn(l, at.blockPosition(), MobSpawnType.BREEDING);
        if (made instanceof AgeableMob m) m.setAge(AgeableMob.BABY_START_AGE);
        if (made != null) l.sendParticles(ParticleTypes.HEART, made.getX(), made.getY() + 0.5, made.getZ(), 5, 0.3, 0.3, 0.3, 0);
    }

    /** Prize Livestock: a prize animal drops everything twice. */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent e) {
        if (!e.getEntity().getPersistentData().getBoolean(PRIZE)) return;
        for (ItemEntity d : List.copyOf(e.getDrops())) {
            ItemEntity copy = new ItemEntity(d.level(), d.getX(), d.getY(), d.getZ(), d.getItem().copy());
            copy.setDefaultPickUpDelay();
            e.getDrops().add(copy);
        }
    }

    // ================================================================ the herd

    @SubscribeEvent
    public static void onIncoming(LivingIncomingDamageEvent e) {
        LivingEntity v = e.getEntity();
        if (!(v.level() instanceof ServerLevel l)) return;
        PerkHit h;
        if (v instanceof ServerPlayer p && (h = hit(p, "herd_immunity")) != null) {
            int need = (int) Farm.lerp(h, "animals", 4, 4);
            if (Animals.near(p, Animal.class, 8, Soil::farmAnimal).size() >= need) e.setAmount(e.getAmount() * (float) (1 - h.value() / 100.0));
            return;
        }
        if (!Soil.farmAnimal(v)) return;
        for (ServerPlayer p : l.players()) {
            if ((h = hit(p, "flock_guard")) == null) continue;
            double r = Farm.lerp(h, "radius", 16, 16);
            if (p.distanceToSqr(v) <= r * r) {
                e.setAmount(e.getAmount() * (float) (1 - h.value() / 100.0));
                return;
            }
        }
    }

    // ================================================================ ticks

    /** Truffle Pig: every value seconds one adult pig near you on soil digs something up. */
    static void truffleTick(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || (l.getGameTime() + p.getId()) % 20 != 0) return;
        List<Pig> pigs = Animals.near(p, Pig.class, 16, x -> !x.isBaby() && soil(l.getBlockState(x.getOnPos())));
        if (pigs.isEmpty() || !Farm.cooldown(p, "habits_truffle", h.value())) return;
        Pig pig = pigs.get(l.random.nextInt(pigs.size()));
        boolean rare = Farm.roll(l, Farm.lerp(h, "rare", 2, 10));
        var it = Soil.pick(l, h.skill().perk().param(rare ? "rare_items" : "items", "minecraft:brown_mushroom"));
        if (it == Items.AIR) return;
        pig.spawnAtLocation(it);
        l.sendParticles(ParticleTypes.COMPOSTER, pig.getX(), pig.getY() + 0.2, pig.getZ(), 8, 0.3, 0.1, 0.3, 0);
        l.playSound(null, pig.blockPosition(), SoundEvents.PIG_AMBIENT, SoundSource.NEUTRAL, 1, 1);
    }

    static boolean soil(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT);
    }

    static final ResourceLocation PIG_BOOST = Habits.id("farming/pig_rider");

    /** Pig Rider: a speed modifier on the pig you ride, taken off when you get off. */
    static void pigTick(Player p, PerkHit h) {
        if (p.level().isClientSide || !(p.getVehicle() instanceof Pig pig)) return;
        AttributeInstance a = pig.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a != null) a.addOrUpdateTransientModifier(new AttributeModifier(PIG_BOOST, h.value() / 100.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
    }

    static void unboostPig(Pig pig) {
        AttributeInstance a = pig.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a != null) a.removeModifier(PIG_BOOST);
    }
}
