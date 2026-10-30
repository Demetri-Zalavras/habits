package com.habits.client;

import com.habits.Habits;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dev-only screenshot harness, inert unless {@code HABITS_SHOTS} is set. Makes a fresh flat survival world, runs a shot
 * plan, writes run/screenshots/hb_*.png at 1920 x 1080, GUI scale 3, then quits. Owner: core.
 * <p>Each client package registers its own plan from its client init: {@code DevShots.PLANS.put("book", shots -> ...)}
 * and runs it with {@code HABITS_SHOTS=book}. {@code HABITS_SHOTS=1} (or an unknown key) takes one plain world shot.
 */
@EventBusSubscriber(modid = Habits.MODID, value = Dist.CLIENT)
public final class DevShots {
    private static final String MODE = System.getenv("HABITS_SHOTS");
    private static final boolean ON = MODE != null && !MODE.isEmpty();
    public static final Map<String, Consumer<List<Shot>>> PLANS = new HashMap<>();
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();

    /** {@code delay} = client ticks to wait after setUp before the screenshot. */
    public record Shot(String name, int delay, Consumer<Minecraft> setUp) {}

    private static final List<Shot> SHOTS = new ArrayList<>();
    private static int stage, wait, index;

    private DevShots() {}

    /** Run {@code r} on the integrated server thread with the (only) player. */
    public static void serverDo(Minecraft mc, Consumer<ServerPlayer> r) {
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> r.accept(server.getPlayerList().getPlayers().get(0)));
    }

    private static void plan() {
        PlaytestShots.register();
        PLANS.put("spec", shots -> {
            shots.add(new Shot("spec_combat", 20, m -> { if (!(m.screen instanceof SpecScreen)) m.setScreen(new SpecScreen()); }));
            // core.specs.combat_specs false: the picker starts at the professions
            shots.add(new Shot("spec_selected", 5, m -> ((SpecScreen) m.screen).shotPick(com.habits.core.Specs.combatSpecs()
                    ? com.habits.core.Category.WARRIOR : com.habits.core.Category.MINING, false)));
            shots.add(new Shot("spec_profession", 5, m -> {
                SpecScreen sc = (SpecScreen) m.screen;
                boolean on = com.habits.core.Specs.combatSpecs(); // off: Mining is the first profession, Farming the second
                sc.shotPick(on ? com.habits.core.Category.WARRIOR : com.habits.core.Category.MINING, true);
                sc.shotPick(on ? com.habits.core.Category.MINING : com.habits.core.Category.FARMING, false);
            }));
            // picked Warrior + Mining with cards and an off-spec charm owned: the book shows two tabs, the charm converts
            shots.add(new Shot("spec_book", 40, m -> {
                serverDo(m, p -> {
                    for (var d : com.habits.core.SkillRegistry.all()) if (d.rarity() == com.habits.core.Rarity.COMMON) com.habits.core.HabitsApi.addCard(p, d.id());
                    com.habits.progression.Drops.grantCharm(p, com.habits.progression.CharmData.roll(com.habits.core.Category.BUILDING,
                            com.habits.core.Rarity.RARE, p.getRandom()));
                    boolean on = com.habits.core.Specs.combatSpecs();
                    com.habits.core.Specs.pick(p, on ? com.habits.core.Category.WARRIOR : com.habits.core.Category.MINING,
                            on ? com.habits.core.Category.MINING : com.habits.core.Category.FARMING);
                });
                m.setScreen(null);
            }));
            shots.add(new Shot("spec_book_open", 30, m -> m.setScreen(new com.habits.client.book.BinderScreen())));
        });
        // combat specs off, Mining + Farming picked, a Nature class book right-clicked: the screen offers both professions
        PLANS.put("classbook", shots -> {
            shots.add(new Shot("classbook", 40, m -> {
                com.habits.core.CoreInit.COMBAT_SPECS.set(false);
                m.setScreen(null);
                serverDo(m, p -> {
                    com.habits.core.Specs.pick(p, com.habits.core.Category.MINING, com.habits.core.Category.FARMING);
                    var c = com.habits.core.SkillRegistry.all().stream().filter(d -> d.classPassive() && d.category() == com.habits.core.Category.NATURE).findFirst().orElseThrow();
                    p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, com.habits.core.ClassBookItem.create(c.id()));
                    p.getMainHandItem().use(p.level(), p, net.minecraft.world.InteractionHand.MAIN_HAND);
                });
            }));
            shots.add(new Shot("classbook_done", 40, m -> {
                for (var w : m.screen.children()) if (w instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().contains("Mining")) b.onPress();
                serverDo(m, p -> LOG.info("[classbook] picks={} classes={} hand={}", com.habits.core.Specs.of(p).picks(), com.habits.core.Specs.of(p).classes(), p.getMainHandItem()));
            }));
        });
        PLANS.put("craftcheck", shots -> shots.add(new Shot("craftcheck", 40, m -> serverDo(m, p -> {
            com.habits.core.Specs.pick(p, com.habits.core.Category.WARRIOR, com.habits.core.Category.CRAFTING);
            p.getInventory().clearContent();
            var menu = new net.minecraft.world.inventory.CraftingMenu(99, p.getInventory(),
                    net.minecraft.world.inventory.ContainerLevelAccess.create(p.level(), p.blockPosition()));
            p.containerMenu = menu;
            for (int slot : new int[]{1, 3, 4, 6}) menu.getSlot(slot).set(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 30));
            menu.slotsChanged(menu.getSlot(1).container);
            menu.clicked(0, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, p);
            int rated = 0, total = 0;
            for (var st : p.getInventory().items) if (st.is(net.minecraft.world.item.Items.DIAMOND_BOOTS)) {
                total++;
                if (com.habits.perk.crafting.Rating.tier(st) != com.habits.perk.crafting.Rating.Tier.NORMAL) rated++;
            }
            LOG.info("[craftcheck] picks={} boots={} rated={}", com.habits.core.Specs.of(p).picks(), total, rated);
            p.containerMenu = p.inventoryMenu;
        }))));
        PLANS.put("timbercheck", shots -> {
            final net.minecraft.core.BlockPos[] base = new net.minecraft.core.BlockPos[1];
            java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> chop = p -> {
                net.minecraft.core.BlockPos low = null;
                int logs = 0;
                for (var q : net.minecraft.core.BlockPos.betweenClosed(base[0].offset(-8, 0, -8), base[0].offset(9, 40, 9)))
                    if (p.level().getBlockState(q).is(net.minecraft.tags.BlockTags.LOGS)) { logs++; if (low == null || q.getY() < low.getY()) low = q.immutable(); }
                LOG.info("[timbercheck] logs left {} ; chopping {}", logs, low);
                if (low != null) { p.teleportTo(low.getX() + 0.5, low.getY(), low.getZ() - 1.5); p.gameMode.destroyBlock(low); }
            };
            shots.add(new Shot("timber1", 40, m -> { m.setScreen(null); serverDo(m, p -> {
                com.habits.core.Specs.pick(p, com.habits.core.Category.WARRIOR, com.habits.core.Category.FARMING);
                p.setData(com.habits.core.CoreRegistries.COLLECTION, com.habits.core.HabitsCollection.EMPTY.with(com.habits.Habits.id("timber"),
                        new com.habits.core.SkillProgress(2, 0, true)));
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_AXE));
                var l = p.serverLevel();
                base[0] = p.blockPosition().offset(6, 0, 6);
                var reg = l.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE);
                for (int x = -1; x <= 2; x++) for (int z = -1; z <= 2; z++) l.setBlock(base[0].offset(x, -1, z), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                boolean ok = reg.getHolderOrThrow(net.minecraft.data.worldgen.features.TreeFeatures.DARK_OAK).value().place(l, l.getChunkSource().getGenerator(), net.minecraft.util.RandomSource.create(5), base[0]);
                LOG.info("[timbercheck] placed {}", ok);
                chop.accept(p);
            }); }));
            for (int i = 2; i <= 4; i++) shots.add(new Shot("timber" + i, 40, m -> serverDo(m, chop)));
        });
        PLANS.put("xpfeed", shots -> shots.add(new Shot("xpfeed", 30, m -> {
            serverDo(m, p -> com.habits.core.Specs.pick(p, com.habits.core.Category.WARRIOR, com.habits.core.Category.CRAFTING));
            m.setScreen(null);
            var defs = com.habits.core.SkillRegistry.all().stream().filter(d -> d.category() == com.habits.core.Category.CRAFTING).limit(2).toList();
            com.habits.core.ClientHooks.each(h -> h.xpGained(new com.habits.core.Payloads.XpGained(defs.get(0).id(), 36, 3, 64, 100)));
            com.habits.core.ClientHooks.each(h -> h.xpGained(new com.habits.core.Payloads.XpGained(defs.get(1).id(), 12, 1, 18, 100)));
        })));
        PLANS.put("rating", shots -> {
            shots.add(new Shot("rating", 10, m -> m.setScreen(com.habits.perk.crafting.RatingClient.shotScreen(m.player))));
            shots.add(new Shot("rating_normal", 10, m -> m.setScreen(com.habits.perk.crafting.RatingClient.shotNormal())));
        });
        if (PLANS.containsKey(MODE)) PLANS.get(MODE).accept(SHOTS);
        else SHOTS.add(new Shot("world", 1, m -> {}));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        if (!ON) return;
        Minecraft mc = Minecraft.getInstance();
        if (stage == 0) {
            if (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen) {
                stage = 1;
                mc.getWindow().setWindowed(1920, 1080);
                mc.options.guiScale().set(3);
                mc.resizeDisplay();
                GameRules rules = new GameRules();
                rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
                rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
                LevelSettings settings = new LevelSettings("hbshots" + System.currentTimeMillis(), GameType.SURVIVAL,
                        false, Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(settings.levelName(), settings, new WorldOptions(1L, false, false),
                        reg -> reg.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
            }
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null) return;
        mc.options.pauseOnLostFocus = false;
        mc.getToasts().clear();
        if (stage == 1) {
            stage = 2;
            wait = 40;
            plan();
            server.execute(() -> server.getPlayerList().getPlayers().get(0).serverLevel().setDayTime(6000));
            return;
        }
        if (wait > 0) { wait--; return; }
        if (stage == 2) {
            if (index >= SHOTS.size()) { stage = 4; LOG.info("[hbshots] done"); mc.stop(); return; }
            Shot s = SHOTS.get(index);
            s.setUp().accept(mc);
            stage = 3;
            wait = s.delay();
            return;
        }
        if (stage == 3) {
            mc.gui.getChat().clearMessages(false);
            String name = "hb_" + SHOTS.get(index).name();
            NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            File dir = new File(mc.gameDirectory, Screenshot.SCREENSHOT_DIR);
            dir.mkdirs();
            try {
                img.writeToFile(new File(dir, name + ".png"));
                LOG.info("[hbshots] {} {}x{}", name, img.getWidth(), img.getHeight());
            } catch (Exception ex) {
                LOG.warn("[hbshots] could not save {}", name, ex);
            } finally {
                img.close();
            }
            index++;
            stage = 2;
        }
    }
}
