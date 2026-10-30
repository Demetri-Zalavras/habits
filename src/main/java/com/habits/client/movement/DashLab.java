package com.habits.client.movement;

import com.habits.client.DevShots;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsCollection;
import com.habits.core.SkillProgress;
import com.habits.perk.movement.Mech;
import com.habits.perk.movement.MoveClientApi;
import com.habits.perk.movement.MovePayloads;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev-only dash measurement ({@code HABITS_SHOTS=dashlab}): sprints a real client down a flat lane, dashes on the ground,
 * at a jump apex and off a 12 block ledge, and logs how much farther each run went than the same run without a dash.
 */
@EventBusSubscriber(modid = com.habits.Habits.MODID, value = Dist.CLIENT)
final class DashLab {
    private DashLab() {}

    /** The _DIAG modes run at 45 degrees, in their own lanes far from the ledges. */
    enum Mode {
        GROUND, APEX, LEDGE, GROUND_DIAG, APEX_DIAG;
        boolean diag() { return name().endsWith("_DIAG"); }
        Mode base() { return diag() ? valueOf(name().substring(0, name().length() - 5)) : this; }
    }

    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    // DASHLAB_LEVELS / DASHLAB_MODES / DASHLAB_REPS narrow or repeat the sweep, e.g. DASHLAB_MODES=GROUND
    private static final int[] LEVELS = java.util.Arrays.stream(env("DASHLAB_LEVELS", "1,5,10").split(",")).mapToInt(Integer::parseInt).toArray();
    private static final java.util.List<Mode> MODES = java.util.Arrays.stream(env("DASHLAB_MODES", "GROUND,APEX,LEDGE,GROUND_DIAG,APEX_DIAG").split(",")).map(Mode::valueOf).toList();
    private static final int REPS = Integer.parseInt(env("DASHLAB_REPS", "1"));
    private static boolean on;
    private static int t, level, lane, air, trigAt;
    private static Mode mode;
    private static boolean dash, fired, left;
    private static double x0, z0;

    static void register() {
        DevShots.PLANS.put("dashlab", shots -> {
            int n = 0;
            for (int lv : LEVELS)
                for (Mode m : MODES)
                    for (int r = 0; r <= REPS; r++) {
                        boolean d = r > 0; // one run without a dash, then REPS with
                        int laneZ = n++;
                        shots.add(new DevShots.Shot("dashlab", 200, mc -> start(mc, lv, m, d, laneZ)));
                    }
        });
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? def : v;
    }

    static void start(Minecraft mc, int lv, Mode m, boolean d, int laneZ) {
        level = lv;
        mode = m;
        dash = d;
        lane = laneZ;
        t = 0;
        fired = false;
        left = false;
        air = 0;
        on = true;
        DevShots.serverDo(mc, sp -> {
            sp.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(Mech.DASH.skillId, new SkillProgress(lv, 0, true)));
            sp.getFoodData().setFoodLevel(20);
            sp.setHealth(sp.getMaxHealth());
            sp.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 400, 4, false, false));
            int z = 8 * laneZ + (m.diag() ? 3000 : 0), y = sp.serverLevel().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0); // flat world; far lanes aren't loaded yet
            if (m == Mode.LEDGE) {
                for (int x = -1; x <= 14; x++)
                    for (int dy = 0; dy < 12; dy++) sp.serverLevel().setBlockAndUpdate(new BlockPos(x, y + dy, z), Blocks.STONE.defaultBlockState());
                y += 12;
            }
            sp.teleportTo(sp.serverLevel(), 0.5, y, z + 0.5, m.diag() ? -45 : -90, 0);
            sp.setDeltaMovement(0, 0, 0);
        });
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        if (!on) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        t++;
        if (t == 10) {
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
        }
        if (!fired) {
            boolean go = switch (mode.base()) {
                case GROUND -> t == 40;
                case APEX -> {
                    if (t == 40) mc.options.keyJump.setDown(true);
                    if (t == 41) mc.options.keyJump.setDown(false);
                    yield t > 42 && !p.onGround() && p.getDeltaMovement().y <= 0;
                }
                default -> {
                    if (!left && p.getX() > 15 && !p.onGround()) left = true;
                    yield left && ++air >= 2;
                }
            };
            if (go) {
                fired = true;
                trigAt = t;
                x0 = p.getX();
                z0 = p.getZ();
                air = 0;
                if (dash) MovementClient.send(MovePayloads.DASH, 0);
            }
            return;
        }
        if (!p.onGround()) air++;
        if (t - trigAt == 80) {
            LOG.info("[dashlab] level={} listed={} mode={} dash={} dx={} airTicks={} sprinting={}", level,
                    MoveClientApi.value(p, Mech.DASH), mode, dash, String.format("%.3f", Math.hypot(p.getX() - x0, p.getZ() - z0)), air, p.isSprinting());
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            on = false;
        }
    }
}
