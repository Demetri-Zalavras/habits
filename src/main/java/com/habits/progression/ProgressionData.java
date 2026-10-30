package com.habits.progression;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The player attachment ({@link ProgRegistries#DATA}): sigils socketed per skill, equipped charms and the sigils kept in
 * the book ({@code stored}: a count per {@link #bucket}, 4 types x 6 rarities, the first 20 in the pre-Mythical layout;
 * shorter lists read 0) and the
 * charms kept in the book ({@code bag}, newest first, unlimited). Immutable;
 * a change is a new instance (caches key on identity). Saved, kept on death, synced to its own player.
 */
public record ProgressionData(Map<ResourceLocation, List<Socketed>> sockets, List<Equipped> charms, List<Integer> stored,
                              List<CharmData> bag) {
    public static final ProgressionData EMPTY = new ProgressionData(Map.of(), List.of(), List.of(), List.of());
    public static final int BUCKETS = SigilType.values().length * com.habits.core.Rarity.values().length;

    /** A sigil in socket {@code slot} (0..3) of a card. */
    public record Socketed(int slot, SigilData sigil) {
        static final Codec<Socketed> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("slot").forGetter(Socketed::slot),
                SigilData.CODEC.fieldOf("sigil").forGetter(Socketed::sigil)
        ).apply(i, Socketed::new));
        static final StreamCodec<ByteBuf, Socketed> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Socketed::slot, SigilData.STREAM_CODEC, Socketed::sigil, Socketed::new);
    }

    /** A charm in charm slot {@code slot} (0..4). */
    public record Equipped(int slot, CharmData charm) {
        static final Codec<Equipped> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("slot").forGetter(Equipped::slot),
                CharmData.CODEC.fieldOf("charm").forGetter(Equipped::charm)
        ).apply(i, Equipped::new));
        static final StreamCodec<ByteBuf, Equipped> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Equipped::slot, CharmData.STREAM_CODEC, Equipped::charm, Equipped::new);
    }

    public static final Codec<ProgressionData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(ResourceLocation.CODEC, Socketed.CODEC.listOf()).optionalFieldOf("sockets", Map.of()).forGetter(ProgressionData::sockets),
            Equipped.CODEC.listOf().optionalFieldOf("charms", List.of()).forGetter(ProgressionData::charms),
            Codec.INT.listOf().optionalFieldOf("stored", List.of()).forGetter(ProgressionData::stored),
            CharmData.CODEC.listOf().optionalFieldOf("bag", List.of()).forGetter(ProgressionData::bag) // optional: older saves load
    ).apply(i, ProgressionData::new));

    public static final StreamCodec<ByteBuf, ProgressionData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.<ByteBuf, ResourceLocation, List<Socketed>, Map<ResourceLocation, List<Socketed>>>map(
                    HashMap::new, ResourceLocation.STREAM_CODEC, Socketed.STREAM_CODEC.apply(ByteBufCodecs.list())),
            ProgressionData::sockets,
            Equipped.STREAM_CODEC.apply(ByteBufCodecs.list()), ProgressionData::charms,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), ProgressionData::stored,
            CharmData.STREAM_CODEC.apply(ByteBufCodecs.list()), ProgressionData::bag,
            ProgressionData::new);

    public ProgressionData {
        sockets = Map.copyOf(sockets);
        charms = List.copyOf(charms);
        stored = List.copyOf(stored);
        bag = List.copyOf(bag);
    }

    /** Nothing socketed or equipped (stored sigils change no perk). */
    public boolean isEmpty() {
        return sockets.isEmpty() && charms.isEmpty();
    }

    /** Sigils on a card (any order, by slot). */
    public List<Socketed> socketed(ResourceLocation skill) {
        return sockets.getOrDefault(skill, List.of());
    }

    @Nullable
    public SigilData sigil(ResourceLocation skill, int slot) {
        for (Socketed s : socketed(skill)) if (s.slot == slot) return s.sigil;
        return null;
    }

    /** A copy with that socket set ({@code sigil} null empties it). */
    public ProgressionData withSigil(ResourceLocation skill, int slot, @Nullable SigilData sigil) {
        List<Socketed> l = new ArrayList<>(socketed(skill));
        l.removeIf(s -> s.slot == slot);
        if (sigil != null) l.add(new Socketed(slot, sigil));
        l.sort(java.util.Comparator.comparingInt(Socketed::slot));
        Map<ResourceLocation, List<Socketed>> m = new HashMap<>(sockets);
        if (l.isEmpty()) m.remove(skill);
        else m.put(skill, l);
        return new ProgressionData(m, charms, stored, bag);
    }

    @Nullable
    public CharmData charm(int slot) {
        for (Equipped e : charms) if (e.slot == slot) return e.charm;
        return null;
    }

    /** A copy with that charm slot set ({@code charm} null empties it). */
    public ProgressionData withCharm(int slot, @Nullable CharmData charm) {
        List<Equipped> l = new ArrayList<>(charms);
        l.removeIf(e -> e.slot == slot);
        if (charm != null) l.add(new Equipped(slot, charm));
        l.sort(java.util.Comparator.comparingInt(Equipped::slot));
        return new ProgressionData(sockets, l, stored, bag);
    }

    /** A copy with {@code charm} added to the front of the bag (newest first). */
    public ProgressionData withBagged(CharmData charm) {
        List<CharmData> l = new ArrayList<>(bag.size() + 1);
        l.add(charm);
        l.addAll(bag);
        return new ProgressionData(sockets, charms, stored, l);
    }

    /** A copy without the bag entries at {@code indices}. */
    public ProgressionData withoutBagged(int... indices) {
        List<CharmData> l = new ArrayList<>(bag.size());
        outer:
        for (int i = 0; i < bag.size(); i++) {
            for (int k : indices) if (k == i) continue outer;
            l.add(bag.get(i));
        }
        return new ProgressionData(sockets, charms, stored, l);
    }

    /** Index of a sigil kind in {@link #stored} ({@link SigilData#bucket}: old saves keep their layout). */
    public static int bucket(SigilData s) {
        return s.bucket();
    }

    /** Sigils of that kind kept in the book. */
    public int stored(SigilData s) {
        int b = bucket(s);
        return b < stored.size() ? stored.get(b) : 0;
    }

    /** A copy with {@code delta} more (or fewer) of that kind, never below 0. */
    public ProgressionData withStored(SigilData s, int delta) {
        List<Integer> l = new ArrayList<>(stored);
        while (l.size() < BUCKETS) l.add(0);
        int b = bucket(s);
        l.set(b, (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) l.get(b) + delta)));
        return new ProgressionData(sockets, charms, l, bag);
    }

    /** The sigil a charm reroll of rarity {@code min} spends: the lowest stored rarity at or above it, then the first type. */
    @Nullable
    public SigilData cheapest(com.habits.core.Rarity min) {
        for (com.habits.core.Rarity r : com.habits.core.Rarity.values()) {
            if (r.ordinal() < min.ordinal()) continue;
            for (SigilType t : SigilType.values()) {
                SigilData s = SigilData.of(t, r);
                if (stored(s) > 0) return s;
            }
        }
        return null;
    }
}
