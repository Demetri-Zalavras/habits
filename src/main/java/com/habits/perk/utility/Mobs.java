package com.habits.perk.utility;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.IdMatch;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityStruckByLightningEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Perks about mobs, pets, golems, lightning, and what you wear. */
final class Mobs {
    private Mobs() {}

    static final ResourceLocation ANVIL_RAIN = U.mech("anvil_rain", "phantom anvil damage");
    static final ResourceLocation TRIM = U.mech("trim_fashion", "trim bonus strength");
    static final ResourceLocation ROD_FAN = U.mech("lightning_rod_fan", "seconds of cooldown");
    static final ResourceLocation STICKY = U.mech("sticky_name", "% less damage to your named pets");
    static final ResourceLocation NAMETAG = U.mech("nametag_magic", "% more damage to Dinnerbone and Grumm");
    static final ResourceLocation FIREWORK = U.mech("firework_show", "scare radius in blocks");
    static final ResourceLocation STAND = U.mech("armor_stand_buddy", "% chance mobs go for your armor stand");
    static final ResourceLocation PETS = U.mech("pet_whisperer", "pet recall range in blocks");
    static final ResourceLocation GOLEM = U.mech("golem_maker", "% more golem health");
    static final ResourceLocation DYE = U.mech("dye_hard", "outfit effect level");
    static final ResourceLocation WEAVER = U.mech("weaver", "banner aura radius in blocks");
    static final ResourceLocation WALL = U.mech("wall_kisser", "shockwave radius in blocks");

    static final String GOLEM_OWNER = "habits_golem_owner";

    static void init() {}

    // ------------------------------------------------------------------ anvil rain: sneak-jump looking down

    private static final Map<UUID, Long> ANVIL_CD = U.playerMap();

    @SubscribeEvent
    static void onJump(LivingEvent.LivingJumpEvent e) {
        if (!(e.getEntity() instanceof Player p) || !p.isShiftKeyDown() || p.getXRot() < 50) return;
        HabitsApi.PerkHit h = U.hit(p, ANVIL_RAIN);
        if (h == null) return;
        long now = p.level().getGameTime();
        if (now < ANVIL_CD.getOrDefault(p.getUUID(), 0L)) return;
        LivingEntity target = lookTarget(p, 24);
        if (target == null) return;
        ANVIL_CD.put(p.getUUID(), now + (long) (U.param(h, "cooldown", 30, 6) * 20));
        dropAnvil(p, target, h);
    }

    static void dropAnvil(Player p, LivingEntity target, HabitsApi.PerkHit h) {
        target.hurt(p.damageSources().anvil(p), (float) h.value());
        if (h.level() >= U.param(h, "stun_from", 10, 10)) target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 9));
        if (p.level() instanceof ServerLevel sl) {
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.ANVIL.defaultBlockState()),
                    target.getX(), target.getY() + target.getBbHeight(), target.getZ(), 16, 0.3, 0.3, 0.3, 0.1);
            sl.playSound(null, target.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.8f, 1);
        }
    }

    @Nullable
    static LivingEntity lookTarget(Player p, double range) {
        Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(range));
        EntityHitResult r = ProjectileUtil.getEntityHitResult(p, eye, end, p.getBoundingBox().expandTowards(p.getLookAngle().scale(range)).inflate(1),
                en -> en instanceof LivingEntity && en != p && en.isAlive() && !(en instanceof Player pl && pl.isSpectator()), range * range);
        return r != null && r.getEntity() instanceof LivingEntity le ? le : null;
    }

    // ------------------------------------------------------------------ lightning: rod strikes (trigger) and called strikes

    private static final Map<UUID, Long> ROD_CD = U.playerMap();
    static final String ROD_TAG = "habits_called";

    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.loadedFromDisk()) return;
        Entity en = e.getEntity();
        if (en instanceof LightningBolt bolt && !bolt.getTags().contains(ROD_TAG)
                && e.getLevel().getBlockState(bolt.blockPosition().below()).is(Blocks.LIGHTNING_ROD)) {
            for (Player p : e.getLevel().getEntitiesOfClass(Player.class, bolt.getBoundingBox().inflate(32))) U.fire(p, "lightning_rod_strike", bolt.position());
        } else if (en instanceof FireworkRocketEntity rocket && rocket.getOwner() instanceof Player p && !p.isFallFlying()) {
            HabitsApi.PerkHit h = U.hit(p, FIREWORK);
            if (h != null) scare(p, rocket.position(), h);
        } else if (en instanceof IronGolem || en instanceof SnowGolem) {
            if (en instanceof IronGolem g && !g.isPlayerCreated()) return;
            golemBuilt((Mob) en);
        }
    }

    @SubscribeEvent
    static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        Player p = e.getEntity();
        HabitsApi.PerkHit h = U.hit(p, ROD_FAN);
        if (h == null || !p.level().isRainingAt(p.blockPosition())) return;
        var access = p.registryAccess();
        IdMatch items = IdMatch.parse(U.str(h, "items", "minecraft:copper_ingot,minecraft:lightning_rod,minecraft:raw_copper,minecraft:copper_block"));
        if (!items.test(BuiltInRegistries.ITEM.getKey(e.getItemStack().getItem()), Registries.ITEM, access)) return;
        long now = p.level().getGameTime();
        if (now < ROD_CD.getOrDefault(p.getUUID(), 0L)) return;
        LivingEntity target = lookTarget(p, 32);
        if (target == null) return;
        ROD_CD.put(p.getUUID(), now + (long) (h.value() * 20));
        callLightning(p, target, (int) U.param(h, "strikes", 1, 3));
        e.setCanceled(true);
    }

    static int callLightning(Player p, LivingEntity first, int strikes) {
        LivingEntity t = first;
        int n = 0;
        java.util.Set<LivingEntity> hit = new java.util.HashSet<>();
        while (t != null && n < strikes) {
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(p.level());
            if (bolt == null) break;
            bolt.moveTo(t.position());
            bolt.addTag(ROD_TAG);
            bolt.addTag("habits_by_" + p.getUUID());
            if (p instanceof net.minecraft.server.level.ServerPlayer sp) bolt.setCause(sp);
            p.level().addFreshEntity(bolt);
            hit.add(t);
            n++;
            Vec3 at = t.position();
            t = null;
            double best = 64;
            for (LivingEntity c : p.level().getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(at, at).inflate(8),
                    c -> c.isAlive() && !(c instanceof Player) && !hit.contains(c))) {
                if (c.distanceToSqr(at) < best) {
                    best = c.distanceToSqr(at);
                    t = c;
                }
            }
        }
        return n;
    }

    /** Your called lightning never touches you. */
    @SubscribeEvent
    static void onStruck(EntityStruckByLightningEvent e) {
        if (e.getEntity() instanceof Player p && e.getLightning().getTags().contains("habits_by_" + p.getUUID())) e.setCanceled(true);
    }

    // ------------------------------------------------------------------ damage hooks

    @SubscribeEvent
    static void onDamage(LivingIncomingDamageEvent e) {
        LivingEntity v = e.getEntity();
        if (v.level().isClientSide) return;
        var src = e.getSource();
        if (v instanceof Player p) {
            if (src.is(DamageTypes.FLY_INTO_WALL)) {
                HabitsApi.PerkHit h = U.hit(p, WALL);
                if (h != null) {
                    shockwave(p, h);
                    e.setAmount(e.getAmount() * (float) (1 - Math.min(100, U.param(h, "resist", 20, 100)) / 100));
                    if (e.getAmount() <= 0) e.setCanceled(true);
                }
            }
            if (src.is(DamageTypes.LIGHTNING_BOLT)) {
                double pct = trimCount(p, "copper") * U.v(p, TRIM) * 1.25;
                if (pct > 0) e.setAmount(e.getAmount() * (float) Math.max(0, 1 - pct / 100));
            }
            if (Odd.immune(p, src)) e.setCanceled(true);
            return;
        }
        if (v instanceof TamableAnimal pet && pet.hasCustomName() && pet.getOwner() instanceof Player owner) {
            double pct = U.v(owner, STICKY);
            if (pct > 0) e.setAmount(e.getAmount() * (float) (1 - pct / 100));
        }
        if (src.getEntity() instanceof Player p && v.hasCustomName()) {
            String n = v.getCustomName().getString();
            if (n.equals("Dinnerbone") || n.equals("Grumm")) {
                double pct = U.v(p, NAMETAG);
                if (pct > 0) e.setAmount(e.getAmount() * (float) (1 + pct / 100));
            }
        }
        if (v instanceof SnowGolem g && src.is(DamageTypes.ON_FIRE) && !g.isOnFire() && g.getPersistentData().hasUUID(GOLEM_OWNER)) {
            e.setCanceled(true); // golems you built never melt in warm biomes
        }
    }

    static int shockwave(Player p, HabitsApi.PerkHit h) {
        double str = U.param(h, "knockback", 0.6, 2.5);
        float dmg = (float) U.param(h, "damage", 0, 6);
        int n = 0;
        for (LivingEntity m : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(h.value()),
                m -> m != p && m.isAlive() && !(m instanceof Player pl && pl.isSpectator()))) {
            Vec3 d = m.position().subtract(p.position());
            m.knockback(str, -d.x, -d.z);
            if (dmg > 0) m.hurt(p.damageSources().playerAttack(p), dmg);
            n++;
        }
        if (p.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY() + 1, p.getZ(), 3, 0.5, 0.5, 0.5, 0);
            sl.playSound(null, p.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.5f, 1.4f);
        }
        return n;
    }

    // ------------------------------------------------------------------ target changes: decoys, scares, piglins

    /** Mob UUID -> game time it stops being scared. Pruned on use; cleared on server stop. */
    static final Map<UUID, Long> SCARED = new ConcurrentHashMap<>();

    @SubscribeEvent
    static void onTarget(LivingChangeTargetEvent e) {
        LivingEntity mob = e.getEntity();
        if (!(e.getNewAboutToBeSetTarget() instanceof Player p) || mob.level().isClientSide) return;
        Long until = SCARED.get(mob.getUUID());
        if (until != null) {
            if (mob.level().getGameTime() < until) { e.setCanceled(true); return; }
            SCARED.remove(mob.getUUID());
        }
        if (Odd.hidden(p, mob)) { e.setCanceled(true); return; }
        if (mob instanceof AbstractPiglin && U.roll(p, trimCount(p, "gold") * U.v(p, TRIM) * 1.25)) { e.setCanceled(true); return; }
        HabitsApi.PerkHit h = U.hit(p, STAND);
        if (h != null && mob instanceof Enemy && U.roll(p, h.value())) {
            ArmorStand s = dressedStand(p, 16);
            if (s != null) e.setNewAboutToBeSetTarget(s);
        }
    }

    @Nullable
    static ArmorStand dressedStand(Player p, double r) {
        for (ArmorStand s : p.level().getEntitiesOfClass(ArmorStand.class, p.getBoundingBox().inflate(r))) {
            if (s.isAlive() && !s.isMarker() && s.getArmorValue() > 0) return s;
        }
        return null;
    }

    static int scare(Player p, Vec3 at, HabitsApi.PerkHit h) {
        long until = p.level().getGameTime() + (long) (U.param(h, "seconds", 3, 10) * 20);
        int n = 0;
        for (PathfinderMob m : p.level().getEntitiesOfClass(PathfinderMob.class, new net.minecraft.world.phys.AABB(at, at).inflate(h.value()), m -> m instanceof Enemy && m.isAlive())) {
            if (n++ >= 32) break;
            SCARED.put(m.getUUID(), until);
            m.setTarget(null);
            Vec3 away = m.position().subtract(at).normalize().scale(h.value() + 6).add(m.position());
            m.getNavigation().moveTo(away.x, away.y, away.z, 1.4);
        }
        if (SCARED.size() > 512) SCARED.entrySet().removeIf(en -> en.getValue() < p.level().getGameTime());
        return n;
    }

    // ------------------------------------------------------------------ name tags, shears

    @SubscribeEvent
    static void onInteract(PlayerInteractEvent.EntityInteract e) {
        Player p = e.getEntity();
        if (!U.ok(p) || e.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        ItemStack s = e.getItemStack();
        Entity t = e.getTarget();
        if (s.is(Items.NAME_TAG) && s.has(DataComponents.CUSTOM_NAME)) {
            String n = s.getHoverName().getString();
            if (Set.of("Dinnerbone", "Grumm", "jeb_", "Toast").contains(n)) U.fire(p, "easter_egg_name", t.position());
            if (t instanceof TamableAnimal pet && pet.isOwnedBy(p)) U.fire(p, "name_pet", t.position());
        } else if (s.is(Items.SHEARS) && t instanceof Sheep sheep && sheep.readyForShearing() && sheep.hasCustomName()
                && sheep.getCustomName().getString().equals("jeb_")) {
            HabitsApi.PerkHit h = U.hit(p, NAMETAG);
            if (h != null) {
                int n = (int) U.param(h, "wool", 1, 4);
                for (int i = 0; i < n; i++) {
                    DyeColor c = DyeColor.byId(p.getRandom().nextInt(16));
                    BuiltInRegistries.ITEM.getOptional(ResourceLocation.withDefaultNamespace(c.getName() + "_wool"))
                            .ifPresent(w -> sheep.spawnAtLocation(new ItemStack(w)));
                }
            }
        }
    }

    // ------------------------------------------------------------------ pets: recall and daily revive

    @SubscribeEvent
    static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof TamableAnimal pet) || !pet.hasCustomName() || !(pet.getOwner() instanceof Player owner)) return;
        HabitsApi.PerkHit h = U.hit(owner, PETS);
        if (h == null || h.level() < U.param(h, "revive_from", 10, 10)) return;
        CompoundTag data = pet.saveWithoutId(new CompoundTag());
        data.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).toString());
        // its gear already dropped where it died: never revive it with a second copy
        for (String k : new String[]{"ArmorItems", "HandItems", "body_armor_item", "Inventory", "Items", "SaddleItem", "DecorItem"}) data.remove(k);
        CompoundTag root = persisted(owner);
        ListTag list = root.getList("habits_pets", Tag.TAG_COMPOUND);
        CompoundTag entry = new CompoundTag();
        entry.put("pet", data);
        entry.putLong("day", owner.level().getDayTime() / 24000);
        list.add(entry);
        root.put("habits_pets", list);
    }

    static CompoundTag persisted(Player p) {
        CompoundTag pd = p.getPersistentData();
        if (!pd.contains(Player.PERSISTED_NBT_TAG)) pd.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        return pd.getCompound(Player.PERSISTED_NBT_TAG);
    }

    /** Revive pets that died on an earlier day, at the owner, full health. */
    static int revivePets(Player p) {
        CompoundTag root = persisted(p);
        if (!root.contains("habits_pets") || !(p.level() instanceof ServerLevel sl)) return 0;
        ListTag list = root.getList("habits_pets", Tag.TAG_COMPOUND);
        long today = sl.getDayTime() / 24000;
        int n = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            CompoundTag entry = list.getCompound(i);
            if (entry.getLong("day") >= today) continue;
            list.remove(i);
            Entity en = EntityType.loadEntityRecursive(entry.getCompound("pet"), sl, x -> x);
            if (en instanceof LivingEntity le) {
                le.setHealth(le.getMaxHealth());
                le.clearFire();
                le.moveTo(p.getX(), p.getY(), p.getZ(), le.getYRot(), le.getXRot());
                if (sl.getEntity(le.getUUID()) != null) le.setUUID(UUID.randomUUID());
                if (sl.addFreshEntity(le)) n++;
            }
        }
        root.put("habits_pets", list);
        return n;
    }

    static int recallPets(Player p, double range) {
        int n = 0;
        for (TamableAnimal pet : p.level().getEntitiesOfClass(TamableAnimal.class, p.getBoundingBox().inflate(Math.min(range, 128)),
                t -> t.isOwnedBy(p) && t.hasCustomName() && !t.isOrderedToSit() && !t.isLeashed() && !t.isPassenger())) {
            if (pet.distanceToSqr(p) < 16 * 16) continue;
            pet.teleportTo(p.getX(), p.getY(), p.getZ());
            pet.getNavigation().stop();
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ golems

    static void golemBuilt(Mob golem) {
        Player p = golem.level().getNearestPlayer(golem, 8);
        if (p == null || !U.ok(p)) return;
        U.fire(p, "build_golem", golem.position());
        HabitsApi.PerkHit h = U.hit(p, GOLEM);
        if (h == null) return;
        golem.getPersistentData().putUUID(GOLEM_OWNER, p.getUUID());
        var hp = golem.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null) hp.addOrReplacePermanentModifier(new AttributeModifier(Habits.id("utility_golem_health"), h.value() / 100, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        var dmg = golem.getAttribute(Attributes.ATTACK_DAMAGE);
        if (dmg != null) dmg.addOrReplacePermanentModifier(new AttributeModifier(Habits.id("utility_golem_damage"), U.param(h, "damage", 0, 100) / 100, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        golem.setHealth(golem.getMaxHealth());
    }

    static int followOwner(Player p, double range) {
        int n = 0;
        for (Mob g : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(range),
                m -> (m instanceof IronGolem || m instanceof SnowGolem) && m.getPersistentData().hasUUID(GOLEM_OWNER)
                        && p.getUUID().equals(m.getPersistentData().getUUID(GOLEM_OWNER)))) {
            if (g.distanceToSqr(p) > 9) g.getNavigation().moveTo(p, 1.0);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ worn gear: trims, dyed leather, banners

    private static final Map<UUID, Map<String, Integer>> TRIMS = U.playerMap();
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    static int trimCount(Player p, String material) {
        Map<String, Integer> m = TRIMS.get(p.getUUID());
        return m == null ? 0 : m.getOrDefault(material, 0);
    }

    static void trims(Player p) {
        double s = U.v(p, TRIM);
        Map<String, Integer> counts = new java.util.HashMap<>();
        if (s > 0) for (EquipmentSlot slot : ARMOR) {
            var trim = p.getItemBySlot(slot).get(DataComponents.TRIM);
            if (trim != null) trim.material().unwrapKey().ifPresent(k -> counts.merge(k.location().getPath(), 1, Integer::sum));
        }
        TRIMS.put(p.getUUID(), counts);
        trimAttr(p, Attributes.ARMOR, "iron", s / 10, AttributeModifier.Operation.ADD_VALUE);
        trimAttr(p, Attributes.ARMOR_TOUGHNESS, "diamond", s / 10, AttributeModifier.Operation.ADD_VALUE);
        trimAttr(p, Attributes.KNOCKBACK_RESISTANCE, "netherite", s / 100, AttributeModifier.Operation.ADD_VALUE);
        trimAttr(p, Attributes.MOVEMENT_SPEED, "redstone", s / 200, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        trimAttr(p, Attributes.ATTACK_SPEED, "quartz", s / 100, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        trimAttr(p, Attributes.LUCK, "amethyst", s / 10, AttributeModifier.Operation.ADD_VALUE);
    }

    private static void trimAttr(Player p, Holder<net.minecraft.world.entity.ai.attributes.Attribute> a, String mat, double per, AttributeModifier.Operation op) {
        U.attr(p, a, Habits.id("utility_trim_" + mat), per * trimCount(p, mat), op);
    }

    /** Nearest dye color to an RGB value. */
    static DyeColor nearestDye(int rgb) {
        DyeColor best = DyeColor.WHITE;
        long bd = Long.MAX_VALUE;
        for (DyeColor c : DyeColor.values()) {
            int t = c.getTextureDiffuseColor();
            long dr = ((t >> 16) & 255) - ((rgb >> 16) & 255), dg = ((t >> 8) & 255) - ((rgb >> 8) & 255), db = (t & 255) - (rgb & 255);
            long d = dr * dr + dg * dg + db * db;
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    static boolean dyeOutfit(Player p, HabitsApi.PerkHit h) {
        Map<DyeColor, Integer> n = new java.util.EnumMap<>(DyeColor.class);
        for (EquipmentSlot slot : ARMOR) {
            var dyed = p.getItemBySlot(slot).get(DataComponents.DYED_COLOR);
            if (dyed != null) n.merge(nearestDye(dyed.rgb()), 1, Integer::sum);
        }
        int need = (int) Math.round(U.param(h, "pieces", 4, 2));
        for (var en : n.entrySet()) {
            if (en.getValue() < need) continue;
            Holder<MobEffect> eff = Music.table(U.str(h, "colors", "")).get(en.getKey().getName());
            if (eff == null) continue;
            U.effect(p, eff, Math.max(0, (int) h.value() - 1), 100);
            return true;
        }
        return false;
    }

    @Nullable
    static DyeColor bannerColor(Player p) {
        ItemStack head = p.getItemBySlot(EquipmentSlot.HEAD);
        if (head.getItem() instanceof BannerItem b) return b.getColor();
        for (ItemStack s : new ItemStack[]{p.getMainHandItem(), p.getOffhandItem()}) {
            if (s.is(Items.SHIELD) && s.has(DataComponents.BASE_COLOR)) return s.get(DataComponents.BASE_COLOR);
        }
        return null;
    }

    static int weave(Player p, HabitsApi.PerkHit h) {
        DyeColor c = bannerColor(p);
        if (c == null) return 0;
        Holder<MobEffect> eff = Music.table(U.str(h, "colors", "")).get(c.getName());
        if (eff == null) return 0;
        int amp = (int) U.param(h, "amp", 0, 1);
        U.effect(p, eff, amp, 100);
        int n = 1;
        if (h.level() < U.param(h, "share_from", 2, 2)) return n;
        for (LivingEntity ally : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(h.value()),
                le -> le != p && le.isAlive() && (le instanceof TamableAnimal t && t.isOwnedBy(p) || le instanceof Player pl && !pl.isSpectator()))) {
            U.effect(ally, eff, amp, 100);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ slow tick

    private static final ResourceLocation STAND_ARMOR = Habits.id("utility_armor_stand");

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.tickCount % 20 != 0 || !U.ok(p)) return;
        trims(p);
        HabitsApi.PerkHit h = U.hit(p, STAND);
        double share = 0;
        if (h != null) {
            ArmorStand s = dressedStand(p, 8);
            if (s != null) share = s.getArmorValue() * U.param(h, "share", 0, 25) / 100;
        }
        U.attr(p, Attributes.ARMOR, STAND_ARMOR, share, AttributeModifier.Operation.ADD_VALUE);
        if (p.tickCount % 40 != 0) return;
        if ((h = U.hit(p, DYE)) != null) dyeOutfit(p, h);
        if ((h = U.hit(p, WEAVER)) != null) weave(p, h);
        if ((h = U.hit(p, NAMETAG)) != null) {
            for (Rabbit r : p.level().getEntitiesOfClass(Rabbit.class, p.getBoundingBox().inflate(4), r -> r.hasCustomName() && r.getCustomName().getString().equals("Toast"))) {
                U.effect(p, MobEffects.LUCK, (int) U.param(h, "luck_amp", 0, 1), 100);
                break;
            }
        }
        if ((h = U.hit(p, PETS)) != null) {
            recallPets(p, h.value());
            if (p.tickCount % 200 == 0) revivePets(p);
        }
        if ((h = U.hit(p, GOLEM)) != null && p.isShiftKeyDown()) followOwner(p, U.param(h, "follow", 8, 32));
    }
}
