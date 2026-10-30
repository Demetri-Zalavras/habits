package com.habits.perk.farming;

import com.habits.Habits;
import com.habits.core.HabitsApi.PerkHit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.level.block.DispenserBlock;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Dolphin;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Animals, pets and mounts. Ticks run every N ticks, staggered by entity id, with one bounded entity query each. */
public final class Animals {
    private Animals() {}

    static final String OWNER = "habits_owner";

    /** Server side, once every {@code n} ticks for this player (staggered so players don't all land on one tick). */
    static boolean every(Player p, int n) {
        return p.level() instanceof ServerLevel l && (l.getGameTime() + p.getId()) % n == 0;
    }

    static <T extends Entity> List<T> near(Player p, Class<T> c, double r, java.util.function.Predicate<T> f) {
        return p.level().getEntitiesOfClass(c, p.getBoundingBox().inflate(r), f);
    }

    static boolean ownedBy(Entity e, Player p) {
        return e instanceof OwnableEntity o && p.getUUID().equals(o.getOwnerUUID());
    }

    // ================================================================ breeding

    @SubscribeEvent
    public static void onBaby(BabyEntitySpawnEvent e) {
        if (!(e.getCausedByPlayer() instanceof ServerPlayer p) || e.getChild() == null || !(p.level() instanceof ServerLevel l)) return;
        AgeableMob child = e.getChild();
        PerkHit h;
        if ((h = Farm.hit(p, "stud_book")) != null && child instanceof AbstractHorse && e.getParentA() instanceof AbstractHorse a && e.getParentB() instanceof AbstractHorse b)
            studBook(l, h, child, a, b);
        if (child instanceof Hoglin && Farm.hit(p, "hoglin_rancher") != null) child.getPersistentData().putUUID(OWNER, p.getUUID());
        if ((h = Farm.hit(p, "matchmaker")) != null && e.getParentA() instanceof Animal a && e.getParentB() instanceof AgeableMob b) {
            int extra = (Farm.roll(l, h.value()) ? 1 : 0) + (Farm.roll(l, Farm.lerp(h, "triplets", 0, 25)) ? 1 : 0);
            for (int i = 0; i < extra; i++) {
                AgeableMob baby = a.getBreedOffspring(l, b);
                if (baby == null) break;
                baby.setBaby(true);
                baby.moveTo(a.getX(), a.getY(), a.getZ(), 0, 0);
                l.addFreshEntityWithPassengers(baby);
            }
        }
    }

    static void studBook(ServerLevel l, PerkHit h, AgeableMob child, AbstractHorse a, AbstractHorse b) {
        double bias = Farm.lerp(h, "bias", 20, 100), bonus = 1 + h.value() / 100;
        var attrs = List.of(Attributes.MAX_HEALTH, Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH);
        double[] caps = {30 * 1.2, 0.3375 * 1.2, 1.0 * 1.2}; // vanilla maxima, plus 20%
        for (int i = 0; i < attrs.size(); i++) {
            AttributeInstance c = child.getAttribute(attrs.get(i));
            if (c == null) continue;
            double v = c.getBaseValue();
            if (Farm.roll(l, bias)) v = Math.max(a.getAttributeBaseValue(attrs.get(i)), b.getAttributeBaseValue(attrs.get(i)));
            c.setBaseValue(Math.min(caps[i], v * bonus));
        }
        child.setHealth(child.getMaxHealth());
    }

    // ================================================================ interactions

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel l) || !Farm.ok(p)) return;
        Entity t = e.getTarget();
        ItemStack held = e.getItemStack();
        PerkHit h;
        if (isCritterBucket(held)) { e.setCanceled(true); e.setCancellationResult(InteractionResult.FAIL); return; }
        if (held.is(Items.BUCKET) && (h = Farm.hit(p, "bucket_anything")) != null && scoop(p, t, held, Farm.tier(h))) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (held.is(Items.LEAD) && t instanceof Mob m && (h = Farm.hit(p, "leash_master")) != null && leash(p, m, h)) {
            if (!p.getAbilities().instabuild) held.shrink(1);
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (held.is(Items.AMETHYST_SHARD) && t instanceof Allay a && (h = Farm.hit(p, "allay_pal")) != null && dupe(p, l, a, h)) {
            if (!p.getAbilities().instabuild) held.shrink(1);
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (held.is(Tags.Items.TOOLS_SHEAR) && t instanceof Sheep s && s.readyForShearing() && (h = Farm.hit(p, "shearer")) != null) shearFlock(p, l, s, held, h);
        if (held.is(Items.BOWL) && t.getType() == EntityType.MOOSHROOM && (h = Farm.hit(p, "stew_herder")) != null && Farm.roll(l, Farm.lerp(h, "effect", 5, 50)))
            com.habits.perk.Later.run(p.server, 0, () -> blessStew(p, l));
    }

    /** Lv5 villagers, Lv10 any non-boss monster; plain leashables are left to vanilla. */
    static boolean leash(ServerPlayer p, Mob m, PerkHit h) {
        if (m.isLeashed() || m.canBeLeashed() && !(m instanceof Enemy)) return false;
        boolean ok = m instanceof AbstractVillager ? h.level() >= (int) Farm.lerp(h, "villager_level", 5, 5)
                : m instanceof Enemy && !m.getType().is(Tags.EntityTypes.BOSSES) && h.level() >= (int) Farm.lerp(h, "hostile_level", 10, 10);
        if (!ok) return false;
        m.setLeashedTo(p, true);
        return true;
    }

    static boolean dupe(ServerPlayer p, ServerLevel l, Allay a, PerkHit h) {
        long now = l.getGameTime();
        if (now < a.getPersistentData().getLong("habits_dupe")) return false;
        Allay copy = EntityType.ALLAY.create(l);
        if (copy == null) return false;
        long cd = (long) (Farm.lerp(h, "dupe_cd", 300, 0) * 20);
        a.getPersistentData().putLong("habits_dupe", now + cd);
        copy.getPersistentData().putLong("habits_dupe", now + cd);
        copy.moveTo(a.position());
        copy.setPersistenceRequired();
        l.addFreshEntity(copy);
        l.sendParticles(ParticleTypes.HEART, a.getX(), a.getY() + 0.5, a.getZ(), 3, 0.3, 0.3, 0.3, 0);
        l.playSound(null, a.blockPosition(), SoundEvents.ALLAY_THROW, SoundSource.NEUTRAL, 1, 1);
        return true;
    }

    static void shearFlock(ServerPlayer p, ServerLevel l, Sheep target, ItemStack shears, PerkHit h) {
        boolean bonus = h.level() >= 10;
        List<Sheep> flock = new ArrayList<>(l.getEntitiesOfClass(Sheep.class, target.getBoundingBox().inflate(h.value()), s -> s != target && s.readyForShearing()));
        for (Sheep s : flock) {
            if (shears.isEmpty()) break;
            s.shear(SoundSource.PLAYERS);
            shears.hurtAndBreak(1, p, EquipmentSlot.MAINHAND);
        }
        if (bonus) {
            flock.add(target);
            for (Sheep s : flock) s.spawnAtLocation(BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(s.getColor().getName() + "_wool")));
        }
    }

    static final List<Holder<MobEffect>> GOOD = List.of(MobEffects.NIGHT_VISION, MobEffects.REGENERATION, MobEffects.JUMP,
            MobEffects.FIRE_RESISTANCE, MobEffects.SATURATION, MobEffects.DAMAGE_RESISTANCE);

    /** Turn one plain mushroom stew you just got into a suspicious stew with a good effect. */
    static void blessStew(ServerPlayer p, ServerLevel l) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(Items.MUSHROOM_STEW)) continue;
            s.shrink(1);
            ItemStack sus = new ItemStack(Items.SUSPICIOUS_STEW);
            sus.set(DataComponents.SUSPICIOUS_STEW_EFFECTS, new SuspiciousStewEffects(List.of(
                    new SuspiciousStewEffects.Entry(GOOD.get(l.random.nextInt(GOOD.size())), 160))));
            Farm.give(p, sus);
            return;
        }
    }

    static void stewTick(Player p, PerkHit h) {
        if (!every(p, 20)) return;
        int max = Math.max(1, Math.min(99, (int) h.value()));
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if ((s.is(Items.MUSHROOM_STEW) || s.is(Items.SUSPICIOUS_STEW)) && s.getMaxStackSize() != max) s.set(DataComponents.MAX_STACK_SIZE, max);
        }
    }

    static void shearerTick(Player p, PerkHit h) {
        if (!every(p, 20)) return;
        double chance = (Farm.lerp(h, "regrow", 1.5, 5) - 1) * 2; // % per second on top of vanilla grazing
        for (Sheep s : near(p, Sheep.class, 16, Sheep::isSheared)) if (Farm.roll(p.level(), chance)) s.ate();
    }

    static void eggTick(Player p, PerkHit h) {
        if (!every(p, 20)) return;
        double r = Farm.lerp(h, "radius", 8, 24);
        int faster = (int) ((h.value() - 1) * 20);
        for (Chicken c : near(p, Chicken.class, r, c -> !c.isBaby())) c.eggTime = Math.max(1, c.eggTime - faster);
        for (ItemEntity it : near(p, ItemEntity.class, r, i -> i.getItem().is(Items.EGG) && !i.hasPickUpDelay())) {
            Farm.give(p, it.getItem().copy());
            it.discard();
        }
    }

    // ================================================================ critter bucket

    static final String CRITTER = "habits_critter";

    static boolean isCritterBucket(ItemStack s) {
        if (!s.is(Items.BUCKET)) return false;
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d != null && d.contains(CRITTER);
    }

    static boolean canScoop(Entity e, Player p, int tier) {
        if (!(e instanceof Mob m) || e instanceof Enemy || m.hasCustomName() || m.isLeashed() || m.isVehicle() || m.isPassenger()) return false;
        if (m instanceof TamableAnimal t && t.isTame() && !p.getUUID().equals(t.getOwnerUUID())) return false;
        if (m instanceof Rabbit) return true;
        if (tier >= 2 && (m instanceof Chicken || m instanceof Bat || m instanceof Frog || m instanceof Parrot)) return true;
        return tier >= 3 && (m instanceof Animal || m instanceof AmbientCreature || m instanceof WaterAnimal) && m.getBbHeight() < 1;
    }

    static boolean scoop(ServerPlayer p, Entity e, ItemStack bucket, int tier) {
        if (isCritterBucket(bucket) || !canScoop(e, p, tier)) return false;
        CompoundTag nbt = new CompoundTag();
        if (!e.save(nbt)) return false;
        ItemStack out = new ItemStack(Items.BUCKET);
        CompoundTag data = new CompoundTag();
        data.put(CRITTER, nbt);
        out.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        out.set(DataComponents.CUSTOM_NAME, Component.translatable("habits.farming.critter_bucket", e.getType().getDescription()).withStyle(s -> s.withItalic(false)));
        e.discard();
        bucket.shrink(1);
        Farm.give(p, out);
        p.level().playSound(null, p.blockPosition(), SoundEvents.BUCKET_FILL_FISH, SoundSource.PLAYERS, 1, 1);
        return true;
    }

    static boolean release(ServerPlayer p, ServerLevel l, ItemStack bucket, BlockPos at) {
        if (!spawnCritter(l, bucket, at)) return false;
        bucket.shrink(1);
        Farm.give(p, new ItemStack(Items.BUCKET));
        return true;
    }

    /** Put the bucket's animal back into the world at {@code at}; the bucket itself is left alone. */
    static boolean spawnCritter(ServerLevel l, ItemStack bucket, BlockPos at) {
        CompoundTag nbt = bucket.get(DataComponents.CUSTOM_DATA).copyTag().getCompound(CRITTER);
        Entity e = EntityType.loadEntityRecursive(nbt, l, en -> {
            en.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, en.getYRot(), en.getXRot());
            return en;
        });
        if (e == null || !l.addFreshEntity(e)) return false;
        l.playSound(null, at, SoundEvents.BUCKET_EMPTY_FISH, SoundSource.PLAYERS, 1, 1);
        return true;
    }

    /** A dispenser releases a critter bucket's animal in front of it (like a fish bucket) and keeps the empty bucket.
     * Without this the bucket's vanilla behavior treated it as an empty bucket and the animal was lost. If the spot is
     * blocked, the full bucket is spat out instead, so the animal is never lost. Every other bucket stays vanilla. */
    static void dispenser(FMLCommonSetupEvent ev) {
        ev.enqueueWork(() -> {
            DispenseItemBehavior empty = DispenserBlock.DISPENSER_REGISTRY.get(Items.BUCKET);
            DispenseItemBehavior critter = new DefaultDispenseItemBehavior() {
                @Override
                protected ItemStack execute(BlockSource src, ItemStack stack) {
                    BlockPos at = src.pos().relative(src.state().getValue(DispenserBlock.FACING));
                    if (src.level().getBlockState(at).getCollisionShape(src.level(), at).isEmpty() && spawnCritter(src.level(), stack, at))
                        return consumeWithRemainder(src, stack, new ItemStack(Items.BUCKET));
                    return super.execute(src, stack);
                }
            };
            DispenserBlock.registerBehavior(Items.BUCKET, (src, stack) -> (isCritterBucket(stack) ? critter : empty).dispense(src, stack));
        });
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (isCritterBucket(e.getItemStack())) { // never scoop or pour fluids with a full critter bucket
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.FAIL);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel l) || !Farm.ok(p)) return;
        if (isCritterBucket(e.getItemStack())) {
            e.setCanceled(true);
            e.setCancellationResult(release(p, l, e.getItemStack(), e.getPos().relative(e.getFace() == null ? net.minecraft.core.Direction.UP : e.getFace()))
                    ? InteractionResult.SUCCESS : InteractionResult.FAIL);
            return;
        }
        // a block click only tries the tongue (it finds nothing and vanilla carries on); whistling is for air clicks,
        // so sneak-clicking a chest or door with an empty hand still works
        PerkHit th;
        if (e.getHand() == InteractionHand.MAIN_HAND && p.isShiftKeyDown() && p.getMainHandItem().isEmpty()
                && (th = Farm.hit(p, "frog_tongue")) != null && tongue(p, th)) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    // ================================================================ sneak + empty hand: tongue, then whistle

    /** Sneak + right-click on air with an empty hand (the client's packet): tongue first, else whistle. */
    static boolean emptyUse(ServerPlayer p) {
        if (!Farm.ok(p) || !p.isShiftKeyDown() || !p.getMainHandItem().isEmpty()) return false;
        PerkHit h = Farm.hit(p, "frog_tongue");
        if (h != null && tongue(p, h)) return true;
        h = Farm.hit(p, "whistle");
        return h != null && whistle(p, h);
    }

    static boolean tongue(ServerPlayer p, PerkHit h) {
        double range = h.value();
        boolean mobs = h.level() >= (int) Farm.lerp(h, "mobs_level", 10, 10);
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        Entity best = null;
        double bestOff = 1.2;
        for (Entity e : p.level().getEntities(p, p.getBoundingBox().expandTowards(look.scale(range)).inflate(1.5))) {
            boolean ok = e instanceof ItemEntity || mobs && e instanceof Mob m && !(m instanceof Enemy && m.getBbWidth() > 1)
                    && m.getBbWidth() <= 1.01 && m.getBbHeight() <= 1.01 && !m.getType().is(Tags.EntityTypes.BOSSES) && !(m instanceof OwnableEntity o && o.getOwnerUUID() != null && !p.getUUID().equals(o.getOwnerUUID()));
            if (!ok) continue;
            Vec3 to = e.position().add(0, e.getBbHeight() / 2, 0).subtract(eye);
            double along = to.dot(look);
            if (along <= 0 || along > range) continue;
            double off = to.subtract(look.scale(along)).length();
            if (off < bestOff) { bestOff = off; best = e; }
        }
        if (best == null) return false;
        Vec3 dir = p.position().subtract(best.position());
        Vec3 v = dir.normalize().scale(Math.min(1.2, dir.length() * 0.2)).add(0, 0.25, 0);
        best.setDeltaMovement(v);
        best.hurtMarked = true;
        p.level().playSound(null, p.blockPosition(), SoundEvents.FROG_TONGUE, SoundSource.PLAYERS, 1, 1);
        p.swing(InteractionHand.MAIN_HAND, true);
        return true;
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent e) {
        if (e.isMounting() && !e.getLevel().isClientSide && e.getEntityMounting() instanceof Player p && e.getEntityBeingMounted() instanceof LivingEntity m)
            Farm.data(p).putUUID("habits_mount", m.getUUID());
    }

    static boolean whistle(ServerPlayer p, PerkHit h) {
        if (!Farm.data(p).hasUUID("habits_mount")) {
            p.displayClientMessage(Component.translatable("habits.farming.no_mount"), true);
            return true;
        }
        Entity m = p.serverLevel().getEntity(Farm.data(p).getUUID("habits_mount")); // loaded only, never loads a chunk
        if (!(m instanceof LivingEntity mount) || !mount.isAlive() || mount.distanceTo(p) > h.value()) {
            p.displayClientMessage(Component.translatable("habits.farming.too_far"), true);
            return true;
        }
        mount.teleportTo(p.getX() + 1, p.getY(), p.getZ());
        if (h.level() >= 10) mount.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1));
        p.level().playSound(null, p.blockPosition(), SoundEvents.HORSE_AMBIENT, SoundSource.NEUTRAL, 1, 1.2f);
        return true;
    }

    // ================================================================ herds

    static void nursery(Player p, PerkHit h) {
        if (!every(p, 20)) return;
        int extra = (int) ((h.value() - 1) * 20);
        for (AgeableMob m : near(p, AgeableMob.class, Farm.lerp(h, "radius", 8, 32), m -> m.getAge() < 0))
            m.setAge(Math.min(0, m.getAge() + extra));
    }

    static void piedPiper(Player p, PerkHit h) {
        if (p.level().isClientSide) return;
        var d = Farm.data(p);
        boolean sneak = p.isShiftKeyDown();
        // empty main hand only: sneak-bridging with blocks in hand never flips it
        if (sneak && !d.getBoolean("habits_piper_sneak") && p.getMainHandItem().isEmpty()) {
            boolean off = !d.getBoolean("habits_piper_off");
            d.putBoolean("habits_piper_off", off);
            p.displayClientMessage(Component.translatable(off ? "habits.farming.piper_off" : "habits.farming.piper_on"), true);
        }
        d.putBoolean("habits_piper_sneak", sneak);
        if (d.getBoolean("habits_piper_off") || !every(p, 10)) return;
        pipe(p, h.value());
    }

    static void pipe(Player p, double radius) {
        List<Animal> herd = near(p, Animal.class, radius, a -> !a.isLeashed() && !a.isVehicle() && !a.isPassenger()
                && !(a instanceof TamableAnimal t && t.isOrderedToSit()) && !isSwarmBee(a)); // Swarm bees are summons, not herd
        // A path search per animal per pulse was most of the server tick with a few pipers near a big herd (each search
        // can walk hundreds of nodes, and fails the same way every pulse). So: re-path only animals not already walking
        // to a player, at most PIPER_PATHS per pulse, starting at a rotating index so every animal gets its turn.
        // Any player, not just this one: pipers near one herd re-pathed each other's animals every pulse (12 bee
        // searches a tick with 20 pipers). An animal now sticks to the player it is walking to or standing by.
        int n = herd.size(), paths = 0, start = n == 0 ? 0 : (int) ((p.level().getGameTime() / 10) % n);
        for (int k = 0; k < n; k++) {
            Animal a = herd.get((start + k) % n);
            a.getLookControl().setLookAt(p);
            if (paths >= PIPER_PATHS || a.level().getNearestPlayer(a, 3) != null) continue;
            var nav = a.getNavigation();
            BlockPos goal = nav.isDone() ? null : nav.getTargetPos();
            if (goal != null && a.level().getNearestPlayer(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 3, false) != null) continue;
            nav.moveTo(p, 1.1);
            paths++;
        }
    }

    /** Path searches one Pied Piper pulse (every 10 ticks) may start. */
    static final int PIPER_PATHS = 6;

    static void pheromones(Player p, PerkHit h) {
        if (p.level().isClientSide) return;
        var d = Farm.data(p);
        boolean still = p.isCrouching() && Math.abs(p.getX() - p.xo) + Math.abs(p.getZ() - p.zo) < 0.01;
        int t = still ? d.getInt("habits_love") + 1 : 0;
        d.putInt("habits_love", t);
        if (t != 40 || !Farm.cooldown(p, "habits_love_cd", Farm.lerp(h, "cooldown", 300, 30))) return;
        for (Animal a : near(p, Animal.class, h.value(), a -> !a.isBaby() && a.getAge() == 0 && a.canFallInLove())) a.setInLove(p);
    }

    /** Marks pandas within 16 blocks for 2 seconds; the panda mixins read the mark (rate and drops). */
    static void sneezeTick(Player p, PerkHit h) {
        if (!every(p, 20)) return;
        for (Panda pd : near(p, Panda.class, 16, pd -> true)) FarmHooks.stampPanda(pd, h);
    }

    static void ramTick(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l)) return;
        var d = Farm.data(p);
        int run = p.isSprinting() ? d.getInt("habits_ram") + 1 : 0;
        d.putInt("habits_ram", run);
        if (run < 20 || l.getGameTime() < d.getLong("habits_ram_cd")) return;
        boolean pvp = l.getServer().isPvpAllowed();
        LivingEntity t = l.getNearestEntity(LivingEntity.class, net.minecraft.world.entity.ai.targeting.TargetingConditions.forNonCombat()
                        .selector(e -> e != p && !ownedBy(e, p) && (pvp || !(e instanceof Player)) && e != p.getVehicle()),
                p, p.getX(), p.getY(), p.getZ(), p.getBoundingBox().inflate(0.6));
        if (t == null) return;
        t.knockback(h.value(), p.getX() - t.getX(), p.getZ() - t.getZ());
        t.hurtMarked = true;
        double dmg = Farm.lerp(h, "damage", 0, 4);
        if (dmg > 0) t.hurt(l.damageSources().playerAttack(p), (float) dmg);
        l.playSound(null, p.blockPosition(), SoundEvents.GOAT_RAM_IMPACT, SoundSource.PLAYERS, 1, 1);
        d.putLong("habits_ram_cd", l.getGameTime() + (long) (Farm.lerp(h, "cooldown", 3, 1) * 20));
        d.putInt("habits_ram", 0);
    }

    // ================================================================ pets

    static void spitTick(Player p, PerkHit h) {
        if (!every(p, 30)) return;
        List<Monster> foes = near(p, Monster.class, 16, m -> m.getTarget() == p);
        if (foes.isEmpty()) return;
        int n = (int) h.value();
        for (Llama ll : near(p, Llama.class, 16, ll -> ll.isTamed() && ownedBy(ll, p))) {
            if (n-- <= 0) break;
            ll.performRangedAttack(foes.get(p.getRandom().nextInt(foes.size())), 1f);
        }
    }

    static void groomTick(Player p, PerkHit h) {
        if (!every(p, 20)) return;
        for (Wolf w : near(p, Wolf.class, 32, w -> w.isTame() && ownedBy(w, p) && w.hasArmor())) {
            ItemStack armor = w.getBodyArmorItem();
            if (armor.isDamaged()) armor.setDamageValue(Math.max(0, armor.getDamageValue() - Farm.round(p.level(), h.value() / 30)));
        }
    }

    /** Is this mob scared of the player right now (cat person in range)? */
    static boolean scares(Player p, Entity mob) {
        PerkHit h = Farm.hit(p, "cat_person");
        return h != null && p.distanceTo(mob) <= h.value();
    }

    static void lookoutTick(Player p, PerkHit h) {
        if (p instanceof ServerPlayer sp && every(p, 10) && (isParrot(p.getShoulderEntityLeft()) || isParrot(p.getShoulderEntityRight()))) lookout(sp, h);
    }

    /** Squawk for mobs behind you; at the outline level also outline them. Returns the outlined ids. */
    static List<Integer> lookout(ServerPlayer sp, PerkHit h) {
        Player p = sp;
        Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
        boolean outline = h.level() >= (int) Farm.lerp(h, "outline_level", 10, 10);
        List<Integer> ids = new ArrayList<>();
        boolean warn = false;
        for (Mob m : near(p, Mob.class, h.value(), m -> m instanceof Enemy)) { // vanilla's hostile marker: slimes, phantoms, ghasts too
            Vec3 to = m.position().subtract(p.position()).multiply(1, 0, 1);
            if (to.dot(look) >= 0) continue;
            warn = true;
            if (outline && ids.size() < 32) ids.add(m.getId());
        }
        // outlines only this player sees (client MinecraftMixin), refreshed every half second
        if (!ids.isEmpty()) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new FarmNet.Lookout(ids));
        if (warn && Farm.cooldown(p, "habits_squawk", 3)) sp.playNotifySound(SoundEvents.PARROT_AMBIENT, SoundSource.NEUTRAL, 1, 1.4f);
        return ids;
    }

    static boolean isParrot(CompoundTag shoulder) {
        return "minecraft:parrot".equals(shoulder.getString("id"));
    }

    static void allayTick(Player p, PerkHit h) {
        if (!every(p, 40)) return;
        ResourceLocation id = Habits.id("allay_pal");
        for (Allay a : near(p, Allay.class, 32, a -> a.getBrain().getMemory(MemoryModuleType.LIKED_PLAYER).filter(p.getUUID()::equals).isPresent())) {
            for (var attr : List.of(Attributes.FLYING_SPEED, Attributes.MOVEMENT_SPEED)) {
                AttributeInstance inst = a.getAttribute(attr);
                if (inst != null) inst.addOrUpdateTransientModifier(new AttributeModifier(id, h.value() / 100, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
            // pathing reach for the longer fetch (NearestItemSensorMixin finds the item, AllayAiMixin lets it walk there)
            AttributeInstance follow = a.getAttribute(Attributes.FOLLOW_RANGE);
            if (follow != null) follow.addOrUpdateTransientModifier(new AttributeModifier(id, Farm.lerp(h, "range", 1.5, 3) - 1, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    // ================================================================ damage hooks

    @SubscribeEvent
    public static void onIncoming(LivingIncomingDamageEvent e) {
        LivingEntity victim = e.getEntity();
        if (!(victim.level() instanceof ServerLevel l)) return;
        Entity src = e.getSource().getEntity(), direct = e.getSource().getDirectEntity();
        PerkHit h;
        // pack alpha: owned wolves hit harder per owned wolf nearby (max 5)
        if (src instanceof Wolf w && w.isTame() && w.getOwner() instanceof Player o && (h = Farm.hit(o, "pack_alpha")) != null) {
            int n = Math.min(5, l.getEntitiesOfClass(Wolf.class, o.getBoundingBox().inflate(16), x -> x.isTame() && ownedBy(x, o)).size());
            e.setAmount(e.getAmount() * (float) (1 + h.value() / 100 * n));
        }
        // spit guard: stronger spit from your llamas
        if (direct instanceof LlamaSpit spit && spit.getOwner() instanceof Llama ll && ll.getOwner() instanceof Player o && (h = Farm.hit(o, "spit_guard")) != null) {
            e.setAmount((float) Farm.lerp(h, "damage", 1, 6));
            if (h.level() >= 10) victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
        }
        // armor groomer thorns
        if (victim instanceof Wolf w && w.isTame() && w.hasArmor() && src instanceof LivingEntity a && w.getOwner() instanceof Player o
                && (h = Farm.hit(o, "armor_groomer")) != null) {
            double pct = Farm.lerp(h, "thorns", 0, 30);
            if (pct > 0 && a != o) a.hurt(l.damageSources().thorns(w), (float) (e.getAmount() * pct / 100));
        }
    }

    @SubscribeEvent
    public static void onDamaged(LivingDamageEvent.Post e) {
        LivingEntity victim = e.getEntity();
        if (!(victim.level() instanceof ServerLevel l)) return;
        Entity src = e.getSource().getEntity(), direct = e.getSource().getDirectEntity();
        if (direct instanceof Bee b && b.getPersistentData().hasUUID(OWNER) && l.getPlayerByUUID(b.getPersistentData().getUUID(OWNER)) instanceof Player o) {
            PerkHit h = Farm.hit(o, "swarm");
            if (h != null && h.level() >= 10) victim.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 1));
        }
        if (!(victim instanceof ServerPlayer p) || !Farm.ok(p)) return;
        LivingEntity attacker = src instanceof LivingEntity le && le != p ? le : null;
        PerkHit h;
        if (attacker != null && !isSwarmBee(attacker)) {
            for (Bee b : SWARM.getOrDefault(p.getUUID(), List.of())) {
                b.setPersistentAngerTarget(attacker.getUUID());
                b.setRemainingPersistentAngerTime(400);
                b.setTarget(attacker);
            }
            if (Farm.hit(p, "hoglin_rancher") != null)
                for (Hoglin hg : near(p, Hoglin.class, 32, x -> isOwnedHoglin(x, p))) hg.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, attacker);
        }
        if (attacker != null && (h = Farm.hit(p, "puffer_up")) != null && Farm.roll(l, h.value())) {
            attacker.addEffect(new MobEffectInstance(MobEffects.POISON, (int) (Farm.lerp(h, "seconds", 3, 6) * 20), (int) Farm.lerp(h, "amp", 0, 1)));
            if (h.level() >= 10) attacker.knockback(1.0, p.getX() - attacker.getX(), p.getZ() - attacker.getZ());
            l.playSound(null, p.blockPosition(), SoundEvents.PUFFER_FISH_BLOW_UP, SoundSource.PLAYERS, 1, 1);
        }
        if ((h = Farm.hit(p, "play_dead")) != null && p.isInWater() && p.getHealth() <= h.value() * 2
                && Farm.cooldown(p, "habits_dead_cd", Farm.lerp(h, "cooldown", 300, 60))) {
            for (Mob m : near(p, Mob.class, 16, m -> m.getTarget() == p)) {
                m.setTarget(null);
                m.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            }
            Farm.data(p).putLong("habits_dead_until", l.getGameTime() + 60);
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, (int) (Farm.lerp(h, "seconds", 3, 10) * 20), (int) Farm.lerp(h, "amp", 0, 2)));
        }
    }

    @SubscribeEvent
    public static void onTarget(LivingChangeTargetEvent e) {
        if (!(e.getNewAboutToBeSetTarget() instanceof Player p) || p.level().isClientSide) return;
        LivingEntity mob = e.getEntity();
        boolean cancel = false;
        if (mob instanceof Bee) cancel = isSwarmBee(mob) && p.getUUID().equals(mob.getPersistentData().getUUID(OWNER)) || Farm.hit(p, "beekeeper") != null;
        else if (mob instanceof Hoglin) cancel = Farm.hit(p, "hoglin_rancher") != null;
        if (!cancel && p.level().getGameTime() < Farm.data(p).getLong("habits_dead_until")) cancel = true;
        if (cancel) e.setCanceled(true);
    }

    // ================================================================ bees, hoglins, foxes, creepers

    static final Map<UUID, List<Bee>> SWARM = new HashMap<>();

    static boolean isSwarmBee(Entity e) {
        return e instanceof Bee && e.getPersistentData().getBoolean("habits_swarm");
    }

    /** Keep {@code value} bees with you: replace stung or lost ones, call stragglers back. */
    static void swarmTick(Player p, PerkHit h) {
        if (p.level() instanceof ServerLevel l && every(p, 20)) swarm(l, p, h);
    }

    static void swarm(ServerLevel l, Player p, PerkHit h) {
        List<Bee> bees = SWARM.computeIfAbsent(p.getUUID(), k -> new ArrayList<>());
        bees.removeIf(b -> {
            boolean gone = !b.isAlive() || b.isRemoved() || b.level() != l || b.distanceToSqr(p) > 32 * 32 || b.hasStung();
            if (gone && !b.isRemoved()) b.discard();
            return gone;
        });
        int want = (int) h.value();
        while (bees.size() > want) bees.remove(bees.size() - 1).discard();
        for (Bee b : bees) {
            b.setStayOutOfHiveCountdown(400);
            if (b.getTarget() == null && b.distanceToSqr(p) > 36) b.getNavigation().moveTo(p.getX(), p.getY() + 1.5, p.getZ(), 1.2);
        }
        while (bees.size() < want) {
            Bee b = EntityType.BEE.create(l);
            if (b == null) break;
            b.moveTo(p.getX() + l.random.nextGaussian(), p.getY() + 1.5, p.getZ() + l.random.nextGaussian(), 0, 0);
            b.getPersistentData().putBoolean("habits_swarm", true);
            b.getPersistentData().putUUID(OWNER, p.getUUID());
            b.setStayOutOfHiveCountdown(400);
            l.addFreshEntity(b);
            bees.add(b);
        }
    }

    static void dismissSwarm(UUID owner) {
        List<Bee> bees = SWARM.remove(owner);
        if (bees != null) bees.forEach(Entity::discard);
    }

    /** Calms bees angry at you. The honey part is BeehiveBlockEntityMixin (on each bee return). */
    static void beekeeperTick(Player p, PerkHit h) {
        if (!every(p, 200)) return;
        int r = (int) h.value();
        for (Bee b : near(p, Bee.class, r, b -> p.getUUID().equals(b.getPersistentAngerTarget()))) b.stopBeingAngry();
    }

    static boolean isOwnedHoglin(Hoglin h, Player p) {
        return h.getPersistentData().hasUUID(OWNER) && p.getUUID().equals(h.getPersistentData().getUUID(OWNER));
    }

    static void hoglinTick(Player p, PerkHit h) {
        if (!every(p, 20)) return;
        List<Hoglin> mine = near(p, Hoglin.class, 48, x -> isOwnedHoglin(x, p));
        mine.sort(Comparator.comparingDouble(x -> x.distanceToSqr(p)));
        ResourceLocation id = Habits.id("hoglin_rancher");
        double bonus = Farm.lerp(h, "health", 0, 50) / 100;
        for (int i = 0; i < Math.min(mine.size(), (int) h.value()); i++) {
            Hoglin hg = mine.get(i);
            AttributeInstance hp = hg.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null && bonus > 0) hp.addOrUpdateTransientModifier(new AttributeModifier(id, bonus, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            if (hg.distanceToSqr(p) > 36 && hg.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).isEmpty())
                hg.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(p, 1.0f, 3));
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide) return;
        Entity ent = e.getEntity();
        if (isSwarmBee(ent) && e.loadedFromDisk()) { e.setCanceled(true); return; } // swarms never persist
        if (ent instanceof Creeper c)
            c.goalSelector.addGoal(3, new AvoidEntityGoal<>(c, Player.class, x -> x instanceof Player pl && scares(pl, c), 16, 1.0, 1.2, x -> true));
        if (ent instanceof net.minecraft.world.entity.animal.Fox f) f.goalSelector.addGoal(2, new FetchGoal(f));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        dismissSwarm(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStopped(ServerStoppedEvent e) {
        SWARM.clear();
    }

    // ================================================================ sniff, dolphins, ghost rider

    static void sniffTick(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l)) return;
        var d = Farm.data(p);
        boolean still = p.isCrouching() && Math.abs(p.getX() - p.xo) + Math.abs(p.getZ() - p.zo) < 0.01
                && Crops.soil(l.getBlockState(p.blockPosition().below())) || p.isCrouching() && l.getBlockState(p.blockPosition().below()).is(Blocks.MUD);
        int t = still ? d.getInt("habits_sniff") + 1 : 0;
        d.putInt("habits_sniff", t);
        if (t != 40 || !Farm.cooldown(p, "habits_sniff_cd", h.value())) return;
        BlockPos at = p.blockPosition();
        List<ItemStack> loot = new ArrayList<>(Farm.loot(l, BuiltInLootTables.SNIFFER_DIGGING, Vec3.atCenterOf(at), p));
        if (Farm.roll(l, Farm.lerp(h, "sherd", 2, 20)))
            BuiltInRegistries.ITEM.getTag(ItemTags.DECORATED_POT_SHERDS).flatMap(tag -> tag.getRandomElement(l.random)).ifPresent(i -> loot.add(new ItemStack(i)));
        for (ItemStack it : loot) Farm.drop(l, Vec3.atCenterOf(at), it);
        l.playSound(null, at, SoundEvents.SNIFFER_DIGGING, SoundSource.PLAYERS, 1, 1);
        l.levelEvent(2001, at.below(), net.minecraft.world.level.block.Block.getId(l.getBlockState(at.below())));
    }

    static void podTick(Player p, PerkHit h) {
        if (!p.isShiftKeyDown() || !p.isInWater()) return;
        List<Dolphin> ds = near(p, Dolphin.class, 3, x -> true);
        if (ds.isEmpty()) return;
        Dolphin d = ds.get(0);
        p.setDeltaMovement(Vec3.directionFromRotation(d.getXRot(), d.getYRot()).scale(h.value() / 20));
        if (!p.level().isClientSide && h.level() >= 10) p.setAirSupply(p.getMaxAirSupply());
    }

    /** Both sides: the rider's client moves the mount, so the client must hold it up too. */
    static void ghostRide(Player p, PerkHit h) {
        Entity v = p.getVehicle();
        if (v == null || v.getControllingPassenger() != p) return;
        int tier = Farm.tier(h);
        Vec3 m = v.getDeltaMovement();
        // tier 1 only sinks slowly; tiers 2-3 stand on the fluid through LivingEntityMixin (canStandOnFluid)
        if (tier <= 1 && v.isInWater() && m.y < -0.02) v.setDeltaMovement(m.x, -0.02, m.z);
        // the lava toll (EntityMixin stops the burning)
        if (tier >= 3 && v.isInLava() && !v.level().isClientSide && v.tickCount % 40 == 0) v.hurt(v.level().damageSources().hotFloor(), 1);
    }

    // ================================================================ pet lover

    static final String PETS = "habits_pets";

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        LivingEntity pet = e.getEntity();
        if (!(pet instanceof OwnableEntity o) || !(o.getOwner() instanceof ServerPlayer owner) || !(pet.level() instanceof ServerLevel l)) return;
        if (pet instanceof AbstractHorse ah && !ah.isTamed() || pet instanceof TamableAnimal ta && !ta.isTame()) return;
        PerkHit h = Farm.hit(owner, "pet_lover");
        if (h == null) return;
        long day = l.getDayTime() / 24000;
        CompoundTag pd = pet.getPersistentData();
        int used = pd.getLong("habits_revive_day") == day ? pd.getInt("habits_revives") : 0;
        if (h.level() < 10 && used >= (int) Farm.lerp(h, "per_day", 1, 99)) return;
        pd.putLong("habits_revive_day", day);
        pd.putInt("habits_revives", used + 1);
        CompoundTag nbt = pet.saveWithoutId(new CompoundTag());
        nbt.putString("id", EntityType.getKey(pet.getType()).toString());
        // the dying body drops its gear and inventory; the copy comes back without it so nothing duplicates
        for (String k : new String[]{"ArmorItems", "HandItems", "body_armor_item", "SaddleItem", "Items", "ArmorItem", "DecorItem", "ChestedHorse",
                "Fire", "DeathTime", "HurtTime", "active_effects", "Passengers", "leash"}) nbt.remove(k);
        CompoundTag entry = new CompoundTag();
        entry.put("e", nbt);
        entry.putLong("due", l.getGameTime() + (long) (h.value() * 20));
        ListTag list = Farm.data(owner).getList(PETS, Tag.TAG_COMPOUND);
        list.add(entry);
        Farm.data(owner).put(PETS, list);
    }

    /** Due pets come back next to you (also after a relog: the list lives on the player). */
    static void petTick(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || !every(p, 20)) return;
        ListTag list = Farm.data(p).getList(PETS, Tag.TAG_COMPOUND);
        if (list.isEmpty()) return;
        for (int i = list.size() - 1; i >= 0; i--) {
            CompoundTag entry = list.getCompound(i);
            if (l.getGameTime() < entry.getLong("due")) continue;
            list.remove(i);
            Entity e = EntityType.loadEntityRecursive(entry.getCompound("e"), l, en -> {
                en.moveTo(p.getX(), p.getY(), p.getZ(), en.getYRot(), 0);
                return en;
            });
            if (e instanceof LivingEntity le) le.setHealth(le.getMaxHealth());
            if (e != null && !l.addFreshEntity(e)) {
                e.setUUID(UUID.randomUUID()); // the old body still holds the id; come back as a new copy
                l.addFreshEntity(e);
            }
        }
        Farm.data(p).put(PETS, list);
    }

    // ================================================================ fetch goal

    /** A trusted fox runs to items near its player, carries them back and hands them over. */
    static final class FetchGoal extends net.minecraft.world.entity.ai.goal.Goal {
        static final java.lang.reflect.Field TRUSTED_0 = field("DATA_TRUSTED_ID_0"), TRUSTED_1 = field("DATA_TRUSTED_ID_1");
        final net.minecraft.world.entity.animal.Fox fox;
        @Nullable Player owner;
        @Nullable ItemEntity item;
        int recheck;

        FetchGoal(net.minecraft.world.entity.animal.Fox fox) {
            this.fox = fox;
            setFlags(java.util.EnumSet.of(Flag.MOVE));
        }

        static java.lang.reflect.Field field(String name) {
            try {
                var f = net.minecraft.world.entity.animal.Fox.class.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (ReflectiveOperationException e) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        boolean trusts(UUID id) {
            try {
                for (var f : new java.lang.reflect.Field[]{TRUSTED_0, TRUSTED_1})
                    if (f != null && fox.getEntityData().get((net.minecraft.network.syncher.EntityDataAccessor<java.util.Optional<UUID>>) f.get(null)).filter(id::equals).isPresent())
                        return true;
            } catch (ReflectiveOperationException ignored) {
            }
            return false;
        }

        @Override
        public boolean canUse() {
            if (--recheck > 0 || fox.isSleeping() || fox.isSitting()) return false;
            recheck = 20;
            owner = null;
            for (Player p : fox.level().getEntitiesOfClass(Player.class, fox.getBoundingBox().inflate(24))) {
                PerkHit h = Farm.hit(p, "fetch");
                if (h == null || !trusts(p.getUUID())) continue;
                // only the N closest trusted foxes of this player help
                int max = (int) Farm.lerp(h, "foxes", 1, 99);
                long closer = fox.level().getEntitiesOfClass(net.minecraft.world.entity.animal.Fox.class, p.getBoundingBox().inflate(24),
                        o -> o != fox && o.distanceToSqr(p) < fox.distanceToSqr(p)).size();
                if (closer >= max) continue;
                owner = p;
                if (!fox.getMainHandItem().isEmpty()) return true;
                List<ItemEntity> items = p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(h.value()), i -> !i.hasPickUpDelay() && i.isAlive());
                item = items.isEmpty() ? null : items.get(0);
                return item != null;
            }
            return false;
        }

        @Override
        public boolean canContinueToUse() {
            return owner != null && owner.isAlive() && (fox.getMainHandItem().isEmpty() ? item != null && item.isAlive() : true) && fox.distanceToSqr(owner) < 48 * 48;
        }

        @Override
        public void tick() {
            if (owner == null) return;
            if (fox.getMainHandItem().isEmpty()) {
                if (item == null || !item.isAlive()) return;
                if (fox.tickCount % 10 == 0) fox.getNavigation().moveTo(item, 1.2);
                if (fox.distanceToSqr(item) < 2.5) {
                    PerkHit h = Farm.hit(owner, "fetch");
                    ItemStack s = item.getItem();
                    ItemStack take = h != null && h.level() >= 10 ? s.copy() : s.copyWithCount(1);
                    s.shrink(take.getCount());
                    if (s.isEmpty()) item.discard();
                    fox.setItemSlot(EquipmentSlot.MAINHAND, take);
                    item = null;
                }
            } else {
                if (fox.tickCount % 10 == 0) fox.getNavigation().moveTo(owner, 1.2);
                if (fox.distanceToSqr(owner) < 4) {
                    Farm.give(owner, fox.getMainHandItem().copy());
                    fox.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                    owner = null;
                }
            }
        }

        @Override
        public void stop() {
            item = null;
            fox.getNavigation().stop();
        }
    }
}
