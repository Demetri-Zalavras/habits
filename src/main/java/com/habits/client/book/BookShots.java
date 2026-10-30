package com.habits.client.book;

import com.habits.Habits;
import com.habits.client.DevShots;
import com.habits.core.CardItems;
import com.habits.progression.CharmData;
import com.habits.progression.ProgRegistries;
import com.habits.progression.ProgressionData;
import com.habits.progression.SigilData;
import com.habits.progression.SigilType;
import com.habits.core.Category;
import com.habits.core.HabitsCollection;
import com.habits.core.PerkSpec;
import com.habits.core.PerkType;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.TriggerSpec;
import com.habits.core.TriggerType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Dev screenshot plan {@code HABITS_SHOTS=book}: the binder with a made-up collection, plus a card sheet. Owner: BOOK-UI. */
final class BookShots {
    private BookShots() {}

    private static final List<SkillDef> DEFS = new ArrayList<>();
    private static final Map<ResourceLocation, SkillProgress> OWNED = new HashMap<>();

    private static SkillDef add(String path, Rarity r, Category c, String icon, String art, String name, String perk, int level, boolean on) {
        SkillDef d = new SkillDef(Habits.id("test/" + path), r, c,
                new TriggerSpec(TriggerType.BREAK_BLOCK, Optional.empty(), Optional.empty(), Optional.empty(), 0, 0.01),
                ResourceLocation.parse(icon), ResourceLocation.parse(art),
                new PerkSpec(Optional.of(PerkType.MINING_SPEED), Optional.empty(), Map.of(), 5, 5, Optional.empty()),
                Optional.of(name), Optional.of("mining " + art.substring(art.indexOf(':') + 1).replace('_', ' ')), Optional.of(perk));
        DEFS.add(d);
        if (level > 0) OWNED.put(d.id(), new SkillProgress(level, level > 2 ? 2 : 0, on));
        return d;
    }

    static {
        add("stonebreaker", Rarity.COMMON, Category.MINING, "minecraft:iron_pickaxe", "minecraft:stone", "Stonebreaker", "Mining speed +{v}%", 4, true);
        add("copper", Rarity.COMMON, Category.MINING, "minecraft:raw_copper", "minecraft:copper_ore", "Copper Nose", "Copper drops +{v}%", 1, true);
        add("coal", Rarity.COMMON, Category.MINING, "minecraft:coal", "minecraft:coal_ore", "Coal Dust", "Smelting +{v}%", 10, true);
        add("gravel", Rarity.COMMON, Category.MINING, "minecraft:flint", "minecraft:gravel", "Gravel Sifter", "Flint +{v}%", 0, true);
        add("torch", Rarity.COMMON, Category.MINING, "minecraft:torch", "minecraft:cobblestone", "Torchbearer", "Light +{v}", 2, true);
        add("tunnel", Rarity.COMMON, Category.MINING, "minecraft:wooden_pickaxe", "minecraft:dirt", "Tunnel Rat", "Dig speed +{v}%", 0, true);
        add("iron", Rarity.UNCOMMON, Category.MINING, "minecraft:iron_ingot", "minecraft:iron_ore", "Iron Will", "Armor +{v}%", 5, false);
        add("gold", Rarity.UNCOMMON, Category.MINING, "minecraft:gold_nugget", "minecraft:gold_ore", "Gold Rush", "Gold +{v}%", 0, true);
        add("lapis", Rarity.UNCOMMON, Category.MINING, "minecraft:lapis_lazuli", "minecraft:lapis_ore", "Lapis Luck", "Enchanting -{v}%", 2, true);
        add("redstone", Rarity.UNCOMMON, Category.MINING, "minecraft:redstone", "minecraft:redstone_ore", "Redstone Rush", "Redstone +{v}%", 0, true);
        add("deep", Rarity.RARE, Category.MINING, "minecraft:diamond_pickaxe", "minecraft:deepslate", "Deep Miner", "Below Y=0 +{v}%", 3, true);
        add("obsidian", Rarity.RARE, Category.MINING, "minecraft:obsidian", "minecraft:obsidian", "Obsidian Skin", "Fire damage -{v}%", 6, true);
        add("emerald", Rarity.EPIC, Category.MINING, "minecraft:emerald", "minecraft:emerald_ore", "Emerald Eye", "Ore glow {v} blocks", 1, true);
        add("netherite", Rarity.LEGENDARY, Category.MINING, "minecraft:netherite_ingot", "minecraft:ancient_debris", "Netherite Heart", "Debris drops +{v}%", 7, true);
        add("wheat", Rarity.UNCOMMON, Category.FARMING, "minecraft:wheat", "minecraft:farmland", "Green Thumb", "Crop growth +{v}%", 2, true);
        add("carrot", Rarity.COMMON, Category.FARMING, "minecraft:carrot", "minecraft:farmland", "Carrot Top", "Carrots +{v}%", 0, true);
        add("pumpkin", Rarity.RARE, Category.FARMING, "minecraft:carved_pumpkin", "minecraft:pumpkin", "Pumpkin King", "Pumpkins +{v}%", 1, true);
        add("bees", Rarity.EPIC, Category.FARMING, "minecraft:honeycomb", "minecraft:bee_nest", "Beekeeper", "Honey +{v}%", 0, true);
        add("bonemeal", Rarity.COMMON, Category.FARMING, "minecraft:bone_meal", "minecraft:dirt", "Bone Mealer", "Bone meal +{v}%", 3, true);
        add("crossbow", Rarity.RARE, Category.WARRIOR, "minecraft:crossbow", "minecraft:bone_block", "Crossbow Sniper", "Crossbow damage +{v}%", 2, true);
        add("wither", Rarity.LEGENDARY, Category.WARRIOR, "minecraft:nether_star", "minecraft:soul_sand", "Witherbane", "Damage to Wither +{v}%", 0, true);
        add("zombie", Rarity.COMMON, Category.WARRIOR, "minecraft:rotten_flesh", "minecraft:mossy_cobblestone", "Zombie Slayer", "Undead damage +{v}%", 4, true);
        add("shield", Rarity.UNCOMMON, Category.WARRIOR, "minecraft:shield", "minecraft:oak_planks", "Shield Wall", "Block +{v}%", 1, true);
        add("berserker", Rarity.EPIC, Category.WARRIOR, "minecraft:iron_axe", "minecraft:red_nether_bricks", "Berserker", "Low health damage +{v}%", 3, true);
        add("feather", Rarity.EPIC, Category.MOVEMENT, "minecraft:feather", "minecraft:end_stone", "Featherfall", "Fall damage -{v}%", 1, true);
        add("sprint", Rarity.COMMON, Category.MOVEMENT, "minecraft:leather_boots", "minecraft:grass_block", "Sprinter", "Sprint speed +{v}%", 2, true);
        add("swim", Rarity.UNCOMMON, Category.MOVEMENT, "minecraft:cod", "minecraft:water", "Swimmer", "Swim speed +{v}%", 0, true);
        add("climb", Rarity.RARE, Category.MOVEMENT, "minecraft:ladder", "minecraft:oak_log", "Climber", "Climb speed +{v}%", 1, true);
        add("beef", Rarity.COMMON, Category.GUARDIAN, "minecraft:cooked_beef", "minecraft:hay_block", "Well Fed", "Food +{v}%", 5, true);
        add("luck", Rarity.LEGENDARY, Category.EXPLORATION, "minecraft:rabbit_foot", "minecraft:amethyst_block", "Lucky Charm", "Luck +{v}", 2, true);
    }

    private static SkillDef byPath(String path) {
        for (SkillDef d : DEFS) if (d.id().getPath().equals("test/" + path)) return d;
        throw new IllegalArgumentException(path);
    }

    private static BinderScreen binder(Minecraft mc, int gs) {
        return binder(mc, gs, NO_NEWS);
    }

    private static final BookSeen NO_NEWS = seen(java.util.Set.of(), null);

    /** Test "new" state: these skills are unseen, {@code newest} is what the book opens on. */
    private static BookSeen seen(java.util.Set<String> unseen, String newest) {
        java.util.Set<ResourceLocation> left = new java.util.HashSet<>();
        unseen.forEach(u -> left.add(Habits.id("test/" + u)));
        return new BookSeen() {
            @Override public boolean isUnseen(ResourceLocation skill) { return left.contains(skill); }
            @Override public ResourceLocation newest() { return newest == null ? null : Habits.id("test/" + newest); }
            @Override public void markSeen(ResourceLocation skill) { left.remove(skill); }
        };
    }

    private static BinderScreen binder(Minecraft mc, int gs, BookSeen news) {
        mc.options.guiScale().set(gs);
        mc.resizeDisplay();
        BinderScreen b = new BinderScreen(() -> DEFS, () -> new HabitsCollection(OWNED), news);
        mc.setScreen(b);
        b.debugX = b.debugY = -999; // no stray hover from the real cursor
        return b;
    }

    /** Book search: the hint, a query typed key by key, no matches, and Esc back to the tab. */
    static void search(List<DevShots.Shot> shots) {
        shots.add(new DevShots.Shot("book_search_hint", 20, mc -> binder(mc, 3)));
        shots.add(new DevShots.Shot("book_search_ore", 10, mc -> type(binder(mc, 3), "ore")));
        shots.add(new DevShots.Shot("book_search_two_words", 10, mc -> type(binder(mc, 3), "rare mining")));
        shots.add(new DevShots.Shot("book_search_none", 10, mc -> type(binder(mc, 3), "zzz")));
        shots.add(new DevShots.Shot("book_search_esc", 10, mc -> {
            BinderScreen b = type(binder(mc, 3), "ore");
            b.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
        }));
    }

    private static BinderScreen type(BinderScreen b, String text) {
        for (char c : text.toCharArray()) b.charTyped(c, 0);
        return b;
    }

    static void plan(List<DevShots.Shot> shots) {
        shots.add(new DevShots.Shot("book_gs3", 20, mc -> {
            binder(mc, 3);
        }));
        shots.add(new DevShots.Shot("book_gs3_hover", 10, mc -> {
            BinderScreen b = binder(mc, 3);
            float[] c = BinderScreen.slotCenter(3);
            b.debugX = c[0]; b.debugY = c[1];
        }));
        shots.add(new DevShots.Shot("book_gs3_hover_locked", 10, mc -> {
            BinderScreen b = binder(mc, 3);
            float[] c = BinderScreen.slotCenter(2);
            b.debugX = c[0]; b.debugY = c[1];
        }));
        shots.add(new DevShots.Shot("book_gs2", 10, mc -> binder(mc, 2)));
        // NEW badges on a few cards; the book opens on the newest one's spread (Crossbow Sniper, page 4) and pulses it
        shots.add(new DevShots.Shot("book_new_open", 12, mc ->
                binder(mc, 3, seen(java.util.Set.of("copper", "lapis", "netherite", "emerald", "crossbow"), "crossbow"))));
        shots.add(new DevShots.Shot("book_new_hover", 4, mc -> {
            BinderScreen b = binder(mc, 3, seen(java.util.Set.of("copper", "lapis", "netherite", "emerald"), null));
            float[] c = BinderScreen.slotCenter(1); // Copper Nose: hover starts its fade
            b.debugX = c[0]; b.debugY = c[1];
        }));
        shots.add(new DevShots.Shot("book_gs2_spread2", 10, mc -> {
            BinderScreen b = binder(mc, 2);
            b.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, 0, 0);
        }));
        shots.add(new DevShots.Shot("book_gs4", 10, mc -> binder(mc, 4)));
        if (CardProf.ON) perf(shots);
        live(shots);
        shots.add(new DevShots.Shot("cards_normal", 10, mc -> sheet(mc, new Sheet(false))));
        shots.add(new DevShots.Shot("cards_small", 10, mc -> sheet(mc, new Sheet(true))));
        shots.add(new DevShots.Shot("cards_closeup", 40, mc -> {
            // Vein Miner Lv7 with 3/8 toward Lv8, like the mockup's annotated card
            DevShots.serverDo(mc, p -> {
                for (int i = 0; i < com.habits.core.Leveling.totalCards(7) + 3; i++) com.habits.core.HabitsApi.addCard(p, Habits.id("vein_miner"));
            });
            sheet(mc, new Closeup());
        }));
    }

    /**
     * End to end through core and the network: real skills, real collection, real clicks on the screen's mouse handlers.
     * Stonebreaker Lv1 gets clicked off (greys out), then a carried Stonebreaker card is dragged in (progress 1/2) and a
     * carried Archery card unlocks its locked slot.
     */
    private static void live(List<DevShots.Shot> shots) {
        // real NEW flow: right-click-add a fresh card of a skill on a later spread, open the book, hover, reopen
        SkillDef[] fresh = new SkillDef[1];
        shots.add(new DevShots.Shot("live_new_open", 30, mc -> {
            for (SkillDef d : com.habits.core.SkillRegistry.all()) if (fresh[0] == null || d.category().ordinal() > fresh[0].category().ordinal()) fresh[0] = d;
            DevShots.serverDo(mc, p -> {
                p.getInventory().setItem(8, new net.minecraft.world.item.ItemStack(com.habits.core.CoreRegistries.BOOK.get()));
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, CardItems.create(fresh[0]));
                com.habits.core.HabitsApi.addCardInHand(p, net.minecraft.world.InteractionHand.MAIN_HAND);
            });
        }));
        shots.add(new DevShots.Shot("live_new_open", 20, mc -> liveScreen(mc)));
        shots.add(new DevShots.Shot("live_new_hover", 25, mc -> {
            BinderScreen b = (BinderScreen) mc.screen;
            int i = b.spreadSkills().indexOf(fresh[0]);
            if (i < 0) return;
            float[] c = BinderScreen.slotCenter(i);
            b.debugX = c[0]; b.debugY = c[1];
        }));
        shots.add(new DevShots.Shot("live_new_reopen", 20, mc -> liveScreen(mc)));
        shots.add(new DevShots.Shot("live_before", 30, mc -> {
            BinderScreen b = liveScreen(mc);
            // no hard-coded ids: the first card of the first spread (owned, then toggled) and the next one (locked, then added)
            List<SkillDef> spread = b.spreadSkills();
            SkillDef owned = spread.isEmpty() ? null : spread.get(0);
            SkillDef locked = spread.size() > 1 ? spread.get(1) : null;
            if (owned == null) return;
            DevShots.serverDo(mc, p -> {
                p.getInventory().clearContent();
                p.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(com.habits.core.CoreRegistries.BOOK.get()));
                p.getInventory().setItem(5, CardItems.create(owned));
                if (locked != null) p.getInventory().setItem(6, CardItems.create(locked));
                com.habits.core.HabitsApi.addCard(p, owned.id());
            });
        }));
        shots.add(new DevShots.Shot("live_toggled", 30, mc -> {
            BinderScreen b = (BinderScreen) mc.screen;
            float[] c = BinderScreen.slotCenter(0); // the owned card
            double[] m = b.toGui(c[0], c[1]);
            b.mouseClicked(m[0], m[1], 0);
        }));
    }

    // ------------------------------------------------------------------ sigils and charms (HABITS_SHOTS=bookprog)

    private static final int[] PROG_LV = {10, 7, 4, 1, 10, 5, 2, 8};
    /** Drag codes of the setup: stored sigils, then stored charms (bag index; the bag is newest first). */
    private static final int S_POTENT_C = code(SigilType.POTENT, Rarity.COMMON), S_SWIFT_E = code(SigilType.SWIFT, Rarity.EPIC),
            S_REACH_U = code(SigilType.REACH, Rarity.UNCOMMON), S_POTENT_L = code(SigilType.POTENT, Rarity.LEGENDARY),
            C_FARM = BinderScreen.CHARM_CODE + 3;
    /** List entries on the Charms page: 2 equipped, then the bag: 0 Odd Rare, 1 Survival Rare, 2 Movement Rare, ... */
    private static final int E_ODD = 2, E_SURV = 3, E_MOVE = 4;

    /**
     * A real player through the real network: the first spread owned at mixed levels (Lv10, 7, 4, 1, ...) plus 50 more
     * skills (3 charm slots of 5), sigils socketed on the first cards, two charms equipped and more of both in the pouch.
     * Then: socket row states and tooltips, a drag mid-way (fits light up, the rest grey), a real drag and drop, a refused
     * drop, the Charms tab, a charm drag, a picked line with a sigil over it, and a refused reroll. At GUI scale 3, 4 and 2.
     */
    static void progression(List<DevShots.Shot> shots) {
        shots.add(new DevShots.Shot("prog_setup", 40, mc -> {
            List<SkillDef> spread = liveScreen(mc, 3).spreadSkills();
            DevShots.serverDo(mc, p -> {
                var inv = p.getInventory();
                inv.clearContent();
                p.setData(ProgRegistries.DATA, ProgressionData.EMPTY);
                inv.setItem(8, new net.minecraft.world.item.ItemStack(com.habits.core.CoreRegistries.BOOK.get()));
                int extra = 0;
                for (int i = 0; i < spread.size(); i++)
                    for (int k = 0; k < com.habits.core.Leveling.totalCards(PROG_LV[i % PROG_LV.length]); k++)
                        com.habits.core.HabitsApi.addCard(p, spread.get(i).id());
                for (SkillDef d : com.habits.core.SkillRegistry.all())
                    if (extra < 50 && !spread.contains(d)) { com.habits.core.HabitsApi.addCard(p, d.id()); extra++; }
                // socketed: card 1 (Lv10) gets three of four, card 2 (Lv7) one, card 3 (Lv4) one
                socket(p, spread.get(0), 0, sig(SigilType.POTENT, Rarity.LEGENDARY));
                socket(p, spread.get(0), 1, sig(SigilType.LUCKY, Rarity.RARE));
                socket(p, spread.get(0), 3, sig(SigilType.POTENT, Rarity.EPIC));
                socket(p, spread.get(1), 0, sig(SigilType.POTENT, Rarity.RARE));
                socket(p, spread.get(2), 1, sig(SigilType.LUCKY, Rarity.UNCOMMON));
                var r = net.minecraft.util.RandomSource.create(7);
                bag(p, CharmData.roll(Category.MINING, Rarity.EPIC, r));
                com.habits.progression.Actions.equipCharm(p, 0, 0);
                bag(p, CharmData.roll(Category.WARRIOR, Rarity.LEGENDARY, r));
                com.habits.progression.Actions.equipCharm(p, 1, 0);
                // the pouch
                store(p, sig(SigilType.POTENT, Rarity.COMMON), 3);
                store(p, sig(SigilType.SWIFT, Rarity.EPIC), 1);
                store(p, sig(SigilType.REACH, Rarity.UNCOMMON), 2);
                store(p, sig(SigilType.LUCKY, Rarity.LEGENDARY), 1);
                store(p, sig(SigilType.POTENT, Rarity.LEGENDARY), 1);
                store(p, sig(SigilType.SWIFT, Rarity.MYTHICAL), 1);
                // the charm list, oldest first (the bag shows newest first): enough to scroll
                bag(p, CharmData.roll(Category.EXPLORATION, Rarity.MYTHICAL, r));
                bag(p, CharmData.roll(Category.BUILDING, Rarity.COMMON, r));
                bag(p, CharmData.roll(Category.NATURE, Rarity.EPIC, r));
                bag(p, CharmData.roll(Category.MINING, Rarity.COMMON, r));
                bag(p, CharmData.roll(Category.CRAFTING, Rarity.COMMON, r));
                bag(p, CharmData.roll(Category.FARMING, Rarity.UNCOMMON, r));
                bag(p, CharmData.roll(Category.MOVEMENT, Rarity.RARE, r));
                bag(p, CharmData.roll(Category.GUARDIAN, Rarity.RARE, r));
                bag(p, CharmData.roll(Category.EXPLORATION, Rarity.RARE, r));
            });
        }));
        for (int gs : new int[]{3, 4, 2}) {
            String sfx = gs == 3 ? "" : "_gs" + gs;
            shots.add(new DevShots.Shot("prog_sockets" + sfx, 20, mc -> {
                BinderScreen b = liveScreen(mc, gs);
                float[] c = b.socketCenter(0, 0); // tooltip of the Legendary Potent sigil on card 1
                b.debugX = c[0]; b.debugY = c[1];
            }));
            shots.add(new DevShots.Shot("prog_charms" + sfx, 15, mc -> {
                BinderScreen b = liveScreen(mc, gs);
                b.charms = true;
            }));
        }
        shots.add(new DevShots.Shot("prog_card_tip", 15, mc -> {
            BinderScreen b = liveScreen(mc, 3);
            float[] c = BinderScreen.slotCenter(0);
            b.debugX = c[0]; b.debugY = c[1] + 20;
        }));
        shots.add(new DevShots.Shot("prog_drag_reach", 15, mc -> {
            BinderScreen b = liveScreen(mc, 3);
            b.debugDrag(S_REACH_U);
            float[] c = b.socketCenter(2, 0);
            b.debugX = c[0]; b.debugY = c[1];
        }));
        // real handlers: press on the Common Potent sigil in the pouch, release on the only socket of card 4 (Lv1)
        shots.add(new DevShots.Shot("prog_drop_live", 20, mc -> {
            BinderScreen b = liveScreen(mc, 3);
            press(b, b.pouchCenter(pouchIndex(b, S_POTENT_C)));
            release(b, b.socketCenter(3, 0));
        }));
        // Swift on a card without a cooldown: refused with a notice
        shots.add(new DevShots.Shot("prog_refuse", 6, mc -> {
            BinderScreen b = liveScreen(mc, 3);
            int target = -1;
            List<SkillDef> spread = b.spreadSkills();
            for (int i = 0; i < 3 && target < 0; i++) if (!SigilType.SWIFT.fits(spread.get(i))) target = i;
            if (target < 0) return;
            press(b, b.pouchCenter(pouchIndex(b, S_SWIFT_E)));
            float[] c = BinderScreen.slotCenter(target);
            release(b, c);
            b.debugX = c[0]; b.debugY = c[1];
        }));
        shots.add(new DevShots.Shot("prog_charms_drag", 15, mc -> {
            BinderScreen b = liveScreen(mc, 3);
            b.charms = true;
            b.debugDrag(C_FARM);
            float[] c = BinderScreen.charmSlotCenter(2);
            b.debugX = c[0]; b.debugY = c[1];
        }));
        shots.add(new DevShots.Shot("prog_reroll", 15, mc -> {
            BinderScreen b = liveScreen(mc, 3);
            b.charms = true;
            b.selCharm = 0;
            b.selLine = 0;
            b.debugDrag(S_POTENT_L);
            float[] c = b.charmEntryCenter(0);
            b.debugX = c[0]; b.debugY = c[1] + 12; // over the picked line
        }));
        // a Common sigil clicked for a line on the Legendary charm: refused
        shots.add(new DevShots.Shot("prog_reroll_refused", 6, mc -> {
            BinderScreen b = liveScreen(mc, 3);
            b.charms = true;
            b.selCharm = 1;
            b.selLine = 0;
            float[] c = b.pouchCenter(pouchIndex(b, S_POTENT_C));
            press(b, c);
            release(b, c);
        }));
        // sigil combine: the shift-click hint on the Common Potent stack (3 kept), then a combine (the pop and the notice)
        shots.add(new DevShots.Shot("prog_sigil_hint", 15, mc -> {
            BinderScreen b = liveScreen(mc, 3);
            float[] c = b.pouchCenter(pouchIndex(b, S_POTENT_C));
            b.debugX = c[0]; b.debugY = c[1];
        }));
        shots.add(new DevShots.Shot("prog_sigil_combine", 6, mc -> {
            BinderScreen b = (BinderScreen) mc.screen;
            b.combineSigilsForShots(sig(SigilType.POTENT, Rarity.COMMON));
            b.debugX = b.debugY = -999;
        }));
        shots.add(new DevShots.Shot("prog_sigil_top_hint", 12, mc -> {
            BinderScreen b = (BinderScreen) mc.screen;
            float[] c = b.pouchCenter(pouchIndex(b, S_POTENT_L));
            b.debugX = c[0]; b.debugY = c[1];
        }));
        // charm salvage through the real click handlers: Salvage, arm one, click it again
        shots.add(new DevShots.Shot("prog_salvage_mode", 15, mc -> {
            BinderScreen b = liveScreen(mc, 3);
            b.charms = true;
            click(b, BinderScreen.salvageToggleCenter());
        }));
        shots.add(new DevShots.Shot("prog_salvage_armed", 15, mc -> {
            BinderScreen b = (BinderScreen) mc.screen; // the list is laid out once drawn, so picks start a frame later
            click(b, b.charmEntryCenter(E_ODD));
        }));
        shots.add(new DevShots.Shot("prog_salvage_done", 30, mc -> {
            BinderScreen b = (BinderScreen) mc.screen;
            click(b, b.charmEntryCenter(E_ODD));
            click(b, BinderScreen.salvageToggleCenter());
        }));
        // a real equip into the empty slot (drag the Movement charm's name row), then a real reroll paid with the Legendary Potent sigil
        shots.add(new DevShots.Shot("prog_equip_live", 20, mc -> {
            BinderScreen b = liveScreen(mc, 3);
            b.charms = true;
            press(b, b.charmEntryCenter(E_MOVE - 1)); // the salvage took one out
            release(b, BinderScreen.charmSlotCenter(2));
        }));
        shots.add(new DevShots.Shot("prog_reroll_live", 20, mc -> {
            BinderScreen b = (BinderScreen) mc.screen;
            b.selCharm = 1;
            b.selLine = 0;
            float[] c = b.pouchCenter(pouchIndex(b, S_POTENT_L));
            press(b, c);
            release(b, c);
        }));
    }

    private static SigilData sig(SigilType t, Rarity r) {
        return new SigilData(t, r);
    }

    private static int code(SigilType t, Rarity r) {
        return BinderScreen.sigilCode(sig(t, r));
    }

    private static void store(net.minecraft.server.level.ServerPlayer p, SigilData s, int n) {
        p.setData(ProgRegistries.DATA, p.getData(ProgRegistries.DATA).withStored(s, n));
    }

    private static void socket(net.minecraft.server.level.ServerPlayer p, SkillDef def, int socket, SigilData s) {
        store(p, s, 1);
        if (!com.habits.progression.Actions.socket(p, def.id(), socket, s)) store(p, s, -1);
    }

    private static int pouchIndex(BinderScreen b, int code) {
        for (int i = 0; i < 34; i++) if (b.pouchSlot(i) == code) return i;
        return 0;
    }

    private static void click(BinderScreen b, float[] c) {
        press(b, c);
        release(b, c);
    }

    private static void bag(net.minecraft.server.level.ServerPlayer p, CharmData c) {
        p.setData(ProgRegistries.DATA, p.getData(ProgRegistries.DATA).withBagged(c));
    }

    private static void press(BinderScreen b, float[] c) {
        double[] m = b.toGui(c[0], c[1]);
        b.mouseClicked(m[0], m[1], 0);
    }

    private static void release(BinderScreen b, float[] c) {
        double[] m = b.toGui(c[0], c[1]);
        b.mouseReleased(m[0], m[1], 0);
    }

    /** The real collection and progression data, without NEW badges (so the book opens on the first spread). */
    private static BinderScreen liveScreen(Minecraft mc, int gs) {
        mc.options.guiScale().set(gs);
        mc.resizeDisplay();
        BinderScreen b = new BinderScreen(com.habits.core.SkillRegistry::all, () -> com.habits.core.HabitsApi.collection(mc.player), NO_NEWS);
        mc.setScreen(b);
        b.debugX = b.debugY = -999;
        return b;
    }

    private static BinderScreen liveScreen(Minecraft mc) {
        mc.options.guiScale().set(3);
        mc.resizeDisplay();
        BinderScreen b = new BinderScreen();
        mc.setScreen(b);
        b.debugX = b.debugY = -999;
        return b;
    }

    /** Binder open and page flip with a cold face cache: worst and first frame times. */
    private static void perf(List<DevShots.Shot> shots) {
        String[] names = {"open", "flip"};
        for (int i = 0; i < 2; i++) {
            int step = i;
            shots.add(new DevShots.Shot("perf_" + names[i], 30, mc -> {
                if (mc.screen instanceof BinderScreen prev) report(names[step == 0 ? 0 : step - 1] + "_prev", prev);
                CardRenderer.clearNow();
                BinderScreen.FRAME_NS.clear();
                BinderScreen b = binder(mc, 3);
                if (step == 1) b.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, 0, 0);
            }));
        }
        shots.add(new DevShots.Shot("perf_done", 1, mc -> {
            if (mc.screen instanceof BinderScreen prev) report("flip", prev);
            CardProf.dump("sections");
        }));
    }

    private static void report(String what, BinderScreen b) {
        List<Long> f = new java.util.ArrayList<>(BinderScreen.FRAME_NS);
        if (f.isEmpty()) return;
        long first = f.get(0);
        f.sort(java.util.Comparator.reverseOrder());
        long steady = f.get(f.size() / 2);
        com.mojang.logging.LogUtils.getLogger().info("[cardprof] binder {}: frames {} first {} ms worst {} ms median {} ms",
                what, f.size(), String.format("%.2f", first / 1e6), String.format("%.2f", f.get(0) / 1e6), String.format("%.2f", steady / 1e6));
    }

    private static void sheet(Minecraft mc, Screen screen) {
        mc.options.guiScale().set(3);
        mc.resizeDisplay();
        mc.setScreen(screen);
    }

    /** The mockup's rarity ladder, at full size (one shot) or at binder and icon sizes. */
    private static final String[] LADDER = {"timber", "midas_touch", "diamond_dowsing", "lava_walker", "harvest_moon"};
    private static final int[] LADDER_LV = {3, 4, 6, 5, 8};

    private static final class Sheet extends Screen {
        private final boolean small;

        Sheet(boolean small) {
            super(Component.empty());
            this.small = small;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            g.fill(0, 0, width, height, 0xFF0D0D10);
            if (!small) {
                int w = 112;
                for (int i = 0; i < 5; i++) {
                    SkillDef d = com.habits.core.SkillRegistry.get(Habits.id(LADDER[i]));
                    if (d != null) CardRenderer.render(g, d, LADDER_LV[i], CardRenderer.Size.FULL, 12 + i * (w + 14), 30, w, 1,
                            true, true, .3f, .28f, .55f);
                }
                return;
            }
            // binder size: 189 px (GUI scale 3) and 126 px (GUI scale 2), then icons and the back
            int y = 6;
            for (int px : new int[]{189, 126}) {
                int w = px / 3, x = 10;
                for (int i = 0; i < 5; i++) {
                    SkillDef d = com.habits.core.SkillRegistry.get(Habits.id(LADDER[i]));
                    if (d == null) continue;
                    CardRenderer.render(g, d, LADDER_LV[i], CardRenderer.Size.BINDER, x, y, w, 1, true,
                            i != 4, .3f, .28f, .55f);
                    x += w + 6;
                }
                if (w < 60) {
                    for (int i = 0; i < 5; i++) {
                        SkillDef d = com.habits.core.SkillRegistry.get(Habits.id(LADDER[i]));
                        if (d != null) CardRenderer.render(g, d, LADDER_LV[i], CardRenderer.Size.ICON, x + i * 16, y, 11, 1,
                                true, true, .3f, .28f, .55f);
                    }
                    CardRenderer.renderBack(g, x + 90, y, w, 1);
                }
                y += CardRenderer.heightFor(w) + 6;
            }
        }
    }

    /** One card at the mockup's annotated size (430 px wide) for a side-by-side check. */
    private static final class Closeup extends Screen {
        Closeup() {
            super(Component.empty());
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            g.fill(0, 0, width, height, 0xFF0D0D10);
            SkillDef d = com.habits.core.SkillRegistry.get(Habits.id("vein_miner"));
            SkillDef s = com.habits.core.SkillRegistry.get(Habits.id("sonic_blade"));
            if (d != null) CardRenderer.render(g, d, 7, CardRenderer.Size.FULL, 10, 10, 107, 1, true, true, .3f, .28f, .55f);
            if (s != null) CardRenderer.render(g, s, 10, CardRenderer.Size.FULL, 130, 10, 147, 1, true, true, .7f, .3f, .8f);
        }
    }
}
