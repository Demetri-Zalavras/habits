package com.habits.perk.movement;

import com.habits.Habits;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Saved per-player movement data (server side only, survives death). Mutated in place; small and capped. */
public final class MoveData {
    /** A remembered place: what it is (structure id, or "waypoint"/"gateway") and where. */
    public record Place(String what, GlobalPos pos) {
        static final Codec<Place> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("what").forGetter(Place::what),
                GlobalPos.CODEC.fieldOf("pos").forGetter(Place::pos)).apply(i, Place::new));
    }

    static final int MAX_PLACES = 64;

    final Set<ResourceLocation> biomes;
    final List<Place> waypoints, gateways, structures;
    /** Bits: far milestones (0-3), atlas milestones (4-7). */
    int milestones;
    int marathonKm;
    Optional<UUID> mount;
    /** Game-time cooldown stamps, saved so a relog doesn't reset them (softReadyAt: leap_of_faith's soft landing). */
    long travelReadyAt, lodestoneReadyAt, restoreReadyAt, softReadyAt;

    MoveData() {
        this(List.of(), List.of(), List.of(), List.of(), 0, 0, Optional.empty(), 0, 0, 0, 0);
    }

    private MoveData(List<ResourceLocation> biomes, List<Place> waypoints, List<Place> gateways, List<Place> structures,
                     int milestones, int marathonKm, Optional<UUID> mount, long travelReadyAt, long lodestoneReadyAt, long restoreReadyAt,
                     long softReadyAt) {
        this.biomes = new HashSet<>(biomes);
        this.waypoints = new ArrayList<>(waypoints);
        this.gateways = new ArrayList<>(gateways);
        this.structures = new ArrayList<>(structures);
        this.milestones = milestones;
        this.marathonKm = marathonKm;
        this.mount = mount;
        this.travelReadyAt = travelReadyAt;
        this.lodestoneReadyAt = lodestoneReadyAt;
        this.restoreReadyAt = restoreReadyAt;
        this.softReadyAt = softReadyAt;
    }

    static final Codec<MoveData> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.listOf().optionalFieldOf("biomes", List.of()).forGetter(d -> List.copyOf(d.biomes)),
            Place.CODEC.listOf().optionalFieldOf("waypoints", List.of()).forGetter(d -> d.waypoints),
            Place.CODEC.listOf().optionalFieldOf("gateways", List.of()).forGetter(d -> d.gateways),
            Place.CODEC.listOf().optionalFieldOf("structures", List.of()).forGetter(d -> d.structures),
            Codec.INT.optionalFieldOf("milestones", 0).forGetter(d -> d.milestones),
            Codec.INT.optionalFieldOf("marathon_km", 0).forGetter(d -> d.marathonKm),
            UUIDUtil.CODEC.optionalFieldOf("mount").forGetter(d -> d.mount),
            Codec.LONG.optionalFieldOf("travel_ready", 0L).forGetter(d -> d.travelReadyAt),
            Codec.LONG.optionalFieldOf("lodestone_ready", 0L).forGetter(d -> d.lodestoneReadyAt),
            Codec.LONG.optionalFieldOf("restore_ready", 0L).forGetter(d -> d.restoreReadyAt),
            Codec.LONG.optionalFieldOf("soft_ready", 0L).forGetter(d -> d.softReadyAt)
    ).apply(i, MoveData::new));

    static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Habits.MODID);
    static final Supplier<AttachmentType<MoveData>> TYPE = ATTACHMENTS.register("movement",
            () -> AttachmentType.builder(MoveData::new).serialize(CODEC).copyOnDeath().build());
    /** Chunk attachment: pressure plates and tripwire strings a player placed here ({@link BlockPos#asLong}). Tomb Raider
     * only ignores the unmarked ones, the world's own traps. */
    static final Supplier<AttachmentType<LongOpenHashSet>> PLACED_TRAPS = ATTACHMENTS.register("placed_traps",
            () -> AttachmentType.builder(() -> new LongOpenHashSet())
                    .serialize(Codec.LONG.listOf().xmap(LongOpenHashSet::new, List::copyOf)).build());

    /** Add to a capped list, dropping the oldest; replaces an entry at the same block. */
    static void remember(List<Place> list, Place p, int cap) {
        list.removeIf(o -> o.pos().equals(p.pos()));
        list.add(p);
        while (list.size() > Math.max(0, Math.min(cap, MAX_PLACES))) list.remove(0);
    }

    static BlockPos at(Place p) {
        return p.pos().pos();
    }
}
