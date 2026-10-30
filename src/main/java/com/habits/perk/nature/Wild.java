package com.habits.perk.nature;

import com.habits.core.HabitsApi.PerkHit;
import com.habits.perk.Later;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;

import java.util.ArrayList;
import java.util.List;

/** Wild animals, pets and mounts, plus the "mobs lose you" family (calm aura, dryad, kelp cover, heart of the sea). */
public final class Wild {
    private Wild() {}

    static boolean mine(Entity e, Player p) {
        return e instanceof OwnableEntity o && p.getUUID().equals(o.getOwnerUUID());
    }

    static List<TamableAnimal> pets(Player p, double r) {
        return p.level().getEntitiesOfClass(TamableAnimal.class, p.getBoundingBox().inflate(r), t -> t.isAlive() && t.isOwnedBy(p));
    }

    /** Wild (not owned), not a monster. */
    static boolean wildAnimal(Entity e) {
        return e instanceof Animal && !(e instanceof Enemy) && !(e instanceof OwnableEntity o && o.getOwnerUUID() != null);
    }

    // ================================================================ targeting

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent e) {
        if (!(e.getNewAboutToBeSetTarget() instanceof Player p) || !Nat.ok(p)) return;
        LivingEntity mob = e.getEntity();
        PerkHit h;
        if (mob instanceof NeutralMob n && !(mob instanceof Enemy) && (h = Nat.hit(p, "calm_aura")) != null && Nat.steadyRoll(mob, p, h.value(), 6000)) {
            e.setCanceled(true);
            n.stopBeingAngry();
            return;
        }
        if ((mob instanceof Drowned || mob instanceof Guardian) && (h = Nat.hit(p, "sea_heart")) != null
                && h.level() >= (int) Nat.lerp(h, "calm_level", 10, 10)) {
            e.setCanceled(true);
            return;
        }
        if (mob instanceof Enemy && hidden(p, mob)) e.setCanceled(true);
    }

    static boolean hidden(Player p, Entity mob) {
        PerkHit h;
        if ((h = Nat.hit(p, "kelp_cover")) != null && inWeeds(p) && Nat.steadyRoll(mob, p, h.value(), 100)) return true;
        return (h = Nat.hit(p, "dryad")) != null && underCanopy(p) && Nat.steadyRoll(mob, p, h.value(), 100);
    }

    static boolean inWeeds(Player p) {
        if (!p.isInWater()) return false;
        for (BlockPos q : new BlockPos[]{p.blockPosition(), BlockPos.containing(p.getEyePosition())}) {
            var s = p.level().getBlockState(q);
            if (s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT) || s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS)) return true;
        }
        return false;
    }

    static boolean underCanopy(Player p) {
        if (!p.isShiftKeyDown()) return false;
        BlockPos head = BlockPos.containing(p.getEyePosition());
        for (int y = 1; y <= 4; y++) if (p.level().getBlockState(head.above(y)).is(BlockTags.LEAVES)) return true;
        return false;
    }

    /** dryad / kelp_cover: mobs already chasing you can lose you too, checked once a second. */
    static void hideTick(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 20 != 0) return;
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(24), m -> m.getTarget() == p && m instanceof Enemy))
            if (hidden(p, m)) m.setTarget(null);
    }

    // ================================================================ hits on you and your mount

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent e) {
        LivingEntity v = e.getEntity();
        if (!(v.level() instanceof ServerLevel l)) return;
        PerkHit h;
        if (v.getControllingPassenger() instanceof Player rider && (h = Nat.hit(rider, "barding")) != null)
            e.setAmount(e.getAmount() * (float) (1 - Math.min(100, h.value()) / 100));
        if (!(v instanceof Player p)) return;
        if (e.getSource().is(DamageTypeTags.IS_FALL) && (h = Nat.hit(p, "wingman")) != null
                && (!p.getShoulderEntityLeft().isEmpty() || !p.getShoulderEntityRight().isEmpty()))
            e.setAmount(e.getAmount() * (float) (1 - Math.min(100, h.value()) / 100));
        Entity src = e.getSource().getEntity();
        if (src instanceof LivingEntity a && a != p && !mine(a, p) && (h = Nat.hit(p, "guard_dog")) != null) guardDog(p, l, a, h);
        if ((h = Nat.hit(p, "beastmaster")) != null && !e.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY) && e.getAmount() > 0) {
            List<TamableAnimal> pets = pets(p, Nat.lerp(h, "radius", 12, 24));
            pets.removeIf(t -> t == src);
            if (!pets.isEmpty()) {
                float shared = e.getAmount() * (float) (Math.min(100, h.value()) / 100);
                e.setAmount(e.getAmount() - shared);
                for (TamableAnimal t : pets) t.hurt(l.damageSources().generic(), shared / pets.size());
            }
        }
    }

    static void guardDog(Player p, ServerLevel l, LivingEntity attacker, PerkHit h) {
        var d = Nat.data(p);
        if (l.getGameTime() < d.getLong("habits_guard_dog")) return;
        List<TamableAnimal> wolves = new ArrayList<>(pets(p, h.value()));
        wolves.removeIf(t -> !(t instanceof Wolf) || t.isOrderedToSit());
        if (wolves.isEmpty()) return;
        d.putLong("habits_guard_dog", l.getGameTime() + (long) (Nat.lerp(h, "cooldown", 20, 5) * 20));
        int t = (int) (Nat.lerp(h, "seconds", 5, 15) * 20);
        for (TamableAnimal w : wolves) {
            if (w.distanceToSqr(p) > 16) w.teleportTo(p.getX() + l.random.nextInt(3) - 1, p.getY(), p.getZ() + l.random.nextInt(3) - 1);
            w.setTarget(attacker);
            Nat.effect(w, MobEffects.DAMAGE_BOOST, t, 0);
            Nat.effect(w, MobEffects.MOVEMENT_SPEED, t, 0);
        }
    }

    // ================================================================ eating and drinking

    @SubscribeEvent
    public static void onFinish(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide() || !Nat.ok(p)) return;
        ItemStack s = e.getItem();
        PerkHit h;
        if (s.is(Items.GLOW_BERRIES) && (h = Nat.hit(p, "glow_forager")) != null)
            Nat.effect(p, MobEffects.NIGHT_VISION, (int) (h.value() * 20), 0);
        if (s.is(Items.MILK_BUCKET) && (h = Nat.hit(p, "fresh_milk")) != null) p.heal((float) h.value());
        if (s.getFoodProperties(p) != null && (h = Nat.hit(p, "shared_meal")) != null)
            for (TamableAnimal t : pets(p, Nat.lerp(h, "radius", 12, 24))) {
                t.heal((float) h.value());
                ((ServerLevel) p.level()).sendParticles(ParticleTypes.HEART, t.getX(), t.getY() + t.getBbHeight(), t.getZ(), 2, 0.2, 0.2, 0.2, 0);
            }
    }

    // ================================================================ pets

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract e) {
        Player p = e.getEntity();
        if (p.level().isClientSide() || e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        PerkHit h = Nat.hit(p, "pet_medic");
        if (h != null && e.getTarget() instanceof Animal a && mine(a, p) && a.isFood(e.getItemStack()) && a.getHealth() < a.getMaxHealth()) {
            a.heal((float) h.value());
            ((ServerLevel) p.level()).sendParticles(ParticleTypes.HEART, a.getX(), a.getY() + a.getBbHeight(), a.getZ(), 3, 0.3, 0.2, 0.3, 0);
        }
    }

    @SubscribeEvent
    public static void onPetDrops(LivingDropsEvent e) {
        if (!(e.getSource().getEntity() instanceof TamableAnimal pet) || !(pet.getOwner() instanceof Player p)) return;
        PerkHit h = Nat.hit(p, "pack_hunter");
        if (h == null || !Nat.roll(pet.level(), h.value())) return;
        List<ItemEntity> more = new ArrayList<>();
        for (ItemEntity it : e.getDrops())
            more.add(new ItemEntity(it.level(), it.getX(), it.getY(), it.getZ(), it.getItem().copy()));
        e.getDrops().addAll(more);
    }

    @SubscribeEvent
    public static void onTame(AnimalTameEvent e) {
        if (e.isCanceled() || !Nat.ok(e.getTamer())) return;
        // every tame is remembered, so the tame that wins the card already counts
        Nat.data(e.getTamer()).putString("habits_spirit", BuiltInRegistries.ENTITY_TYPE.getKey(e.getAnimal().getType()).toString());
    }

    /** The last animal you tamed lends you its effect. */
    static void spiritAnimal(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 40 != 0) return;
        String kind = Nat.data(p).getString("habits_spirit");
        if (kind.isEmpty()) return;
        String effect = h.skill().perk().param("other", "minecraft:luck");
        for (String pair : h.skill().perk().param("spirits", "").split(",")) {
            String[] kv = pair.split("=");
            if (kv.length == 2 && kv[0].trim().equals(kind)) effect = kv[1];
        }
        Nat.effect(effect).ifPresent(eff -> Nat.effect(p, eff, 60, (int) h.value() - 1));
    }

    @SubscribeEvent
    public static void onBreed(BabyEntitySpawnEvent e) {
        Player p = e.getCausedByPlayer();
        PerkHit h = Nat.hit(p, "quick_love");
        if (h == null || !(p.level() instanceof ServerLevel l)) return;
        double keep = 1 - Math.min(100, h.value()) / 100;
        Mob a = e.getParentA(), b = e.getParentB();
        // vanilla sets the 5 minute wait right after this event
        Later.run(l, 1, () -> {
            for (Mob m : new Mob[]{a, b})
                if (m instanceof Animal an && an.getAge() > 0) an.setAge((int) (an.getAge() * keep));
        });
    }

    @SubscribeEvent
    public static void onEgg(ProjectileImpactEvent e) {
        if (!(e.getProjectile() instanceof ThrownEgg egg) || !(egg.level() instanceof ServerLevel l) || !(egg.getOwner() instanceof Player p)) return;
        PerkHit h = Nat.hit(p, "hatchery");
        if (h == null || !Nat.roll(l, h.value())) return;
        int n = Math.max(1, Nat.round(l, Nat.lerp(h, "chicks", 1, 3)));
        for (int i = 0; i < n; i++) {
            Chicken c = EntityType.CHICKEN.create(l);
            if (c == null) return;
            c.setAge(-24000);
            c.moveTo(egg.getX(), egg.getY(), egg.getZ(), egg.getYRot(), 0);
            l.addFreshEntity(c);
        }
    }

    @SubscribeEvent
    public static void onWake(PlayerWakeUpEvent e) {
        Player p = e.getEntity();
        if (!(p.level() instanceof ServerLevel l) || !l.isDay()) return;
        PerkHit h = Nat.hit(p, "cat_nap");
        if (h == null || l.getEntitiesOfClass(Cat.class, p.getBoundingBox().inflate(Nat.lerp(h, "radius", 16, 16)), c -> c.isOwnedBy(p)).isEmpty()) return;
        int t = (int) (h.value() * 20), amp = (int) Nat.lerp(h, "amp", 0, 1);
        Nat.effect(p, MobEffects.REGENERATION, t, amp);
        Nat.effect(p, MobEffects.ABSORPTION, t, amp);
    }

    @SubscribeEvent
    public static void onKill(LivingDeathEvent e) {
        if (!(e.getSource().getEntity() instanceof Player p) || !wildAnimal(e.getEntity())) return;
        PerkHit h = Nat.hit(p, "apex");
        if (h == null) return;
        int food = Math.max(1, (int) Math.round(h.value()));
        p.getFoodData().eat(food, (float) (Nat.lerp(h, "sat", 0.5, 4) / (2.0 * food)));
    }

    // ================================================================ ticks

    static void mountMend(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 20 != 0) return;
        if (p.getVehicle() instanceof LivingEntity m && m.getHealth() < m.getMaxHealth()) m.heal((float) (h.value() / 60));
    }

    static void gameTracker(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 40 != 0 || !p.isShiftKeyDown()) return;
        for (LivingEntity a : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(h.value()), Wild::wildAnimal))
            Nat.effect(a, MobEffects.GLOWING, 50, 0);
    }

    /** A sprinting mount bowls over the mobs it runs into. */
    static void stampede(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || p.tickCount % 4 != 0 || !(p.getVehicle() instanceof LivingEntity mount)) return;
        Vec3 v = mount.getDeltaMovement();
        if (v.horizontalDistanceSqr() < 0.04) return;
        double knock = Nat.lerp(h, "knock", 0.8, 2);
        AABB box = mount.getBoundingBox().inflate(0.4).move(v.x * 2, 0, v.z * 2);
        for (LivingEntity m : l.getEntitiesOfClass(LivingEntity.class, box, m -> m instanceof Enemy && m != mount && m.isAlive())) {
            if (m.hurt(l.damageSources().mobAttack(mount), (float) h.value())) {
                Vec3 push = new Vec3(v.x, 0, v.z).normalize().scale(knock);
                m.push(push.x, 0.3, push.z);
                m.hurtMarked = true;
            }
        }
    }
}
