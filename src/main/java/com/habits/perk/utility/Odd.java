package com.habits.perk.utility;

import com.habits.core.HabitsApi;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.SuspiciousEffectHolder;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSpawnPhantomsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Odd perks: standing still, chat, sleep, death, meta (cards), books, cakes, maps, flower pots, fletching. */
final class Odd {
    private Odd() {}

    static final ResourceLocation STATUE = U.mech("statue", "seconds still before mobs lose you");
    static final ResourceLocation CHATTER = U.mech("chatterbox", "seconds of speed after chatting");
    static final ResourceLocation INSOMNIAC = U.mech("insomniac", "% fewer phantoms");
    static final ResourceLocation DARWIN = U.mech("darwin_award", "seconds immune to what killed you");
    static final ResourceLocation LUCKY = U.mech("lucky_charm", "% more card drops");
    static final ResourceLocation COLLECTOR = U.mech("collector", "max health per maxed skill");
    static final ResourceLocation PAPERWORK = U.mech("paperwork", "seconds between recalls");
    static final ResourceLocation CAKE = U.mech("cake_day", "seconds of party buff");
    static final ResourceLocation FLOWER = U.mech("flower_pot", "flower pots that bloom each morning");
    static final ResourceLocation FLETCHER = U.mech("fletchers_eye", "tipped arrows per potion");
    static final ResourceLocation CARTOGRAPHER = U.mech("cartographer", "map marker radius in blocks");
    static final ResourceLocation STICKY = Mobs.STICKY;

    static void init() {}

    // ------------------------------------------------------------------ statue

    private static final Map<UUID, double[]> STILL = U.playerMap(); // x, y, z, yaw, pitch, seconds still

    static int stillSeconds(Player p) {
        double[] s = STILL.get(p.getUUID());
        return s == null ? 0 : (int) s[5];
    }

    /** Called every second for every player. */
    static void stillTick(Player p) {
        double[] s = STILL.computeIfAbsent(p.getUUID(), k -> new double[6]);
        boolean same = s[0] == p.getX() && s[1] == p.getY() && s[2] == p.getZ() && s[3] == p.getYRot() && s[4] == p.getXRot();
        s[0] = p.getX(); s[1] = p.getY(); s[2] = p.getZ(); s[3] = p.getYRot(); s[4] = p.getXRot();
        s[5] = same ? s[5] + 1 : 0;
        if (same && s[5] > 0 && s[5] % 60 == 0) U.fire(p, "stood_still_minute");
        HabitsApi.PerkHit h = U.hit(p, STATUE);
        if (h == null || s[5] < h.value()) return;
        U.effect(p, MobEffects.REGENERATION, (int) U.param(h, "regen_amp", 0, 1), 60);
        if (s[5] == Math.ceil(h.value())) {
            for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(24), m -> m.getTarget() == p)) {
                if (U.roll(p, U.param(h, "lose", 20, 100))) m.setTarget(null);
            }
        }
    }

    /** Hostiles fail to pick a statue-still player as a target. */
    static boolean hidden(Player p, net.minecraft.world.entity.LivingEntity mob) {
        if (!(mob instanceof Enemy)) return false;
        HabitsApi.PerkHit h = U.hit(p, STATUE);
        return h != null && stillSeconds(p) >= h.value() && U.roll(p, U.param(h, "lose", 20, 100));
    }

    // ------------------------------------------------------------------ chatterbox (called from the chat listener)

    private static final Map<UUID, Long> CHAT_CD = U.playerMap();

    static boolean chatter(Player p) {
        HabitsApi.PerkHit h = U.hit(p, CHATTER);
        if (h == null) return false;
        long now = p.level().getGameTime();
        if (now < CHAT_CD.getOrDefault(p.getUUID(), 0L)) return false;
        CHAT_CD.put(p.getUUID(), now + (long) (U.param(h, "cooldown", 60, 30) * 20));
        int dur = (int) (h.value() * 20);
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, dur, (int) U.param(h, "amp", 0, 1)));
        if (h.level() >= U.param(h, "haste_from", 10, 10)) p.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, dur, 1));
        return true;
    }

    // ------------------------------------------------------------------ insomniac

    @SubscribeEvent
    static void onPhantoms(PlayerSpawnPhantomsEvent e) {
        Player p = e.getEntity();
        HabitsApi.PerkHit h = U.hit(p, INSOMNIAC);
        if (h != null && U.roll(p, h.value())) e.setResult(PlayerSpawnPhantomsEvent.Result.DENY);
    }

    private static final Map<UUID, Boolean> SLEEPLESS = U.playerMap();

    static void insomniacTick(ServerPlayer p) {
        int awake = p.getStats().getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_REST));
        if (awake >= 72000) {
            if (SLEEPLESS.putIfAbsent(p.getUUID(), true) == null) U.fire(p, "three_sleepless_days");
        } else SLEEPLESS.remove(p.getUUID());
        HabitsApi.PerkHit h = U.hit(p, INSOMNIAC);
        if (h != null && h.level() >= U.param(h, "night_vision_from", 10, 10) && p.level().isNight() && p.level().dimensionType().hasSkyLight()) {
            U.effect(p, MobEffects.NIGHT_VISION, 0, 300);
        }
    }

    // ------------------------------------------------------------------ darwin award

    private record Immunity(ResourceLocation type, long until) {}

    private static final Map<UUID, ResourceLocation> DIED_OF = U.playerMap();
    private static final Map<UUID, Boolean> NEW_DEATH = U.playerMap();
    private static final Map<UUID, Immunity> IMMUNE = U.playerMap();

    @SubscribeEvent
    static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.isSpectator() || !U.TEST_PLAYERS.contains(p.getUUID()) && p.isFakePlayer()) return;
        var key = e.getSource().typeHolder().unwrapKey();
        if (key.isEmpty() || key.get().location().getPath().equals("generic_kill")) return;
        ResourceLocation type = key.get().location();
        CompoundTag root = Mobs.persisted(p);
        ListTag seen = root.getList("habits_death_types", Tag.TAG_STRING);
        boolean fresh = true;
        for (int i = 0; i < seen.size(); i++) if (seen.getString(i).equals(type.toString())) fresh = false;
        if (fresh) {
            seen.add(StringTag.valueOf(type.toString()));
            root.put("habits_death_types", seen);
            NEW_DEATH.put(p.getUUID(), true);
        }
        DIED_OF.put(p.getUUID(), type);
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        Player p = e.getEntity();
        if (e.isEndConquered() || !U.ok(p)) return;
        if (NEW_DEATH.remove(p.getUUID()) != null) U.fire(p, "new_death_type");
        ResourceLocation type = DIED_OF.remove(p.getUUID());
        HabitsApi.PerkHit h = U.hit(p, DARWIN);
        if (type != null && h != null) IMMUNE.put(p.getUUID(), new Immunity(type, p.level().getGameTime() + (long) (h.value() * 20)));
    }

    static boolean immune(Player p, DamageSource src) {
        Immunity im = IMMUNE.get(p.getUUID());
        if (im == null) return false;
        if (p.level().getGameTime() >= im.until) { IMMUNE.remove(p.getUUID()); return false; }
        return src.typeHolder().unwrapKey().map(k -> k.location().equals(im.type)).orElse(false);
    }

    static void setImmune(Player p, ResourceLocation type, long until) {
        IMMUNE.put(p.getUUID(), new Immunity(type, until));
    }

    // ------------------------------------------------------------------ collector: milestones and health

    private static final ResourceLocation COLLECTOR_HP = com.habits.Habits.id("utility_collector");

    static void collectorTick(Player p) {
        int owned = 0, maxed = 0;
        for (var en : HabitsApi.collection(p).skills().values()) {
            if (en.level() > 0) owned++;
            if (en.level() >= 10) maxed++;
        }
        CompoundTag root = Mobs.persisted(p);
        int done = root.getInt("habits_collector_ms");
        int reached = owned / 50 * 50;
        if (reached > done && reached <= 150) {
            root.putInt("habits_collector_ms", reached);
            U.fire(p, "skill_milestone");
        }
        HabitsApi.PerkHit h = U.hit(p, COLLECTOR);
        double hp = h == null ? 0 : Math.min(U.param(h, "cap", 40, 40), h.value() * maxed);
        U.attr(p, Attributes.MAX_HEALTH, COLLECTOR_HP, hp, AttributeModifier.Operation.ADD_VALUE);
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    // ------------------------------------------------------------------ paperwork: recall books

    private record Recall(String where, double x, double y, double z, long at) {}

    private static final Map<UUID, Long> RECALL_CD = U.playerMap();
    private static final Map<UUID, Recall> CHANNEL = U.playerMap();

    @SubscribeEvent
    static void onUseItem(PlayerInteractEvent.RightClickItem e) {
        Player p = e.getEntity();
        ItemStack s = e.getItemStack();
        var book = s.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (book == null || !(p instanceof ServerPlayer sp)) return;
        HabitsApi.PerkHit h = U.hit(p, PAPERWORK);
        if (h == null) return;
        String title = book.title().raw().trim().toLowerCase(Locale.ROOT);
        boolean extra = h.level() >= U.param(h, "more_titles_from", 10, 10);
        if (!title.equals("home") && !(extra && (title.equals("spawn") || title.equals("death")))) return;
        e.setCanceled(true);
        long now = p.level().getGameTime();
        if (now < RECALL_CD.getOrDefault(p.getUUID(), 0L)) {
            p.displayClientMessage(Component.translatable("habits.utility.cooldown"), true);
            return;
        }
        RECALL_CD.put(p.getUUID(), now + (long) (h.value() * 20));
        if (h.level() < U.param(h, "keep_from", 5, 5)) s.shrink(1);
        long channel = (long) (U.param(h, "channel", 3, 0) * 20);
        if (channel <= 0) recall(sp, title);
        else {
            CHANNEL.put(p.getUUID(), new Recall(title, p.getX(), p.getY(), p.getZ(), now + channel));
            p.displayClientMessage(Component.translatable("habits.utility.recall_channel"), true);
        }
    }

    static void channelTick(ServerPlayer p) {
        Recall r = CHANNEL.get(p.getUUID());
        if (r == null) return;
        if (p.distanceToSqr(r.x, r.y, r.z) > 1) {
            CHANNEL.remove(p.getUUID());
            p.displayClientMessage(Component.translatable("habits.utility.recall_broken"), true);
        } else if (p.level().getGameTime() >= r.at) {
            CHANNEL.remove(p.getUUID());
            recall(p, r.where);
        }
    }

    static boolean recall(ServerPlayer p, String where) {
        ServerLevel level;
        double x, y, z;
        if (where.equals("death")) {
            var g = p.getLastDeathLocation();
            if (g.isEmpty() || (level = p.server.getLevel(g.get().dimension())) == null) return false;
            BlockPos b = g.get().pos();
            x = b.getX() + 0.5; y = b.getY(); z = b.getZ() + 0.5;
        } else if (where.equals("spawn")) {
            level = p.server.overworld();
            BlockPos b = level.getSharedSpawnPos();
            x = b.getX() + 0.5; y = b.getY(); z = b.getZ() + 0.5;
        } else {
            var t = p.findRespawnPositionAndUseSpawnBlock(false, net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING);
            level = t.newLevel();
            x = t.pos().x; y = t.pos().y; z = t.pos().z;
        }
        p.teleportTo(level, x, y, z, Set.of(), p.getYRot(), p.getXRot());
        level.playSound(null, p.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8f, 1.2f);
        return true;
    }

    // ------------------------------------------------------------------ cake day

    private static final Map<UUID, GlobalPos> LAST_CAKE = U.playerMap();
    /** Cakes already regrown once. Cleared on server stop; pruned when large. */
    static final Set<GlobalPos> REGROWN = ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    static void onClickBlock(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (!U.ok(p)) return;
        var state = p.level().getBlockState(e.getPos());
        if (state.getBlock() instanceof CakeBlock) LAST_CAKE.put(p.getUUID(), GlobalPos.of(p.level().dimension(), e.getPos().immutable()));
        else if (state.is(Blocks.FLETCHING_TABLE) && e.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
            HabitsApi.PerkHit h = U.hit(p, FLETCHER);
            if (h != null && fletch(p, (int) h.value()) > 0) e.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onStat(StatAwardEvent e) {
        if (e.getStat() != Stats.CUSTOM.get(Stats.EAT_CAKE_SLICE) || !(e.getEntity() instanceof ServerPlayer eater)) return;
        GlobalPos g = LAST_CAKE.get(eater.getUUID());
        if (g == null || g.dimension() != eater.level().dimension()) return;
        ServerPlayer owner = cakeOwner(eater, g);
        if (owner != null) party(owner, eater, g.pos());
    }

    @Nullable
    static ServerPlayer cakeOwner(ServerPlayer eater, GlobalPos g) {
        for (ServerPlayer p : eater.server.getPlayerList().getPlayers()) {
            if (U.hit(p, CAKE) != null && Stations.hasPlaced(p, g)) return p;
        }
        return null;
    }

    static void party(Player owner, Player eater, BlockPos cake) {
        HabitsApi.PerkHit h = U.hit(owner, CAKE);
        if (h == null) return;
        int dur = (int) (h.value() * 20);
        boolean big = h.level() >= U.param(h, "big_from", 10, 10);
        eater.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, dur, big ? 1 : 0));
        if (big) {
            eater.addEffect(new MobEffectInstance(MobEffects.JUMP, dur, 1));
            eater.addEffect(new MobEffectInstance(MobEffects.LUCK, dur, 0));
        }
        Level level = eater.level();
        ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
        rocket.set(DataComponents.FIREWORKS, new Fireworks(0, List.of(new FireworkExplosion(FireworkExplosion.Shape.SMALL_BALL,
                IntList.of(0xFF5FA2, 0xFFD34E, 0x5FE0FF), IntList.of(), false, true))));
        level.addFreshEntity(new FireworkRocketEntity(level, cake.getX() + 0.5, cake.getY() + 1.2, cake.getZ() + 0.5, rocket));
        // the last slice: grow the cake back once (it is removed right after this stat)
        var state = level.getBlockState(cake);
        GlobalPos g = GlobalPos.of(level.dimension(), cake);
        if (big && state.getBlock() instanceof CakeBlock && state.getValue(CakeBlock.BITES) == CakeBlock.MAX_BITES && REGROWN.add(g)
                && level.getServer() != null) {
            com.habits.perk.Later.run(level.getServer(), 0, () -> {
                if (level.getBlockState(cake).isAir()) level.setBlockAndUpdate(cake, Blocks.CAKE.defaultBlockState());
            });
            if (REGROWN.size() > 1024) REGROWN.clear();
        }
    }

    // ------------------------------------------------------------------ flower pots bloom while you sleep

    @SubscribeEvent
    static void onWake(PlayerWakeUpEvent e) {
        Player p = e.getEntity();
        if (e.wakeImmediately() || p.level().isClientSide) return;
        HabitsApi.PerkHit h = U.hit(p, FLOWER);
        if (h != null) bloom(p, h);
    }

    /** Up to {@code value} potted flowers within 6 blocks each drop a flower and give their stew effect. One 13x7x13 read per sleep. */
    static int bloom(Player p, HabitsApi.PerkHit h) {
        int max = (int) h.value(), n = 0;
        int dur = (int) (U.param(h, "seconds", 10, 300) * 20);
        BlockPos c = p.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(c.offset(-6, -3, -6), c.offset(6, 3, 6))) {
            if (n >= max) break;
            if (!p.level().isLoaded(b) || !(p.level().getBlockState(b).getBlock() instanceof FlowerPotBlock pot)) continue;
            var plant = pot.getPotted();
            if (plant == Blocks.AIR) continue;
            n++;
            U.give(p, new ItemStack(plant.asItem()));
            SuspiciousEffectHolder holder = SuspiciousEffectHolder.tryGet(plant.asItem());
            if (holder != null) for (var eff : holder.getSuspiciousEffects().effects()) p.addEffect(new MobEffectInstance(eff.effect(), dur, 0));
        }
        return n;
    }

    // ------------------------------------------------------------------ fletcher's eye: potion + arrows at a fletching table

    static int fletch(Player p, int perPotion) {
        ItemStack potion = p.getMainHandItem();
        PotionContents pc = potion.get(DataComponents.POTION_CONTENTS);
        if (pc == null || pc.is(Potions.WATER) || !pc.hasEffects() || !(potion.is(Items.POTION) || potion.is(Items.SPLASH_POTION) || potion.is(Items.LINGERING_POTION))) return 0;
        var inv = p.getInventory();
        int made = 0;
        for (int i = 0; i < inv.items.size() && made < perPotion; i++) {
            ItemStack s = inv.items.get(i);
            if (!s.is(Items.ARROW) || !s.getComponentsPatch().isEmpty()) continue;
            int k = Math.min(s.getCount(), perPotion - made);
            s.shrink(k);
            made += k;
        }
        if (made == 0) return 0;
        potion.shrink(1);
        U.give(p, new ItemStack(Items.GLASS_BOTTLE));
        ItemStack tipped = new ItemStack(Items.TIPPED_ARROW, 1);
        tipped.set(DataComponents.POTION_CONTENTS, pc);
        for (int left = made; left > 0; ) {
            int k = Math.min(64, left);
            U.give(p, tipped.copyWithCount(k));
            left -= k;
        }
        p.level().playSound(null, p.blockPosition(), SoundEvents.VILLAGER_WORK_FLETCHER, SoundSource.PLAYERS, 1, 1);
        return made;
    }

    // ------------------------------------------------------------------ cartographer: live mob markers on a held map

    private static final Map<UUID, List<String>> MARKERS = U.playerMap();

    static int mapMarkers(Player p, HabitsApi.PerkHit h) {
        ItemStack map = p.getMainHandItem().is(Items.FILLED_MAP) ? p.getMainHandItem() : p.getOffhandItem().is(Items.FILLED_MAP) ? p.getOffhandItem() : ItemStack.EMPTY;
        List<String> old = MARKERS.remove(p.getUUID());
        MapItemSavedData data = map.isEmpty() ? null : MapItem.getSavedData(map, p.level());
        if (old != null && data != null) for (String id : old) data.removeDecoration(id);
        if (data == null || data.dimension != p.level().dimension()) return 0;
        boolean all = h.level() >= U.param(h, "all_mobs_from", 5, 5);
        List<String> ids = new ArrayList<>();
        for (var m : p.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, p.getBoundingBox().inflate(h.value()),
                m -> m != p && m.isAlive() && (m instanceof Enemy || all && (m instanceof Mob || m instanceof Player pl && !pl.isSpectator())))) {
            if (ids.size() >= 24) break;
            String id = "habits_" + p.getUUID() + "_" + m.getId();
            Holder<MapDecorationType> type = m instanceof Enemy ? MapDecorationTypes.RED_MARKER : MapDecorationTypes.BLUE_MARKER;
            data.addDecoration(type, p.level(), id, m.getX(), m.getZ(), m.getYRot(), null);
            ids.add(id);
        }
        MARKERS.put(p.getUUID(), ids);
        if (h.level() >= U.param(h, "fast_fill_from", 10, 10) && map.getItem() instanceof MapItem mi) mi.update(p.level(), p, data);
        return ids.size();
    }

    // ------------------------------------------------------------------ named items (sticky_name): no despawn, back from the void

    private static final Map<UUID, List<UUID>> STICKY_ITEMS = U.playerMap();

    static void tossed(Player p, ItemEntity ie) {
        if (!ie.getItem().has(DataComponents.CUSTOM_NAME)) return;
        HabitsApi.PerkHit h = U.hit(p, STICKY);
        if (h == null) return;
        ie.setUnlimitedLifetime();
        if (h.level() >= U.param(h, "void_from", 10, 10)) {
            List<UUID> l = STICKY_ITEMS.computeIfAbsent(p.getUUID(), k -> new ArrayList<>());
            l.add(ie.getUUID());
            if (l.size() > 32) l.remove(0);
        }
    }

    static void rescueItems(Player p) {
        List<UUID> l = STICKY_ITEMS.get(p.getUUID());
        if (l == null || !(p.level() instanceof ServerLevel sl)) return;
        l.removeIf(id -> {
            if (!(sl.getEntity(id) instanceof ItemEntity ie) || !ie.isAlive()) return true;
            if (ie.getY() < sl.getMinBuildHeight() - 8) {
                ie.teleportTo(p.getX(), p.getY() + 0.5, p.getZ());
                ie.setDeltaMovement(0, 0, 0);
            }
            return false;
        });
    }

    // ------------------------------------------------------------------ ticks

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !U.ok(p)) return;
        if (p.tickCount % 5 == 0) {
            channelTick(p);
            rescueItems(p);
        }
        HabitsApi.PerkHit h;
        if (p.tickCount % 10 == 0 && (h = U.hit(p, CARTOGRAPHER)) != null) mapMarkers(p, h);
        if (p.tickCount % 20 != 0) return;
        stillTick(p);
        insomniacTick(p);
        if (p.tickCount % 100 == 0) collectorTick(p);
    }
}
