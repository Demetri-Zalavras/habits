package com.habits.perk.exploration;

import com.habits.core.HabitsApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Distance perks: every {@code value} blocks travelled (on foot, or riding for {@code "on": "ride"}) pays out once. */
final class Travel {
    private Travel() {}

    /** More than this per second is a teleport, not travel. */
    static final double MAX_STEP = 40;

    private static final class Leg {
        Vec3 last;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim;
        double blocks;
    }

    private static final Map<UUID, Map<ResourceLocation, Leg>> LEGS = X.playerMap();

    static final Map<ResourceLocation, BiConsumer<ServerPlayer, HabitsApi.PerkHit>> REWARDS = new HashMap<>();

    static void init() {
        leg("trail_rations", "blocks walked per hunger point", (p, h) -> p.getFoodData().eat((int) X.param(h, "amount", 1), 0));
        leg("mile_markers", "blocks walked per XP payout", (p, h) -> p.giveExperiencePoints((int) X.param(h, "amount", 1)));
        leg("cobbler", "blocks walked per boot repair", (p, h) -> {
            ItemStack boots = p.getItemBySlot(EquipmentSlot.FEET);
            if (boots.isDamaged()) boots.setDamageValue(Math.max(0, boots.getDamageValue() - (int) X.param(h, "amount", 1)));
        });
        leg("caravan_master", "blocks ridden per mount heal", (p, h) -> {
            if (p.getVehicle() instanceof LivingEntity mount) mount.heal((float) X.param(h, "amount", 1));
        });
        // class skills (Wayfarer)
        leg("wayfarer", "blocks walked per rest", (p, h) -> {
            p.heal((float) X.param(h, "heal", 2));
            p.getFoodData().eat((int) X.param(h, "food", 1), 0);
        });
        leg("wayfarer_roadside_finds", "blocks walked per roadside find", (p, h) -> {
            String[] items = X.str(h, "items", "minecraft:bread").split(",");
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(items[p.getRandom().nextInt(items.length)].trim()))
                    .ifPresent(i -> X.give(p, new ItemStack(i)));
        });
    }

    private static void leg(String name, String meaning, BiConsumer<ServerPlayer, HabitsApi.PerkHit> reward) {
        ResourceLocation[] id = new ResourceLocation[1];
        id[0] = X.mech(name, meaning, (p, h) -> step(p, h, id[0], reward));
        REWARDS.put(id[0], reward);
    }

    /** One second of travel: count the distance if it is the right kind of moving, pay out per {@code value} blocks. */
    static int step(ServerPlayer p, HabitsApi.PerkHit h, ResourceLocation id, BiConsumer<ServerPlayer, HabitsApi.PerkHit> reward) {
        Leg leg = LEGS.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).computeIfAbsent(id, k -> new Leg());
        Vec3 now = p.position();
        boolean riding = "ride".equals(X.str(h, "on", "foot"));
        boolean counts = riding ? p.isPassenger() : !p.isPassenger() && !p.isFallFlying() && !p.getAbilities().flying;
        if (leg.last != null && leg.dim == p.level().dimension() && counts) {
            double d = Math.sqrt(Math.pow(now.x - leg.last.x, 2) + Math.pow(now.z - leg.last.z, 2));
            if (d <= MAX_STEP) leg.blocks += d;
        }
        leg.last = now;
        leg.dim = p.level().dimension();
        int paid = 0;
        double per = Math.max(1, h.value());
        while (leg.blocks >= per) {
            leg.blocks -= per;
            reward.accept(p, h);
            paid++;
        }
        return paid;
    }
}
