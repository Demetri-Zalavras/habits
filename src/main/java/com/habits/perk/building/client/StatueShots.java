package com.habits.perk.building.client;

import com.habits.Habits;
import com.habits.client.DevShots;
import com.habits.core.Category;
import com.habits.core.CoreRegistries;
import com.habits.core.Specs;
import com.habits.perk.building.Statue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import java.util.List;

/** Dev-only: {@code HABITS_SHOTS=statue} places a statue in front of the player as a sculptor. */
@EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
final class StatueShots {
    private StatueShots() {}

    @SubscribeEvent
    static void setup(FMLClientSetupEvent e) {
        DevShots.PLANS.put("statue", shots -> {
            shots.add(new DevShots.Shot("statue", 30, mc -> { mc.setScreen(null); mc.options.hideGui = true; DevShots.serverDo(mc, p -> {
                p.setData(CoreRegistries.SPECS, new Specs(List.of(Category.WARRIOR, Category.BUILDING), false));
                p.connection.teleport(p.getX(), p.getY(), p.getZ(), 0, 25);
                BlockPos at = p.blockPosition().south(3);
                var stack = new ItemStack(Statue.ITEM.get());
                p.serverLevel().setBlock(at, Statue.BLOCK.get().defaultBlockState(), 3);
                Statue.BLOCK.get().setPlacedBy(p.serverLevel(), at, p.serverLevel().getBlockState(at), p, stack);
            }); }));
            shots.add(new DevShots.Shot("statue_close", 20, mc -> { mc.setScreen(null); DevShots.serverDo(mc, p ->
                    p.connection.teleport(p.getX() + 1.2, p.getY(), p.getZ() + 1.6, 40, 35)); }));
        });
    }
}
