package com.habits.danger.client;

import com.habits.Habits;
import com.habits.client.DevShots;
import com.habits.danger.Elites;
import com.habits.danger.Scaling;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Dev-only: {@code HABITS_SHOTS=danger} shows a two-affix elite near the camera, then the "Danger rises" line. */
@EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
final class DangerShots {
    private DangerShots() {}

    @SubscribeEvent
    static void setup(FMLClientSetupEvent e) {
        DevShots.PLANS.put("danger", shots -> {
            shots.add(new DevShots.Shot("danger_elite", 25, mc -> DevShots.serverDo(mc, p -> {
                p.connection.teleport(p.getX(), p.getY(), p.getZ(), 0, 12);
                Mob m = EntityType.HUSK.create(p.serverLevel()); // husks do not burn in daylight, so the flames are the affix's
                m.moveTo(p.getX() + 0.3, p.getY(), p.getZ() + 4.5, 180, 0);
                m.setYHeadRot(180);
                m.setNoAi(true);
                p.serverLevel().addFreshEntity(m);
                Scaling.apply(m, 1, 1);
                Elites.make(m, Elites.Affix.FIERY.bit() | Elites.Affix.SHIELDED.bit(), 1);
            })));
            shots.add(new DevShots.Shot("danger_rises", 30, mc -> DevShots.serverDo(mc, p ->
                    p.connection.teleport(2800, p.getY(), p.getZ(), 0, 12))));
        });
    }
}
