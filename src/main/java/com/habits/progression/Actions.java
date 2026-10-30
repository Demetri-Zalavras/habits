package com.habits.progression;

import com.habits.core.Category;
import com.habits.core.HabitsApi;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/** Server-side socket and charm actions behind {@link ProgPayloads}. Everything is re-validated here; each returns true
 * when it changed something. Gated on alive + not spectator. */
public final class Actions {
    private Actions() {}

    static boolean canAct(ServerPlayer p) {
        return p.isAlive() && !p.isSpectator();
    }

    private static void refuse(ServerPlayer p, String key) {
        p.displayClientMessage(Component.translatable("progression.habits.msg." + key), true);
    }

    /** Put a stored sigil of that kind into socket {@code socket} of an owned card. A sigil already there goes back to
     * the store (swapping is free). */
    public static boolean socket(ServerPlayer p, ResourceLocation skill, int socket, SigilData sigil) {
        if (!canAct(p)) return false;
        SkillDef def = SkillRegistry.get(skill);
        if (def == null || sigil == null) return false;
        ProgressionData d = Effects.data(p);
        if (d.stored(sigil) <= 0) { refuse(p, "no_sigil"); return false; }
        if (socket < 0 || socket >= ProgressionApi.socketCount(HabitsApi.level(p, skill))) { refuse(p, "socket_locked"); return false; }
        if (!sigil.type().fits(def)) { refuse(p, "wrong_type"); return false; }
        SigilData old = d.sigil(skill, socket);
        d = d.withStored(sigil, -1).withSigil(skill, socket, sigil);
        if (old != null) d = d.withStored(old, 1);
        p.setData(ProgRegistries.DATA, d);
        Effects.changed(p);
        return true;
    }

    /** Take the sigil out of a socket, back to the store. */
    public static boolean unsocket(ServerPlayer p, ResourceLocation skill, int socket) {
        if (!canAct(p)) return false;
        ProgressionData d = Effects.data(p);
        SigilData old = d.sigil(skill, socket);
        if (old == null) return false;
        p.setData(ProgRegistries.DATA, d.withSigil(skill, socket, null).withStored(old, 1));
        Effects.changed(p);
        return true;
    }

    /** Equip stored charm {@code index} (the book's bag) into charm slot {@code charmSlot} (must be unlocked). A charm
     * already there goes back to the bag. */
    public static boolean equipCharm(ServerPlayer p, int charmSlot, int index) {
        if (!canAct(p)) return false;
        ProgressionData d = Effects.data(p);
        if (index < 0 || index >= d.bag().size()) return false;
        if (charmSlot < 0 || charmSlot >= ProgressionApi.charmSlots(p)) { refuse(p, "charm_slot_locked"); return false; }
        CharmData charm = d.bag().get(index), old = d.charm(charmSlot);
        d = d.withoutBagged(index).withCharm(charmSlot, charm);
        if (old != null) d = d.withBagged(old);
        p.setData(ProgRegistries.DATA, d);
        Effects.changed(p);
        return true;
    }

    /** Take the charm out of a charm slot, back to the bag. */
    public static boolean unequipCharm(ServerPlayer p, int charmSlot) {
        if (!canAct(p)) return false;
        ProgressionData d = Effects.data(p);
        CharmData old = d.charm(charmSlot);
        if (old == null) return false;
        p.setData(ProgRegistries.DATA, d.withCharm(charmSlot, null).withBagged(old));
        Effects.changed(p);
        return true;
    }

    /** Reroll line {@code line} of a charm (equipped in charm slot {@code index} when {@code equipped}, else stored charm
     * {@code index} in the bag) by spending one stored sigil of the charm's rarity or higher ({@link ProgressionData#cheapest}). */
    public static boolean rerollCharmLine(ServerPlayer p, boolean equipped, int index, int line) {
        if (!canAct(p)) return false;
        ProgressionData d = Effects.data(p);
        CharmData charm = equipped ? d.charm(index) : index >= 0 && index < d.bag().size() ? d.bag().get(index) : null;
        if (charm == null || line < 0 || line >= charm.lines().size()) return false;
        SigilData cost = d.cheapest(charm.rarity());
        if (cost == null) { refuse(p, "reroll_too_weak"); return false; }
        d = d.withStored(cost, -1);
        CharmData next = charm.reroll(line, p.getRandom());
        if (equipped) d = d.withCharm(index, next);
        else {
            java.util.List<CharmData> bag = new java.util.ArrayList<>(d.bag());
            bag.set(index, next); // keeps its place in the list
            d = new ProgressionData(d.sockets(), d.charms(), d.stored(), bag);
        }
        p.setData(ProgRegistries.DATA, d);
        if (equipped) Effects.changed(p);
        return true;
    }

    /** The rarity after {@code r}; null for Legendary (the top rank never combines). */
    @Nullable
    public static Rarity next(Rarity r) {
        return r.ordinal() + 1 < Rarity.values().length ? Rarity.values()[r.ordinal() + 1] : null;
    }

    /** {@code sigilCombineCount} stored sigils of one kind become one of the same type at the next rarity. Free. */
    public static boolean combineSigils(ServerPlayer p, SigilData kind) {
        if (!canAct(p) || kind == null) return false;
        Rarity up = next(kind.rarity());
        if (up == null) { refuse(p, "combine_top"); return false; }
        ProgressionData d = Effects.data(p);
        int need = ProgressionInit.SIGIL_COMBINE_COUNT.get();
        if (d.stored(kind) < need) { refuse(p, "combine_need_two"); return false; }
        p.setData(ProgRegistries.DATA, d.withStored(kind, -need));
        return Drops.grant(p, new SigilData(kind.type(), up), 1);
    }

    /** Stored charm {@code index} (bag index) breaks down into one sigil of a random type at the charm's rarity. Equipped
     * charms are not in the bag, so never salvage. */
    public static boolean salvageCharm(ServerPlayer p, int index) {
        if (!canAct(p)) return false;
        ProgressionData d = Effects.data(p);
        if (index < 0 || index >= d.bag().size()) return false;
        Rarity r = d.bag().get(index).rarity();
        p.setData(ProgRegistries.DATA, d.withoutBagged(index));
        return Drops.grant(p, new SigilData(SigilType.values()[p.getRandom().nextInt(SigilType.values().length)], r), 1);
    }

    /** Every charm (bag and equipped) outside the player's specializations is rerolled into one of them at the same
     * rarity. Nothing to do before the first pick. Returns true when any changed. */
    static boolean convertOffSpec(ServerPlayer p) {
        if (com.habits.core.Specs.of(p).picks().isEmpty()) return false;
        ProgressionData d = Effects.data(p);
        boolean changed = false;
        java.util.List<CharmData> bag = new java.util.ArrayList<>(d.bag());
        for (int i = 0; i < bag.size(); i++)
            if (!com.habits.core.Specs.allows(p, bag.get(i).category())) { bag.set(i, convert(p, bag.get(i))); changed = true; }
        d = new ProgressionData(d.sockets(), d.charms(), d.stored(), bag);
        for (ProgressionData.Equipped e : d.charms())
            if (!com.habits.core.Specs.allows(p, e.charm().category())) { d = d.withCharm(e.slot(), convert(p, e.charm())); changed = true; }
        if (changed) p.setData(ProgRegistries.DATA, d);
        return changed;
    }

    static CharmData convert(net.minecraft.world.entity.player.Player p, CharmData c) {
        return CharmData.roll(com.habits.core.Specs.randomCategory(p, p.getRandom()), c.rarity(), p.getRandom());
    }
}
