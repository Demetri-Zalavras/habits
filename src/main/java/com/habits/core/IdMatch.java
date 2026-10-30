package com.habits.core;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A set of ids and tags. JSON: one string or a list; {@code "#ns:path"} is a tag, anything else an id.
 * <pre>"minecraft:stone"   ["minecraft:stone", "#minecraft:logs"]</pre>
 * Shared by trigger matching (core) and perk params (PERKS: {@link #parse} a param string, then {@link #test}).
 */
public record IdMatch(List<ResourceLocation> ids, List<ResourceLocation> tags) {
    private static final Codec<String> ENTRY = Codec.STRING.validate(s -> parseOne(s) == null
            ? DataResult.error(() -> "bad id or #tag: " + s) : DataResult.success(s));

    public static final Codec<IdMatch> CODEC = Codec.either(ENTRY, ENTRY.listOf()).xmap(
            e -> of(e.map(List::of, l -> l)),
            m -> { List<String> s = m.strings(); return s.size() == 1 ? Either.left(s.get(0)) : Either.right(s); });

    /** Parse a comma-separated param string ({@code "#minecraft:logs,minecraft:stone"}); null if blank or bad. */
    @Nullable
    public static IdMatch parse(@Nullable String s) {
        if (s == null || s.isBlank()) return null;
        List<String> parts = new ArrayList<>();
        for (String p : s.split(",")) parts.add(p.trim());
        for (String p : parts) if (parseOne(p) == null) return null;
        return of(parts);
    }

    private static IdMatch of(List<String> entries) {
        List<ResourceLocation> ids = new ArrayList<>(), tags = new ArrayList<>();
        for (String e : entries) (e.startsWith("#") ? tags : ids).add(parseOne(e));
        return new IdMatch(List.copyOf(ids), List.copyOf(tags));
    }

    @Nullable
    private static ResourceLocation parseOne(String s) {
        return ResourceLocation.tryParse(s.startsWith("#") ? s.substring(1) : s);
    }

    private List<String> strings() {
        List<String> out = new ArrayList<>();
        ids.forEach(i -> out.add(i.toString()));
        tags.forEach(t -> out.add("#" + t));
        return out;
    }

    /** True if {@code id} is listed, or is in a listed tag of {@code registry}. Null id never matches. Tags are
     * ignored when {@code registry} is null (dimensions, advancements). */
    public <T> boolean test(@Nullable ResourceLocation id, @Nullable ResourceKey<? extends Registry<T>> registry, RegistryAccess access) {
        if (id == null) return false;
        if (ids.contains(id)) return true;
        if (tags.isEmpty() || registry == null) return false;
        Optional<Holder.Reference<T>> holder = access.registry(registry).flatMap(r -> r.getHolder(ResourceKey.create(registry, id)));
        if (holder.isEmpty()) return false;
        for (ResourceLocation t : tags) if (holder.get().is(TagKey.create(registry, t))) return true;
        return false;
    }

    /** Same, for a {@link TargetKind}'s registry. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean test(@Nullable ResourceLocation id, TargetKind kind, RegistryAccess access) {
        return test(id, (ResourceKey) kind.registry, access);
    }
}
