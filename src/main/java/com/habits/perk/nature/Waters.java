package com.habits.perk.nature;

import com.habits.core.HabitsApi.PerkHit;
import com.habits.perk.Later;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.animal.Cod;
import net.minecraft.world.entity.animal.Pufferfish;
import net.minecraft.world.entity.animal.Salmon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;

/** Fishing and the sea. */
public final class Waters {
    private Waters() {}

    static final ResourceKey<LootTable> SHIPWRECK_MAP = ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/shipwreck_map"));

    // ================================================================ catches

    @SubscribeEvent
    public static void onFished(ItemFishedEvent e) {
        Player p = e.getEntity();
        if (!(p.level() instanceof ServerLevel l) || !Nat.ok(p)) return;
        FishingHook hook = e.getHookEntity();
        BlockPos at = hook.blockPosition();
        Vec3 pos = hook.position();
        if (l.isNight()) Nat.fire(p, "nature_night_catch", pos);
        if (l.isRainingAt(at.above())) Nat.fire(p, "nature_rain_catch", pos);
        if (l.getBiome(at).is(BiomeTags.IS_OCEAN)) Nat.fire(p, "nature_ocean_catch", pos);
        catches(p, l, hook, e.getDrops());
    }

    static void catches(Player p, ServerLevel l, FishingHook hook, List<ItemStack> drops) {
        boolean fish = drops.stream().anyMatch(s -> s.is(ItemTags.FISHES));
        List<ItemStack> add = new ArrayList<>();
        PerkHit h;
        if ((h = Nat.hit(p, "reel_rhythm")) != null) {
            var d = Nat.data(p);
            long now = l.getGameTime();
            int streak = now - d.getLong("habits_reel_at") <= Nat.lerp(h, "window", 60, 90) * 20 ? d.getInt("habits_reel") + 1 : 1;
            d.putInt("habits_reel", Math.min(streak, (int) Nat.lerp(h, "max", 3, 5)));
            d.putLong("habits_reel_at", now);
        }
        if (fish && (h = Nat.hit(p, "tall_tale")) != null) {
            int xp = Nat.round(l, (l.random.nextInt(6) + 1) * h.value() / 100);
            if (xp > 0) l.addFreshEntity(new ExperienceOrb(l, p.getX(), p.getY() + 0.5, p.getZ() + 0.5, xp));
        }
        if ((h = Nat.hit(p, "message_bottle")) != null && Nat.roll(l, h.value())) {
            LootParams params = new LootParams.Builder(l).withParameter(LootContextParams.ORIGIN, hook.position()).create(LootContextParamSets.CHEST);
            for (ItemStack s : l.getServer().reloadableRegistries().getLootTable(SHIPWRECK_MAP).getRandomItems(params))
                if (s.is(Items.FILLED_MAP)) add.add(s);
        }
        if (fish && (h = Nat.hit(p, "school")) != null) {
            int max = (int) Nat.lerp(h, "max", 2, 10);
            for (AbstractFish f : l.getEntitiesOfClass(AbstractFish.class, hook.getBoundingBox().inflate(h.value()), AbstractFish::isAlive)) {
                if (max-- <= 0) break;
                add.add(new ItemStack(f instanceof Cod ? Items.COD : f instanceof Salmon ? Items.SALMON : f instanceof Pufferfish ? Items.PUFFERFISH : Items.TROPICAL_FISH));
                l.sendParticles(ParticleTypes.SPLASH, f.getX(), f.getY(), f.getZ(), 6, 0.2, 0.2, 0.2, 0);
                f.discard();
            }
        }
        if ((h = Nat.hit(p, "old_man")) != null && Nat.roll(l, h.value())) add.add(legend(h));
        for (ItemStack s : add) {
            drops.add(s);
            fly(hook, p, s);
        }
    }

    /** The Old Man's fish: a huge meal with a long Regeneration and Absorption. */
    static ItemStack legend(PerkHit h) {
        ItemStack s = new ItemStack(Items.COOKED_SALMON);
        int food = (int) Nat.lerp(h, "food", 10, 20), t = (int) (Nat.lerp(h, "seconds", 20, 90) * 20);
        s.set(DataComponents.FOOD, new FoodProperties.Builder().nutrition(food).saturationModifier(1.2f).alwaysEdible()
                .effect(() -> new MobEffectInstance(MobEffects.REGENERATION, t, 1), 1)
                .effect(() -> new MobEffectInstance(MobEffects.ABSORPTION, t, 2), 1).build());
        s.set(DataComponents.ITEM_NAME, Component.translatable("item.habits.nature.legend_fish"));
        s.set(DataComponents.RARITY, Rarity.EPIC);
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    /** Thrown at the player like a vanilla catch. */
    static void fly(FishingHook hook, Player p, ItemStack s) {
        ItemEntity it = new ItemEntity(hook.level(), hook.getX(), hook.getY(), hook.getZ(), s);
        double dx = p.getX() - hook.getX(), dy = p.getY() - hook.getY(), dz = p.getZ() - hook.getZ();
        it.setDeltaMovement(dx * 0.1, dy * 0.1 + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08, dz * 0.1);
        hook.level().addFreshEntity(it);
        if (s.is(ItemTags.FISHES)) p.awardStat(Stats.FISH_CAUGHT, 1);
    }

    // ================================================================ faster bites

    /** Cut the bobber's wait by {@code percent}: the countdown runs 1/(1-cut) times as fast. Same trick as Storm Angler. */
    static void hurry(FishingHook hook, double percent) {
        int lured = hook.timeUntilLured;
        if (lured <= 1 || !hook.isInWater()) return;
        double extra = 1 / (1 - Math.min(90, percent) / 100) - 1;
        // vanilla rerolls the wait if it ever hits 0 between its own ticks, so stop at 1
        hook.timeUntilLured = Math.max(1, lured - Nat.round(hook.level(), extra));
    }

    static void reelTick(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.fishing == null) return;
        var d = Nat.data(p);
        if (p.level().getGameTime() - d.getLong("habits_reel_at") > Nat.lerp(h, "window", 60, 90) * 20) return;
        hurry(p.fishing, d.getInt("habits_reel") * h.value());
    }

    static void chumTick(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.fishing == null) return;
        var d = Nat.data(p);
        if (p.level().getGameTime() > d.getLong("habits_chum_until")) return;
        double r = Nat.lerp(h, "radius", 8, 16);
        if (p.fishing.distanceToSqr(d.getDouble("habits_chum_x"), d.getDouble("habits_chum_y"), d.getDouble("habits_chum_z")) <= r * r)
            hurry(p.fishing, Nat.lerp(h, "cut", 30, 70));
    }

    static void chum(Player p, Vec3 at, PerkHit h) {
        var d = Nat.data(p);
        d.putDouble("habits_chum_x", at.x);
        d.putDouble("habits_chum_y", at.y);
        d.putDouble("habits_chum_z", at.z);
        d.putLong("habits_chum_until", p.level().getGameTime() + (long) (h.value() * 20));
    }

    // ================================================================ buckets: chum, tide pool

    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickItem e) {
        Player p = e.getEntity();
        if (!(p.level() instanceof ServerLevel l) || !Nat.ok(p)) return;
        InteractionHand hand = e.getHand();
        ItemStack s = e.getItemStack();
        PerkHit h;
        if (s.getItem() instanceof MobBucketItem && !s.is(Items.AXOLOTL_BUCKET) && !s.is(Items.TADPOLE_BUCKET) && (h = Nat.hit(p, "chum_bucket")) != null) {
            Vec3 at = p.getEyePosition().add(p.getLookAngle().scale(3));
            PerkHit ch = h;
            Later.run(l, 1, () -> {
                if (p.getItemInHand(hand).is(Items.BUCKET)) chum(p, at, ch);
            });
        }
        if (s.is(Items.BUCKET) && (h = Nat.hit(p, "tide_pool")) != null && Nat.matches(l, h.skill().perk().param("biome", "#minecraft:is_ocean"), p.blockPosition())
                && Nat.roll(l, h.value())) {
            String list = h.skill().perk().param("buckets", "minecraft:cod_bucket");
            Later.run(l, 1, () -> {
                if (!p.getItemInHand(hand).is(Items.WATER_BUCKET)) return;
                ItemStack fish = Nat.pick(l, list);
                if (fish.isEmpty()) return;
                p.setItemInHand(hand, fish);
                l.playSound(null, p.blockPosition(), SoundEvents.BUCKET_FILL_FISH, SoundSource.PLAYERS, 1, 1);
            });
        }
    }

    // ================================================================ underwater

    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !(p.level() instanceof ServerLevel l) || !p.isInWater()) return;
        PerkHit h = Nat.hit(p, "ink_cloud");
        if (h == null || !(e.getSource().getEntity() instanceof LivingEntity a) || a == p || !Nat.roll(l, h.value())) return;
        int t = (int) (Nat.lerp(h, "seconds", 2, 6) * 20);
        Nat.effect(a, MobEffects.BLINDNESS, t, 0);
        Nat.effect(a, MobEffects.MOVEMENT_SLOWDOWN, t, 1);
        l.sendParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY() + 0.8, p.getZ(), 30, 0.6, 0.6, 0.6, 0.05);
        l.playSound(null, p.blockPosition(), SoundEvents.SQUID_SQUIRT, SoundSource.PLAYERS, 1, 1);
    }

    static void deepWatch(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 40 != 0 || !p.isUnderWater()) return;
        for (LivingEntity m : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(h.value()), m -> m instanceof Enemy))
            Nat.effect(m, MobEffects.GLOWING, 50, 0);
    }

    static void siren(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 40 != 0 || !p.isInWater()) return;
        int amp = (int) Nat.lerp(h, "amp", 0, 2);
        for (LivingEntity m : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(h.value()), m -> m instanceof Enemy && m.isInWater())) {
            Nat.effect(m, MobEffects.MOVEMENT_SLOWDOWN, 50, amp);
            Nat.effect(m, MobEffects.WEAKNESS, 50, 0);
        }
    }

    static void seaHeart(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 40 != 0 || !p.isInWater()) return;
        Nat.effect(p, MobEffects.CONDUIT_POWER, 60, (int) h.value() - 1);
        Nat.effect(p, MobEffects.DOLPHINS_GRACE, 60, 0);
    }
}
