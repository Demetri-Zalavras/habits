package com.habits.perk;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsApi.PerkHit;
import com.habits.core.PerkType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Ticking stat {@link PerkType}s: ATTRIBUTE, REACH, REGEN, MOB_EFFECT_CONDITIONAL, CROP_GROWTH_AURA, MAGNET_PICKUP
 * (server) and CLIMB_SPEED (the local player, since the client moves players). */
final class StatTicks {
    private StatTicks() {}

    /** Effects we keep refreshed last a little past the night vision flicker (200 ticks) and are removed when off. */
    static final int EFFECT_TICKS = 260;

    /** Per player: modifier id -> attribute it sits on, so modifiers of skills that went off get removed. */
    private static final Map<UUID, Map<ResourceLocation, Holder<Attribute>>> APPLIED = new ConcurrentHashMap<>();
    /** Per player: effects this perk granted last refresh. */
    private static final Map<UUID, Set<Holder<MobEffect>>> EFFECTS = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Holder<Attribute>>> ATTRS = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Holder<MobEffect>>> MOB_EFFECTS = new ConcurrentHashMap<>();

    static void init() {
        NeoForge.EVENT_BUS.addListener(StatTicks::tick);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> forget(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> { APPLIED.clear(); EFFECTS.clear(); });
    }

    static void forget(UUID id) {
        APPLIED.remove(id);
        EFFECTS.remove(id);
    }

    static void tick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (!p.isAlive() || p.isSpectator()) return;
        if (p.level().isClientSide) {
            if (p.isLocalPlayer()) climb(p);
            return;
        }
        if (p.tickCount % 2 == 0) magnet(p);
        if (p.tickCount % 10 == 0) attributes(p);
        if (p.tickCount % 20 == 0) {
            regen(p);
            effects(p);
            cropAura(p);
        }
    }

    // ---------------------------------------------------------------- ATTRIBUTE + REACH

    static void attributes(Player p) {
        Map<ResourceLocation, Holder<Attribute>> before = APPLIED.getOrDefault(p.getUUID(), Map.of());
        Map<ResourceLocation, Holder<Attribute>> now = new HashMap<>();
        for (PerkHit h : HabitsApi.perkValues(p, PerkType.ATTRIBUTE)) {
            if (!Conditions.test(p, h.skill().perk().params().get("condition"))) continue;
            Optional<Holder<Attribute>> attr = attribute(h.skill().perk().param("attribute", ""));
            if (attr.isEmpty()) continue;
            AttributeModifier.Operation op = switch (h.skill().perk().param("operation", "add_value")) {
                case "add_multiplied_base" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
                case "add_multiplied_total" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
                default -> AttributeModifier.Operation.ADD_VALUE;
            };
            double amount = op == AttributeModifier.Operation.ADD_VALUE ? h.value() : h.value() / 100;
            apply(p, now, attr.get(), modifierId(h, ""), amount, op);
        }
        for (PerkHit h : HabitsApi.perkValues(p, PerkType.REACH)) {
            if (!Conditions.test(p, h.skill().perk().params().get("condition"))) continue;
            String kind = h.skill().perk().param("kind", "both");
            if (!kind.equals("entity")) apply(p, now, Attributes.BLOCK_INTERACTION_RANGE, modifierId(h, "/block"), h.value(), AttributeModifier.Operation.ADD_VALUE);
            if (!kind.equals("block")) apply(p, now, Attributes.ENTITY_INTERACTION_RANGE, modifierId(h, "/entity"), h.value(), AttributeModifier.Operation.ADD_VALUE);
        }
        before.forEach((id, attr) -> {
            if (now.containsKey(id)) return;
            AttributeInstance inst = p.getAttribute(attr);
            if (inst != null) inst.removeModifier(id);
        });
        if (now.isEmpty()) APPLIED.remove(p.getUUID()); else APPLIED.put(p.getUUID(), now);
    }

    private static ResourceLocation modifierId(PerkHit h, String suffix) {
        ResourceLocation s = h.skill().id();
        return Habits.id("stat/" + s.getNamespace() + "/" + s.getPath() + suffix);
    }

    private static void apply(Player p, Map<ResourceLocation, Holder<Attribute>> now, Holder<Attribute> attr, ResourceLocation id,
                              double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        now.put(id, attr);
        AttributeModifier old = inst.getModifier(id);
        if (old != null && old.amount() == amount && old.operation() == op) return; // unchanged: no resync
        inst.addOrUpdateTransientModifier(new AttributeModifier(id, amount, op));
    }

    private static Optional<Holder<Attribute>> attribute(String id) {
        return ATTRS.computeIfAbsent(id, k -> Optional.ofNullable(ResourceLocation.tryParse(k)).flatMap(rl -> lookup(rl)
                // 1.21.1 ids carry a generic./player. prefix; the newer bare form ("minecraft:movement_speed") is accepted too
                .or(() -> lookup(rl.withPrefix("generic."))).or(() -> lookup(rl.withPrefix("player.")))));
    }

    private static Optional<Holder<Attribute>> lookup(ResourceLocation rl) {
        return BuiltInRegistries.ATTRIBUTE.getHolder(ResourceKey.create(Registries.ATTRIBUTE, rl)).map(r -> (Holder<Attribute>) r);
    }

    // ---------------------------------------------------------------- REGEN (health per minute, checked each second)

    static void regen(Player p) {
        if (p.getHealth() >= p.getMaxHealth()) return;
        double v = Stats.sum(p, PerkType.REGEN);
        if (v > 0) p.heal((float) (v / 60));
    }

    // ---------------------------------------------------------------- MOB_EFFECT_CONDITIONAL

    static void effects(Player p) {
        Set<Holder<MobEffect>> before = EFFECTS.getOrDefault(p.getUUID(), Set.of());
        Map<Holder<MobEffect>, Integer> want = new HashMap<>();
        for (PerkHit h : HabitsApi.perkValues(p, PerkType.MOB_EFFECT_CONDITIONAL)) {
            int amp = (int) Math.round(h.value()) - 1;
            if (amp < 0 || !Conditions.test(p, h.skill().perk().params().get("condition"))) continue;
            mobEffect(h.skill().perk().param("effect", "")).ifPresent(eff -> want.merge(eff, amp, Math::max));
        }
        want.forEach((eff, amp) -> p.addEffect(new MobEffectInstance(eff, EFFECT_TICKS, amp, true, false, true)));
        for (Holder<MobEffect> eff : before) {
            if (want.containsKey(eff)) continue;
            MobEffectInstance cur = p.getEffect(eff);
            // only take back our own instance (ambient, no particles, short), never a potion the player drank
            if (cur != null && cur.isAmbient() && !cur.isVisible() && cur.getDuration() <= EFFECT_TICKS) p.removeEffect(eff);
        }
        if (want.isEmpty()) EFFECTS.remove(p.getUUID()); else EFFECTS.put(p.getUUID(), new HashSet<>(want.keySet()));
    }

    private static Optional<Holder<MobEffect>> mobEffect(String id) {
        return MOB_EFFECTS.computeIfAbsent(id, k -> Optional.ofNullable(ResourceLocation.tryParse(k))
                .flatMap(rl -> BuiltInRegistries.MOB_EFFECT.getHolder(ResourceKey.create(Registries.MOB_EFFECT, rl)).map(r -> (Holder<MobEffect>) r)));
    }

    // ---------------------------------------------------------------- CROP_GROWTH_AURA

    // ponytail: CropBlock only (wheat, carrots, potatoes, beetroot, modded crops); stems, berries, wart are not aged here
    static void cropAura(Player p) {
        int radius = 0;
        double chance = 0;
        for (PerkHit h : HabitsApi.perkValues(p, PerkType.CROP_GROWTH_AURA)) {
            if (!Conditions.test(p, h.skill().perk().params().get("condition"))) continue;
            chance += h.value();
            radius = Math.max(radius, parseInt(h.skill().perk().param("radius", "4"), 4));
        }
        if (chance <= 0 || !(p.level() instanceof ServerLevel level)) return;
        BlockPos c = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-radius, -2, -radius), c.offset(radius, 2, radius))) {
            BlockState s = level.getBlockState(pos);
            if (s.getBlock() instanceof CropBlock crop && !crop.isMaxAge(s) && p.getRandom().nextDouble() * 100 < chance) {
                level.setBlock(pos, crop.getStateForAge(crop.getAge(s) + 1), 2);
            }
        }
    }

    private static int parseInt(String s, int fallback) {
        try { return Math.max(0, Math.min(16, Integer.parseInt(s.trim()))); } catch (NumberFormatException ex) { return fallback; }
    }

    // ---------------------------------------------------------------- MAGNET_PICKUP

    static void magnet(Player p) {
        double r = Stats.sum(p, PerkType.MAGNET_PICKUP);
        if (r <= 0) return;
        Vec3 to = p.position().add(0, 0.5, 0);
        for (Entity e : p.level().getEntities(p, new AABB(to, to).inflate(r), e -> e instanceof ItemEntity || e instanceof ExperienceOrb)) {
            if (e instanceof ItemEntity item && item.hasPickUpDelay()) continue;
            Vec3 d = to.subtract(e.position());
            if (d.lengthSqr() > r * r || d.lengthSqr() < 0.01) continue;
            e.setDeltaMovement(d.normalize().scale(0.35));
            e.hasImpulse = true;
        }
    }

    // ---------------------------------------------------------------- CLIMB_SPEED (local player, client)

    static void climb(Player p) {
        if (!p.onClimbable() || p.getDeltaMovement().y <= 0 || p.isShiftKeyDown()) return;
        double v = Stats.sum(p, PerkType.CLIMB_SPEED);
        if (v > 0) p.move(MoverType.SELF, new Vec3(0, p.getDeltaMovement().y * v / 100, 0));
    }
}
