package com.habits.client.fx;

import com.habits.client.DevShots;
import com.habits.core.CardData;
import com.habits.core.CardItems;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.client.CameraType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Dev shots for FX ({@code HABITS_SHOTS=fx}): dropped cards close up (a full inventory still drops them), the card in hand
 * (first and third person), flip frame sequences every 250 ms on a pinned clock (Common, Epic, Legendary), a left-click
 * skip mid-hold, a REAL right-click add (core's CardAdded must start the flip), and the frame-time measurement.
 * {@code HABITS_SHOTS=fxitems} takes only the item shots; {@code HABITS_SHOTS=fxflip} only the flip frames.
 */
final class FxShots {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long T0 = 1_000_000L;
    private static final int STEP = 250, PERF_TICKS = 200;

    private FxShots() {}

    static void register() {
        DevShots.PLANS.put("fxitems", FxShots::items);
        DevShots.PLANS.put("fxperf", FxShots::perf);
        DevShots.PLANS.put("fxflip", shots -> {
            flip(shots, "fx_common", Rarity.COMMON, 6000, -1, Kind.NEW);
            flip(shots, "fx_legendary", Rarity.LEGENDARY, 18000, -1, Kind.NEW);
            flip(shots, "fx_skip", Rarity.RARE, 6000, 1500, Kind.NEW);
        });
        DevShots.PLANS.put("fxv", shots -> {
            items(shots);
            flip(shots, "fx_common", Rarity.COMMON, 6000, -1, Kind.NEW);
            flip(shots, "fx_legendary", Rarity.LEGENDARY, 18000, -1, Kind.NEW);
            flip(shots, "fx_skip", Rarity.RARE, 6000, 1500, Kind.NEW);
            real(shots);
        });
        DevShots.PLANS.put("fx", shots -> {
            items(shots);
            flip(shots, "fx_common", Rarity.COMMON, 6000, -1, Kind.NEW);
            flip(shots, "fx_new", Rarity.EPIC, 6000, -1, Kind.NEW);
            flip(shots, "fx_legendary", Rarity.LEGENDARY, 18000, -1, Kind.NEW);
            flip(shots, "fx_skip", Rarity.RARE, 6000, 1500, Kind.NEW);
            real(shots);
            perf(shots);
        });
    }

    // ---- items: ground close-up, hand, slots -------------------------------------------------------------------------------

    private static void items(List<DevShots.Shot> shots) {
        shots.add(new DevShots.Shot("fx_ground_close", 60, mc -> DevShots.serverDo(mc, p -> stage(p, 2.2, 28))));
        shots.add(new DevShots.Shot("fx_ground_angles", 30, mc -> DevShots.serverDo(mc, p -> {
            clearItems(p);
            p.connection.teleport(p.getX(), p.getY(), p.getZ(), 0, 30);
            Rarity[] all = Rarity.values();
            for (int i = 0; i < 10; i++) // ten cards in a row: the item spin shows fronts, backs and edges side by side
                drop(p, p.getX() + (i - 4.5) * 0.7, p.getZ() + 2.6 + (i % 2) * 0.5, card(all[i % all.length]), true);
        })));
        shots.add(new DevShots.Shot("fx_item_frame", 30, mc -> DevShots.serverDo(mc, p -> {
            clearItems(p);
            p.connection.teleport(p.getX(), p.getY(), p.getZ(), 0, 10);
            net.minecraft.core.BlockPos wall = p.blockPosition().offset(0, 1, 3);
            p.serverLevel().setBlockAndUpdate(wall, net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
            for (int i = -1; i <= 1; i += 2) {
                net.minecraft.core.BlockPos w = wall.offset(i, 0, 0);
                p.serverLevel().setBlockAndUpdate(w, net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState());
            }
            Rarity[] rs = {Rarity.RARE, Rarity.EPIC, Rarity.LEGENDARY};
            for (int i = -1; i <= 1; i++) {
                net.minecraft.world.entity.decoration.ItemFrame f = new net.minecraft.world.entity.decoration.ItemFrame(p.serverLevel(),
                        wall.offset(i, 0, -1), net.minecraft.core.Direction.NORTH);
                f.setItem(card(rs[i + 1]));
                p.serverLevel().addFreshEntity(f);
            }
        })));
        shots.add(new DevShots.Shot("fx_hand_first", 10, mc -> {
            DevShots.serverDo(mc, p -> {
                clearItems(p);
                p.connection.teleport(p.getX(), p.getY(), p.getZ(), 180, 0); // back to the frame wall: room for the camera
            });
            mc.player.getInventory().selected = 4;
        }));
        shots.add(new DevShots.Shot("fx_hand_third", 10, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT)));
        shots.add(new DevShots.Shot("fx_hand_done", 2, mc -> {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.player.getInventory().selected = 0;
        }));
    }

    /** Common, Rare, Legendary cards {@code dist} blocks ahead (looking south, pitch {@code pitch}); hotbar = one per rarity. */
    private static void stage(ServerPlayer p, double dist, float pitch) {
        clearItems(p);
        p.connection.teleport(p.getX(), p.getY(), p.getZ(), 0, pitch);
        Rarity[] rs = {Rarity.COMMON, Rarity.RARE, Rarity.LEGENDARY};
        double gap = dist < 3 ? 0.9 : 1.6;
        for (int i = 0; i < 3; i++)
            drop(p, p.getX() + (1 - i) * gap, p.getZ() + dist, card(rs[i]), true);
        Rarity[] all = Rarity.values();
        for (int i = 0; i < all.length; i++) p.getInventory().setItem(i, card(all[i]));
        // own the first three skills at Lv1, 2, 3 so the slot level number shows
        for (int i = 0; i < 3; i++)
            for (int n = 0; n < 60 && HabitsApi.level(p, skill(all[i]).id()) < i + 1; n++)
                HabitsApi.addCard(p, skill(all[i]).id());
    }

    private static void drop(ServerPlayer p, double x, double z, ItemStack s, boolean frozen) {
        ItemEntity it = new ItemEntity(p.level(), x, p.getY() + 0.3, z, s);
        if (frozen) {
            it.setPickUpDelay(Short.MAX_VALUE); // ItemEntity.INFINITE_PICKUP_DELAY
            it.setUnlimitedLifetime();
        }
        it.setDeltaMovement(0, 0, 0);
        p.level().addFreshEntity(it);
    }

    private static void clearItems(ServerPlayer p) {
        p.serverLevel().getEntities(EntityType.ITEM, e -> true).forEach(e -> e.discard());
    }

    // ---- skills by rarity (the catalog changes; never name a skill) --------------------------------------------------

    /** A skill of rarity {@code r}; if the catalog has none, any skill with its rarity swapped (client-side fake). */
    private static SkillDef skill(Rarity r) {
        List<SkillDef> all = SkillRegistry.all().stream().sorted(Comparator.comparing(d -> d.id().toString())).toList();
        for (SkillDef d : all) if (d.rarity() == r) return d;
        SkillDef d = all.get(0);
        return new SkillDef(d.id(), r, d.category(), d.trigger(), d.icon(), d.art(), d.perk(), d.name(), d.foundBy(), d.perkText());
    }

    private static ItemStack card(Rarity r) {
        SkillDef d = skill(r);
        ItemStack s = CardItems.create(d);
        s.set(CoreRegistries.CARD_DATA.get(), new CardData(d.id(), r));
        return s;
    }

    // ---- flips ---------------------------------------------------------------------------------------------------------

    /** Frames every {@link #STEP} ms on a pinned clock; {@code skipAt} >= 0 left-clicks at that time. */
    enum Kind { NEW, PROGRESS, LEVEL_UP }

    private static FlipHud.Added added(SkillDef d, Kind k) {
        return switch (k) {
            case NEW -> new FlipHud.Added(d.id(), 1, 0, 2, true, false);
            case PROGRESS -> new FlipHud.Added(d.id(), 2, 1, 3, false, false);
            case LEVEL_UP -> new FlipHud.Added(d.id(), 3, 0, 4, false, true);
        };
    }

    private static void flip(List<DevShots.Shot> shots, String name, Rarity r, long dayTime, int skipAt, Kind kind) {
        shots.add(new DevShots.Shot(name + "_time", 10, mc -> DevShots.serverDo(mc, p -> p.serverLevel().setDayTime(dayTime))));
        double k = FxClientInit.perRarity(FxClientInit.SPEED, r);
        double hold = FxClientInit.perRarity(FxClientInit.HOLD_MS, r);
        double total = (skipAt >= 0 ? skipAt : FlipHud.REVEAL * k + hold) + FlipHud.FLY_MS * k + 300;
        LOG.info("[fxshot] {} {}: face up at {} ms, leaves at {} ms, gone at {} ms", name, r, FlipHud.REVEAL * k,
                FlipHud.REVEAL * k + hold, FlipHud.REVEAL * k + hold + FlipHud.FLY_MS * k);
        int frames = (int) (total / STEP) + 1;
        for (int i = 0; i < frames; i++) {
            long at = T0 + (long) i * STEP;
            boolean first = i == 0, skip = skipAt >= 0 && i * STEP == skipAt;
            shots.add(new DevShots.Shot(String.format("%s_%02d", name, i), first ? 4 : 2, mc -> {
                FlipHud.frozen = at;
                if (skip) FlipHud.skip();
                if (!first) return;
                FlipHud.clear();
                SkillDef d = skill(r);
                FlipHud.enqueue(d, added(d, kind));
                FlipHud.startNow();
            }));
        }
    }

    /** The real path: book in the hotbar, a card in hand, core's right-click add sends CardAdded, the flip plays. */
    private static void real(List<DevShots.Shot> shots) {
        shots.add(new DevShots.Shot("fx_real_add", 2, mc -> {
            FlipHud.frozen = null;
            FlipHud.clear();
            REAL_SEEN = 0;
            DevShots.serverDo(mc, p -> {
                p.serverLevel().setDayTime(6000);
                p.getInventory().setItem(8, new ItemStack(CoreRegistries.BOOK.get()));
                p.getInventory().selected = 2;
                // a skill the player does not own yet: only a brand-new unlock flips
                SkillDef fresh = SkillRegistry.all().stream().sorted(Comparator.comparing(d -> d.id().toString()))
                        .filter(d -> HabitsApi.level(p, d.id()) == 0).findFirst().orElseThrow();
                p.getInventory().setItem(2, CardItems.create(fresh));
                HabitsApi.addCardInHand(p, net.minecraft.world.InteractionHand.MAIN_HAND);
            });
        }));
        for (int i = 0; i < 8; i++)
            shots.add(new DevShots.Shot("fx_real_" + i, 4, mc -> LOG.info("[fxshot] real add: flips received {}, playing {}",
                    REAL_SEEN, FlipHud.playing())));
    }

    static int REAL_SEEN;

    // ---- perf ----------------------------------------------------------------------------------------------------------

    private static void perf(List<DevShots.Shot> shots) {
        shots.add(new DevShots.Shot("fx_perf_empty", PERF_TICKS, mc -> {
            FlipHud.frozen = null;
            FlipHud.clear();
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            mc.getWindow().updateVsync(false);
            DevShots.serverDo(mc, p -> {
                p.serverLevel().setDayTime(18000);
                clearItems(p);
            });
            Perf.start("baseline: same world, no cards, no flip");
        }));
        shots.add(new DevShots.Shot("fx_perf_paper", PERF_TICKS, mc -> {
            Perf.report();
            DevShots.serverDo(mc, p -> {
                for (int i = 0; i < 50; i++)
                    drop(p, p.getX() + (i % 10 - 4.5) * 1.2, p.getZ() + 4 + i / 10 * 1.2, new ItemStack(net.minecraft.world.item.Items.PAPER), true);
            });
            Perf.start("vanilla reference: 50 paper items");
        }));
        window(shots, "fx_perf_cards", "50 mixed cards on the ground, no flip", true, false);
        window(shots, "fx_perf_flip", "a flip always playing, no cards", false, true);
        window(shots, "fx_perf_full", "50 mixed cards on the ground + a flip always playing", true, true);
        shots.add(new DevShots.Shot("fx_perf_end", 1, mc -> Perf.report()));
    }

    private static void window(List<DevShots.Shot> shots, String name, String label, boolean cards, boolean flips) {
        shots.add(new DevShots.Shot(name, PERF_TICKS, mc -> {
            Perf.report();
            FlipHud.clear();
            DevShots.serverDo(mc, p -> {
                clearItems(p);
                if (!cards) return;
                Rarity[] all = Rarity.values();
                for (int i = 0; i < 50; i++)
                    drop(p, p.getX() + (i % 10 - 4.5) * 1.2, p.getZ() + 4 + i / 10 * 1.2, card(all[i % all.length]), true);
            });
            if (flips) { // a different card each time, so every flip paints a new face (the worst case)
                List<SkillDef> all = SkillRegistry.all().stream().sorted(Comparator.comparing(d -> d.id().toString())).toList();
                for (int i = 0; i < 8; i++) {
                    SkillDef d = all.get((i * 37) % all.size());
                    FlipHud.enqueue(d, added(d, Kind.PROGRESS));
                }
            }
            Perf.start(label);
        }));
    }

    /** Frame times (average and 1% low) and the CPU time spent inside FX render code, per measured window. */
    static final class Perf {
        static boolean on;
        static String label;
        static long fxNs, last;
        static int frames;
        static final long[] TIMES = new long[200_000];

        static long paints, paintNs, paintMax;

        static void paint(long ns) {
            paints++;
            paintNs += ns;
            paintMax = Math.max(paintMax, ns);
        }

        static void start(String l) {
            paints = paintNs = paintMax = 0;
            label = l;
            frames = 0;
            fxNs = last = 0;
            on = true;
        }

        static void frame() {
            if (!on) return;
            long now = System.nanoTime();
            if (last != 0 && frames < TIMES.length) TIMES[frames++] = now - last;
            last = now;
        }

        static void report() {
            if (!on || frames == 0) return;
            on = false;
            long[] t = Arrays.copyOf(TIMES, frames);
            Arrays.sort(t);
            long sum = 0;
            for (long v : t) sum += v;
            int n = Math.max(1, frames / 100);
            long worst = 0;
            for (int i = frames - n; i < frames; i++) worst += t[i];
            LOG.info("[fxperf] {}: {} frames, avg {} ms ({} fps), 1% low {} ms ({} fps), fx cpu {} us/frame", label, frames,
                    String.format("%.3f", sum / 1e6 / frames), String.format("%.0f", frames * 1e9 / sum),
                    String.format("%.3f", worst / 1e6 / n), String.format("%.0f", n * 1e9 / worst),
                    String.format("%.1f", fxNs / 1e3 / frames));
            if (paints > 0) LOG.info("[fxperf]   card faces painted: {}, avg {} ms, max {} ms", paints,
                    String.format("%.2f", paintNs / 1e6 / paints), String.format("%.2f", paintMax / 1e6));
        }
    }
}
