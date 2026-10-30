package com.habits.perk.nature;

import com.habits.core.HabitsApi.PerkHit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Weather: the rain walk and weather change events, plus the weather mechanics. */
public final class Skies {
    private Skies() {}

    static final Map<UUID, Vec3> LAST = new HashMap<>();
    static final Map<ResourceKey<Level>, Boolean> RAINING = new HashMap<>();

    static boolean outside(Player p) {
        Level l = p.level();
        return l.dimensionType().hasSkyLight() && !l.dimensionType().hasCeiling() && l.canSeeSky(BlockPos.containing(p.getEyePosition()));
    }

    // ================================================================ events: habits:nature_rain_walk (units), habits:nature_weather_change

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.level().isClientSide() || p.tickCount % 20 != 0 || !Nat.ok(p)) return;
        Vec3 now = p.position(), was = LAST.put(p.getUUID(), now);
        if (was == null || p.isPassenger() || p.isInWater() || !p.level().isRainingAt(p.blockPosition().above())) return;
        double d = Math.hypot(now.x - was.x, now.z - was.z);
        if (d > 0.5 && d < 40) Nat.fireUnits(p, "nature_rain_walk", d);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 20 != 0) return;
        for (ServerLevel l : e.getServer().getAllLevels()) {
            if (!l.dimensionType().hasSkyLight() || l.dimensionType().hasCeiling()) continue;
            Boolean was = RAINING.put(l.dimension(), l.isRaining());
            if (was == null || !was || l.isRaining()) continue;
            for (ServerPlayer p : l.players()) if (Nat.ok(p) && outside(p)) clearedUp(p);
        }
    }

    /** Rain just stopped over a player under the sky. */
    static void clearedUp(Player p) {
        Nat.fire(p, "nature_weather_change", null);
        PerkHit h = Nat.hit(p, "rainbow");
        if (h == null) return;
        int t = (int) (h.value() * 20);
        Nat.effect(p, MobEffects.LUCK, t, (int) Nat.lerp(h, "amp", 0, 2));
        Nat.effect(p, MobEffects.REGENERATION, t, 0);
        p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1, 1.2f);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        LAST.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent e) {
        LAST.clear();
        RAINING.clear();
    }

    // ================================================================ rainmaker

    @SubscribeEvent
    public static void onDrink(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof Player p) || !(p.level() instanceof ServerLevel l) || !e.getItem().is(Items.POTION)) return;
        PotionContents pc = e.getItem().get(DataComponents.POTION_CONTENTS);
        PerkHit h = Nat.hit(p, "rainmaker");
        if (h == null || pc == null || !pc.is(Potions.WATER) || !outside(p)) return;
        var d = Nat.data(p);
        if (l.getGameTime() < d.getLong("habits_rainmaker")) {
            p.displayClientMessage(Component.translatable("nature.habits.rainmaker.wait",
                    (d.getLong("habits_rainmaker") - l.getGameTime()) / 1200 + 1), true);
            return;
        }
        d.putLong("habits_rainmaker", l.getGameTime() + (long) (h.value() * 1200));
        flipWeather(l, (int) (Nat.lerp(h, "minutes", 5, 15) * 1200));
        l.playSound(null, p.blockPosition(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 0.6f, 1.4f);
    }

    static void flipWeather(ServerLevel l, int ticks) {
        if (l.getLevelData().isRaining()) l.setWeatherParameters(ticks, 0, false, false);
        else l.setWeatherParameters(0, ticks, true, false);
    }

    // ================================================================ ticks

    static void cloudReader(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || p.tickCount % 40 != 0 || !(l.getLevelData() instanceof ServerLevelData d)
                || !(p.getMainHandItem().is(Items.CLOCK) || p.getOffhandItem().is(Items.CLOCK))) return;
        String key;
        int ticks;
        if (d.getClearWeatherTime() > 0) { key = "clear"; ticks = d.getClearWeatherTime(); }
        else if (l.isRaining()) { key = "stops"; ticks = d.getRainTime(); }
        else { key = "rain"; ticks = d.getRainTime(); }
        int min = ticks / 1200 + 1;
        p.displayClientMessage(min > h.value() ? Component.translatable("nature.habits.forecast.calm", (int) h.value())
                : Component.translatable("nature.habits.forecast." + key, min), true);
    }

    static void thunderChild(Player p, PerkHit h) {
        if (p.level().isClientSide() || p.tickCount % 40 != 0 || !p.level().isThundering() || !outside(p)) return;
        Nat.effect(p, MobEffects.DAMAGE_BOOST, 60, (int) h.value() - 1);
        Nat.effect(p, MobEffects.MOVEMENT_SPEED, 60, (int) h.value() - 1);
    }

    /** While it rains on you, plants around you get extra random ticks. */
    static void monsoon(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || p.tickCount % 20 != 0 || !l.isRainingAt(p.blockPosition().above())) return;
        int r = (int) Nat.lerp(h, "radius", 4, 10), grows = Nat.round(l, h.value());
        for (int i = 0; i < grows * 4 && grows > 0; i++) {
            BlockPos q = p.blockPosition().offset(l.random.nextInt(2 * r + 1) - r, l.random.nextInt(5) - 2, l.random.nextInt(2 * r + 1) - r);
            BlockState s = l.getBlockState(q);
            if (s.isRandomlyTicking() && growable(s)) {
                s.randomTick(l, q, l.random);
                grows--;
            }
        }
    }

    static boolean growable(BlockState s) {
        return s.is(BlockTags.CROPS) || s.is(BlockTags.SAPLINGS) || s.getBlock() instanceof SweetBerryBushBlock || s.is(Blocks.SUGAR_CANE)
                || s.is(Blocks.CACTUS) || s.is(Blocks.BAMBOO) || s.is(Blocks.COCOA) || s.is(Blocks.MELON_STEM) || s.is(Blocks.PUMPKIN_STEM);
    }

    /** Thunderstorm: lightning picks off monsters around you (visual bolt + the strike itself, so no fires). */
    static void eyeOfStorm(Player p, PerkHit h) {
        if (!(p.level() instanceof ServerLevel l) || p.tickCount % 20 != 0 || !l.isThundering() || !outside(p)
                || l.random.nextDouble() * 60 >= h.value()) return;
        double safe = Nat.lerp(h, "safe", 4, 4);
        List<LivingEntity> mobs = l.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(Nat.lerp(h, "radius", 8, 24)),
                m -> m instanceof Enemy && m.isAlive() && m.distanceToSqr(p) > safe * safe && l.canSeeSky(m.blockPosition()));
        if (mobs.isEmpty()) return;
        LivingEntity m = mobs.get(l.random.nextInt(mobs.size()));
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(l);
        if (bolt == null) return;
        bolt.moveTo(m.position());
        bolt.setVisualOnly(true);
        if (p instanceof ServerPlayer sp) bolt.setCause(sp);
        l.addFreshEntity(bolt);
        m.thunderHit(l, bolt);
    }
}
