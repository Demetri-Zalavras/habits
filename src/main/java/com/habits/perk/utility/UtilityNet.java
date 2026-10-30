package com.habits.perk.utility;

import com.habits.Habits;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Our packets: the Ender Link key (the server re-checks the skill tier itself) and Bookworm's table seed. */
public final class UtilityNet {
    private UtilityNet() {}

    /** Client -> server, no data. */
    public record EnderKey() implements CustomPacketPayload {
        public static final EnderKey INSTANCE = new EnderKey();
        public static final Type<EnderKey> TYPE = new Type<>(Habits.id("utility_ender_key"));
        public static final StreamCodec<ByteBuf, EnderKey> STREAM_CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client -> server, no data: sneak + use is held (Mending Touch); the server re-checks sneak, skill and rate. */
    public record MendPulse() implements CustomPacketPayload {
        public static final MendPulse INSTANCE = new MendPulse();
        public static final Type<MendPulse> TYPE = new Type<>(Habits.id("utility_mend_pulse"));
        public static final StreamCodec<ByteBuf, MendPulse> STREAM_CODEC = StreamCodec.unit(INSTANCE);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server -> client: the open enchanting table's full seed (vanilla's data slot packet carries only a short). */
    public record TableSeed(int seed) implements CustomPacketPayload {
        public static final Type<TableSeed> TYPE = new Type<>(Habits.id("utility_table_seed"));
        public static final StreamCodec<ByteBuf, TableSeed> STREAM_CODEC = ByteBufCodecs.INT.map(TableSeed::new, TableSeed::seed);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    static void init(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("2")
                .playToServer(EnderKey.TYPE, EnderKey.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) Inventory.enderKey(sp);
                }))
                .playToServer(MendPulse.TYPE, MendPulse.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> {
                    if (ctx.player() instanceof ServerPlayer sp) Xp.mendPulse(sp);
                }))
                .playToClient(TableSeed.TYPE, TableSeed.STREAM_CODEC, (p, ctx) -> ctx.enqueueWork(() -> UtilityHooks.tableSeed = p.seed())));
    }
}
