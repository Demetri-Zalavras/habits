package com.habits.perk.utility;

import com.habits.core.HabitsApi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.stats.Stats;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Villager, wandering trader and raid perks. */
final class Villagers {
    private Villagers() {}

    static final ResourceLocation HAGGLER = U.mech("haggler", "% off villager prices");
    static final ResourceLocation GAB = U.mech("gift_of_gab", "% chance a used-up trade restocks");
    static final ResourceLocation SECRETS = U.mech("trade_secrets", "% more villager XP per trade");
    static final ResourceLocation WANDER = U.mech("wandering_deals", "% chance per extra rare offer");
    static final ResourceLocation BARGAIN = U.mech("bargain_hunter", "% chance a trade is free");
    static final ResourceLocation GOLDEN = U.mech("golden_tongue", "Hero of the Village level near villagers");
    static final ResourceLocation JOB_FAIR = U.mech("job_fair", "% of a baby villager's growing skipped");
    static final ResourceLocation POLITE = U.mech("polite", "kindness radius in blocks");
    static final ResourceLocation RAID_BOSS = U.mech("raid_boss", "% chance per bonus totem from a won raid");

    static void init() {}

    // ------------------------------------------------------------------ trade screen opens

    @SubscribeEvent
    static void onOpen(PlayerContainerEvent.Open e) {
        Player p = e.getEntity();
        if (!(e.getContainer() instanceof MerchantMenu) || !U.ok(p)) return;
        Villager v = tradingVillager(p);
        if (v != null) opened(p, v);
    }

    @Nullable
    static Villager tradingVillager(Player p) {
        for (Villager v : p.level().getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(8))) if (v.getTradingPlayer() == p) return v;
        return null;
    }

    /** Runs before the offers are sent to the client. Vanilla resets special prices when trading stops, so the cut is undone. */
    static void opened(Player p, Villager v) {
        HabitsApi.PerkHit h = U.hit(p, HAGGLER);
        if (h != null) {
            for (MerchantOffer o : v.getOffers()) {
                int base = o.getBaseCostA().getCount();
                int cut = (int) Math.floor(base * h.value() / 100);
                if (cut > 0) o.addToSpecialPriceDiff(-cut);
            }
        }
        h = U.hit(p, GAB);
        if (h != null) gab(p, v, h.value());
    }

    private static final Map<UUID, Long> GAB_CD = U.playerMap();

    static boolean gab(Player p, Villager v, double pct) {
        long now = p.level().getGameTime();
        if (now < GAB_CD.getOrDefault(p.getUUID(), 0L)) return false;
        List<MerchantOffer> used = new ArrayList<>();
        for (MerchantOffer o : v.getOffers()) if (o.getUses() > 0) used.add(o);
        if (used.isEmpty()) return false;
        GAB_CD.put(p.getUUID(), now + 1200); // one roll per minute, so reopening the screen is not a free reroll
        if (!U.roll(p, pct)) return false;
        used.get(p.getRandom().nextInt(used.size())).resetUses();
        return true;
    }

    // ------------------------------------------------------------------ trades

    @SubscribeEvent
    static void onTrade(TradeWithVillagerEvent e) {
        Player p = e.getEntity();
        if (!U.ok(p)) return;
        AbstractVillager av = e.getAbstractVillager();
        MerchantOffer o = e.getMerchantOffer();
        if (av instanceof WanderingTrader) U.fire(p, "trade_wandering", av.position());
        HabitsApi.PerkHit h = U.hit(p, SECRETS);
        if (h != null && av instanceof Villager v) v.setVillagerXp(v.getVillagerXp() + U.carry(p, o.getXp() * h.value() / 100));
        h = U.hit(p, BARGAIN);
        if (h != null && U.roll(p, h.value())) {
            U.give(p, o.getCostA().copy());
            if (!o.getCostB().isEmpty()) U.give(p, o.getCostB().copy());
        }
    }

    // ------------------------------------------------------------------ entity joins: wandering traders, baby villagers

    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide()) return;
        if (e.getEntity() instanceof WanderingTrader t && !t.getTags().contains("habits_deals")) {
            t.addTag("habits_deals"); // once per trader, not on every chunk load
            Player p = bestNear(t, WANDER, 48);
            if (p != null) addDeals(p, t, U.hit(p, WANDER));
        } else if (e.getEntity() instanceof Villager v && !e.loadedFromDisk() && v.getAge() == -24000) {
            for (Player p : v.level().getEntitiesOfClass(Player.class, v.getBoundingBox().inflate(16))) U.fire(p, "villager_born", v.position());
            Player p = bestNear(v, JOB_FAIR, 16);
            if (p != null) v.setAge(-(int) (24000 * (1 - Math.min(100, U.v(p, JOB_FAIR)) / 100)));
        }
    }

    @Nullable
    static Player bestNear(net.minecraft.world.entity.Entity at, ResourceLocation mech, double r) {
        Player best = null;
        double bv = 0;
        for (Player p : at.level().getEntitiesOfClass(Player.class, at.getBoundingBox().inflate(r))) {
            double v = U.v(p, mech);
            if (v > bv) {
                bv = v;
                best = p;
            }
        }
        return best;
    }

    static int addDeals(Player p, WanderingTrader t, @Nullable HabitsApi.PerkHit h) {
        if (h == null) return 0;
        List<String[]> pool = new ArrayList<>();
        for (String part : U.str(h, "offers", "minecraft:heart_of_the_sea=16").split(",")) {
            String[] kv = part.trim().split("=");
            if (kv.length == 2) pool.add(kv);
        }
        int slots = (int) U.param(h, "slots", 1, 3), added = 0;
        MerchantOffers offers = t.getOffers();
        for (int i = 0; i < slots && !pool.isEmpty(); i++) {
            if (!U.roll(p, h.value())) continue;
            String[] kv = pool.remove(p.getRandom().nextInt(pool.size()));
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(kv[0]));
            if (item.isEmpty() || item.get() == Items.AIR) continue;
            int price = Math.max(1, Math.min(64, Integer.parseInt(kv[1].trim())));
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, price), new ItemStack(item.get()), 1, 1, 0.05f));
            added++;
        }
        return added;
    }

    // ------------------------------------------------------------------ cures

    @SubscribeEvent
    static void onConvert(LivingConversionEvent.Post e) {
        if (!(e.getEntity() instanceof ZombieVillager zv) || !(e.getOutcome() instanceof Villager)) return;
        for (Player p : zv.level().getEntitiesOfClass(Player.class, zv.getBoundingBox().inflate(16))) U.fire(p, "cured_villager", zv.position());
    }

    // ------------------------------------------------------------------ slow tick: golden tongue, raid boss omen buff

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.tickCount % 40 != 0 || !U.ok(p)) return;
        HabitsApi.PerkHit h = U.hit(p, GOLDEN);
        if (h != null && !p.level().getEntitiesOfClass(AbstractVillager.class, p.getBoundingBox().inflate(U.param(h, "radius", 16, 64)), x -> true).isEmpty()) {
            U.effect(p, MobEffects.HERO_OF_THE_VILLAGE, Math.max(0, (int) h.value() - 1), 100);
        }
        h = U.hit(p, RAID_BOSS);
        if (h != null && (p.hasEffect(MobEffects.BAD_OMEN) || p.hasEffect(MobEffects.RAID_OMEN))) {
            U.effect(p, MobEffects.DAMAGE_BOOST, (int) U.param(h, "strength", 0, 1), 100);
            if (h.level() >= U.param(h, "resist_from", 10, 10)) U.effect(p, MobEffects.DAMAGE_RESISTANCE, 0, 100);
        }
    }

    @SubscribeEvent
    static void onStat(StatAwardEvent e) {
        if (e.getStat() != Stats.CUSTOM.get(Stats.RAID_WIN) || !(e.getEntity() instanceof Player p)) return;
        HabitsApi.PerkHit h = U.hit(p, RAID_BOSS);
        if (h != null) raidReward(p, h);
    }

    static int raidReward(Player p, HabitsApi.PerkHit h) {
        int totems = 0;
        for (int i = 0; i < (int) U.param(h, "totems", 1, 3); i++) if (U.roll(p, h.value())) totems++;
        if (totems > 0) U.give(p, new ItemStack(Items.TOTEM_OF_UNDYING, totems));
        U.give(p, new ItemStack(Items.EMERALD, (int) U.param(h, "emeralds", 4, 32)));
        return totems;
    }

    // ------------------------------------------------------------------ chat: polite (chatterbox lives in Odd)

    private static final Map<UUID, Long> POLITE_CD = U.playerMap();

    @SubscribeEvent
    static void onChat(ServerChatEvent e) {
        ServerPlayer p = e.getPlayer();
        String text = e.getRawText().toLowerCase(Locale.ROOT);
        com.habits.perk.Later.run(p.server, 0, () -> {
            if (!U.ok(p)) return;
            U.fire(p, "chat");
            Odd.chatter(p);
            if (text.contains("please") || text.contains("thank")) {
                U.fire(p, "polite_chat");
                polite(p);
            }
        });
    }

    static void polite(Player p) {
        HabitsApi.PerkHit h = U.hit(p, POLITE);
        if (h == null) return;
        long now = p.level().getGameTime();
        if (now < POLITE_CD.getOrDefault(p.getUUID(), 0L)) return;
        POLITE_CD.put(p.getUUID(), now + (long) (U.param(h, "cooldown", 30, 30) * 20));
        float heal = (float) U.param(h, "heal", 2, 8);
        var box = p.getBoundingBox().inflate(h.value());
        for (TamableAnimal pet : p.level().getEntitiesOfClass(TamableAnimal.class, box, t -> t.isOwnedBy(p))) pet.heal(heal);
        boolean every = h.level() >= U.param(h, "all_villagers_from", 10, 10);
        long day = p.level().getDayTime() / 24000;
        List<Villager> vs = p.level().getEntitiesOfClass(Villager.class, box, v -> !v.isBaby());
        vs.sort((a, b) -> Double.compare(a.distanceToSqr(p), b.distanceToSqr(p)));
        String[] gifts = U.str(h, "gifts", "minecraft:bread,minecraft:apple,minecraft:cookie").split(",");
        String key = "habits_gift_" + p.getUUID();
        for (Villager v : vs) {
            if (v.getPersistentData().getLong(key) == day + 1) continue; // stored as day + 1 so 0 means never
            v.getPersistentData().putLong(key, day + 1);
            BuiltInRegistries.ITEM.getOptional(ResourceLocation.tryParse(gifts[p.getRandom().nextInt(gifts.length)].trim()))
                    .ifPresent(it -> v.spawnAtLocation(new ItemStack(it)));
            if (!every) break;
        }
    }
}
