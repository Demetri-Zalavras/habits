package com.habits.perf.client;

import com.habits.Habits;
import com.habits.client.DevShots;
import com.habits.core.CardItems;
import com.habits.core.ClientHooks;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsCollection;
import com.habits.core.Payloads;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import com.habits.perf.PerfHarness;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dev-only client frame-time harness (docs/PERF.md): {@code HABITS_SHOTS=perf ./gradlew runClient --offline}. Uses
 * DevShots' fresh flat world, then rotates scenes for {@link #ROUNDS} rounds of {@link #SECONDS} s each, measuring the
 * interval between frames, render-thread allocation per frame and one JFR file per scene, and writes
 * {@code run/perf/client.txt}. Each scene ends with a screenshot (hb_perf_*.png) so the scene can be checked by eye.
 */
@EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT)
public final class PerfClient {
    private PerfClient() {}

    private static final Logger LOG = LogUtils.getLogger();
    static final int SECONDS = PerfHarnessEnv.SECONDS, ROUNDS = PerfHarnessEnv.ROUNDS;
    static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** paper: vanilla reference (50 paper items, no skills). off: 50 cards, mod visuals off (no beams, no flips, no
     * skills so no perk HUDs/outlines). cards: 50 cards with beams and a flip every 2 s, no skills (the design doc's
     * "50 cards plus a flip" case). full: every skill Lv10, 50 cards with beams, a flip every 2 s. binder: every
     * skill Lv10, binder open, a page turn every 0.5 s through every category. inventory: vanilla inventory screen. */
    enum Scene { PAPER, OFF, CARDS, FULL, BINDER, INVENTORY }

    static Scene scene;
    static boolean measuring;
    static long last, allocAtPre, gcCount0, gcMs0;
    static int frames, ticks;
    static long[] times = new long[1 << 20], allocs = new long[1 << 20];
    /** Per frame: render-event ns of the frame before, client-tick ns, and 1 when the binder turned a page in it. */
    static long[] renders = new long[1 << 20], tickNs = new long[1 << 20], flips = new long[1 << 20];
    static long tickStart, renderStart, curTick, curRender, curFlip;
    static Object recording;
    static final Map<Scene, List<double[]>> results = new TreeMap<>();
    static final List<String> report = new ArrayList<>();

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent e) {
        DevShots.PLANS.put("perf", PerfClient::plan);
    }

    static void plan(List<DevShots.Shot> shots) {
        shots.add(new DevShots.Shot("perf_setup", 60, mc -> {
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            mc.getWindow().updateVsync(false);
            mc.options.renderDistance().set(12);
            DevShots.serverDo(mc, p -> {
                p.serverLevel().setDayTime(18000);
                busyWorld(p);
            });
        }));
        for (int r = 0; r < ROUNDS; r++)
            for (Scene s : Scene.values()) {
                if (PerfHarnessEnv.SCENES != null && !PerfHarnessEnv.SCENES.contains(s.name())) continue;
                final int round = r + 1;
                shots.add(new DevShots.Shot("perf_" + round + "_" + s.name().toLowerCase(), SECONDS * 20, mc -> begin(mc, s, round)));
            }
        shots.add(new DevShots.Shot("perf_end", 1, mc -> { end(); summary(); }));
    }

    // ---------------------------------------------------------------- scenes

    static void begin(Minecraft mc, Scene s, int round) {
        end();
        if (mc.screen != null) mc.setScreen(null);
        boolean mod = s == Scene.FULL || s == Scene.BINDER;
        fx("BEAM", s != Scene.OFF);
        fx("FLIP", s != Scene.OFF);
        DevShots.serverDo(mc, p -> {
            p.setData(CoreRegistries.COLLECTION, mod ? everything() : HabitsCollection.EMPTY);
            p.getInventory().clearContent();
            p.getInventory().add(new ItemStack(CoreRegistries.BOOK.get()));
            p.setYRot(0);
            p.setXRot(20);
            p.connection.teleport(p.getX(), p.getY(), p.getZ(), 0, 20);
            clearItems(p);
            if (s == Scene.BINDER || s == Scene.INVENTORY) return;
            Rarity[] rar = Rarity.values();
            List<SkillDef> defs = sorted();
            for (int i = 0; i < 50; i++) {
                ItemStack st = s == Scene.PAPER ? new ItemStack(Items.PAPER)
                        : CardItems.create(byRarity(defs, rar[i % rar.length]));
                drop(p, p.getX() + (i % 10 - 4.5) * 1.2, p.getZ() + 4 + (i / 10) * 1.2, st);
            }
        });
        scene = s;
        ticks = 0;
        frames = 0;
        last = 0;
        measuring = true;
        gcCount0 = gc(true);
        gcMs0 = gc(false);
        recording = PerfHarness.Jfr.start("perf/client-" + round + "-" + s.name().toLowerCase() + ".jfr");
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        if (!measuring) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if ((scene == Scene.FULL || scene == Scene.CARDS) && ticks % 40 == 1) { // a flip every 2 s, a different card each time (fresh face paint)
            List<SkillDef> defs = sorted();
            SkillDef d = defs.get((ticks / 40 * 37) % defs.size());
            var payload = new Payloads.CardAdded(d.id(), 1, 0, 1, true, false);
            ClientHooks.each(h -> h.cardAdded(payload)); // the flip plays for brand-new skills
        }
        if (scene == Scene.BINDER) {
            if (ticks == 2) ClientHooks.each(ClientHooks::openBinder);
            else if (ticks > 2 && ticks % 10 == 0 && mc.screen != null) {
                curFlip = 1;
                var before = mc.screen;
                mc.screen.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0);
                if (ticks % 400 == 0) { mc.setScreen(null); ClientHooks.each(ClientHooks::openBinder); } // wrap to the first spread
                if (before != mc.screen) LOG.debug("[habits-perf] binder reopened");
            }
        }
        if (scene == Scene.INVENTORY && ticks == 2) mc.setScreen(new InventoryScreen(mc.player));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void framePre(RenderFrameEvent.Pre e) {
        if (!measuring || ticks < 20) return; // first second: chunks, scene spawn
        long now = System.nanoTime();
        if (last != 0 && frames < times.length) {
            times[frames] = now - last;
            allocs[frames] = THREADS.getCurrentThreadAllocatedBytes() - allocAtPre;
            renders[frames] = curRender;
            tickNs[frames] = curTick;
            flips[frames] = curFlip;
            frames++;
        }
        curRender = curTick = curFlip = 0;
        renderStart = now;
        last = now;
        allocAtPre = THREADS.getCurrentThreadAllocatedBytes();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void framePost(RenderFrameEvent.Post e) {
        if (measuring && renderStart != 0) curRender = System.nanoTime() - renderStart;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tickPre(ClientTickEvent.Pre e) {
        tickStart = System.nanoTime();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tickPost(ClientTickEvent.Post e) {
        if (measuring) curTick += System.nanoTime() - tickStart;
    }

    /** The slowest frames split into the render pass before them, client ticks, and the rest (swap, GPU wait, GC). */
    static void worstFrames() {
        Integer[] idx = new Integer[frames];
        for (int i = 0; i < frames; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Long.compare(times[b], times[a]));
        int n1 = Math.max(1, frames / 100);
        double t = 0, r = 0, k = 0, f = 0;
        for (int j = 0; j < n1; j++) { int i = idx[j]; t += times[i]; r += renders[i]; k += tickNs[i]; f += flips[i]; }
        LOG.info("[habits-perf] {} worst 1% ({} frames): interval {} render {} tick {} rest {} ms, {} with a page turn", scene, n1,
                ms(t / n1), ms(r / n1), ms(k / n1), ms((t - r - k) / n1), (int) f);
        double tr = 0, tt = 0, ta = 0;
        for (int i = 0; i < frames; i++) { ta += times[i]; tr += renders[i]; tt += tickNs[i]; }
        LOG.info("[habits-perf] {} all: interval {} render {} tick {} rest {} ms", scene, ms(ta / frames), ms(tr / frames), ms(tt / frames), ms((ta - tr - tt) / frames));
        // a page turn happens in a tick; its render (face builds) lands in the next frame's interval
        for (int off = 0; off < 4; off++) {
            double sum = 0, rs = 0, max = 0;
            int n = 0;
            for (int i = 0; i + off < frames; i++) {
                if (flips[i] != 1) continue;
                long v = times[i + off];
                sum += v; rs += renders[i + off]; max = Math.max(max, v); n++;
            }
            if (n > 0) LOG.info("[habits-perf] {} page turn +{} frames ({} turns): interval mean {} max {}, render mean {} ms", scene, off, n,
                    ms(sum / n), ms(max), ms(rs / n));
        }
        for (int j = 0; j < Math.min(10, frames); j++) {
            int i = idx[j];
            LOG.info("[habits-perf]   frame {}: interval {} render {} tick {} rest {} ms{}", i, ms(times[i]), ms(renders[i]), ms(tickNs[i]),
                    ms(times[i] - renders[i] - tickNs[i]), flips[i] == 1 ? " PAGE TURN" : "");
        }
    }

    static String ms(double ns) {
        return String.format("%.2f", ns / 1e6);
    }

    static void end() {
        if (!measuring) return;
        measuring = false;
        if (recording != null) PerfHarness.Jfr.stop(recording);
        recording = null;
        if (frames < 10) return;
        worstFrames();
        long[] t = Arrays.copyOf(times, frames);
        Arrays.sort(t);
        double mean = Arrays.stream(t).average().orElse(0) / 1e6;
        int n1 = Math.max(1, frames / 100), n01 = Math.max(1, frames / 1000);
        double low1 = 0, low01 = 0;
        for (int i = frames - n1; i < frames; i++) low1 += t[i];
        for (int i = frames - n01; i < frames; i++) low01 += t[i];
        low1 = low1 / n1 / 1e6;
        low01 = low01 / n01 / 1e6;
        double kb = Arrays.stream(allocs, 0, frames).average().orElse(0) / 1024;
        results.computeIfAbsent(scene, k -> new ArrayList<>()).add(new double[]{mean, low1, low01, 1000 / mean, 1000 / low1, kb});
        String line = String.format("%-9s %6d frames  mean %.3f ms (%.0f fps)  1%% low %.3f ms (%.0f fps)  0.1%% low %.3f ms  render-thread alloc %.1f KB/frame  gc %d (%d ms)",
                scene, frames, mean, 1000 / mean, low1, 1000 / low1, low01, kb, gc(true) - gcCount0, gc(false) - gcMs0);
        report.add(line);
        LOG.info("[habits-perf] {}", line);
    }

    static void summary() {
        fx("BEAM", true);
        fx("FLIP", true);
        report.add("");
        report.add("median of " + ROUNDS + " rounds, " + SECONDS + " s each (mean ms / 1% low ms / 0.1% low ms / mean fps / 1% low fps / KB per frame):");
        results.forEach((s, rs) -> {
            double[] med = new double[6];
            for (int k = 0; k < 6; k++) {
                final int kk = k;
                double[] col = rs.stream().mapToDouble(r -> r[kk]).sorted().toArray();
                med[k] = col[col.length / 2];
            }
            report.add(String.format("%-9s %.3f / %.3f / %.3f / %.0f / %.0f / %.1f", s, med[0], med[1], med[2], med[3], med[4], med[5]));
        });
        try {
            Files.createDirectories(Path.of("perf"));
            Files.write(Path.of("perf/client.txt"), report);
        } catch (Exception ex) {
            LOG.error("[habits-perf] write failed", ex);
        }
        report.forEach(l -> LOG.info("[habits-perf] {}", l));
    }

    // ---------------------------------------------------------------- helpers

    /** Collections so far (count) or their total time in ms, all collectors. */
    static long gc(boolean count) {
        long n = 0;
        for (var b : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, count ? b.getCollectionCount() : b.getCollectionTime());
        return n;
    }

    static List<SkillDef> sorted() {
        return SkillRegistry.all().stream().sorted(Comparator.comparing(d -> d.id().toString())).toList();
    }

    static SkillDef byRarity(List<SkillDef> defs, Rarity r) {
        for (SkillDef d : defs) if (d.rarity() == r) return d;
        return defs.get(0);
    }

    static HabitsCollection everything() {
        Map<ResourceLocation, SkillProgress> m = new HashMap<>();
        for (SkillDef d : SkillRegistry.all()) m.put(d.id(), new SkillProgress(10, 0, true));
        return new HabitsCollection(m);
    }

    /** 40 animals and 12 frozen zombies around the player so outlines, glow and entity rendering have work. */
    static void busyWorld(ServerPlayer p) {
        var l = p.serverLevel();
        List<EntityType<? extends Mob>> kinds = List.of(EntityType.COW, EntityType.SHEEP, EntityType.PIG, EntityType.CHICKEN);
        for (int i = 0; i < 52; i++) {
            EntityType<? extends Mob> t = i < 40 ? kinds.get(i % 4) : EntityType.ZOMBIE;
            Mob m = t.create(l);
            if (m == null) continue;
            double a = i * 0.7, r = 8 + (i % 5) * 3;
            m.moveTo(p.getX() + Math.cos(a) * r, p.getY(), p.getZ() + Math.sin(a) * r, 0, 0);
            m.setPersistenceRequired();
            if (t == EntityType.ZOMBIE) { m.setNoAi(true); m.setSilent(true); }
            l.addFreshEntity(m);
        }
    }

    static void drop(ServerPlayer p, double x, double z, ItemStack s) {
        ItemEntity it = new ItemEntity(p.level(), x, p.getY() + 0.3, z, s);
        it.setPickUpDelay(Short.MAX_VALUE);
        it.setUnlimitedLifetime();
        it.setDeltaMovement(0, 0, 0);
        p.level().addFreshEntity(it);
    }

    static void clearItems(ServerPlayer p) {
        p.serverLevel().getEntities(EntityType.ITEM, e -> true).forEach(Entity::discard);
    }

    /** FX's beam / flip switches, set through reflection (dev harness only; restored at the end). */
    static void fx(String field, boolean on) {
        try {
            var f = Class.forName("com.habits.client.fx.FxClientInit").getDeclaredField(field);
            f.setAccessible(true);
            ((ModConfigSpec.BooleanValue) f.get(null)).set(on);
        } catch (NoSuchFieldException ex) {
            // switch removed from FX (the loot beam is gone): nothing to toggle
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Env knobs shared with nothing else; a holder so the constants stay compile-time simple. */
    static final class PerfHarnessEnv {
        static final int SECONDS = env("HABITS_PERF_SECONDS", 30), ROUNDS = env("HABITS_PERF_ROUNDS", 3);
        /** Comma list of scene names to run (HABITS_PERF_SCENES=BINDER), or null for all. */
        static final String SCENES = System.getenv("HABITS_PERF_SCENES");

        static int env(String k, int def) {
            String v = System.getenv(k);
            return v == null ? def : Integer.parseInt(v);
        }
    }
}
