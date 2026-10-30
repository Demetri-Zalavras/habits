package com.habits.core;

import com.habits.Habits;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.MapMaker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The public API. TRIGGERS call only {@link #onTrigger}; PERKS call only the read methods ({@link #level},
 * {@link #perkActive}, {@link #perkValue}, {@link #perkValues}, {@link #mechanicValue}, {@link #mechanicHit}); client code may call the read methods too (the
 * collection is synced to its own player). Mutations live in core.
 */
public final class HabitsApi {
    private HabitsApi() {}

    /** One owned, enabled skill contributing to a perk type. */
    public record PerkHit(SkillDef skill, int level, double value) {}

    // ---------------------------------------------------------------- triggers -> core

    /**
     * Something happened. Core finds skills of the player's specializations with this trigger type whose
     * {@link TriggerSpec} matches {@code ctx}: a skill not yet owned unlocks at Lv1 (guaranteed), an owned one gains XP
     * ({@link SkillXp}). XP per action = the skill's {@code chance} (per unit for {@link TriggerType#perUnit}) times
     * {@link Leveling#XP_PER_CARD}, the rarity's {@code core.xpRate}, {@code core.xpMultiplier} and the Lucky sigil /
     * charm XP boosts; the Wild Card skill can double it. Server thread only. Returns how many skills unlocked or levelled.
     */
    public static int onTrigger(ServerPlayer player, TriggerType type, TriggerContext ctx) {
        List<SkillDef> skills = SkillRegistry.candidates(type, ctx.target());
        if (skills.isEmpty() || !canAct(player)) return 0;
        if (player instanceof net.neoforged.neoforge.common.util.FakePlayer && !SkillXp.TEST_PLAYERS.contains(player.getUUID())) {
            com.habits.progression.Drops.onCardMiss(player); // machines never learn skills; Drops has its own fake-player rule
            return 0;
        }
        HabitsCollection col = collection(player);
        double mult = CoreInit.DROP_RATE.get();
        int changed = 0;
        boolean doubled = false, rolledWild = false;
        for (int i = 0; i < skills.size(); i++) {
            SkillDef def = skills.get(i);
            TriggerSpec spec = def.trigger();
            // cheap gates first: tag matching is the expensive part
            if (ctx.amount() < spec.min() || def.title().isPresent() || !Specs.allows(player, def) || col.get(def.id()).maxed()
                    || !matches(spec, ctx, player)) continue;
            double xp = spec.chance() * (type.perUnit ? ctx.amount() : 1) * Leveling.XP_PER_CARD * CoreInit.at(CoreInit.XP_RATE, def.rarity())
                    * mult * com.habits.progression.Effects.dropMultiplier(player, def); // progression: Lucky sigils, charm XP lines
            if (!rolledWild) { rolledWild = true; doubled = wildDouble(player); }
            if (SkillXp.onAction(player, def, doubled ? xp * 2 : xp)) changed++;
        }
        if (changed == 0) com.habits.progression.Drops.onCardMiss(player); // progression: an action that levels nothing may give a sigil
        return changed;
    }

    /** Feed row and sound for an unlock or a level-up of {@code def} (state before: {@code before}); a Legendary one also
     * fires CUSTOM {@code habits:legendary_card}. Returns true when it unlocked or levelled. */
    static boolean notifyGain(ServerPlayer player, SkillDef def, SkillProgress before) {
        SkillProgress now = collection(player).get(def.id());
        boolean wasNew = before.level() == 0 && now.level() > 0, leveled = now.level() > before.level() && !wasNew;
        Payloads.CardAdded added = new Payloads.CardAdded(def.id(), now.level(), now.progress(), now.needed(def.rarity()), wasNew, leveled);
        PacketDistributor.sendToPlayer(player, new Payloads.CardGained(added));
        if (leveled && player.getRandom().nextDouble() < CoreInit.CLASS_BOOK_LEVEL_CHANCE.get()) ClassBookItem.grant(player);
        if (wasNew || leveled) player.playNotifySound(leveled ? SOUND_LEVEL_UP : SOUND_ADD, SoundSource.PLAYERS,
                CoreInit.AUTO_ADD_VOLUME.get().floatValue(), 1);
        if ((wasNew || leveled) && def.rarity() == Rarity.LEGENDARY && !inLegendaryEvent) {
            inLegendaryEvent = true; // never from inside this event (a Legendary skill levelled by habits:legendary_card would loop)
            try {
                com.habits.trigger.Triggers.fire(player, TriggerType.CUSTOM, TriggerContext.of(LEGENDARY_CARD));
            } finally {
                inLegendaryEvent = false;
            }
        }
        return wasNew || leveled;
    }

    /** Wild Card skill: its value is the % chance this action's XP is doubled. */
    static boolean wildDouble(ServerPlayer player) {
        PerkHit h = mechanicHit(player, WILD_CARD);
        return h != null && player.getRandom().nextDouble() * 100 < h.value();
    }

    static boolean matches(TriggerSpec spec, TriggerContext ctx, ServerPlayer player) {
        var access = player.registryAccess();
        return (spec.match().isEmpty() || spec.match().get().test(ctx.target(), spec.type().targetKind, access))
                && (spec.item().isEmpty() || spec.item().get().test(ctx.item(), TargetKind.ITEM, access))
                && (spec.entity().isEmpty() || spec.entity().get().test(ctx.entity(), TargetKind.ENTITY, access));
    }

    /** Server thread only. */
    private static boolean inLegendaryEvent;

    /** One card's worth of XP for {@code def} (unlocks it when not owned), with the feed row; {@code pos} is unused since
     * cards stopped dropping. False when maxed. Commands, previews and tests. */
    public static boolean giveCard(ServerPlayer player, SkillDef def, @Nullable Vec3 pos) {
        SkillProgress before = collection(player).get(def.id());
        if (!addCard(player, def.id())) return false;
        notifyGain(player, def, before);
        return true;
    }

    /** CUSTOM event fired after any Legendary skill unlocks or levels up (the Wild Card skill's trigger). */
    public static final ResourceLocation LEGENDARY_CARD = Habits.id("legendary_card");
    /** The Wild Card skill's mechanic: % chance an action's skill XP is doubled. */
    public static final ResourceLocation WILD_CARD = Habits.id("wild_card");

    /** The book's "Auto-add drops" toggle. Fake players (machines, tests) count as off until it is set on them: nobody
     * sees their feed. */
    public static boolean autoAdd(Player player) {
        return player.hasData(CoreRegistries.AUTO_ADD) ? player.getData(CoreRegistries.AUTO_ADD)
                : !(player instanceof net.neoforged.neoforge.common.util.FakePlayer);
    }

    static boolean skipMaxed(Player player, SkillDef def) {
        return collection(player).get(def.id()).maxed();
    }

    /** {@link Payloads.SetAutoAdd}: the book toggle was clicked. */
    public static void setAutoAdd(ServerPlayer player, boolean on) {
        if (canAct(player)) player.setData(CoreRegistries.AUTO_ADD, on);
    }

    /** Shows a sigil gain in the player's feed ({@code +count Rarity Name}, {@code icon} drawn small). For PROGRESSION. */
    public static void feedSigil(ServerPlayer player, ItemStack icon, Rarity rarity, Component name, int count) {
        PacketDistributor.sendToPlayer(player, new Payloads.FeedSigil(icon.copyWithCount(1), rarity, name, count));
    }

    /** Gives a won card. {@code pos} is ignored (cards never drop where the action was, design "Playtest changes"); kept
     * so callers passing {@link TriggerContext#pos()} still compile. Puts the card straight into the inventory with the
     * vanilla pickup pop, never into an empty selected hotbar slot (the hand the player may be punching with). Only when
     * no other slot has room does it drop at the player's feet; returns that item entity,
     * or null when the card went into the inventory. */
    @Nullable
    public static ItemEntity dropCard(ServerPlayer player, SkillDef def, @Nullable Vec3 pos) {
        return giveStack(player, CardItems.create(def));
    }

    /** {@link #dropCard} for any stack. */
    @Nullable
    static ItemEntity giveStack(ServerPlayer player, ItemStack card) {
        Inventory inv = player.getInventory();
        int slot = inv.getSlotWithRemainingSpace(card); // a matching card stack first; an empty slot never matches
        for (int i = 0; slot == -1 && i < inv.items.size(); i++) if (i != inv.selected && inv.items.get(i).isEmpty()) slot = i;
        if (slot != -1 && inv.add(slot, card)) {
            var r = player.getRandom();
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
                    0.2f, ((r.nextFloat() - r.nextFloat()) * 0.7f + 1) * 2); // vanilla pickup pitch
            player.containerMenu.broadcastChanges();
            return null;
        }
        ItemEntity e = new ItemEntity(player.serverLevel(), player.getX(), player.getY(), player.getZ(), card);
        e.setNoPickUpDelay();
        player.serverLevel().addFreshEntity(e);
        return e;
    }

    /** Alive and not a spectator: every core action is gated on this. */
    static boolean canAct(Player player) {
        return player.isAlive() && !player.isSpectator();
    }

    // ---------------------------------------------------------------- reads (both sides)

    public static HabitsCollection collection(Player player) {
        // a ServerPlayer under construction (spawn search runs collision hooks) has no connection yet: creating the
        // attachment would sync through a null connection and fail the login with "Invalid player data"
        if (!connected(player)) return player.getExistingDataOrNull(CoreRegistries.COLLECTION) instanceof HabitsCollection c ? c : HabitsCollection.EMPTY;
        return player.getData(CoreRegistries.COLLECTION);
    }

    /** False only for a server player still being built (no connection yet); attachments must not be created then. */
    public static boolean connected(Player player) {
        return !(player instanceof ServerPlayer sp) || sp.connection != null;
    }

    /** Owned level of a skill, 0 when not owned. */
    public static int level(Player player, ResourceLocation skill) {
        return collection(player).get(skill).level();
    }

    /** Owned (level 1+) and toggled on. */
    public static boolean perkActive(Player player, ResourceLocation skill) {
        SkillProgress p = collection(player).get(skill);
        SkillDef def = SkillRegistry.get(skill);
        return p.level() > 0 && p.enabled() && (def == null || Specs.allows(player, def));
    }

    /** Sum of the perk values of every active skill with this perk type, ignoring params. Use {@link #perkValues} for
     * perks with filters or a condition (block, entity, damage, attribute, condition...). */
    public static double perkValue(Player player, PerkType type) {
        return index(player).sum[type.ordinal()];
    }

    /** Every active skill with this perk type, with its value at the owned level. Match params (and the condition) yourself.
     * Read-only (cached per collection). */
    public static List<PerkHit> perkValues(Player player, PerkType type) {
        return index(player).byType[type.ordinal()];
    }

    /** Value of a custom mechanic for this player: the BEST (highest) value among active skills using it, 0 if none.
     * Mechanics do not stack across skills. */
    public static double mechanicValue(Player player, ResourceLocation mechanic) {
        PerkHit h = mechanicHit(player, mechanic);
        return h == null ? 0 : h.value();
    }

    /** The best active skill using this mechanic (for its params and level), or null. */
    @Nullable
    public static PerkHit mechanicHit(Player player, ResourceLocation mechanic) {
        return index(player).best.get(mechanic);
    }

    /** Every read above is answered from this, built once per collection instance (collections are immutable; a change
     * is a new instance) and per skill reload. Perk reads run per tick and per event, so they must not scan the
     * collection (614 skills when maxed) or allocate. Weak identity keys: an index dies with its collection. */
    static final class Index {
        final int generation;
        final List<PerkHit>[] byType;
        final double[] sum;
        /** Best hit per mechanic: first skill in collection order wins ties (same rule as the old scans). */
        final Map<ResourceLocation, PerkHit> best;
        /** Registered mechanics with their best hit, in {@link #best}'s iteration order, for {@link PerkMechanics#tick}. */
        final PerkMechanic[] tickers;
        final PerkHit[] tickHits;

        @SuppressWarnings("unchecked")
        Index(HabitsCollection c, int generation, Player player) {
            this.generation = generation;
            int n = PerkType.values().length;
            List<PerkHit>[] lists = new List[n];
            sum = new double[n];
            best = new HashMap<>();
            c.skills().forEach((id, p) -> {
                if (p.level() <= 0 || !p.enabled()) return;
                // progression: the def as this player's sigils and charms boost it (registry def when none)
                SkillDef def = com.habits.progression.Effects.def(player, SkillRegistry.get(id));
                if (def == null || !Specs.allows(player, def)) return;
                PerkSpec perk = def.perk();
                PerkHit hit = new PerkHit(def, p.level(), def.perkValue(p.level()));
                if (perk.type().isPresent()) {
                    int t = perk.type().get().ordinal();
                    (lists[t] == null ? lists[t] = new ArrayList<>() : lists[t]).add(hit);
                } else if (perk.mechanic().isPresent()) {
                    best.merge(perk.mechanic().get(), hit, (a, b) -> a.value() >= b.value() ? a : b);
                }
            });
            byType = new List[n];
            for (int t = 0; t < n; t++) {
                byType[t] = lists[t] == null ? List.of() : List.copyOf(lists[t]);
                for (PerkHit h : byType[t]) sum[t] += h.value();
            }
            List<PerkMechanic> ms = new ArrayList<>();
            List<PerkHit> hs = new ArrayList<>();
            best.forEach((mid, hit) -> {
                PerkMechanic m = PerkMechanics.get(mid);
                if (m != null) { ms.add(m); hs.add(hit); }
            });
            tickers = ms.toArray(PerkMechanic[]::new);
            tickHits = hs.toArray(PerkHit[]::new);
        }
    }

    private static final Map<HabitsCollection, Index> INDEX = new MapMaker().weakKeys().makeMap();

    static Index index(Player player) {
        HabitsCollection c = collection(player);
        int gen = SkillRegistry.generation();
        Index i = INDEX.get(c);
        if (i == null || i.generation != gen) {
            i = new Index(c, gen, player);
            INDEX.put(c, i);
        }
        return i;
    }

    /** Progression changed this player's sockets or charms: re-set the collection as a fresh (equal) instance so the
     * perk read index here and every perk package snapshot keyed on the collection instance rebuild, on both sides
     * (setData syncs). Nothing to rebuild for an empty collection. */
    public static void refreshPerks(ServerPlayer player) {
        HabitsCollection c = collection(player);
        if (!c.skills().isEmpty()) player.setData(CoreRegistries.COLLECTION, new HabitsCollection(c.skills()));
    }

    /** Unlocked but not yet hovered in the binder (the "NEW" badge). Both sides (synced to its own player). */
    public static boolean isUnseen(Player player, ResourceLocation skill) {
        return player.getData(CoreRegistries.UNSEEN).contains(skill);
    }

    /** The most recently unlocked unseen skill (the binder opens on its page), or null. */
    @Nullable
    public static ResourceLocation newestUnseen(Player player) {
        List<ResourceLocation> l = player.getData(CoreRegistries.UNSEEN);
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    // ---------------------------------------------------------------- mutations (core only, server thread)

    /** Add one card of {@code skill} to the collection. Returns false (nothing changes) if maxed or unknown. No book
     * check, no item consumed: the raw operation for commands, tests and the item paths below. */
    public static boolean addCard(ServerPlayer player, ResourceLocation skill) {
        SkillDef def = SkillRegistry.get(skill);
        if (def == null) return false;
        HabitsCollection c = collection(player);
        SkillProgress before = c.get(skill);
        SkillProgress next = Leveling.addCard(before, def.rarity());
        if (next == null) return false;
        player.setData(CoreRegistries.COLLECTION, c.with(skill, next));
        if (before.level() == 0) markUnseen(player, skill);
        return true;
    }

    /** A skill was just unlocked: it shows "NEW" in the binder until {@link #markSeen}. */
    static void markUnseen(ServerPlayer player, ResourceLocation skill) {
        List<ResourceLocation> l = new ArrayList<>(player.getData(CoreRegistries.UNSEEN));
        l.remove(skill);
        l.add(skill); // newest last
        player.setData(CoreRegistries.UNSEEN, List.copyOf(l));
    }

    /** {@link Payloads.MarkSeen}: the player hovered the card. Unknown or already-seen ids change nothing. */
    public static void markSeen(ServerPlayer player, ResourceLocation skill) {
        List<ResourceLocation> l = player.getData(CoreRegistries.UNSEEN);
        if (!l.contains(skill)) return;
        List<ResourceLocation> next = new ArrayList<>(l);
        next.remove(skill);
        player.setData(CoreRegistries.UNSEEN, List.copyOf(next));
    }

    /** Right-click path: needs the book in inventory; consumes the card in {@code hand}, or says "Maxed". */
    public static void addCardInHand(ServerPlayer player, InteractionHand hand) {
        consumeCard(player, player.getItemInHand(hand));
    }

    /** Binder click path ({@link Payloads.TogglePerk}): flips enabled on an owned skill. */
    public static void togglePerk(ServerPlayer player, ResourceLocation skill) {
        if (!canAct(player)) return;
        HabitsCollection c = collection(player);
        SkillProgress p = c.get(skill);
        if (p.level() <= 0) return;
        player.setData(CoreRegistries.COLLECTION, c.with(skill, new SkillProgress(p.level(), p.progress(), !p.enabled())));
    }

    // sounds are defined in the core sounds part (vanilla sound files); unregistered events travel as direct holders
    static final SoundEvent SOUND_ADD = SoundEvent.createVariableRangeEvent(Habits.id("core.card_add"));
    static final SoundEvent SOUND_LEVEL_UP = SoundEvent.createVariableRangeEvent(Habits.id("core.card_level_up"));
    static final SoundEvent SOUND_REFUSE = SoundEvent.createVariableRangeEvent(Habits.id("core.card_refuse"));

    /** Shared item path: validates the stack is a known card, the book is carried and the skill is not maxed, then
     * shrinks the stack by one and sends {@link Payloads.CardAdded}. Returns that payload, or null when refused. */
    @Nullable
    static Payloads.CardAdded consumeCard(ServerPlayer player, ItemStack stack) {
        SkillDef def = CardItems.skillOf(stack);
        if (def == null || !canAct(player)) return null;
        return spend(player, stack, def);
    }

    /** {@link Payloads.UseWildCard}: spends a Wild Card from either hand as one card of {@code skill}, which must be
     * owned and below Lv10 (same book check, messages and payload as a right-click add). Returns true when it did. */
    public static boolean useWildCard(ServerPlayer player, ResourceLocation skill) {
        if (!canAct(player)) return false;
        ItemStack wild = player.getMainHandItem().is(CoreRegistries.WILD_CARD.get()) ? player.getMainHandItem()
                : player.getOffhandItem().is(CoreRegistries.WILD_CARD.get()) ? player.getOffhandItem() : null;
        SkillDef def = SkillRegistry.get(skill);
        if (wild == null || def == null) return false;
        if (level(player, skill) <= 0) {
            refuse(player, Component.translatable("habits.msg.wild_unowned"));
            return false;
        }
        return spend(player, wild, def) != null;
    }

    /** Book check, add, shrink the stack by one, payload, action bar and sound; null (with a refusal) when it can't. */
    @Nullable
    private static Payloads.CardAdded spend(ServerPlayer player, ItemStack stack, SkillDef def) {
        if (!Specs.allows(player, def)) {
            refuse(player, Component.translatable("habits.msg.off_spec", def.category().displayName()));
            return null;
        }
        if (!player.getInventory().contains(s -> s.is(CoreRegistries.BOOK.get()))) {
            refuse(player, Component.translatable("habits.msg.need_book"));
            return null;
        }
        Payloads.CardAdded added = add(player, def);
        if (added == null) {
            refuse(player, Component.translatable("habits.msg.maxed"));
            return null;
        }
        stack.shrink(1);
        PacketDistributor.sendToPlayer(player, added);
        Component msg = actionBar(added, def);
        if (msg != null) { // a new skill gets the FX flip (text and sting) instead
            player.displayClientMessage(msg, true);
            player.playNotifySound(added.leveledUp() ? SOUND_LEVEL_UP : SOUND_ADD, SoundSource.PLAYERS, 1, 1);
        }
        return added;
    }

    /** {@link #addCard} plus what it did, or null when maxed or unknown. Sends nothing. */
    @Nullable
    private static Payloads.CardAdded add(ServerPlayer player, SkillDef def) {
        int before = level(player, def.id());
        if (!addCard(player, def.id())) return null;
        SkillProgress now = collection(player).get(def.id());
        return new Payloads.CardAdded(def.id(), now.level(), now.progress(), now.needed(def.rarity()), before == 0,
                now.level() > before && before > 0);
    }

    /** The action-bar line after an add, or null for a brand-new skill (the FX flip shows that one; they overlapped).
     * Null also means no add sound. */
    @Nullable
    static Component actionBar(Payloads.CardAdded a, SkillDef def) {
        if (a.wasNew()) return null;
        return a.leveledUp()
                ? Component.translatable("habits.msg.level_up", def.displayName(), a.level())
                : Component.translatable("habits.msg.added", def.displayName(), a.level(), a.progress(), a.needed());
    }

    private static void refuse(ServerPlayer player, Component msg) {
        player.displayClientMessage(msg, true);
        player.playNotifySound(SOUND_REFUSE, SoundSource.PLAYERS, 0.7f, 1);
    }
}
