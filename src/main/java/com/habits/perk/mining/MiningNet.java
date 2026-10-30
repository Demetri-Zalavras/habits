package com.habits.perk.mining;

import com.habits.Habits;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.List;

/** Mining-building payloads. Client handlers live in {@code client.MiningClient}; server handlers re-validate. */
public final class MiningNet {
    private MiningNet() {}

    /** Marks drawn by the client. style: 0 = glowing outline through walls, 1 = sparkle particles, 2 = dowsing trail
     * toward the first position. */
    public record Marks(List<BlockPos> pos, List<Integer> colors, int ticks, int style) implements CustomPacketPayload {
        public static final Type<Marks> TYPE = new Type<>(Habits.id("mining_marks"));
        public static final StreamCodec<ByteBuf, Marks> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), Marks::pos,
                ByteBufCodecs.INT.apply(ByteBufCodecs.list()), Marks::colors,
                ByteBufCodecs.VAR_INT, Marks::ticks, ByteBufCodecs.VAR_INT, Marks::style, Marks::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Momentum stacks (Stonebreaker), so client break speed matches the server. */
    public record Momentum(int stacks) implements CustomPacketPayload {
        public static final Type<Momentum> TYPE = new Type<>(Habits.id("mining_momentum"));
        public static final StreamCodec<ByteBuf, Momentum> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(Momentum::new, Momentum::stacks);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Sign waypoints (Scribe). dims are dimension ids, parallel to names and pos. */
    public record Waypoints(List<String> names, List<BlockPos> pos, List<String> dims) implements CustomPacketPayload {
        public static final Type<Waypoints> TYPE = new Type<>(Habits.id("mining_waypoints"));
        public static final StreamCodec<ByteBuf, Waypoints> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Waypoints::names,
                BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), Waypoints::pos,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Waypoints::dims, Waypoints::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client -> server key actions. */
    public record Action(int kind) implements CustomPacketPayload {
        public static final int UNDO = 0, REMOTE = 1, AIR_PLACE = 2;
        public static final Type<Action> TYPE = new Type<>(Habits.id("mining_action"));
        public static final StreamCodec<ByteBuf, Action> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(Action::new, Action::kind);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client side receivers; set by client.MiningClient. Default no-op on a dedicated server. */
    public interface ClientSink {
        default void marks(Marks m) {}
        default void momentum(int stacks) {}
        default void waypoints(Waypoints w) {}
    }

    public static ClientSink sink = new ClientSink() {};

    static void register(RegisterPayloadHandlersEvent e) {
        var r = e.registrar("mining-1");
        r.playToClient(Marks.TYPE, Marks.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> sink.marks(p)));
        r.playToClient(Momentum.TYPE, Momentum.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> sink.momentum(p.stacks())));
        r.playToClient(Waypoints.TYPE, Waypoints.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> sink.waypoints(p)));
        r.playToServer(Action.TYPE, Action.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) BuildPerks.action(sp, p.kind());
        }));
    }

    /** Send to a real player (fake players have no connection). */
    static void send(ServerPlayer p, CustomPacketPayload payload) {
        if (!(p instanceof FakePlayer) && p.connection != null) PacketDistributor.sendToPlayer(p, payload);
    }

    /** The last marks built (any player). Test hook; one reference, never a growing map. */
    static Marks last;

    static void marks(ServerPlayer p, List<BlockPos> pos, List<Integer> colors, int ticks, int style) {
        last = new Marks(List.copyOf(pos), List.copyOf(colors), ticks, style);
        if (!pos.isEmpty()) send(p, last);
    }
}
