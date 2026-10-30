package com.habits.client.movement;

import com.habits.perk.movement.Mech;
import com.habits.perk.movement.MoveClientApi;
import com.habits.perk.movement.MovePayloads;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;

/**
 * The shared compass bar (Pathfinder and every skill that marks things). Markers come from the server every few
 * seconds and are cached here; per frame it only does arithmetic and draws. Text is rebuilt only when the player
 * changes block.
 */
final class CompassBar {
    private CompassBar() {}

    static final int WIDTH = 182;
    static final int[] COLORS = {0xFFFF5555, 0xFFBBBBBB, 0xFF55FF55, 0xFF55FFFF, 0xFFFFD700, 0xFFFFFFFF, 0xFFFF3333, 0xFFC08040,
            0xFFFFAA00, 0xFFFF55FF};

    private static byte[] kinds = new byte[0];
    private static int[] mx = new int[0], my = new int[0], mz = new int[0];
    private static String[] labels = new String[0];
    private static long labelsFor = Long.MIN_VALUE;
    private static String coords = "";
    private static String[] letters;

    static void set(MovePayloads.Markers m) {
        int n = m.kinds().length;
        kinds = m.kinds();
        mx = new int[n];
        my = new int[n];
        mz = new int[n];
        for (int i = 0; i < n; i++) {
            long l = m.xyz()[i];
            mx[i] = BlockPos.getX(l);
            my[i] = BlockPos.getY(l);
            mz[i] = BlockPos.getZ(l);
        }
        labels = new String[n];
        labelsFor = Long.MIN_VALUE;
    }

    static void clear() {
        set(new MovePayloads.Markers(new byte[0], new long[0]));
        letters = null;
    }

    static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.options.hideGui) return;
        boolean path = MoveClientApi.has(p, Mech.PATHFINDER);
        if (!path && kinds.length == 0) return;
        if (letters == null) letters = new String[]{I18n.get("habits.movement.compass.s"), I18n.get("habits.movement.compass.w"),
                I18n.get("habits.movement.compass.n"), I18n.get("habits.movement.compass.e")};
        Font font = mc.font;
        int cx = g.guiWidth() / 2, half = WIDTH / 2, y = 3;
        g.fill(cx - half, y, cx + half, y + 12, 0x88000000);
        float yaw = p.getViewYRot(dt.getGameTimeDeltaPartialTick(true));
        for (int deg = 0; deg < 360; deg += 15) {
            float rel = wrap(deg - yaw);
            if (Math.abs(rel) > 88) continue;
            int x = cx + Math.round(rel / 90f * (half - 4));
            if (deg % 90 == 0) {
                String s = letters[deg / 90];
                g.drawString(font, s, x - font.width(s) / 2, y + 2, deg == 180 ? 0xFFFF6060 : 0xFFFFFFFF, true);
            } else g.fill(x, y + 8, x + 1, y + 11, 0xAAFFFFFF);
        }
        BlockPos at = p.blockPosition();
        long key = at.asLong();
        if (key != labelsFor) rebuildText(p, at, path);
        for (int i = 0; i < kinds.length; i++) {
            double dx = mx[i] + 0.5 - p.getX(), dz = mz[i] + 0.5 - p.getZ();
            float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float rel = wrap(bearing - yaw);
            boolean off = Math.abs(rel) > 88;
            int x = cx + Math.round(Math.max(-88, Math.min(88, rel)) / 90f * (half - 4));
            int color = COLORS[Math.max(0, Math.min(COLORS.length - 1, kinds[i]))];
            if (off) color = (color & 0x00FFFFFF) | 0x66000000;
            g.fill(x - 2, y + 4, x + 3, y + 9, 0xFF000000);
            g.fill(x - 1, y + 5, x + 2, y + 8, color);
            if (!off && labels[i] != null) g.drawString(font, labels[i], x - font.width(labels[i]) / 2, y + 14, color, true);
        }
        if (path) g.drawString(font, coords, cx - font.width(coords) / 2, y + (anyLabel() ? 24 : 14), 0xFFE0E0E0, true);
    }

    private static boolean anyLabel() {
        for (String s : labels) if (s != null) return true;
        return false;
    }

    /** Distance labels and the coordinate line, rebuilt only when the player's block changes. */
    private static void rebuildText(LocalPlayer p, BlockPos at, boolean path) {
        labelsFor = at.asLong();
        coords = at.getX() + ", " + at.getY() + ", " + at.getZ();
        boolean homeDist = MoveClientApi.has(p, Mech.OVERWORLDER)
                && MoveClientApi.level(p, Mech.OVERWORLDER) >= MoveClientApi.param(p, Mech.OVERWORLDER, "distance_level");
        for (int i = 0; i < kinds.length; i++) {
            byte k = kinds[i];
            boolean show = k == MovePayloads.M_HOME ? homeDist
                    : k == MovePayloads.M_BED || k == MovePayloads.M_DEATH || k == MovePayloads.M_WAYPOINT || k == MovePayloads.M_SPY
                    || k == MovePayloads.M_TREASURE;
            labels[i] = show ? I18n.get("habits.movement.compass.dist", (int) Math.hypot(mx[i] - at.getX(), mz[i] - at.getZ())) : null;
        }
    }

    static float wrap(float deg) {
        deg %= 360;
        if (deg >= 180) deg -= 360;
        if (deg < -180) deg += 360;
        return deg;
    }
}
