package com.habits.perk.exploration;

import com.habits.core.HabitsApi;
import com.habits.core.TargetKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Place-bound perks: an effect while inside a structure, high up or far from spawn, and damage dealt or taken inside
 * structures ({@code side} = dealt, taken or both). */
final class Zones {
    private Zones() {}

    static final List<ResourceLocation> AURAS = new ArrayList<>();
    static final List<ResourceLocation> COMBAT = new ArrayList<>();

    /** GameTests: these players count as standing in a stronghold. */
    static final Set<UUID> TEST_INSIDE = ConcurrentHashMap.newKeySet();

    static void init() {
        for (String n : new String[]{"dungeon_lamp", "ruin_haste", "treasure_luck", "summit_wind", "frontiersman", "hearthside",
                "purpur_drift", "wreck_diver", "delver_stoneskin", "delver_ruin_fury", "worldseer_far_wanderer"})
            AURAS.add(X.mech(n, "effect level, or seconds it lingers", Zones::aura));
        COMBAT.add(X.mech("dungeon_slayer", "% more damage dealt inside structures"));
        COMBAT.add(X.mech("trap_sense", "% less damage from traps inside structures"));
        COMBAT.add(X.mech("old_walls", "% less damage taken inside structures"));
        COMBAT.add(X.mech("delver", "% more damage dealt and less taken inside structures"));
        COMBAT.add(X.mech("delver_trapwise", "% less damage from traps and magic inside structures"));
    }

    // ------------------------------------------------------------------ where am I

    private record Here(long time, List<StructureStart> starts) {}

    private static final Map<UUID, Here> HERE = X.playerMap();

    /** Structure starts whose pieces contain the player, cached for the game tick. */
    static List<StructureStart> starts(ServerPlayer p) {
        long now = p.level().getGameTime();
        Here h = HERE.get(p.getUUID());
        if (h != null && h.time == now) return h.starts;
        ServerLevel sl = p.serverLevel();
        BlockPos pos = p.blockPosition();
        List<StructureStart> out = new ArrayList<>();
        for (Structure s : sl.structureManager().getAllStructuresAt(pos).keySet()) {
            StructureStart st = sl.structureManager().getStructureWithPieceAt(pos, s);
            if (st.isValid()) out.add(st);
        }
        HERE.put(p.getUUID(), new Here(now, out));
        return out;
    }

    static List<ResourceLocation> structures(ServerPlayer p) {
        var reg = p.level().registryAccess().registryOrThrow(Registries.STRUCTURE);
        List<ResourceLocation> out = new ArrayList<>();
        for (StructureStart st : starts(p)) out.add(reg.getKey(st.getStructure()));
        if (TEST_INSIDE.contains(p.getUUID())) out.add(ResourceLocation.withDefaultNamespace("stronghold"));
        return out;
    }

    /** Inside a structure matching the skill's {@code structure} param ("any" or missing = any structure). */
    static boolean inStructure(ServerPlayer p, HabitsApi.PerkHit h) {
        String want = X.str(h, "structure", "any");
        for (ResourceLocation id : structures(p))
            if ("any".equals(want) || X.matches(h, "structure", id, TargetKind.STRUCTURE, p)) return true;
        return false;
    }

    /** Every place param the skill sets must hold: {@code structure}, {@code min_y}, {@code from_spawn} (blocks). */
    static boolean inZone(ServerPlayer p, HabitsApi.PerkHit h) {
        if (X.has(h, "min_y") && p.getY() < X.param(h, "min_y", 0)) return false;
        if (X.has(h, "from_spawn")) {
            BlockPos s = p.serverLevel().getSharedSpawnPos();
            double dx = p.getX() - s.getX(), dz = p.getZ() - s.getZ(), d = X.param(h, "from_spawn", 0);
            if (dx * dx + dz * dz < d * d) return false;
        }
        return !X.has(h, "structure") || inStructure(p, h);
    }

    /** Value is the effect level unless the skill fixes {@code level}; then it is the linger in seconds. */
    static void aura(ServerPlayer p, HabitsApi.PerkHit h) {
        if (!inZone(p, h)) return;
        var effect = BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(X.str(h, "effect", "minecraft:luck")));
        if (effect.isEmpty()) return;
        int level = (int) (X.has(h, "level") ? X.param(h, "level", 1) : h.value());
        double linger = X.has(h, "linger") ? X.param(h, "linger", 1) : h.value();
        if (level < 1) return;
        // +1 s so the once-a-second refresh never leaves a gap
        p.addEffect(new MobEffectInstance(effect.get(), (int) (linger * 20) + 20, level - 1, true, false, true));
    }

    // ------------------------------------------------------------------ fights inside structures

    @SubscribeEvent
    static void onDamage(LivingIncomingDamageEvent e) {
        var typeId = e.getSource().typeHolder().unwrapKey().map(k -> k.location()).orElse(null);
        float amount = e.getAmount();
        if (e.getEntity() instanceof ServerPlayer victim && X.ok(victim))
            for (ResourceLocation id : COMBAT) amount *= (float) factor(victim, id, "taken", typeId);
        if (e.getSource().getEntity() instanceof ServerPlayer attacker && attacker != e.getEntity() && X.ok(attacker))
            for (ResourceLocation id : COMBAT) amount *= (float) factor(attacker, id, "dealt", typeId);
        e.setAmount(amount);
    }

    static double factor(ServerPlayer p, ResourceLocation id, String side, ResourceLocation type) {
        HabitsApi.PerkHit h = HabitsApi.mechanicHit(p, id);
        String want = h == null ? "" : X.str(h, "side", "taken");
        if (h == null || !side.equals(want) && !"both".equals(want) || !X.matches(h, "damage", type, TargetKind.DAMAGE_TYPE, p)
                || !inStructure(p, h)) return 1;
        double v = Math.min(100, h.value()) / 100;
        return "taken".equals(side) ? 1 - v : 1 + h.value() / 100;
    }

}
