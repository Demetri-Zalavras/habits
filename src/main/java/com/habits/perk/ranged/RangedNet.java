package com.habits.perk.ranged;

import com.habits.Habits;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Ranged payloads. One so far: the Quickdraw armed count, so the client's bow pull starts full like the server's. */
public final class RangedNet {
    private RangedNet() {}

    /** S2C: instant draws armed for this player (replaces the last count). */
    public record Quick(int armed) implements CustomPacketPayload {
        public static final Type<Quick> TYPE = new Type<>(Habits.id("ranged_quick"));
        public static final StreamCodec<ByteBuf, Quick> STREAM_CODEC = ByteBufCodecs.VAR_INT.map(Quick::new, Quick::armed);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void register(RegisterPayloadHandlersEvent e) {
        e.registrar("ranged-1").playToClient(Quick.TYPE, Quick.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> Draw.clientArmed = p.armed()));
    }

    /** Set the server's armed count and tell that player's client. Fake players have no connection. */
    static void quick(ServerPlayer p, Status.P st, int armed) {
        st.quickReady = armed;
        if (!(p instanceof FakePlayer) && p.connection != null) PacketDistributor.sendToPlayer(p, new Quick(armed));
    }
}
