package com.habits.core;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Titles: skills with a {@code "title"} condition ({@link SkillDef.TitleSpec}), earned at Lv10 when it holds (checked
 * every {@link #CHECK_TICKS}) and never levelled. Every owned title's perk stacks (they skip the specialization
 * lockout). One owned title can be shown before the player's name ({@link CoreRegistries#SHOWN_TITLE}), in chat, the
 * tab list and over their head.
 */
public final class Titles {
    private Titles() {}

    static final int CHECK_TICKS = 100;

    static void init(IEventBus bus) {
        bus.addListener((PlayerTickEvent.Post e) -> {
            if (e.getEntity() instanceof ServerPlayer sp && sp.tickCount % CHECK_TICKS == 0 && HabitsApi.canAct(sp)
                    && !(sp instanceof net.neoforged.neoforge.common.util.FakePlayer)) check(sp);
        });
        bus.addListener((PlayerEvent.NameFormat e) -> {
            Component t = prefix(e.getEntity());
            if (t != null) e.setDisplayname(t.copy().append(e.getDisplayname()));
        });
        bus.addListener((PlayerEvent.TabListNameFormat e) -> {
            Component t = prefix(e.getEntity());
            if (t != null) e.setDisplayName(t.copy().append(e.getEntity().getName()));
        });
    }

    /** "[Dragonslayer] " in the title's rarity colour, or null when none is shown. */
    @Nullable
    static Component prefix(Player p) {
        Optional<ResourceLocation> id = p.getExistingDataOrNull(CoreRegistries.SHOWN_TITLE);
        SkillDef d = id == null || id.isEmpty() ? null : SkillRegistry.get(id.get());
        if (d == null || d.title().isEmpty()) return null;
        return Component.literal("[").append(d.displayName()).append("] ").withStyle(s -> s.withColor(d.rarity().color));
    }

    /** Grants every title whose condition now holds. Returns how many were earned. */
    static int check(ServerPlayer p) {
        HabitsCollection col = HabitsApi.collection(p);
        int earned = 0;
        for (SkillDef d : SkillRegistry.all()) {
            if (d.title().isEmpty() || col.get(d.id()).level() > 0 || !holds(p, col, d.title().get())) continue;
            grant(p, d);
            earned++;
            col = HabitsApi.collection(p);
        }
        return earned;
    }

    /** Gives a title (Lv10, no XP), with the feed row and a chat line. */
    public static void grant(ServerPlayer p, SkillDef title) {
        SkillProgress before = HabitsApi.collection(p).get(title.id());
        p.setData(CoreRegistries.COLLECTION, HabitsApi.collection(p).with(title.id(), new SkillProgress(Leveling.MAX_LEVEL, 0, true)));
        HabitsApi.notifyGain(p, title, before);
        p.displayClientMessage(Component.translatable("habits.msg.title_earned", title.displayName().copy().withColor(title.rarity().color),
                title.perkLine(Leveling.MAX_LEVEL)).withStyle(ChatFormatting.GOLD), false);
    }

    static boolean holds(ServerPlayer p, HabitsCollection col, SkillDef.TitleSpec t) {
        if (t.advancement().isPresent()) {
            var adv = p.server.getAdvancements().get(t.advancement().get());
            if (adv == null || !p.getAdvancements().getOrStartProgress(adv).isDone()) return false;
        }
        if (t.statType().isPresent() && t.stat().isPresent()) {
            Stat<?> stat = stat(BuiltInRegistries.STAT_TYPE.get(t.statType().get()), t.stat().get());
            if (stat == null || p.getStats().getValue(stat) < t.min()) return false;
        }
        if (t.totalLevel() > 0 || t.maxed() > 0) {
            int total = 0, maxed = 0;
            for (var e : col.skills().entrySet()) {
                SkillDef d = SkillRegistry.get(e.getKey());
                if (d == null || d.title().isPresent()) continue;
                total += e.getValue().level();
                if (e.getValue().maxed()) maxed++;
            }
            if (total < t.totalLevel() || maxed < t.maxed()) return false;
        }
        return t.advancement().isPresent() || t.statType().isPresent() || t.totalLevel() > 0 || t.maxed() > 0;
    }

    @Nullable
    private static <T> Stat<T> stat(@Nullable StatType<T> type, ResourceLocation id) {
        if (type == null) return null;
        // getOptional: defaulted registries (entities) hand back the default (pig) for an unknown id, e.g. a removed mod's boss
        return type.getRegistry().getOptional(id).map(type::get).orElse(null);
    }

    /** {@link Payloads.ShowTitle}: shows an owned title before the name, or none ({@code id} not owned or empty). */
    static void show(ServerPlayer p, Optional<ResourceLocation> id) {
        Optional<ResourceLocation> v = id.filter(t -> { SkillDef d = SkillRegistry.get(t); return d != null && d.title().isPresent()
                && HabitsApi.collection(p).get(t).level() > 0; });
        p.setData(CoreRegistries.SHOWN_TITLE, v);
        p.refreshDisplayName();
        p.refreshTabListName();
    }
}
