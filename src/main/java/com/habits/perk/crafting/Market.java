package com.habits.perk.crafting;

import com.habits.core.HabitsApi;
import com.habits.perk.Later;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;

import java.util.Map;
import java.util.UUID;

/** Villager, golem and beacon perks. */
final class Market {
    private Market() {}

    static final ResourceLocation BAKERS_DOZEN = C.mech("bakers_dozen", "% chance of one more of what you bought");
    static final ResourceLocation THANK_YOU = C.mech("thank_you_gift", "% chance of a thank-you gift");
    static final ResourceLocation SECOND_OPINION = C.mech("second_opinion", "seconds between rerolls");
    static final ResourceLocation GOLEM_MECHANIC = C.mech("golem_mechanic", "extra health per iron ingot");
    static final ResourceLocation BEACON_TUNER = C.mech("beacon_tuner", "seconds beacon effects linger");

    private static final Map<UUID, Long> REROLL_CD = C.playerMap();

    static void init() {}

    // ------------------------------------------------------------------ trades

    @SubscribeEvent
    static void onTrade(TradeWithVillagerEvent e) {
        Player p = e.getEntity();
        HabitsApi.PerkHit h;
        ItemStack got = e.getMerchantOffer().getResult();
        if ((h = C.hit(p, BAKERS_DOZEN)) != null && got.isStackable() && !C.special(got)
                && !C.matches(p, h, "exclude", "minecraft:emerald,minecraft:emerald_block", got) && C.roll(p, h.value())) {
            C.give(p, got.copyWithCount(1));
        }
        if ((h = C.hit(p, THANK_YOU)) != null && e.getAbstractVillager() instanceof Villager v && C.roll(p, h.value())) gift(p, v);
    }

    /** The villager's Hero of the Village gift, tossed to you. */
    static void gift(Player p, Villager v) {
        if (!(p.level() instanceof ServerLevel level)) return;
        ResourceLocation prof = BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession());
        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.withDefaultNamespace("gameplay/hero_of_the_village/" + prof.getPath() + "_gift"));
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, v.position())
                .withParameter(LootContextParams.THIS_ENTITY, v).create(LootContextParamSets.GIFT);
        for (ItemStack s : table.getRandomItems(params)) C.give(p, s);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getY() + 1.8, v.getZ(), 5, 0.3, 0.3, 0.3, 0);
    }

    // ------------------------------------------------------------------ entity clicks: reroll a fresh villager, golem repair

    @SubscribeEvent
    static void onInteract(PlayerInteractEvent.EntityInteract e) {
        Player p = e.getEntity();
        if (e.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack held = p.getItemInHand(e.getHand());
        HabitsApi.PerkHit h;
        if (e.getTarget() instanceof Villager v && p.isShiftKeyDown() && held.is(Items.EMERALD) && (h = C.hit(p, SECOND_OPINION)) != null) {
            if (reroll(p, v, h)) {
                held.shrink(1);
                e.setCanceled(true);
            }
        } else if (e.getTarget() instanceof IronGolem g && held.is(Items.IRON_INGOT) && (h = C.hit(p, GOLEM_MECHANIC)) != null) {
            float before = g.getHealth();
            double extra = h.value();
            // after vanilla's own repair, only if it happened (so a full golem never eats an ingot for us)
            Later.run((ServerLevel) p.level(), 1, () -> {
                if (g.isAlive() && g.getHealth() > before) g.heal((float) extra);
            });
        }
    }

    /** A villager that never traded (novice, 0 XP) rolls new offers. */
    static boolean reroll(Player p, Villager v, HabitsApi.PerkHit h) {
        var data = v.getVillagerData();
        if (data.getLevel() != 1 || v.getVillagerXp() > 0 || data.getProfession() == VillagerProfession.NONE
                || data.getProfession() == VillagerProfession.NITWIT || v.isBaby()) return false;
        if (!C.ready(REROLL_CD, p, h.value())) return false;
        v.setOffers(null);
        v.getOffers();
        if (p.level() instanceof ServerLevel level)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getY() + 1.8, v.getZ(), 8, 0.3, 0.3, 0.3, 0);
        return true;
    }

    // ------------------------------------------------------------------ beacon tuner: ambient effects linger

    @SubscribeEvent
    static void onExpire(MobEffectEvent.Expired e) {
        MobEffectInstance i = e.getEffectInstance();
        if (i == null || !i.isAmbient() || !i.isVisible() || !(e.getEntity() instanceof Player p) || !(p.level() instanceof ServerLevel level)) return;
        HabitsApi.PerkHit h = C.hit(p, BEACON_TUNER);
        if (h == null) return;
        var eff = i.getEffect();
        int amp = i.getAmplifier();
        int ticks = (int) (h.value() * 20);
        // beacons and conduits apply ambient, visible effects (perk refreshers are invisible); not while the map iterates
        Later.run(level, 1, () -> {
            if (p.isAlive() && !p.hasEffect(eff)) p.addEffect(new MobEffectInstance(eff, ticks, amp, false, true, true));
        });
    }
}
