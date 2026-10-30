package com.habits.perk;

import com.habits.core.IdMatch;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** The perk {@code condition} grammar (see {@link com.habits.core.PerkType} javadoc). Public: any perk package may call
 * {@link #test}. Each condition string is parsed once and cached; unknown terms never hold (logged once). */
public final class Conditions {
    private Conditions() {}

    private static final Map<String, Predicate<Player>> CACHE = new ConcurrentHashMap<>();

    /** True when every comma-separated term of {@code condition} holds; blank or null = always. Both sides. */
    public static boolean test(Player p, @Nullable String condition) {
        if (condition == null || condition.isBlank()) return true;
        return CACHE.computeIfAbsent(condition, Conditions::compile).test(p);
    }

    private static Predicate<Player> compile(String condition) {
        Predicate<Player> all = p -> true;
        for (String raw : condition.split(",")) all = all.and(term(raw.trim()));
        return all;
    }

    private static Predicate<Player> term(String t) {
        int eq = t.indexOf('=');
        if (eq > 0) {
            String key = t.substring(0, eq).trim(), arg = t.substring(eq + 1).trim();
            switch (key) {
                case "dimension" -> {
                    ResourceLocation id = ResourceLocation.tryParse(arg);
                    if (id != null) return p -> p.level().dimension().location().equals(id);
                }
                case "biome" -> {
                    IdMatch m = IdMatch.parse(arg);
                    if (m != null) return p -> p.level().getBiome(p.blockPosition()).unwrapKey()
                            .map(k -> m.test(k.location(), Registries.BIOME, p.level().registryAccess())).orElse(false);
                }
                case "holding" -> {
                    IdMatch m = IdMatch.parse(arg);
                    if (m != null) return p -> m.test(BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()),
                            Registries.ITEM, p.level().registryAccess());
                }
                default -> {}
            }
        } else {
            switch (t) {
                case "underground": return p -> p.getY() < 50 && p.level().getBrightness(LightLayer.SKY, p.blockPosition()) == 0;
                case "in_water": return Player::isInWater;
                case "in_lava": return Player::isInLava;
                case "in_rain": return p -> p.level().isRainingAt(p.blockPosition()) || p.level().isRainingAt(p.blockPosition().above());
                case "sneaking": return Player::isShiftKeyDown;
                case "sprinting": return Player::isSprinting;
                case "swimming": return Player::isSwimming;
                case "gliding": return Player::isFallFlying;
                case "riding": return Player::isPassenger;
                case "on_fire": return Player::isOnFire;
                case "sleeping": return Player::isSleeping;
                case "low_health": return p -> p.getHealth() <= p.getMaxHealth() * 0.3f;
                case "full_health": return p -> p.getHealth() >= p.getMaxHealth();
                case "night": return p -> p.level().isNight();
                case "day": return p -> p.level().isDay();
                default: break;
            }
        }
        LogUtils.getLogger().warn("[habits] unknown perk condition term '{}' (never holds)", t);
        return p -> false;
    }
}
