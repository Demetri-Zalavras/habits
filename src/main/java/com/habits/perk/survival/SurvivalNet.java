package com.habits.perk.survival;

import com.habits.Habits;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.List;

/** Survival payloads. */
public final class SurvivalNet {
    private SurvivalNet() {}

    /** Outline these entities for this player only, for {@code ticks}. */
    public record Glow(List<Integer> ids, int ticks) implements CustomPacketPayload {
        public static final Type<Glow> TYPE = new Type<>(Habits.id("survival_glow"));
        public static final StreamCodec<ByteBuf, Glow> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), Glow::ids, ByteBufCodecs.VAR_INT, Glow::ticks, Glow::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void register(RegisterPayloadHandlersEvent e) {
        e.registrar("survival-1").playToClient(Glow.TYPE, Glow.STREAM_CODEC,
                (g, ctx) -> ctx.enqueueWork(() -> SurvivalHooks.glow(g, ctx.player().level().getGameTime())));
    }

    /** The last glow built (any player). Test hook; one reference. */
    static Glow last;

    static void glow(Player p, List<Integer> ids, int ticks) {
        if (ids.isEmpty()) return;
        last = new Glow(ids, ticks);
        if (p instanceof ServerPlayer sp && !(sp instanceof FakePlayer) && sp.connection != null) PacketDistributor.sendToPlayer(sp, last);
    }
}
