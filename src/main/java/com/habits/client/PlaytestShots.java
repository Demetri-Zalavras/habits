package com.habits.client;

import com.habits.Habits;
import com.habits.core.HabitsApi;
import com.mojang.logging.LogUtils;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.slf4j.Logger;

import java.util.List;

/**
 * Dev shots for the playtest-only checks ({@code HABITS_SHOTS=playtest}): the outlines (Spider Sense, Owl Eyes, Shoulder
 * Lookout), Rummager's slot tint, Bookworm's hints and pandas near Sneeze Harvest. Each perk goes through the real path
 * where a shot can drive it (the client really eats the spider eye; the server really opens the chest).
 */
final class PlaytestShots {
    private static final Logger LOG = LogUtils.getLogger();

    private PlaytestShots() {}

    static void register() {
        DevShots.PLANS.put("playtest", PlaytestShots::plan);
        DevShots.PLANS.put("playtest_heave", PlaytestShots::heave);
    }

    static void grant(ServerPlayer p, String skill, int level) {
        for (int n = 0; n < 80 && HabitsApi.level(p, Habits.id(skill)) < level; n++) HabitsApi.addCard(p, Habits.id(skill));
    }

    /** Clears a 21x21 room around the player, faces south, and returns the floor block under the player. */
    private static BlockPos reset(ServerPlayer p) {
        ServerLevel l = p.serverLevel();
        l.getEntities(p, p.getBoundingBox().inflate(40), e -> !(e instanceof ServerPlayer)).forEach(e -> e.discard());
        BlockPos c = p.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(c.offset(-12, 0, -12), c.offset(12, 6, 14))) l.setBlockAndUpdate(b, Blocks.AIR.defaultBlockState());
        p.connection.teleport(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 0, 5);
        return c;
    }

    private static void wall(ServerLevel l, BlockPos from, BlockPos to, Block b) {
        for (BlockPos x : BlockPos.betweenClosed(from, to)) l.setBlockAndUpdate(x, b.defaultBlockState());
    }

    private static <T extends Mob> T spawn(ServerLevel l, EntityType<T> type, BlockPos at) {
        T m = type.create(l);
        m.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180, 0);
        m.finalizeSpawn(l, l.getCurrentDifficultyAt(at), MobSpawnType.COMMAND, null);
        m.setNoAi(true);
        m.setPersistenceRequired();
        l.addFreshEntity(m);
        return m;
    }

    private static void plan(List<DevShots.Shot> shots) {
        // ---- Spider Sense: the client eats a spider eye for real; creepers hide behind a wall 8 blocks south
        shots.add(new DevShots.Shot("pt_sense_setup", 10, mc -> DevShots.serverDo(mc, p -> {
            BlockPos c = reset(p);
            grant(p, "spider_sense", 10);
            wall(p.serverLevel(), c.offset(-4, 0, 4), c.offset(4, 3, 4), Blocks.STONE);
            spawn(p.serverLevel(), EntityType.CREEPER, c.offset(-2, 0, 8));
            spawn(p.serverLevel(), EntityType.SPIDER, c.offset(2, 0, 8));
            p.getFoodData().setFoodLevel(6);
            p.getInventory().selected = 0;
            p.getInventory().setItem(0, new ItemStack(Items.SPIDER_EYE, 4));
        })));
        shots.add(new DevShots.Shot("pt_sense_eating", 45, mc -> mc.options.keyUse.setDown(true)));
        shots.add(new DevShots.Shot("pt_sense", 10, mc -> mc.options.keyUse.setDown(false)));
        shots.add(new DevShots.Shot("pt_sense_third", 10, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK)));

        // ---- Owl Eyes: night vision + ores buried in a stone block 6 blocks south
        shots.add(new DevShots.Shot("pt_owl", 60, mc -> {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            DevShots.serverDo(mc, p -> {
                BlockPos c = reset(p);
                grant(p, "owl_eyes", 10);
                ServerLevel l = p.serverLevel();
                wall(l, c.offset(-4, 0, 4), c.offset(4, 3, 8), Blocks.STONE);
                l.setBlockAndUpdate(c.offset(-2, 0, 6), Blocks.DIAMOND_ORE.defaultBlockState());
                l.setBlockAndUpdate(c.offset(0, 1, 7), Blocks.IRON_ORE.defaultBlockState());
                l.setBlockAndUpdate(c.offset(2, 2, 6), Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState());
                p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 2000, 0));
            });
        }));

        // ---- Shoulder Lookout: a parrot on the shoulder, a zombie behind the player; front camera sees both
        shots.add(new DevShots.Shot("pt_lookout_setup", 5, mc -> DevShots.serverDo(mc, p -> {
            p.removeEffect(MobEffects.NIGHT_VISION);
            BlockPos c = reset(p);
            grant(p, "shoulder_lookout", 10);
            Parrot parrot = EntityType.PARROT.create(p.serverLevel());
            parrot.moveTo(p.getX(), p.getY(), p.getZ());
            parrot.tame(p);
            p.serverLevel().addFreshEntity(parrot);
            LOG.info("[hbshots] parrot on shoulder: {}", parrot.setEntityOnShoulder(p));
            wall(p.serverLevel(), c.offset(-3, 0, -4), c.offset(3, 2, -4), Blocks.OAK_PLANKS);
            spawn(p.serverLevel(), EntityType.CREEPER, c.offset(-1, 0, -8));
            spawn(p.serverLevel(), EntityType.SKELETON, c.offset(1, 0, -8)).setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
                    new ItemStack(Items.LEATHER_HELMET));
        })));
        shots.add(new DevShots.Shot("pt_lookout", 30, mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT)));

        // ---- Rummager: the server opens a real chest; dirt and cobblestone are carried, the rest is not
        shots.add(new DevShots.Shot("pt_rummager", 30, mc -> {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            DevShots.serverDo(mc, p -> {
                BlockPos c = reset(p);
                grant(p, "rummager", 10);
                p.getInventory().clearContent();
                p.getInventory().setItem(0, new ItemStack(Items.DIRT, 12));
                p.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 5));
                BlockPos at = c.offset(0, 0, 2);
                p.serverLevel().setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
                if (p.serverLevel().getBlockEntity(at) instanceof ChestBlockEntity chest) {
                    ItemStack[] in = {new ItemStack(Items.DIRT, 30), new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.COBBLESTONE, 64),
                            new ItemStack(Items.OAK_LOG, 8), new ItemStack(Items.DIRT, 4), new ItemStack(Items.BREAD, 3)};
                    for (int i = 0; i < in.length; i++) chest.setItem(i * 3, in[i]);
                    p.openMenu(chest);
                }
            });
        }));

        // ---- Bookworm: a real enchanting table with 15 shelves, a sword and lapis put in, the cursor on the third offer
        shots.add(new DevShots.Shot("pt_bookworm_setup", 20, mc -> {
            mc.setScreen(null);
            DevShots.serverDo(mc, p -> {
                p.closeContainer();
                BlockPos c = reset(p);
                grant(p, "bookworm", 10);
                ServerLevel l = p.serverLevel();
                BlockPos t = c.offset(0, 0, 3);
                l.setBlockAndUpdate(t, Blocks.ENCHANTING_TABLE.defaultBlockState());
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == 2 && !(dz == -2 && Math.abs(dx) <= 1))
                        l.setBlockAndUpdate(t.offset(dx, 0, dz), Blocks.BOOKSHELF.defaultBlockState());
                p.onEnchantmentPerformed(ItemStack.EMPTY, 0); // a fresh player has seed 0, which a short carries whole
                p.setExperienceLevels(30);
                p.openMenu(l.getBlockState(t).getMenuProvider(l, t));
                p.containerMenu.getSlot(0).set(new ItemStack(Items.DIAMOND_SWORD));
                p.containerMenu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 10));
            });
        }));
        shots.add(new DevShots.Shot("pt_bookworm", 10, mc -> hoverOffer(mc, 2)));
        shots.add(new DevShots.Shot("pt_bookworm_1", 10, mc -> hoverOffer(mc, 0)));
        // what a dedicated-server client gets: the seed data slot cut to a short. The hints must still match the server's roll.
        shots.add(new DevShots.Shot("pt_bookworm_remote", 10, mc -> {
            var menu = mc.player.containerMenu;
            if (menu instanceof net.minecraft.world.inventory.EnchantmentMenu m) {
                m.setData(3, (short) m.getEnchantmentSeed());
                for (int r = 0; r < 3; r++) LOG.info("[hbshots] bookworm seed {} (the old client path) row {}: {}", m.getEnchantmentSeed(), r + 1,
                        names(m.getEnchantmentList(mc.level.registryAccess(), m.getSlot(0).getItem(), r, m.costs[r])));
            }
            hoverOffer(mc, 2);
            DevShots.serverDo(mc, p -> {
                if (p.containerMenu instanceof net.minecraft.world.inventory.EnchantmentMenu m)
                    for (int r = 0; r < 3; r++) LOG.info("[hbshots] bookworm server seed {} row {}: {}", m.getEnchantmentSeed(), r + 1,
                            names(m.getEnchantmentList(p.registryAccess(), m.getSlot(0).getItem(), r, m.costs[r])));
            });
        }));

        heave(shots);

        // ---- Sneeze Harvest: baby pandas around the player; frames every second, slimeballs counted in the log
        shots.add(new DevShots.Shot("pt_sneeze_setup", 5, mc -> {
            mc.setScreen(null);
            DevShots.serverDo(mc, p -> {
                p.closeContainer();
                BlockPos c = reset(p);
                grant(p, "sneeze_harvest", 10);
                for (int i = 0; i < 6; i++) {
                    Panda pd = EntityType.PANDA.create(p.serverLevel());
                    pd.setAge(-24000 * 5);
                    pd.setMainGene(Panda.Gene.WEAK); // weak babies sneeze most, so a short run catches it
                    pd.setHiddenGene(Panda.Gene.WEAK);
                    pd.moveTo(c.getX() - 2.5 + i, c.getY(), c.getZ() + 4 + (i % 2), 180, 0);
                    pd.setPersistenceRequired();
                    p.serverLevel().addFreshEntity(pd);
                }
            });
        }));
        for (int i = 0; i < 30; i++) {
            int n = i;
            shots.add(new DevShots.Shot("pt_sneeze_" + i, 10, mc -> DevShots.serverDo(mc, p -> LOG.info("[hbshots] sneeze frame {} slimeballs={} sneezing={}", n,
                    p.serverLevel().getEntities(EntityType.ITEM, e -> e.getItem().is(Items.SLIME_BALL)).size(),
                    p.serverLevel().getEntities(EntityType.PANDA, Panda::isSneezing).size()))));
        }
    }

    /** Heave through the real client: sneak, right-click a sheep with an empty hand, then left-click the air to throw. */
    private static void heave(List<DevShots.Shot> shots) {
        int[] sheep = {0};
        shots.add(new DevShots.Shot("pt_heave_setup", 10, mc -> {
            mc.setScreen(null);
            DevShots.serverDo(mc, p -> {
            p.closeContainer();
            BlockPos c = reset(p);
            grant(p, "heave", 10);
            p.getInventory().clearContent();
            p.connection.teleport(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 0, 30);
            var sh = spawn(p.serverLevel(), EntityType.SHEEP, c.offset(0, 0, 2));
            sh.setNoAi(false); // a no-AI mob has no gravity, so the throw would hang in the air
            sheep[0] = sh.getId();
            });
        }));
        shots.add(new DevShots.Shot("pt_heave_sneak", 5, mc -> mc.options.keyShift.setDown(true)));
        shots.add(new DevShots.Shot("pt_heave_pickup", 10, mc -> {
            var e = mc.level.getEntity(sheep[0]);
            if (e != null) mc.gameMode.interact(mc.player, e, net.minecraft.world.InteractionHand.MAIN_HAND);
            else LOG.warn("[hbshots] no sheep on the client");
        }));
        shots.add(new DevShots.Shot("pt_heave_carry", 10, mc -> {
            mc.options.keyShift.setDown(false);
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            var e = mc.level.getEntity(sheep[0]);
            LOG.info("[hbshots] heave client: sheep vehicle={} player passengers={}", e == null ? null : e.getVehicle(), mc.player.getPassengers());
        }));
        shots.add(new DevShots.Shot("pt_heave_first", 5, mc -> {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            DevShots.serverDo(mc, p -> p.connection.teleport(p.getX(), p.getY(), p.getZ(), 0, -10));
        }));
        for (int i = 0; i < 4; i++) {
            boolean first = i == 0;
            shots.add(new DevShots.Shot("pt_heave_throw_" + i, 3, mc -> {
                if (first) net.minecraft.client.KeyMapping.click(mc.options.keyAttack.getKey());
                var e = mc.level.getEntity(sheep[0]);
                LOG.info("[hbshots] heave throw: sheep at {} vehicle={}", e == null ? null : e.position(), e == null ? null : e.getVehicle());
            }));
        }
    }

    private static List<String> names(List<net.minecraft.world.item.enchantment.EnchantmentInstance> list) {
        return list.stream().map(e -> e.enchantment.getRegisteredName() + " " + e.level).toList();
    }

    /** Parks the mouse on enchant offer {@code row} (MouseHandler fields: a programmatic GLFW move fires no callback). */
    private static void hoverOffer(Minecraft mc, int row) {
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.EnchantmentScreen s)) { LOG.warn("[hbshots] no enchant screen"); return; }
        double scale = mc.getWindow().getGuiScale();
        int left = (s.width - 176) / 2, top = (s.height - 166) / 2;
        double gx = left + 60 + 54, gy = top + 14 + 19 * row + 9;
        try {
            for (String f : new String[]{"xpos", "ypos"}) {
                var fl = net.minecraft.client.MouseHandler.class.getDeclaredField(f);
                fl.setAccessible(true);
                fl.setDouble(mc.mouseHandler, (f.equals("xpos") ? gx : gy) * scale);
            }
        } catch (ReflectiveOperationException ex) {
            LOG.warn("[hbshots] could not move the cursor", ex);
        }
    }
}
