package com.habits.progression;

import com.habits.Habits;
import com.habits.core.Category;
import com.habits.core.HabitsApi;
import com.habits.core.Rarity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where sigils and charms come from: missed card rolls (sigils: a small chance per miss plus a minimum gap), hostile mob
 * kills, loot chests ({@link ChestLoot}) and bosses (a guaranteed Epic, Legendary or, rarely, Mythical charm). Sigils go straight into the
 * book's store ({@link #grant}); sigil items (chest loot, /give) are absorbed on pickup and by a once a second inventory
 * sweep. Charms go into the book's bag the same way ({@link #grantCharm}); charm items (chest loot, older saves) are
 * absorbed like sigil items. A sigil or charm item on the ground glows in its rarity color (a scoreboard team per rarity sets the outline color;
 * the entity leaves the team when it leaves the level).
 */
public final class Drops {
    private Drops() {}

    /** Game time of each player's last sigil from a missed roll. Cleared on logout and server stop. */
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    /** Fake players allowed to roll sigils on misses (GameTests only; other packages' tests stay sigil-free). */
    public static final Set<UUID> TEST_PLAYERS = ConcurrentHashMap.newKeySet();
    /** GameTests: overrides the miss chance when 0..1 (setting the config value from a test races other tests' reads). */
    public static volatile double testMissChance = -1;

    static final SoundEvent CHIME = SoundEvent.createVariableRangeEvent(Habits.id("progression.sigil_drop"));

    static void init(IEventBus bus) {
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> LAST.remove(e.getEntity().getUUID()));
        bus.addListener((ServerStoppedEvent e) -> { LAST.clear(); TEST_PLAYERS.clear(); testMissChance = -1; });
        bus.addListener(Drops::onDrops);
        bus.addListener(Drops::onJoin);
        bus.addListener(Drops::onLeave);
        bus.addListener(Drops::onPickup);
        bus.addListener(Drops::onTick);
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> { if (absorbs(e.getEntity())) absorbInventory((ServerPlayer) e.getEntity()); });
    }

    /** Core calls this after a trigger roll that won no card. */
    /** Tests: pretend the player has been idle this long (ms); -1 = real idle time. */
    static long testIdleMs = -1;

    public static void onCardMiss(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || (p.isFakePlayer() && !TEST_PLAYERS.contains(p.getUUID()))) return;
        // vanilla's idle timer: any move, look, swing, click or chat resets it, so AFK triggers never pay sigils
        long idle = testIdleMs >= 0 ? testIdleMs : net.minecraft.Util.getMillis() - p.getLastActionTime();
        if (idle > ProgressionInit.MISS_IDLE_SECONDS.get() * 1000L) return;
        long now = p.level().getGameTime();
        Long last = LAST.get(p.getUUID());
        if (last != null && now - last < ProgressionInit.MISS_GAP_SECONDS.get() * 20L && now >= last) return;
        double chance = testMissChance >= 0 ? testMissChance : ProgressionInit.MISS_CHANCE.get();
        if (p.getRandom().nextDouble() >= chance) return;
        LAST.put(p.getUUID(), now);
        grant(p, rollSigil(p.getRandom(), ProgressionInit.SIGIL_WEIGHTS), 1);
    }

    /** Puts {@code n} sigils straight into the book's store (never the inventory or the ground): the chime and a
     * "+1 Rare Potent" row in the gain feed. False (nothing given) when the player is dead or spectating. */
    public static boolean grant(ServerPlayer p, SigilData s, int n) {
        if (n <= 0 || !p.isAlive() || p.isSpectator()) return false;
        p.setData(ProgRegistries.DATA, Effects.data(p).withStored(s, n));
        chime(p, s.rarity());
        HabitsApi.feedSigil(p, SigilItem.create(s), s.rarity(),
                Component.translatable("sigil.habits." + s.type().getSerializedName()), n); // left gain feed row
        return true;
    }

    /** Puts a charm straight into the book's bag (front of the list): the chime and a "+1 Rare Mining Charm" feed row.
     * A charm outside the player's specializations arrives as one of them. False (nothing given) when the player is dead or spectating. */
    public static boolean grantCharm(ServerPlayer p, CharmData c) {
        if (!p.isAlive() || p.isSpectator()) return false;
        if (!com.habits.core.Specs.allows(p, c.category())) c = Actions.convert(p, c); // chest loot, older items, trades
        p.setData(ProgRegistries.DATA, Effects.data(p).withBagged(c));
        chime(p, c.rarity());
        HabitsApi.feedSigil(p, CharmItem.create(c), c.rarity(), Component.translatable("charm.habits.feed", c.category().displayName()), 1);
        return true;
    }

    /** Fake players other than test ones (deployers, quarries) keep sigil items as items. */
    private static boolean absorbs(Player p) {
        return p instanceof ServerPlayer && (!(p instanceof FakePlayer) || TEST_PLAYERS.contains(p.getUUID()));
    }

    /** A sigil or charm item touched on the ground goes into the book instead of the inventory. */
    static void onPickup(ItemEntityPickupEvent.Pre e) {
        ItemEntity item = e.getItemEntity();
        SigilData s = SigilItem.dataOf(item.getItem());
        CharmData c = CharmItem.dataOf(item.getItem());
        if (s == null && c == null || !absorbs(e.getPlayer())) return;
        ServerPlayer p = (ServerPlayer) e.getPlayer();
        e.setCanPickup(TriState.FALSE);
        if (item.hasPickUpDelay() || (item.getTarget() != null && !item.getTarget().equals(p.getUUID()))) return;
        int n = item.getItem().getCount();
        if (s != null ? !grant(p, s, n) : !grantCharm(p, c)) return; // charms stack to 1
        p.take(item, n); // the pickup animation
        item.discard();
    }

    /** Sigil and charm items that reached the inventory anyway (taken from a chest, /give, older saves) move into the
     * book. Only the player's own slots: items inside shulker boxes and bundles stay. */
    static void absorbInventory(ServerPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            SigilData s = SigilItem.dataOf(st);
            CharmData c = CharmItem.dataOf(st);
            if (s != null && grant(p, s, st.getCount())) inv.setItem(i, ItemStack.EMPTY);
            else if (c != null && grantCharm(p, c)) inv.setItem(i, ItemStack.EMPTY);
        }
    }

    private static void onTick(PlayerTickEvent.Post e) {
        if (e.getEntity().tickCount % 20 == 0 && absorbs(e.getEntity())) absorbInventory((ServerPlayer) e.getEntity());
    }

    static void chime(ServerPlayer p, Rarity r) {
        float pitch = (float) (ProgressionInit.CHIME_PITCH.get() + ProgressionInit.CHIME_PITCH_STEP.get() * r.ordinal());
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), CHIME, SoundSource.PLAYERS,
                ProgressionInit.CHIME_VOLUME.get().floatValue(), pitch);
    }

    static Rarity rollRarity(RandomSource r, net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<java.util.List<? extends Number>> weights) {
        return Rarity.values()[Math.min(Rarity.values().length - 1, PMath.pickWeighted(weights.get(), r.nextDouble()))];
    }

    static SigilData rollSigil(RandomSource r, net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<java.util.List<? extends Number>> weights) {
        return new SigilData(SigilType.values()[r.nextInt(SigilType.values().length)], rollRarity(r, weights));
    }

    /** A charm in one of the player's specializations (any category when none, e.g. chest loot). */
    static CharmData rollCharm(@Nullable Player p, RandomSource r, Rarity rarity) {
        var offered = com.habits.core.Specs.offered();
        Category c = p == null ? offered.get(r.nextInt(offered.size())) : com.habits.core.Specs.randomCategory(p, r);
        return CharmData.roll(c, rarity, r);
    }

    // ------------------------------------------------------------------ mobs and bosses

    static boolean isBoss(LivingEntity e) {
        return e.getType().is(Tags.EntityTypes.BOSSES) || e.getMaxHealth() >= ProgressionInit.BOSS_HEALTH.get();
    }

    private static void onDrops(LivingDropsEvent e) {
        LivingEntity dead = e.getEntity();
        if (!(e.getSource().getEntity() instanceof ServerPlayer killer) || !(dead.level() instanceof ServerLevel level)) return;
        if (killer.isFakePlayer() && !TEST_PLAYERS.contains(killer.getUUID())) return;
        RandomSource r = killer.getRandom();
        if (isBoss(dead)) {
            double roll = r.nextDouble();
            Rarity rarity = roll < ProgressionInit.BOSS_MYTHICAL_CHANCE.get() ? Rarity.MYTHICAL
                    : roll < ProgressionInit.BOSS_MYTHICAL_CHANCE.get() + ProgressionInit.BOSS_LEGENDARY_CHANCE.get() ? Rarity.LEGENDARY : Rarity.EPIC;
            grantCharm(killer, rollCharm(killer, r, rarity));
            if (r.nextDouble() < com.habits.core.CoreInit.CLASS_BOOK_BOSS_CHANCE.get()) {
                ItemStack book = com.habits.core.ClassBookItem.roll(level.getServer(), r);
                if (book != null) e.getDrops().add(new ItemEntity(level, dead.getX(), dead.getY(), dead.getZ(), book));
            }
            if (r.nextDouble() < com.habits.core.CoreInit.RESPEC_BOSS_CHANCE.get())
                e.getDrops().add(new ItemEntity(level, dead.getX(), dead.getY(), dead.getZ(), new ItemStack(com.habits.core.CoreRegistries.RESPEC_SCROLL.get())));
            return;
        }
        if (!(dead instanceof Enemy)) return;
        if (r.nextDouble() < ProgressionInit.MOB_SIGIL_CHANCE.get()) grant(killer, rollSigil(r, ProgressionInit.LOOT_WEIGHTS), 1);
        if (r.nextDouble() < ProgressionInit.MOB_CHARM_CHANCE.get())
            grantCharm(killer, rollCharm(killer, r, rollRarity(r, ProgressionInit.LOOT_WEIGHTS)));
    }

    // ------------------------------------------------------------------ glow

    @Nullable
    static Rarity rarityOf(ItemStack s) {
        SigilData sd = SigilItem.dataOf(s);
        if (sd != null) return sd.rarity();
        CharmData cd = CharmItem.dataOf(s);
        return cd == null ? null : cd.rarity();
    }

    private static ChatFormatting color(Rarity r) {
        return switch (r) {
            case COMMON -> ChatFormatting.GRAY;
            case UNCOMMON -> ChatFormatting.GREEN;
            case RARE -> ChatFormatting.AQUA;
            case EPIC -> ChatFormatting.LIGHT_PURPLE;
            case LEGENDARY -> ChatFormatting.GOLD;
            case MYTHICAL -> ChatFormatting.DARK_RED;
        };
    }

    private static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof ItemEntity item)) return;
        Rarity r = rarityOf(item.getItem());
        if (r == null) return;
        Scoreboard sb = e.getLevel().getScoreboard();
        String name = "habits_" + r.getSerializedName();
        PlayerTeam team = sb.getPlayerTeam(name);
        if (team == null) {
            team = sb.addPlayerTeam(name);
            team.setColor(color(r));
        }
        sb.addPlayerToTeam(item.getStringUUID(), team);
        item.setGlowingTag(true);
    }

    private static void onLeave(EntityLeaveLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof ItemEntity item)) return;
        Scoreboard sb = e.getLevel().getScoreboard();
        PlayerTeam team = sb.getPlayersTeam(item.getStringUUID());
        if (team != null && team.getName().startsWith("habits_")) sb.removePlayerFromTeam(item.getStringUUID(), team);
    }
}
