package com.habits.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's specializations ({@link CoreRegistries#SPECS}): one combat style and one profession, picked on the
 * spec screen. Only cards of those two categories drop, add or work; charms only roll in them. No picks yet (fake
 * players, a world from before specs until the screen is answered) means no lockout. With {@code core.specs.combat_specs}
 * off the pick is two different professions and combat categories are locked for everyone. {@code respec}: a Scroll of
 * Unlearning was read; the old picks stay active until the new pick, which wipes every card outside it.
 */
public record Specs(List<Category> picks, boolean respec, List<ResourceLocation> classes) {
    public static final Specs NONE = new Specs(List.of(), false);

    public static final Codec<Specs> CODEC = RecordCodecBuilder.create(i -> i.group(
            Category.CODEC.listOf().fieldOf("picks").forGetter(Specs::picks),
            Codec.BOOL.optionalFieldOf("respec", false).forGetter(Specs::respec),
            ResourceLocation.CODEC.listOf().optionalFieldOf("classes", List.of()).forGetter(Specs::classes)
    ).apply(i, Specs::new));

    public static final StreamCodec<ByteBuf, Specs> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(i -> Category.values()[Math.floorMod(i, Category.values().length)], Enum::ordinal)
                    .apply(ByteBufCodecs.list()), Specs::picks,
            ByteBufCodecs.BOOL, Specs::respec,
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), Specs::classes, Specs::new);

    public Specs {
        picks = List.copyOf(picks);
        classes = List.copyOf(classes);
    }

    /** Base classes only (no class book used). */
    public Specs(List<Category> picks, boolean respec) {
        this(picks, respec, List.of());
    }

    /** The class held on the base {@code c} (a {@code class_passive} skill), or null for the plain base. */
    @org.jetbrains.annotations.Nullable
    public SkillDef classOn(Category c) {
        for (ResourceLocation id : classes) {
            SkillDef d = SkillRegistry.get(id);
            if (d != null && d.category() == c) return d;
        }
        return null;
    }

    // ---------------------------------------------------------------- reads (both sides)

    public static Specs of(Player p) {
        if (!HabitsApi.connected(p)) return p.getExistingDataOrNull(CoreRegistries.SPECS) instanceof Specs s ? s : NONE;
        return p.getData(CoreRegistries.SPECS);
    }

    /** {@code core.specs.combat_specs}: combat styles are offered (else two professions are picked). */
    // shortcut: common config is read locally on each side (not synced), so a dedicated server and its clients need the same value; sync it if packs ever differ
    public static boolean combatSpecs() {
        return CoreInit.COMBAT_SPECS.get();
    }

    /** The categories a player can pick or roll a charm in: all, or the professions when combat specs are off. */
    public static List<Category> offered() {
        return java.util.Arrays.stream(Category.values()).filter(c -> !c.combat || combatSpecs()).toList();
    }

    /** Cards and charms of this category drop and work for the player. */
    public static boolean allows(Player p, Category c) {
        if (c.combat && !combatSpecs()) return false;
        List<Category> picks = of(p).picks;
        return picks.isEmpty() || picks.contains(c);
    }

    /** This skill drops and works for the player: its category is picked, and a class skill needs that class held
     * (a class passive is itself the class). Titles always work once earned. */
    public static boolean allows(Player p, SkillDef def) {
        if (def.title().isPresent()) return true;
        if (!allows(p, def.category())) return false;
        if (def.classPassive()) return of(p).classes.contains(def.id());
        return def.classId().isEmpty() || of(p).classes.contains(def.classId().get());
    }

    /** The spec screen should be open: never picked, a respec is pending, combat specs were turned back on after a
     * profession-only pick, or (combat specs off) only one profession is picked so far. */
    public static boolean needsPick(Player p) {
        Specs s = of(p);
        return s.picks.isEmpty() || s.respec || (combatSpecs() ? s.picks.stream().noneMatch(c -> c.combat) : s.picks.size() < 2);
    }

    /** A category the player may get a charm in: one of their picks, any when none. */
    public static Category randomCategory(Player p, RandomSource r) {
        List<Category> picks = of(p).picks;
        List<Category> from = picks.isEmpty() ? offered() : picks;
        return from.get(r.nextInt(from.size()));
    }

    // ---------------------------------------------------------------- writes (server)

    /** {@link Payloads.PickSpecs}: a combat style and a profession, only while a pick is due; with combat specs off
     * {@code combat} is the first of two different professions, and a player holding one profession must keep it (the
     * second is added). A respec pick removes every owned card outside the picks. Returns true when it took. */
    public static boolean pick(ServerPlayer p, Category combat, Category profession) {
        boolean withCombat = combatSpecs();
        if (combat.combat != withCombat || profession.combat || combat == profession || !needsPick(p)) return false;
        List<Category> picks = List.of(combat, profession);
        Specs old = of(p);
        if (!withCombat && !old.respec && !picks.containsAll(old.picks)) return false;
        boolean wipe = old.respec;
        // a class stays while its base stays picked; one left behind is released (a Legendary goes back to the world)
        List<ResourceLocation> keep = new ArrayList<>();
        for (ResourceLocation id : old.classes) {
            SkillDef d = SkillRegistry.get(id);
            if (d != null && picks.contains(d.category())) keep.add(id);
            else ClassHolders.release(p, id);
        }
        p.setData(CoreRegistries.SPECS, new Specs(picks, false, keep));
        syncTags(p);
        HabitsCollection c = HabitsApi.collection(p);
        if (wipe) {
            Map<ResourceLocation, SkillProgress> kept = new HashMap<>();
            c.skills().forEach((id, prog) -> {
                SkillDef d = SkillRegistry.get(id);
                // combat cards are kept (inactive) while combat specs are off, as dropCombatPick promises
                if (d == null || allows(p, d) || d.category().combat && !withCombat) kept.put(id, prog);
            });
            p.setData(CoreRegistries.COLLECTION, new HabitsCollection(kept));
            List<ResourceLocation> unseen = new ArrayList<>(p.getData(CoreRegistries.UNSEEN));
            unseen.retainAll(kept.keySet());
            p.setData(CoreRegistries.UNSEEN, unseen);
        } else {
            HabitsApi.refreshPerks(p); // off-spec perks switch off
        }
        com.habits.progression.ProgressionApi.specsChanged(p); // charm stats of the old picks come off
        p.displayClientMessage(Component.translatable("habits.msg.specs_picked", combat.displayName(), profession.displayName()), false);
        return true;
    }

    /** Class book: becomes {@code clazz} (a class passive skill) on its base, which must be one of the picks. Every
     * skill resets (sockets refunded, charms re-rolled into the picks) and the class passive starts at Lv1. A Legendary
     * class has one holder per world. Returns a refusal message key, or null when it took. */
    @org.jetbrains.annotations.Nullable
    public static String becomeClass(ServerPlayer p, SkillDef clazz) {
        return becomeClass(p, clazz, false);
    }

    /** As {@link #becomeClass(ServerPlayer, SkillDef)}; {@code first}: with combat specs off and two professions, the
     * book replaces the first profession instead of the second. */
    @org.jetbrains.annotations.Nullable
    public static String becomeClass(ServerPlayer p, SkillDef clazz, boolean first) {
        String refusal = classRefusal(p, clazz);
        if (refusal != null) return refusal;
        Specs s = of(p);
        if (!ClassHolders.claim(p, clazz)) return "habits.msg.class_taken";
        // a class of an unpicked base takes the place of the pick of its kind (combat style or profession)
        Category swapped = swappedBase(p, clazz, first);
        List<Category> picks = new ArrayList<>(s.picks);
        if (swapped != null) picks.set(picks.indexOf(swapped), clazz.category());
        else if (!combatSpecs() && !picks.contains(clazz.category())) picks.add(clazz.category()); // combat specs off, one profession: fills the second
        List<ResourceLocation> classes = new ArrayList<>();
        for (ResourceLocation id : s.classes) {
            SkillDef d = SkillRegistry.get(id);
            if (d != null && (d.category() == clazz.category() || d.category() == swapped)) ClassHolders.release(p, id);
            else classes.add(id);
        }
        classes.add(clazz.id());
        p.setData(CoreRegistries.SPECS, new Specs(picks, false, classes));
        syncTags(p);
        SkillXp.flush(p);
        // only the class's base (and a swapped-out pick) resets: the other pick and titles stay
        java.util.function.Predicate<ResourceLocation> wiped = id -> {
            SkillDef d = SkillRegistry.get(id);
            return d != null && (d.category() == clazz.category() || d.category() == swapped) && d.title().isEmpty();
        };
        com.habits.progression.ProgressionApi.refundSockets(p, wiped);
        java.util.Map<ResourceLocation, SkillProgress> kept = new java.util.HashMap<>(HabitsApi.collection(p).skills());
        kept.keySet().removeIf(wiped);
        kept.put(clazz.id(), new SkillProgress(1, 0, true));
        p.setData(CoreRegistries.COLLECTION, new HabitsCollection(kept));
        List<ResourceLocation> unseen = new ArrayList<>(p.getData(CoreRegistries.UNSEEN));
        unseen.removeIf(id -> !kept.containsKey(id));
        if (!unseen.contains(clazz.id())) unseen.add(clazz.id());
        p.setData(CoreRegistries.UNSEEN, unseen);
        com.habits.progression.ProgressionApi.specsChanged(p);
        p.displayClientMessage(Component.translatable("habits.msg.class_became", clazz.displayName().copy().withColor(clazz.rarity().color)), false);
        return null;
    }

    /** Why {@code p} cannot become {@code clazz} now (a message key), or null. Claims nothing. */
    @org.jetbrains.annotations.Nullable
    public static String classRefusal(ServerPlayer p, SkillDef clazz) {
        if (!clazz.classPassive()) return "habits.msg.class_unknown";
        if (clazz.category().combat && !combatSpecs()) return "habits.msg.class_off";
        Specs s = of(p);
        if (s.picks.isEmpty()) return "habits.msg.class_pick_first";
        if (s.classes.contains(clazz.id())) return "habits.msg.class_already";
        if (ClassHolders.takenByOther(p, clazz)) return "habits.msg.class_taken";
        return null;
    }

    /** The picks a class book of {@code clazz} can replace, for the player to choose from: both professions with combat
     * specs off, else the one {@link #swappedBase} names; empty when its base is picked or a slot is free. */
    public static List<Category> replaceable(Player p, SkillDef clazz) {
        Category last = swappedBase(p, clazz);
        if (last == null) return List.of();
        return combatSpecs() ? List.of(last) : List.copyOf(of(p).picks);
    }

    /** The pick a class book of {@code clazz} would replace: the pick of its kind when its base is not picked (the most
     * recent one, i.e. the second profession with combat specs off), else null. With combat specs off and one profession
     * held, null: the book fills the empty second slot. */
    @org.jetbrains.annotations.Nullable
    public static Category swappedBase(Player p, SkillDef clazz) {
        return swappedBase(p, clazz, false);
    }

    /** As {@link #swappedBase(Player, SkillDef)}; {@code first}: with combat specs off, the first profession. */
    @org.jetbrains.annotations.Nullable
    public static Category swappedBase(Player p, SkillDef clazz, boolean first) {
        Specs s = of(p);
        if (s.picks.contains(clazz.category()) || !combatSpecs() && s.picks.size() < 2) return null;
        if (!combatSpecs()) return s.picks.get(first ? 0 : s.picks.size() - 1);
        for (Category c : s.picks.reversed()) if (c.combat == clazz.category().combat) return c;
        return null;
    }

    /** Scroll of Unlearning: marks a respec and opens the screen. False when there is nothing to unlearn yet. */
    public static boolean startRespec(ServerPlayer p) {
        Specs s = of(p);
        if (s.picks.isEmpty() || s.respec) return false;
        p.setData(CoreRegistries.SPECS, new Specs(s.picks, true, s.classes));
        openPicker(p);
        return true;
    }

    /** Admin reset: no picks, no wipe; the screen opens. */
    public static void reset(ServerPlayer p) {
        for (ResourceLocation id : of(p).classes) ClassHolders.release(p, id);
        p.setData(CoreRegistries.SPECS, NONE);
        syncTags(p);
        HabitsApi.refreshPerks(p);
        com.habits.progression.ProgressionApi.specsChanged(p);
        openPicker(p);
    }

    /** Entity tags {@code habits_<category>} for the picks: FTB Quests' gamestage tasks read them (FTB Library's
     * entity-tag stage provider), so each spec quest chapter unlocks for its players. */
    static void syncTags(ServerPlayer p) {
        List<Category> picks = of(p).picks;
        for (Category c : Category.values()) {
            String tag = "habits_" + c.getSerializedName();
            if (picks.contains(c)) p.addTag(tag);
            else p.removeTag(tag);
        }
    }

    static void openPicker(ServerPlayer p) {
        if (!(p instanceof FakePlayer)) PacketDistributor.sendToPlayer(p, Payloads.OpenSpecPick.INSTANCE);
    }

    /** Combat specs turned off after this player picked one: the combat pick and its class go (the profession and
     * every card stay; combat cards just stop working), and charms of it re-roll into the profession. */
    static void dropCombatPick(ServerPlayer p) {
        Specs s = of(p);
        if (combatSpecs() || s.picks.stream().noneMatch(c -> c.combat)) return;
        List<ResourceLocation> keep = new ArrayList<>();
        for (ResourceLocation id : s.classes) {
            SkillDef d = SkillRegistry.get(id);
            if (d != null && d.category().combat) ClassHolders.release(p, id);
            else keep.add(id);
        }
        p.setData(CoreRegistries.SPECS, new Specs(s.picks.stream().filter(c -> !c.combat).toList(), s.respec, keep));
        HabitsApi.refreshPerks(p);
        com.habits.progression.ProgressionApi.specsChanged(p);
    }

    static void onLogin(ServerPlayer p) {
        dropCombatPick(p);
        syncTags(p);
        if (needsPick(p)) openPicker(p);
    }
}
