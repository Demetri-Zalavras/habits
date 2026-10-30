package com.habits.progression;

import com.habits.Habits;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> server progression actions. The book sends them with {@code PacketDistributor.sendToServer(new X(...))};
 * {@link Actions} validates everything. Sigils come from the book's store, charms from the book's bag
 * ({@link ProgressionData#bag} indices). */
public final class ProgPayloads {
    private ProgPayloads() {}

    /** Server -> client at login: the server's socket and charm slot numbers. The common config is not synced, so
     * without this a client whose config differs shows slots the server refuses. Read through {@link ProgressionApi}. */
    public record Limits(java.util.List<Integer> socketLevels, int charmBase, int charmPer, int charmMax, int sigilCombine) implements CustomPacketPayload {
        public static final Type<Limits> TYPE = new Type<>(Habits.id("prog_limits"));
        public static final StreamCodec<ByteBuf, Limits> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), Limits::socketLevels, ByteBufCodecs.VAR_INT, Limits::charmBase,
                ByteBufCodecs.VAR_INT, Limits::charmPer, ByteBufCodecs.VAR_INT, Limits::charmMax, ByteBufCodecs.VAR_INT, Limits::sigilCombine, Limits::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        /** This side's own config. */
        static Limits local() {
            return new Limits(ProgressionInit.SOCKET_LEVELS.get().stream().map(Number::intValue).toList(),
                    ProgressionInit.CHARM_BASE_SLOTS.get(), ProgressionInit.CHARM_SKILLS_PER_SLOT.get(), ProgressionInit.CHARM_MAX_SLOTS.get(),
                    ProgressionInit.SIGIL_COMBINE_COUNT.get());
        }
    }

    /** Put a stored sigil of kind {@code sigil} into socket {@code socket} (0..3) of card {@code skill}. */
    public record Socket(ResourceLocation skill, int socket, SigilData sigil) implements CustomPacketPayload {
        public static final Type<Socket> TYPE = new Type<>(Habits.id("socket"));
        public static final StreamCodec<ByteBuf, Socket> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Socket::skill, ByteBufCodecs.VAR_INT, Socket::socket, SigilData.STREAM_CODEC, Socket::sigil, Socket::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Take the sigil out of socket {@code socket} of card {@code skill}, back to the store. */
    public record Unsocket(ResourceLocation skill, int socket) implements CustomPacketPayload {
        public static final Type<Unsocket> TYPE = new Type<>(Habits.id("unsocket"));
        public static final StreamCodec<ByteBuf, Unsocket> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Unsocket::skill, ByteBufCodecs.VAR_INT, Unsocket::socket, Unsocket::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Equip stored charm {@code slot} (bag index) into charm slot {@code charmSlot} (0..4). */
    public record EquipCharm(int charmSlot, int slot) implements CustomPacketPayload {
        public static final Type<EquipCharm> TYPE = new Type<>(Habits.id("equip_charm"));
        public static final StreamCodec<ByteBuf, EquipCharm> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, EquipCharm::charmSlot, ByteBufCodecs.VAR_INT, EquipCharm::slot, EquipCharm::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record UnequipCharm(int charmSlot) implements CustomPacketPayload {
        public static final Type<UnequipCharm> TYPE = new Type<>(Habits.id("unequip_charm"));
        public static final StreamCodec<ByteBuf, UnequipCharm> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(UnequipCharm::new, UnequipCharm::charmSlot);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Reroll line {@code line} of a charm: equipped in charm slot {@code index} when {@code equipped}, else in
     * stored charm (bag index) {@code index}; spends the cheapest stored sigil that qualifies. */
    public record RerollCharm(boolean equipped, int index, int line) implements CustomPacketPayload {
        public static final Type<RerollCharm> TYPE = new Type<>(Habits.id("reroll_charm"));
        public static final StreamCodec<ByteBuf, RerollCharm> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, RerollCharm::equipped, ByteBufCodecs.VAR_INT, RerollCharm::index,
                ByteBufCodecs.VAR_INT, RerollCharm::line, RerollCharm::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Two stored sigils of kind {@code sigil} into one of the next rarity. */
    public record CombineSigils(SigilData sigil) implements CustomPacketPayload {
        public static final Type<CombineSigils> TYPE = new Type<>(Habits.id("combine_sigils"));
        public static final StreamCodec<ByteBuf, CombineSigils> STREAM_CODEC = SigilData.STREAM_CODEC.map(CombineSigils::new, CombineSigils::sigil);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Stored charm {@code index} (bag index) into one sigil of its rarity. */
    public record SalvageCharm(int index) implements CustomPacketPayload {
        public static final Type<SalvageCharm> TYPE = new Type<>(Habits.id("salvage_charm"));
        public static final StreamCodec<ByteBuf, SalvageCharm> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(SalvageCharm::new, SalvageCharm::index);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static void on(IPayloadContext ctx, java.util.function.Consumer<ServerPlayer> work) {
        ctx.enqueueWork(() -> { if (ctx.player() instanceof ServerPlayer sp) work.accept(sp); });
    }

    static void register(RegisterPayloadHandlersEvent e) {
        var r = e.registrar("6"); // 6: charm salvage replaced charm combine
        r.playToClient(Limits.TYPE, Limits.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> ProgressionApi.serverLimits = p));
        r.playToServer(Socket.TYPE, Socket.STREAM_CODEC, (p, ctx) -> on(ctx, sp -> Actions.socket(sp, p.skill(), p.socket(), p.sigil())));
        r.playToServer(Unsocket.TYPE, Unsocket.STREAM_CODEC, (p, ctx) -> on(ctx, sp -> Actions.unsocket(sp, p.skill(), p.socket())));
        r.playToServer(EquipCharm.TYPE, EquipCharm.STREAM_CODEC, (p, ctx) -> on(ctx, sp -> Actions.equipCharm(sp, p.charmSlot(), p.slot())));
        r.playToServer(UnequipCharm.TYPE, UnequipCharm.STREAM_CODEC, (p, ctx) -> on(ctx, sp -> Actions.unequipCharm(sp, p.charmSlot())));
        r.playToServer(RerollCharm.TYPE, RerollCharm.STREAM_CODEC,
                (p, ctx) -> on(ctx, sp -> Actions.rerollCharmLine(sp, p.equipped(), p.index(), p.line())));
        r.playToServer(CombineSigils.TYPE, CombineSigils.STREAM_CODEC, (p, ctx) -> on(ctx, sp -> Actions.combineSigils(sp, p.sigil())));
        r.playToServer(SalvageCharm.TYPE, SalvageCharm.STREAM_CODEC, (p, ctx) -> on(ctx, sp -> Actions.salvageCharm(sp, p.index())));
    }
}
