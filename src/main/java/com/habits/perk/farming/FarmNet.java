package com.habits.perk.farming;

import com.habits.Habits;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.List;

/** Our packets: sneak + right-click on air (vanilla never tells the server), and the lookout's outlined mobs. */
public final class FarmNet {
    private FarmNet() {}

    /** Client -> server, no data. The server re-checks sneak, empty hand and the perks itself. */
    public record EmptyUse() implements CustomPacketPayload {
        public static final EmptyUse INSTANCE = new EmptyUse();
        public static final Type<EmptyUse> TYPE = new Type<>(Habits.id("farming_empty_use"));
        public static final StreamCodec<ByteBuf, EmptyUse> STREAM_CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server -> client: entity ids that Shoulder Lookout outlines for this player only, for a moment. */
    public record Lookout(List<Integer> ids) implements CustomPacketPayload {
        public static final Type<Lookout> TYPE = new Type<>(Habits.id("farming_lookout"));
        public static final StreamCodec<ByteBuf, Lookout> STREAM_CODEC =
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(64)).map(Lookout::new, Lookout::ids);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void init(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1")
                .playToServer(EmptyUse.TYPE, EmptyUse.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp && Farm.cooldown(sp, "habits_empty_use", 0.25)) Animals.emptyUse(sp);
                }))
                .playToClient(Lookout.TYPE, Lookout.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() ->
                        FarmHooks.outline(p.ids(), ctx.player().level().getGameTime()))));
    }
}
