package com.habits.progression;

import com.habits.core.HabitsApi;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Read API for the book UI (both sides; the player's own data is synced to their client). Mutations go through
 * {@link ProgPayloads} from the client ({@link Actions} on the server).
 * <pre>
 * List&lt;SigilData&gt; sockets(Player, ResourceLocation skill)  // one entry per open socket, null = empty
 * int socketCount(int level)                                  // sockets open at a card level (config 1/4/7/10)
 * boolean fits(SigilType, SkillDef)                           // can this sigil type go on this card (grey out if not)
 * int charmSlots(Player)                                      // unlocked charm slots (1 + owned/25, max 5; config)
 * List&lt;CharmData&gt; equippedCharms(Player)                     // one entry per unlocked slot, null = empty
 * double perkMultiplier / cooldownMultiplier / rangeMultiplier / dropMultiplier(Player, SkillDef)
 *                                                             // effective factors for display (1 = no change)
 * &#64;Nullable SigilData sigilOf(ItemStack) / CharmData charmOf(ItemStack)  // what an inventory stack is
 * &#64;Nullable SigilData sigil(Player, ResourceLocation skill, int socket)  // one socket, no list (per frame)
 * int socketLevel(int socket)                                 // card level that opens a socket, -1 = never
 * int nextCharmSlotAt(int ownedSkills)                        // owned skills for the next charm slot, -1 = all open
 * SkillDef boosted(Player, SkillDef)                          // the skill as this player's perks see it
 * int sigilCount(Player, SigilType, Rarity) / (Player, SigilData)  // sigils of that kind kept in the book
 * List&lt;SigilData&gt; storedSigils(Player)                      // kinds with a count above 0, by type then rarity
 * &#64;Nullable SigilData rerollCost(Player, Rarity charm)          // the stored sigil a reroll would spend, null = none
 * List&lt;CharmData&gt; storedCharms(Player)                      // charms kept in the book (the bag), newest first
 * </pre>
 * Sigil text: {@code sigil.habits.<type>} name, {@code sigil.habits.<type>.effect} effect line; charm lines:
 * {@link CharmData#lineText}.
 */
public final class ProgressionApi {
    private ProgressionApi() {}

    /** The server's slot numbers on a client (from {@link ProgPayloads.Limits} at login); null before the first login. */
    static volatile ProgPayloads.Limits serverLimits;

    /** Socket and charm slot numbers: the server's on the client side, the local config on the server side. */
    static ProgPayloads.Limits limits() {
        ProgPayloads.Limits s = serverLimits;
        return s != null && net.neoforged.fml.util.thread.EffectiveSide.get().isClient() ? s : ProgPayloads.Limits.local();
    }

    public static List<SigilData> sockets(Player p, ResourceLocation skill) {
        int n = socketCount(HabitsApi.level(p, skill));
        ProgressionData d = Effects.data(p);
        List<SigilData> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(d.sigil(skill, i));
        return out;
    }

    /** Sigils of one kind it takes to combine into one of the next rarity. */
    public static int sigilCombineCount() {
        return limits().sigilCombine();
    }

    public static int socketCount(int level) {
        return Math.min(4, PMath.socketCount(level, limits().socketLevels()));
    }

    public static boolean fits(SigilType type, SkillDef def) {
        return type.fits(def);
    }

    public static int charmSlots(Player p) {
        int owned = 0;
        for (SkillProgress s : HabitsApi.collection(p).skills().values()) if (s.level() > 0) owned++;
        ProgPayloads.Limits l = limits();
        return Math.min(5, PMath.charmSlots(owned, l.charmBase(), l.charmPer(), l.charmMax()));
    }

    public static List<CharmData> equippedCharms(Player p) {
        int n = charmSlots(p);
        ProgressionData d = Effects.data(p);
        List<CharmData> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(d.charm(i));
        return out;
    }

    public static double perkMultiplier(Player p, SkillDef def) {
        return Effects.perkMultiplier(p, def);
    }

    public static double cooldownMultiplier(Player p, SkillDef def) {
        return Effects.cooldownMultiplier(p, def);
    }

    public static double rangeMultiplier(Player p, SkillDef def) {
        return Effects.rangeMultiplier(p, def);
    }

    public static double dropMultiplier(Player p, SkillDef def) {
        return Effects.dropMultiplier(p, def);
    }

    @Nullable
    public static SigilData sigilOf(net.minecraft.world.item.ItemStack s) {
        return SigilItem.dataOf(s);
    }

    @Nullable
    public static CharmData charmOf(net.minecraft.world.item.ItemStack s) {
        return CharmItem.dataOf(s);
    }

    // book: per-frame reads for the binder (no list per call) and the numbers it shows next to them

    @Nullable
    public static SigilData sigil(Player p, ResourceLocation skill, int socket) {
        return Effects.data(p).sigil(skill, socket);
    }

    /** Card level that opens socket {@code socket} (0..3): the socket-th lowest configured level; -1 when it never opens. */
    public static int socketLevel(int socket) {
        List<Integer> l = limits().socketLevels();
        if (socket < 0 || socket >= Math.min(4, l.size())) return -1;
        for (Number t : l) {
            int below = 0, same = 0;
            for (Number u : l) { if (u.intValue() < t.intValue()) below++; else if (u.intValue() == t.intValue()) same++; }
            if (socket >= below && socket < below + same) return t.intValue();
        }
        return -1;
    }

    /** Owned skills needed for the next charm slot; -1 when every slot is already open. */
    public static int nextCharmSlotAt(int ownedSkills) {
        ProgPayloads.Limits lim = limits();
        int base = lim.charmBase(), per = lim.charmPer();
        int max = Math.min(5, lim.charmMax());
        int slots = Math.min(5, PMath.charmSlots(ownedSkills, base, per, max));
        return slots >= max ? -1 : (slots - base + 1) * per;
    }

    /** The skill with this player's sigils and charms applied (the def itself when nothing changes it). */
    public static SkillDef boosted(Player p, SkillDef def) {
        return Effects.def(p, def);
    }

    // the book's sigil store: sigils never sit in an inventory

    public static int sigilCount(Player p, SigilType type, com.habits.core.Rarity rarity) {
        return sigilCount(p, SigilData.of(type, rarity));
    }

    /** Same, no allocation (per frame: iterate {@link SigilData#ALL}). */
    public static int sigilCount(Player p, SigilData kind) {
        return Effects.data(p).stored(kind);
    }

    public static List<SigilData> storedSigils(Player p) {
        ProgressionData d = Effects.data(p);
        List<SigilData> out = new ArrayList<>();
        for (SigilData s : SigilData.ALL) if (d.stored(s) > 0) out.add(s);
        return out;
    }

    @Nullable
    public static SigilData rerollCost(Player p, com.habits.core.Rarity charm) {
        return Effects.data(p).cheapest(charm);
    }

    /** Charms kept in the book, newest first (the synced list itself: no copy, safe per frame). Bag indices are what
     * {@link ProgPayloads.EquipCharm}, {@link ProgPayloads.RerollCharm} and {@link ProgPayloads.SalvageCharm} take. */
    public static List<CharmData> storedCharms(Player p) {
        return Effects.data(p).bag();
    }

    /** Every socketed sigil back into the book's store (core's one-time level reset). */
    public static void refundSockets(net.minecraft.server.level.ServerPlayer p) {
        ProgressionData d = Effects.data(p);
        if (d.sockets().isEmpty()) return;
        for (var e : d.sockets().values()) for (ProgressionData.Socketed s : e) d = d.withStored(s.sigil(), 1);
        p.setData(ProgRegistries.DATA, new ProgressionData(java.util.Map.of(), d.charms(), d.stored(), d.bag()));
        Effects.changed(p);
    }

    /** Sends the sigils socketed in the matching skills back to the pouch; other sockets stay. */
    public static void refundSockets(net.minecraft.server.level.ServerPlayer p, java.util.function.Predicate<net.minecraft.resources.ResourceLocation> which) {
        ProgressionData d = Effects.data(p);
        java.util.Map<net.minecraft.resources.ResourceLocation, java.util.List<ProgressionData.Socketed>> keep = new java.util.HashMap<>();
        boolean any = false;
        for (var e : d.sockets().entrySet()) {
            if (!which.test(e.getKey())) { keep.put(e.getKey(), e.getValue()); continue; }
            for (ProgressionData.Socketed s : e.getValue()) d = d.withStored(s.sigil(), 1);
            any = true;
        }
        if (!any) return;
        p.setData(ProgRegistries.DATA, new ProgressionData(keep, d.charms(), d.stored(), d.bag()));
        Effects.changed(p);
    }

    /** Core changed the player's specializations: charm stats and boosted defs follow the new picks. */
    public static void specsChanged(net.minecraft.server.level.ServerPlayer p) {
        Actions.convertOffSpec(p); // charms outside the picks become charms of the picks
        Effects.changed(p);
    }
}
