package com.habits.perk.movement;

import com.habits.Habits;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Movement payloads. Client input only states intent; {@link Actions} re-checks everything on the server. */
public final class MovePayloads {
    private MovePayloads() {}

    // Act kinds
    public static final byte DOUBLE_JUMP = 1, WALL_JUMP = 2, DASH = 3, BLINK = 4, ISLAND_HOP = 5, PING = 6, WHISTLE = 7,
            MOUNT = 8, WAYPOINT = 9, WAYPOINT_CLEAR = 10, TRAVEL_LIST = 11, TRAVEL = 12, MARK_ENTITY = 13;
    // held key bits
    public static final byte K_JUMP = 1, K_FORWARD = 2, K_SPRINT = 4;
    // marker kinds (compass bar)
    public static final byte M_BED = 0, M_DEATH = 1, M_HOME = 2, M_WAYPOINT = 3, M_ORE = 4, M_STRUCTURE = 5, M_RAIDER = 6,
            M_WRECK = 7, M_TREASURE = 8, M_SPY = 9;
    // highlight kinds (outline colours)
    public static final byte H_LOOT = 0, H_TRAP = 1, H_SUSPICIOUS = 2, H_SECRET = 3, H_PORTAL_GAP = 4;

    /** C2S: a one-shot movement action (double jump, dash...). {@code arg} is kind specific (entity id, list index). */
    public record Act(byte kind, int arg) implements CustomPacketPayload {
        public static final Type<Act> TYPE = new Type<>(Habits.id("movement_act"));
        public static final StreamCodec<ByteBuf, Act> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE, Act::kind, ByteBufCodecs.VAR_INT, Act::arg, Act::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: held keys the server can't see (jump, forward, sprint key). Sent only when they change. */
    public record Keys(byte bits) implements CustomPacketPayload {
        public static final Type<Keys> TYPE = new Type<>(Habits.id("movement_keys"));
        public static final StreamCodec<ByteBuf, Keys> STREAM_CODEC = ByteBufCodecs.BYTE.map(Keys::new, Keys::bits);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: spyglass scout marks the block it is looking at. */
    public record MarkSpot(BlockPos pos) implements CustomPacketPayload {
        public static final Type<MarkSpot> TYPE = new Type<>(Habits.id("movement_mark"));
        public static final StreamCodec<ByteBuf, MarkSpot> STREAM_CODEC = BlockPos.STREAM_CODEC.map(MarkSpot::new, MarkSpot::pos);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: every compass bar marker for this player (replaces the last list). {@code xyz} packs BlockPos.asLong. */
    public record Markers(byte[] kinds, long[] xyz) implements CustomPacketPayload {
        public static final Type<Markers> TYPE = new Type<>(Habits.id("movement_markers"));
        public static final StreamCodec<ByteBuf, Markers> STREAM_CODEC = StreamCodec.of((buf, m) -> {
            int n = Math.min(m.kinds.length, 64);
            buf.writeByte(n);
            for (int i = 0; i < n; i++) {
                buf.writeByte(m.kinds[i]);
                buf.writeLong(m.xyz[i]);
            }
        }, buf -> {
            int n = Math.min(buf.readUnsignedByte(), 64);
            byte[] k = new byte[n];
            long[] x = new long[n];
            for (int i = 0; i < n; i++) {
                k[i] = buf.readByte();
                x[i] = buf.readLong();
            }
            return new Markers(k, x);
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: outline these blocks through walls for {@code ticks}. One list per kind; a new list replaces the old one. */
    public record Highlights(byte kind, List<BlockPos> blocks, int ticks) implements CustomPacketPayload {
        public static final Type<Highlights> TYPE = new Type<>(Habits.id("movement_highlights"));
        public static final StreamCodec<ByteBuf, Highlights> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BYTE, Highlights::kind, BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(256)), Highlights::blocks,
                ByteBufCodecs.VAR_INT, Highlights::ticks, Highlights::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: make these entities glow for this player only, for {@code ticks}. */
    public record Glow(List<Integer> ids, int ticks) implements CustomPacketPayload {
        public static final Type<Glow> TYPE = new Type<>(Habits.id("movement_glow"));
        public static final StreamCodec<ByteBuf, Glow> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(128)), Glow::ids, ByteBufCodecs.VAR_INT, Glow::ticks, Glow::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: fast travel destinations (label lines), opens the picker. */
    public record TravelList(List<String> labels, int minutesLeft) implements CustomPacketPayload {
        public static final Type<TravelList> TYPE = new Type<>(Habits.id("movement_travel"));
        public static final StreamCodec<ByteBuf, TravelList> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)), TravelList::labels, ByteBufCodecs.VAR_INT,
                TravelList::minutesLeft, TravelList::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client side receivers, set by com.habits.client.movement (no-ops on a dedicated server). */
    public interface ClientSink {
        default void markers(Markers m) {}
        default void highlights(Highlights h) {}
        default void glow(Glow g) {}
        default void travel(TravelList t) {}
    }

    public static volatile ClientSink SINK = new ClientSink() {};

    static void register(RegisterPayloadHandlersEvent e) {
        var r = e.registrar("1");
        r.playToServer(Act.TYPE, Act.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> Actions.act(sp(ctx), p.kind(), p.arg())));
        r.playToServer(Keys.TYPE, Keys.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> MoveState.of(sp(ctx)).keys = p.bits()));
        r.playToServer(MarkSpot.TYPE, MarkSpot.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> Actions.markSpot(sp(ctx), p.pos())));
        r.playToClient(Markers.TYPE, Markers.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> SINK.markers(p)));
        r.playToClient(Highlights.TYPE, Highlights.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> SINK.highlights(p)));
        r.playToClient(Glow.TYPE, Glow.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> SINK.glow(p)));
        r.playToClient(TravelList.TYPE, TravelList.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> SINK.travel(p)));
    }

    private static ServerPlayer sp(IPayloadContext ctx) {
        return (ServerPlayer) ctx.player();
    }
}
