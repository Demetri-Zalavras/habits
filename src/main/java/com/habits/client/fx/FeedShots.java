package com.habits.client.fx;

import com.habits.client.DevShots;
import com.habits.core.ClientHooks;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Dev shots for the gain feed ({@code HABITS_SHOTS=feed}): real auto-adds (a level-up, a repeat that merges, a new
 * skill) plus a sigil row, on a pinned clock just in, mid and fading, at GUI scale 3, 2 and 4; then the book toggle. */
final class FeedShots {
    private static final long T0 = 2_000_000L;

    private FeedShots() {}

    static void register() {
        DevShots.PLANS.put("feed", FeedShots::plan);
    }

    private static void plan(List<DevShots.Shot> shots) {
        shots.add(new DevShots.Shot("feed_setup", 20, mc -> {
            FlipHud.frozen = T0;
            DevShots.serverDo(mc, p -> {
                List<SkillDef> picks = new ArrayList<>();
                for (Rarity r : new Rarity[]{Rarity.COMMON, Rarity.UNCOMMON, Rarity.EPIC})
                    for (SkillDef d : SkillRegistry.all()) if (d.rarity() == r) { picks.add(d); break; }
                SkillDef up = picks.get(0), repeat = picks.get(1), fresh = picks.get(2);
                p.setData(CoreRegistries.AUTO_ADD, true);
                p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY
                        .with(up.id(), new SkillProgress(4, 4, true)).with(repeat.id(), new SkillProgress(3, 1, true)));
                HabitsApi.giveCard(p, up, null);      // Lv4 4/5 -> Lv5!
                HabitsApi.giveCard(p, repeat, null);  // Lv3 2/4
                HabitsApi.giveCard(p, repeat, null);  // merges: Lv3 3/4
                HabitsApi.feedSigil(p, new ItemStack(Items.AMETHYST_SHARD), Rarity.RARE, Component.translatable("sigil.habits.potent"), 1);
                HabitsApi.giveCard(p, fresh, null);   // NEW
            });
        }));
        at(shots, "feed_in", 140);
        at(shots, "feed_mid", 2000);
        at(shots, "feed_fade", 5000 + 220);
        scale(shots, "feed_gs2", 2);
        scale(shots, "feed_gs4", 4);
        shots.add(new DevShots.Shot("feed_book_on", 10, mc -> {
            scaleNow(mc, 3);
            FlipHud.frozen = null;
            ClientHooks.each(ClientHooks::openBinder);
        }));
        shots.add(new DevShots.Shot("feed_book_off", 10, mc -> DevShots.serverDo(mc, p -> HabitsApi.setAutoAdd(p, false))));
        // hover the toggle (book units: left page footer, right end) for its tooltip
        shots.add(new DevShots.Shot("feed_book_tip", 4, mc -> hover(mc.screen, 52 + 10 + 215 - 20, 10 + 312 - 9)));
    }

    /** Dev only: the binder's package-private debug pointer. */
    private static void hover(Object screen, float x, float y) {
        try {
            for (String f : new String[]{"debugX", "debugY"}) {
                var field = screen.getClass().getDeclaredField(f);
                field.setAccessible(true);
                field.setFloat(screen, f.equals("debugX") ? x : y);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void at(List<DevShots.Shot> shots, String name, long t) {
        shots.add(new DevShots.Shot(name, 2, mc -> FlipHud.frozen = T0 + t));
    }

    private static void scale(List<DevShots.Shot> shots, String name, int gs) {
        shots.add(new DevShots.Shot(name, 3, mc -> {
            FlipHud.frozen = T0 + 2000;
            scaleNow(mc, gs);
        }));
    }

    private static void scaleNow(Minecraft mc, int gs) {
        mc.options.guiScale().set(gs);
        mc.resizeDisplay();
    }
}
