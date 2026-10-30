package com.habits.perk.utility;

import com.habits.core.HabitsApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.brewing.PotionBrewEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Brewing stand and cauldron perks. */
final class Brewing {
    private Brewing() {}

    static final ResourceLocation BREWER = U.mech("brewer", "% longer potion effects");
    static final ResourceLocation DOUBLE = U.mech("double_batch", "% chance a brew fills an empty bottle slot");
    static final ResourceLocation OVERPROOF = U.mech("overproof", "% chance a brew is one level stronger");
    static final ResourceLocation BLAZE = U.mech("blaze_saver", "brews per blaze powder");
    static final ResourceLocation LADLE = U.mech("witchs_ladle", "bottles from one potion");
    static final ResourceLocation WASHER = U.mech("washer", "seconds per bad effect washed off");

    static final String TAG_LONG = "habits_brewer";
    static final String TAG_STRONG = "habits_overproof";

    static void init() {}

    // ------------------------------------------------------------------ brews at stands the player used

    @SubscribeEvent
    static void onBrewed(PotionBrewEvent.Post e) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (Player p : server.getPlayerList().getPlayers()) brewedFor(p, e);
        for (Player p : TEST) brewedFor(p, e);
    }

    static final List<Player> TEST = new ArrayList<>();

    static void brewedFor(Player p, PotionBrewEvent.Post e) {
        if (!U.ok(p)) return;
        for (BrewingStandBlockEntity stand : Stations.used(p, BrewingStandBlockEntity.class)) {
            if (stand.getItem(0) != e.getItem(0) && stand.getItem(1) != e.getItem(1) && stand.getItem(2) != e.getItem(2)) continue;
            applyBrew(p, e);
            return;
        }
    }

    /** Mark each brewed potion (applied when drunk), and maybe fill an empty bottle slot. Slots 0..2 are the bottles. */
    static void applyBrew(Player p, PotionBrewEvent e) {
        HabitsApi.PerkHit lon = U.hit(p, BREWER), str = U.hit(p, OVERPROOF), dbl = U.hit(p, DOUBLE);
        ItemStack sample = ItemStack.EMPTY;
        for (int i = 0; i < 3; i++) {
            ItemStack s = e.getItem(i);
            PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
            if (s.isEmpty() || pc == null || !pc.hasEffects()) continue;
            boolean strong = str != null && U.roll(p, str.value());
            if (lon != null || strong) {
                CustomData.update(DataComponents.CUSTOM_DATA, s, t -> {
                    if (lon != null) t.putInt(TAG_LONG, (int) lon.value());
                    if (strong) t.putBoolean(TAG_STRONG, true);
                });
                List<Component> lines = new ArrayList<>();
                Style st = Style.EMPTY.withItalic(false).withColor(0x9FD8FF);
                if (lon != null) lines.add(Component.translatable("habits.utility.brewer_lore", (int) lon.value()).setStyle(st));
                if (strong) lines.add(Component.translatable("habits.utility.overproof_lore").setStyle(st));
                s.set(DataComponents.LORE, new ItemLore(lines));
            }
            if (sample.isEmpty()) sample = s;
        }
        if (dbl != null && !sample.isEmpty() && U.roll(p, dbl.value())) {
            for (int i = 0; i < 3; i++) {
                if (e.getItem(i).isEmpty()) {
                    e.setItem(i, sample.copy());
                    break;
                }
            }
        }
    }

    @SubscribeEvent
    static void onDrink(LivingEntityUseItemEvent.Finish e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide) return;
        ItemStack used = e.getItem();
        CustomData cd = used.get(DataComponents.CUSTOM_DATA);
        PotionContents pc = used.get(DataComponents.POTION_CONTENTS);
        if (cd == null || pc == null || !used.is(Items.POTION)) return;
        int longer = cd.copyTag().getInt(TAG_LONG);
        boolean strong = cd.contains(TAG_STRONG);
        if (longer <= 0 && !strong) return;
        for (MobEffectInstance base : pc.getAllEffects()) {
            if (base.getEffect().value().isInstantenous()) continue;
            MobEffectInstance cur = p.getEffect(base.getEffect());
            if (cur == null) continue;
            int dur = (int) (cur.getDuration() * (1 + longer / 100.0));
            int amp = cur.getAmplifier() + (strong ? 1 : 0);
            p.removeEffect(base.getEffect());
            p.addEffect(new MobEffectInstance(base.getEffect(), dur, amp, cur.isAmbient(), cur.isVisible(), cur.showIcon()));
        }
    }

    // ------------------------------------------------------------------ slow tick: blaze saver, washer

    private static final Map<UUID, Map<Long, Integer>> LAST_FUEL = U.playerMap();
    private static final Map<UUID, Integer> WASH = U.playerMap();

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post e) {
        Player p = e.getEntity();
        if (p.tickCount % 20 != 0 || !U.ok(p)) return;
        HabitsApi.PerkHit h;
        if ((h = U.hit(p, BLAZE)) != null) blaze(p, h.value());
        if ((h = U.hit(p, WASHER)) != null) wash(p, h.value(), 20);
    }

    /** Each brew costs one fuel point; refund it with chance 1 - 20/value so a powder lasts {@code value} brews on average. */
    static void blaze(Player p, double brews) {
        Map<Long, Integer> last = LAST_FUEL.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
        for (BrewingStandBlockEntity stand : Stations.used(p, BrewingStandBlockEntity.class)) {
            ContainerData d = Furnaces.brewData(stand);
            if (d == null) continue;
            long key = stand.getBlockPos().asLong();
            int fuel = d.get(1);
            Integer was = last.put(key, fuel);
            if (was != null && fuel == was - 1 && p.getRandom().nextDouble() < 1 - 20 / Math.max(20, brews)) {
                d.set(1, fuel + 1);
                last.put(key, fuel + 1);
            }
        }
        if (last.size() > Stations.CAP * 2) last.clear();
    }

    static boolean wash(Player p, double seconds, int elapsed) {
        boolean wet = p.level().getBlockState(p.blockPosition()).is(Blocks.WATER_CAULDRON) || p.level().isRainingAt(p.blockPosition());
        if (!wet) return false;
        int t = WASH.merge(p.getUUID(), elapsed, Integer::sum);
        if (t < seconds * 20) return false;
        WASH.put(p.getUUID(), 0);
        for (MobEffectInstance m : List.copyOf(p.getActiveEffects())) {
            if (m.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) {
                p.removeEffect(m.getEffect());
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ witch's ladle: potion cauldrons

    @SubscribeEvent
    static void onCauldron(PlayerInteractEvent.RightClickBlock e) {
        Player p = e.getEntity();
        if (!(p.level() instanceof ServerLevel level) || !U.ok(p)) return;
        BlockPos pos = e.getPos();
        if (!level.getBlockState(pos).is(Blocks.WATER_CAULDRON)) return;
        ItemStack held = e.getItemStack();
        Ladles data = Ladles.get(level);
        Ladles.Entry entry = data.map.get(pos.asLong());
        if (entry != null && held.is(Items.GLASS_BOTTLE)) {
            e.setCanceled(true);
            held.consume(1, p);
            U.give(p, entry.potion.copy());
            if (--entry.left <= 0) data.map.remove(pos.asLong());
            data.setDirty();
            level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1, 1);
            return;
        }
        HabitsApi.PerkHit h = U.hit(p, LADLE);
        PotionContents pc = held.get(DataComponents.POTION_CONTENTS);
        if (h == null || entry != null || !p.isShiftKeyDown() || !held.is(Items.POTION) || pc == null || pc.is(Potions.WATER)) return;
        e.setCanceled(true);
        Ladles.Entry n = new Ladles.Entry(held.copyWithCount(1), (int) h.value());
        held.shrink(1);
        U.give(p, new ItemStack(Items.GLASS_BOTTLE));
        data.map.put(pos.asLong(), n);
        data.setDirty();
        level.playSound(null, pos, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 1, 1.2f);
    }

    /** Potion cauldrons per level, saved with the world. Entries are dropped once the cauldron is no longer water. */
    static final class Ladles extends SavedData {
        static final class Entry {
            final ItemStack potion;
            int left;

            Entry(ItemStack potion, int left) {
                this.potion = potion;
                this.left = left;
            }
        }

        final Map<Long, Entry> map = new HashMap<>();

        static Ladles get(ServerLevel level) {
            Ladles l = level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Ladles::new, Ladles::load), "habits_utility_ladles");
            l.map.entrySet().removeIf(en -> level.isLoaded(BlockPos.of(en.getKey())) && !level.getBlockState(BlockPos.of(en.getKey())).is(Blocks.WATER_CAULDRON));
            return l;
        }

        static Ladles load(CompoundTag tag, HolderLookup.Provider reg) {
            Ladles l = new Ladles();
            ListTag list = tag.getList("e", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                ItemStack s = ItemStack.parseOptional(reg, c.getCompound("item"));
                if (!s.isEmpty()) l.map.put(c.getLong("pos"), new Entry(s, c.getInt("left")));
            }
            return l;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
            ListTag list = new ListTag();
            map.forEach((pos, en) -> {
                CompoundTag c = new CompoundTag();
                c.putLong("pos", pos);
                c.putInt("left", en.left);
                c.put("item", en.potion.save(reg));
                list.add(c);
            });
            tag.put("e", list);
            return tag;
        }
    }
}
