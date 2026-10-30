package com.habits.core;

import com.habits.Habits;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.List;

/** All custom payloads. Owner: core. Server handlers re-validate everything. Client packages send with
 * {@code PacketDistributor.sendToServer(new Payloads.X(...))}. */
public final class Payloads {
    private Payloads() {}

    /**
     * Server -> client: a card was just added to the collection (right-click). FX plays the flip from it through
     * {@link ClientHooks#cardAdded}. Fields are the state AFTER the add.
     *
     * @param wasNew    this card unlocked the skill (level is now 1)
     * @param leveledUp this card raised an already owned skill's level (false when {@code wasNew})
     */
    public record CardAdded(ResourceLocation skill, int level, int progress, int needed, boolean wasNew,
                            boolean leveledUp) implements CustomPacketPayload {
        public static final Type<CardAdded> TYPE = new Type<>(Habits.id("card_added"));
        public static final StreamCodec<ByteBuf, CardAdded> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            ResourceLocation.STREAM_CODEC.encode(buf, p.skill);
            ByteBufCodecs.VAR_INT.encode(buf, p.level);
            ByteBufCodecs.VAR_INT.encode(buf, p.progress);
            ByteBufCodecs.VAR_INT.encode(buf, p.needed);
            ByteBufCodecs.BOOL.encode(buf, p.wasNew);
            ByteBufCodecs.BOOL.encode(buf, p.leveledUp);
        }, buf -> new CardAdded(ResourceLocation.STREAM_CODEC.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.BOOL.decode(buf)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        /** The definition, or null if the client does not know it. Client side. */
        public SkillDef def() { return SkillRegistry.get(skill); }
    }

    /** Server -> client: a won card added itself (auto-add). FX shows a feed row through {@link ClientHooks#cardGained};
     * no flip. */
    public record CardGained(CardAdded card) implements CustomPacketPayload {
        public static final Type<CardGained> TYPE = new Type<>(Habits.id("card_gained"));
        public static final StreamCodec<ByteBuf, CardGained> STREAM_CODEC = CardAdded.STREAM_CODEC.map(CardGained::new, CardGained::card);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server -> client: XP earned for one skill since the last batch ({@link SkillXp}), with the progress it reached;
     * FX shows it as a feed row with a bar through {@link ClientHooks#xpGained}. */
    public record XpGained(ResourceLocation skill, int gained, int level, int progress, int needed) implements CustomPacketPayload {
        public static final Type<XpGained> TYPE = new Type<>(Habits.id("xp_gained"));
        public static final StreamCodec<ByteBuf, XpGained> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, XpGained::skill, ByteBufCodecs.VAR_INT, XpGained::gained,
                ByteBufCodecs.VAR_INT, XpGained::level, ByteBufCodecs.VAR_INT, XpGained::progress,
                ByteBufCodecs.VAR_INT, XpGained::needed, XpGained::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server -> client: a feed row for a sigil gain ({@link HabitsApi#feedSigil}), through {@link ClientHooks#sigilGained}. */
    public record FeedSigil(ItemStack icon, Rarity rarity, Component name, int count) implements CustomPacketPayload {
        public static final Type<FeedSigil> TYPE = new Type<>(Habits.id("feed_sigil"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FeedSigil> STREAM_CODEC = StreamCodec.composite(
                ItemStack.STREAM_CODEC, FeedSigil::icon,
                ByteBufCodecs.VAR_INT.map(i -> Rarity.values()[Math.floorMod(i, Rarity.values().length)], Enum::ordinal), FeedSigil::rarity,
                ComponentSerialization.STREAM_CODEC, FeedSigil::name, ByteBufCodecs.VAR_INT, FeedSigil::count, FeedSigil::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client -> server: the book's "Auto-add drops" toggle. */
    public record SetAutoAdd(boolean on) implements CustomPacketPayload {
        public static final Type<SetAutoAdd> TYPE = new Type<>(Habits.id("set_auto_add"));
        public static final StreamCodec<ByteBuf, SetAutoAdd> STREAM_CODEC = ByteBufCodecs.BOOL.map(SetAutoAdd::new, SetAutoAdd::on);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client -> server: the player hovered a "NEW" card in the binder; clears it from the unseen set. */
    public record MarkSeen(ResourceLocation skill) implements CustomPacketPayload {
        public static final Type<MarkSeen> TYPE = new Type<>(Habits.id("mark_seen"));
        public static final StreamCodec<ByteBuf, MarkSeen> STREAM_CODEC = ResourceLocation.STREAM_CODEC.map(MarkSeen::new, MarkSeen::skill);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server -> client: every skill definition. Sent on login and after /reload. */
    public record SyncSkills(List<SkillDef> skills) implements CustomPacketPayload {
        public static final Type<SyncSkills> TYPE = new Type<>(Habits.id("sync_skills"));
        public static final StreamCodec<ByteBuf, SyncSkills> STREAM_CODEC =
                SkillDef.STREAM_CODEC.apply(ByteBufCodecs.list()).map(SyncSkills::new, SyncSkills::skills);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client -> server: toggle an owned skill's perk on/off (binder click). */
    public record TogglePerk(ResourceLocation skill) implements CustomPacketPayload {
        public static final Type<TogglePerk> TYPE = new Type<>(Habits.id("toggle_perk"));
        public static final StreamCodec<ByteBuf, TogglePerk> STREAM_CODEC = ResourceLocation.STREAM_CODEC.map(TogglePerk::new, TogglePerk::skill);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client -> server: the player swung at nothing (vanilla never tells the server). Fires CUSTOM
     * {@code habits:swing_air} after {@link CoreInit#swingAir} validates and rate-limits it. */
    public record SwingAir() implements CustomPacketPayload {
        public static final SwingAir INSTANCE = new SwingAir();
        public static final Type<SwingAir> TYPE = new Type<>(Habits.id("swing_air"));
        public static final StreamCodec<ByteBuf, SwingAir> STREAM_CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: spend the Wild Card in hand on this skill. The server checks the card, the book, ownership and the level. */
    public record UseWildCard(ResourceLocation skill) implements CustomPacketPayload {
        public static final Type<UseWildCard> TYPE = new Type<>(Habits.id("use_wild_card"));
        public static final StreamCodec<ByteBuf, UseWildCard> STREAM_CODEC = ResourceLocation.STREAM_CODEC.map(UseWildCard::new, UseWildCard::skill);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: open the specialization screen ({@link Specs#needsPick}). */
    public record OpenSpecPick() implements CustomPacketPayload {
        public static final OpenSpecPick INSTANCE = new OpenSpecPick();
        public static final Type<OpenSpecPick> TYPE = new Type<>(Habits.id("open_spec_pick"));
        public static final StreamCodec<ByteBuf, OpenSpecPick> STREAM_CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: the picked combat style and profession. {@link Specs#pick} re-checks both and that a pick is due. */
    public record PickSpecs(Category combat, Category profession) implements CustomPacketPayload {
        public static final Type<PickSpecs> TYPE = new Type<>(Habits.id("pick_specs"));
        static final StreamCodec<ByteBuf, Category> CAT =
                ByteBufCodecs.VAR_INT.map(i -> Category.values()[Math.floorMod(i, Category.values().length)], Enum::ordinal);
        public static final StreamCodec<ByteBuf, PickSpecs> STREAM_CODEC = StreamCodec.composite(
                CAT, PickSpecs::combat, CAT, PickSpecs::profession, PickSpecs::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: a class book was right-clicked: open the confirm screen. {@code replace}: the picks the book can take the
     * place of (empty: it joins its own picked base or fills the empty second slot). */
    public record OpenClassBook(ResourceLocation clazz, List<Category> replace) implements CustomPacketPayload {
        public static final Type<OpenClassBook> TYPE = new Type<>(Habits.id("open_class_book"));
        public static final StreamCodec<ByteBuf, OpenClassBook> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, OpenClassBook::clazz, PickSpecs.CAT.apply(ByteBufCodecs.list(2)), OpenClassBook::replace, OpenClassBook::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: become the class of the held book, giving up {@code replace}. {@link ClassBookItem#confirm} re-checks everything. */
    public record UseClassBook(ResourceLocation clazz, java.util.Optional<Category> replace) implements CustomPacketPayload {
        public static final Type<UseClassBook> TYPE = new Type<>(Habits.id("use_class_book"));
        public static final StreamCodec<ByteBuf, UseClassBook> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, UseClassBook::clazz, ByteBufCodecs.optional(PickSpecs.CAT), UseClassBook::replace, UseClassBook::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: show an owned title before the name (empty: none). */
    public record ShowTitle(java.util.Optional<ResourceLocation> title) implements CustomPacketPayload {
        public static final Type<ShowTitle> TYPE = new Type<>(Habits.id("show_title"));
        public static final StreamCodec<ByteBuf, ShowTitle> STREAM_CODEC =
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC).map(ShowTitle::new, ShowTitle::title);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: pick a fork option for an owned skill's step. {@link Forks#set} re-checks everything. */
    public record SetFork(ResourceLocation skill, int level, String option) implements CustomPacketPayload {
        public static final Type<SetFork> TYPE = new Type<>(Habits.id("set_fork"));
        public static final StreamCodec<ByteBuf, SetFork> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, SetFork::skill, ByteBufCodecs.VAR_INT, SetFork::level,
                ByteBufCodecs.STRING_UTF8, SetFork::option, SetFork::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void register(RegisterPayloadHandlersEvent e) {
        var r = e.registrar("4"); // 4: class book screen
        r.playToClient(SyncSkills.TYPE, SyncSkills.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            SkillRegistry.replaceAll(p.skills());
            ClientHooks.each(ClientHooks::skillsReloaded);
        }));
        r.playToServer(TogglePerk.TYPE, TogglePerk.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> HabitsApi.togglePerk((ServerPlayer) ctx.player(), p.skill())));
        r.playToClient(CardAdded.TYPE, CardAdded.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.each(h -> h.cardAdded(p))));
        r.playToClient(CardGained.TYPE, CardGained.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.each(h -> h.cardGained(p.card()))));
        r.playToClient(XpGained.TYPE, XpGained.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.each(h -> h.xpGained(p))));
        r.playToClient(FeedSigil.TYPE, FeedSigil.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.each(h -> h.sigilGained(p))));
        r.playToServer(SetAutoAdd.TYPE, SetAutoAdd.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> HabitsApi.setAutoAdd((ServerPlayer) ctx.player(), p.on())));
        r.playToServer(MarkSeen.TYPE, MarkSeen.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> HabitsApi.markSeen((ServerPlayer) ctx.player(), p.skill())));
        r.playToServer(UseWildCard.TYPE, UseWildCard.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> HabitsApi.useWildCard((ServerPlayer) ctx.player(), p.skill())));
        r.playToServer(ShowTitle.TYPE, ShowTitle.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> Titles.show((ServerPlayer) ctx.player(), p.title())));
        r.playToServer(SetFork.TYPE, SetFork.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> Forks.set((ServerPlayer) ctx.player(), p.skill(), p.level(), p.option())));
        r.playToClient(OpenSpecPick.TYPE, OpenSpecPick.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.each(ClientHooks::openSpecPick)));
        r.playToServer(PickSpecs.TYPE, PickSpecs.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> Specs.pick((ServerPlayer) ctx.player(), p.combat(), p.profession())));
        r.playToClient(OpenClassBook.TYPE, OpenClassBook.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClientHooks.each(h -> h.openClassBook(p))));
        r.playToServer(UseClassBook.TYPE, UseClassBook.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> ClassBookItem.confirm((ServerPlayer) ctx.player(), p.clazz(), p.replace().orElse(null))));
        r.playToServer(SwingAir.TYPE, SwingAir.STREAM_CODEC,
                (p, ctx) -> ctx.enqueueWork(() -> CoreInit.swingAir((ServerPlayer) ctx.player())));
    }
}
