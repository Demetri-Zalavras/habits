package com.habits.perk.utility;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterials;
import net.minecraft.world.item.armortrim.TrimPatterns;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** GameTests for the crafting-utility-odd mechanics: each top pick Lv1 vs Lv10, dupe safety, and a timing budget. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class UtilityTests {
    private UtilityTests() {}

    static ServerPlayer player(GameTestHelper h, String skill, int level) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-util"));
        U.TEST_PLAYERS.add(p.getUUID());
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        if (skill != null) p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(Habits.id(skill), new SkillProgress(level, 0, true)));
        return p;
    }

    static HabitsApi.PerkHit hit(ServerPlayer p, String skill) {
        HabitsApi.PerkHit hit = HabitsApi.mechanicHit(p, Habits.id(skill));
        if (hit == null) throw new IllegalStateException("no hit for " + skill);
        return hit;
    }

    static int count(ServerPlayer p, Item item) {
        return p.getInventory().countItem(item);
    }

    /** signature_work: 160 sticks crafted 4 at a time is two batches of 64, so mass_craft fires twice, not on every
     * craft after the 64th. */
    @GameTest(template = "empty")
    public static void mendingTouchPoursBarIntoHeldThenArmor(GameTestHelper h) {
        ServerPlayer p = player(h, "mending_touch", 10);
        p.setShiftKeyDown(true);
        p.giveExperiencePoints(1000);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        pick.setDamageValue(500);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, pick);
        int xp = Xp.totalXp(p);
        Xp.mendPulse(p);
        int fixed = 500 - pick.getDamageValue();
        h.assertTrue(fixed == (int) Math.ceil(pick.getMaxDamage() * 0.025), "one pulse repairs 2.5% of max, got " + fixed);
        h.assertTrue(xp - Xp.totalXp(p) == fixed, "Lv10 pays 1 XP per point");
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        ItemStack helm = new ItemStack(Items.IRON_HELMET);
        helm.setDamageValue(50);
        p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, helm);
        Xp.mendPulse(p); // same tick: rate limited
        h.assertTrue(helm.getDamageValue() == 50, "pulses are rate limited");
        h.runAfterDelay(3, () -> {
            Xp.mendPulse(p);
            h.assertTrue(helm.getDamageValue() < 50, "empty hand repairs worn armor");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void lootChestCountsOnlyOncePerPlayer(GameTestHelper h) {
        ServerPlayer p = player(h, "loot_goblin", 10);
        BlockPos at = h.absolutePos(new BlockPos(2, 1, 2));
        h.getLevel().setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) h.getLevel().getBlockEntity(at);
        var hit = new net.minecraft.world.phys.BlockHitResult(at.getCenter(), net.minecraft.core.Direction.UP, at, false);
        for (int i = 0; i < 2; i++) {
            // Lootr-style: the table stays set after every open
            chest.setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON);
            Inventory.onClickBlock(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(
                    p, net.minecraft.world.InteractionHand.MAIN_HAND, at, hit));
            h.assertTrue(Inventory.LOOT.containsKey(p.getUUID()) == (i == 0), "pending only on the first open, try " + i);
            Inventory.LOOT.remove(p.getUUID());
        }
        h.succeed();
    }

    /** Held right-click on a lagging server queues several use packets; each one reopens the (Lootr) chest in the same
     * tick. Only the first open may roll Loot Goblin, or a full chest sprays a new batch at the player's feet per packet. */
    @GameTest(template = "empty")
    public static void lootGoblinRollsOncePerBurstOfOpens(GameTestHelper h) {
        ServerPlayer p = player(h, "loot_goblin", 10); // 100%, 3 rolls
        BlockPos at = h.absolutePos(new BlockPos(2, 1, 2));
        h.getLevel().setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) h.getLevel().getBlockEntity(at);
        var hit = new net.minecraft.world.phys.BlockHitResult(at.getCenter(), net.minecraft.core.Direction.UP, at, false);
        SimpleContainer full = new SimpleContainer(27);
        for (int i = 0; i < 27; i++) full.setItem(i, new ItemStack(Items.BEDROCK, 64));
        AABB box = new AABB(at).inflate(8);
        int afterFirst = -1;
        for (int i = 0; i < 3; i++) {
            chest.setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON); // Lootr keeps it
            Inventory.onClickBlock(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(
                    p, net.minecraft.world.InteractionHand.MAIN_HAND, at, hit));
            Inventory.onOpen(new net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open(p,
                    net.minecraft.world.inventory.ChestMenu.threeRows(i, p.getInventory(), full)));
            // overflow lands in the inventory first, then the ground
            int drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, box).size();
            for (ItemStack s : p.getInventory().items) if (!s.isEmpty()) drops++;
            if (i == 0) afterFirst = drops;
            h.assertTrue(afterFirst > 0 && drops == afterFirst, "open " + i + " gave more: " + afterFirst + " -> " + drops);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void lootGoblinWaitsForLootrConversion(GameTestHelper h) {
        ServerPlayer p = player(h, "loot_goblin", 10);
        BlockPos at = h.absolutePos(new BlockPos(2, 1, 2));
        h.getLevel().setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) h.getLevel().getBlockEntity(at);
        var hit = new net.minecraft.world.phys.BlockHitResult(at.getCenter(), net.minecraft.core.Direction.UP, at, false);
        Runnable click = () -> Inventory.onClickBlock(new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(
                p, net.minecraft.world.InteractionHand.MAIN_HAND, at, hit));
        // Lootr cancelled the fill: the menu shows the vanilla chest, table still set
        chest.setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.SIMPLE_DUNGEON);
        click.run();
        Inventory.onOpen(new net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open(p,
                net.minecraft.world.inventory.ChestMenu.threeRows(0, p.getInventory(), chest)));
        h.assertTrue(chest.getLootTable() != null, "chest Lootr is about to replace left untouched"); // any read or insert would unpack it
        // after conversion: the per-player inventory opens and gets the roll
        SimpleContainer lootr = new SimpleContainer(27);
        click.run();
        Inventory.onOpen(new net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open(p,
                net.minecraft.world.inventory.ChestMenu.threeRows(1, p.getInventory(), lootr)));
        h.assertFalse(lootr.isEmpty(), "the Lootr chest's first open rolls");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void massCraftOncePer64(GameTestHelper h) {
        ServerPlayer p = player(h, null, 0);
        com.habits.trigger.Triggers.TEST_PLAYERS.add(p.getUUID());
        int[] n = new int[1];
        com.habits.trigger.Triggers.Spy spy = (who, type, ctx) -> { if (who == p && Habits.id("mass_craft").equals(ctx.target())) n[0]++; };
        com.habits.trigger.Triggers.SPIES.add(spy);
        try {
            for (int i = 0; i < 40; i++)
                net.neoforged.neoforge.event.EventHooks.firePlayerCraftingEvent(p, new ItemStack(Items.STICK, 4),
                        new net.minecraft.world.inventory.TransientCraftingContainer(p.inventoryMenu, 2, 2));
            h.assertValueEqual(n[0], 2, "mass_craft events for 160 sticks");
        } finally {
            com.habits.trigger.Triggers.SPIES.remove(spy);
            com.habits.trigger.Triggers.TEST_PLAYERS.remove(p.getUUID());
            U.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ every domain skill loads and has its mechanic

    @GameTest(template = "empty")
    public static void allSkillsLoadWithMechanics(GameTestHelper h) {
        int n = 0;
        for (var def : SkillRegistry.all()) {
            var m = def.perk().mechanic();
            if (m.isEmpty() || !m.get().getPath().equals(def.id().getPath())) continue;
            if (com.habits.core.PerkMechanics.get(m.get()) == null) continue;
            n++;
        }
        h.assertTrue(n >= 90, "90 utility skills with registered mechanics, got " + n);
        h.succeed();
    }

    // ------------------------------------------------------------------ top picks: effect and Lv1 vs Lv10

    @GameTest(template = "empty")
    public static void pocketFurnaceSmeltsWithBagFuel(GameTestHelper h) {
        ServerPlayer lo = player(h, "pocket_furnace", 1), hi = player(h, "pocket_furnace", 10);
        for (ServerPlayer p : List.of(lo, hi)) {
            p.getInventory().setItem(9, new ItemStack(Items.RAW_IRON, 3));
            p.getInventory().setItem(10, new ItemStack(Items.COAL, 1));
        }
        h.assertFalse(Furnaces.pocketTick(lo, hit(lo, "pocket_furnace").value(), 20), "Lv1 waits 20 s");
        h.assertTrue(Furnaces.pocketTick(hi, hit(hi, "pocket_furnace").value(), 20), "Lv10 smelts every second");
        h.assertValueEqual(count(hi, Items.IRON_INGOT), 1, "one ingot out");
        h.assertValueEqual(count(hi, Items.RAW_IRON), 2, "one raw iron in");
        h.assertValueEqual(count(hi, Items.COAL), 0, "coal burned");
        // banked fuel smelts 7 more; nothing smelts without inputs, nothing is created
        for (int i = 0; i < 10; i++) Furnaces.pocketTick(hi, 1, 20);
        h.assertValueEqual(count(hi, Items.IRON_INGOT) + count(hi, Items.RAW_IRON), 3, "iron conserved");
        ServerPlayer broke = player(h, "pocket_furnace", 10);
        broke.getInventory().setItem(9, new ItemStack(Items.RAW_IRON, 3));
        h.assertFalse(Furnaces.pocketTick(broke, 1, 20), "no fuel, no smelt");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void anvilRainScales(GameTestHelper h) {
        ServerPlayer lo = player(h, "anvil_rain", 1), hi = player(h, "anvil_rain", 10);
        Zombie a = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 3)), b = h.spawn(EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        float before = a.getHealth();
        Mobs.dropAnvil(lo, a, hit(lo, "anvil_rain"));
        Mobs.dropAnvil(hi, b, hit(hi, "anvil_rain"));
        float lost1 = before - a.getHealth();
        h.assertTrue(lost1 > 0 && lost1 <= 4, "Lv1 hits for up to 4, lost " + lost1);
        h.assertTrue(!b.isAlive() || before - b.getHealth() > lost1 * 3, "Lv10 hits far harder");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void trimFashionIronAddsArmor(GameTestHelper h) {
        var reg = h.getLevel().registryAccess();
        ArmorTrim trim = new ArmorTrim(reg.registryOrThrow(Registries.TRIM_MATERIAL).getHolderOrThrow(TrimMaterials.IRON),
                reg.registryOrThrow(Registries.TRIM_PATTERN).getHolderOrThrow(TrimPatterns.COAST));
        double[] got = new double[2];
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            ServerPlayer p = player(h, "trim_fashion", lvl);
            ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
            chest.set(DataComponents.TRIM, trim);
            p.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, chest);
            Mobs.trims(p);
            var mod = p.getAttribute(Attributes.ARMOR).getModifier(Habits.id("utility_trim_iron"));
            got[i++] = mod == null ? 0 : mod.amount();
        }
        h.assertValueEqual(got[0], 0.2, "Lv1 +0.2 armor per iron trim");
        h.assertValueEqual(got[1], 2.0, "Lv10 +2 armor per iron trim");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void overchargeGoesOnePastMaxOnly(GameTestHelper h) {
        ServerPlayer p = player(h, "overcharge", 10);
        var sharp = h.getLevel().registryAccess().holderOrThrow(Enchantments.SHARPNESS);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(sharp, 5);
        Tables.overcharge(p, sword);
        h.assertValueEqual(EnchantmentHelper.getItemEnchantmentLevel(sharp, sword), 6, "Sharpness VI");
        Tables.overcharge(p, sword);
        h.assertValueEqual(EnchantmentHelper.getItemEnchantmentLevel(sharp, sword), 6, "never past max + 1");
        h.assertValueEqual(hit(player(h, "overcharge", 1), "overcharge").value(), 2.0, "Lv1 2%");
        h.assertValueEqual(hit(p, "overcharge").value(), 25.0, "Lv10 25%");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void djBuffsWithinRadius(GameTestHelper h) {
        BlockPos rel = new BlockPos(1, 1, 1);
        h.setBlock(rel, Blocks.JUKEBOX);
        JukeboxBlockEntity box = (JukeboxBlockEntity) h.getBlockEntity(rel);
        box.setTheItem(new ItemStack(Items.MUSIC_DISC_PIGSTEP));
        if (!box.getSongPlayer().isPlaying()) box.tryForcePlaySong();
        h.assertTrue(box.getSongPlayer().isPlaying(), "jukebox plays");
        ServerPlayer lo = player(h, "dj", 1), hi = player(h, "dj", 10);
        BlockPos abs = h.absolutePos(rel);
        for (ServerPlayer p : List.of(lo, hi)) {
            p.moveTo(abs.getX() + 20.5, abs.getY(), abs.getZ() + 0.5, 0, 0);
            Stations.used(p, abs);
            Music.dj(p, hit(p, "dj"));
        }
        h.assertFalse(lo.hasEffect(MobEffects.DAMAGE_BOOST), "Lv1 radius 8 misses at 20");
        h.assertTrue(hi.hasEffect(MobEffects.DAMAGE_BOOST), "Lv10 radius 40 reaches 20 (pigstep = strength)");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void bellringerGlowRadius(GameTestHelper h) {
        Zombie z = h.spawn(EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        BlockPos bell = h.absolutePos(new BlockPos(1, 1, 1)).offset(-25, 0, 0);
        ServerPlayer lo = player(h, "bellringer", 1);
        Music.ringBell(lo, bell);
        h.assertFalse(z.hasEffect(MobEffects.GLOWING), "Lv1 radius 16 misses at 25");
        ServerPlayer hi = player(h, "bellringer", 10);
        Music.ringBell(hi, bell);
        h.assertTrue(z.hasEffect(MobEffects.GLOWING), "Lv10 radius 64 lights it");
        h.assertTrue(z.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "Lv10 also slows");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void lightningChainsAtHighLevel(GameTestHelper h) {
        ServerPlayer p = player(h, "lightning_rod_fan", 10);
        Zombie a = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 3));
        h.spawn(EntityType.ZOMBIE, new BlockPos(5, 1, 3));
        h.spawn(EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        h.assertValueEqual(Mobs.callLightning(p, a, (int) U.param(hit(p, "lightning_rod_fan"), "strikes", 1, 3)), 3, "Lv10 chains 3");
        ServerPlayer q = player(h, "lightning_rod_fan", 1);
        h.assertValueEqual(Mobs.callLightning(q, a, (int) U.param(hit(q, "lightning_rod_fan"), "strikes", 1, 3)), 1, "Lv1 one strike");
        h.assertValueEqual(hit(q, "lightning_rod_fan").value(), 60.0, "Lv1 60 s cooldown");
        h.assertValueEqual(hit(p, "lightning_rod_fan").value(), 8.0, "Lv10 8 s cooldown");
        var bolts = h.getLevel().getEntitiesOfClass(LightningBolt.class, new AABB(h.absolutePos(BlockPos.ZERO)).inflate(16));
        h.assertTrue(bolts.stream().allMatch(b -> b.getTags().contains(Mobs.ROD_TAG)), "called bolts are tagged");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void hagglerCutsPricesAndResets(GameTestHelper h) {
        int[] cost = new int[2];
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            ServerPlayer p = player(h, "haggler", lvl);
            Villager v = h.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
            v.getOffers().clear();
            v.getOffers().add(new MerchantOffer(new ItemCost(Items.EMERALD, 20), new ItemStack(Items.DIAMOND), 10, 1, 0.05f));
            Villagers.opened(p, v);
            cost[i++] = v.getOffers().get(0).getCostA().getCount();
            v.getOffers().get(0).resetSpecialPriceDiff(); // what vanilla does when trading stops
            h.assertValueEqual(v.getOffers().get(0).getCostA().getCount(), 20, "price resets after trading");
        }
        h.assertValueEqual(cost[0], 20, "Lv1 3% of 20 rounds to no cut");
        h.assertValueEqual(cost[1], 12, "Lv10 40% off");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void statueHidesAfterStillness(GameTestHelper h) {
        ServerPlayer lo = player(h, "statue", 1), hi = player(h, "statue", 10);
        Zombie z = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 3));
        for (int s = 0; s < 3; s++) { Odd.stillTick(lo); Odd.stillTick(hi); }
        h.assertTrue(Odd.hidden(hi, z), "Lv10: still 2 s, fully hidden");
        h.assertFalse(Odd.hidden(lo, z), "Lv1 needs 10 s");
        h.assertTrue(hi.hasEffect(MobEffects.REGENERATION), "Lv10 regen while still");
        hi.moveTo(hi.getX() + 1, hi.getY(), hi.getZ());
        Odd.stillTick(hi);
        h.assertFalse(Odd.hidden(hi, z), "moving breaks it");
        h.succeed();
    }

    // ------------------------------------------------------------------ dupe safety

    /** pack_rat across death: with keepInventory the storage moves to the respawned player and nothing drops; without it
     * the storage drops and the new player starts empty. Uses the real drop event and the real respawn clone hook. */
    @GameTest(template = "empty")
    public static void packRatSurvivesDeathWithKeepInventory(GameTestHelper h) {
        var rule = h.getLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
        boolean was = rule.get();
        try {
            for (boolean keep : new boolean[]{true, false}) {
                rule.set(keep, h.getLevel().getServer());
                ServerPlayer old = player(h, "pack_rat", 10), fresh = player(h, "pack_rat", 10);
                Inventory.packInsert(old, new ItemStack(Items.DIAMOND, 5), 9);
                List<ItemEntity> drops = new java.util.ArrayList<>();
                NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.living.LivingDropsEvent(old,
                        h.getLevel().damageSources().generic(), drops, false));
                net.neoforged.neoforge.event.EventHooks.onPlayerClone(fresh, old, true);
                int kept = 0, dropped = 0;
                for (ItemStack s : Inventory.pack(fresh)) if (s.is(Items.DIAMOND)) kept += s.getCount();
                for (ItemEntity e : drops) if (e.getItem().is(Items.DIAMOND)) dropped += e.getItem().getCount();
                h.assertValueEqual(kept, keep ? 5 : 0, "keepInventory " + keep + ": pack on the respawned player");
                h.assertValueEqual(dropped, keep ? 0 : 5, "keepInventory " + keep + ": pack items dropped");
                U.TEST_PLAYERS.remove(old.getUUID());
                U.TEST_PLAYERS.remove(fresh.getUUID());
            }
        } finally {
            rule.set(was, h.getLevel().getServer());
        }
        h.succeed();
    }

    /** pack_rat: the book (the only way to open the pack) never goes in, by overflow pickup or the pack screen, and a
     * book stuck in an old save comes back to the inventory. */
    @GameTest(template = "empty")
    public static void packRatNeverSwallowsTheBook(GameTestHelper h) {
        ServerPlayer p = player(h, "pack_rat", 10);
        ItemStack book = new ItemStack(com.habits.core.CoreRegistries.BOOK.get());
        Inventory.packInsert(p, book, 9);
        h.assertValueEqual(book.getCount(), 1, "overflow pickup leaves the book on the ground");
        h.assertTrue(Inventory.pack(p).stream().noneMatch(Inventory::isBook), "no book in the pack");
        var menu = Inventory.packMenu(0, p.getInventory(), p);
        var slot = menu.slots.get(0);
        h.assertFalse(slot.mayPlace(book), "pack slot refuses the book");
        h.assertTrue(slot.mayPlace(new ItemStack(Items.DIAMOND)), "pack slot takes other items");
        p.getInventory().setItem(0, book.copy());
        menu.quickMoveStack(p, menu.slots.size() - 9); // hotbar slot 0 in the menu: shift-click toward the pack
        h.assertTrue(p.getInventory().getItem(0).is(com.habits.core.CoreRegistries.BOOK.get()), "shift-click leaves the book in the hotbar");
        h.assertTrue(Inventory.pack(p).stream().noneMatch(Inventory::isBook), "still no book in the pack");
        p.getInventory().setItem(0, ItemStack.EMPTY);
        p.setData(Inventory.PACK, List.of(ItemStack.EMPTY, book.copy()));
        Inventory.ejectBooks(p);
        h.assertTrue(Inventory.pack(p).stream().noneMatch(Inventory::isBook), "stuck book left the pack");
        h.assertTrue(p.getInventory().contains(book), "stuck book is back in the inventory");
        U.TEST_PLAYERS.remove(p.getUUID());
        h.succeed();
    }

    /** pocket_workbench: the cooldown reaches 0 at Lv9; at Lv10 the portable grid refills from the main inventory after a
     * craft, moving exactly what was used and nothing else. Lv9 leaves the grid empty. */
    @GameTest(template = "empty")
    public static void pocketWorkbenchRefillsFromInventory(GameTestHelper h) {
        h.assertTrue(hit(player(h, "pocket_workbench", 8), "pocket_workbench").value() > 0, "Lv8 still has a cooldown");
        h.assertValueEqual(hit(player(h, "pocket_workbench", 9), "pocket_workbench").value(), 0.0, "Lv9 cooldown");
        for (int lvl : new int[]{9, 10}) {
            ServerPlayer p = player(h, "pocket_workbench", lvl);
            p.getInventory().setItem(9, new ItemStack(Items.COAL, 5));
            p.getInventory().setItem(10, new ItemStack(Items.STICK, 5));
            p.getInventory().setItem(11, new ItemStack(Items.DIAMOND, 10));
            Crafting.PocketMenu menu = new Crafting.PocketMenu(1, p.getInventory(), ContainerLevelAccess.create(h.getLevel(), p.blockPosition()));
            p.containerMenu = menu;
            CraftingContainer grid = (CraftingContainer) menu.getSlot(1).container;
            grid.setItem(0, new ItemStack(Items.COAL));
            grid.setItem(3, new ItemStack(Items.STICK));
            menu.slotsChanged(grid);
            menu.clicked(0, 0, ClickType.PICKUP, p);
            h.assertTrue(menu.getCarried().is(Items.TORCH), "Lv" + lvl + " crafted torches");
            NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(p));
            boolean refills = lvl >= 10;
            h.assertValueEqual(grid.getItem(0).getCount(), refills ? 1 : 0, "Lv" + lvl + " coal slot");
            h.assertValueEqual(grid.getItem(3).getCount(), refills ? 1 : 0, "Lv" + lvl + " stick slot");
            h.assertTrue(grid.getItem(0).isEmpty() || grid.getItem(0).is(Items.COAL), "coal slot holds coal");
            h.assertValueEqual(count(p, Items.COAL) + grid.getItem(0).getCount(), 5, "Lv" + lvl + " coal conserved");
            h.assertValueEqual(count(p, Items.STICK) + grid.getItem(3).getCount(), 5, "Lv" + lvl + " sticks conserved");
            h.assertValueEqual(count(p, Items.DIAMOND), 10, "Lv" + lvl + " unused items stay put");
            int inGrid = 0;
            for (int i = 0; i < grid.getContainerSize(); i++) inGrid += grid.getItem(i).getCount();
            h.assertValueEqual(inGrid, refills ? 2 : 0, "Lv" + lvl + " nothing else enters the grid");
            p.containerMenu = p.inventoryMenu;
            U.TEST_PLAYERS.remove(p.getUUID());
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void craftBonusRefusesLoopableRecipes(GameTestHelper h) {
        ServerPlayer p = player(h, "handy", 10);
        CraftingMenu menu = new CraftingMenu(0, p.getInventory(), ContainerLevelAccess.NULL);
        CraftingContainer grid = (CraftingContainer) menu.getSlot(1).container;
        for (int i = 0; i < 9; i++) grid.setItem(i, new ItemStack(Items.IRON_INGOT));
        h.assertFalse(Crafting.bonusSafe(p, grid, new ItemStack(Items.IRON_BLOCK), 1), "ingots to block is reversible: no bonus");
        grid.clearContent();
        grid.setItem(0, new ItemStack(Items.COAL));
        grid.setItem(3, new ItemStack(Items.STICK));
        h.assertTrue(Crafting.bonusSafe(p, grid, new ItemStack(Items.TORCH, 4), 2), "torches are fine");
        grid.clearContent();
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND_BLOCK, 64))));
        grid.setItem(0, box);
        grid.setItem(1, new ItemStack(Items.RED_DYE));
        ItemStack red = new ItemStack(Items.RED_SHULKER_BOX);
        red.set(DataComponents.CONTAINER, box.get(DataComponents.CONTAINER));
        h.assertFalse(Crafting.bonusSafe(p, grid, red, 2), "dyeing a full shulker never doubles it");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void handShulkerCannotDupe(GameTestHelper h) {
        ServerPlayer p = player(h, "shulker_sense", 1);
        ItemStack held = new ItemStack(Items.SHULKER_BOX);
        p.getInventory().selected = 0;
        p.getInventory().setItem(0, held);
        Inventory.HandShulkerMenu menu = new Inventory.HandShulkerMenu(1, p.getInventory(), held);
        p.containerMenu = menu;
        menu.setCarried(new ItemStack(Items.DIAMOND, 5));
        menu.clicked(0, 0, ClickType.PICKUP, p);
        h.assertValueEqual(held.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems().iterator().next().getCount(), 5, "box saved");
        menu.clicked(menu.heldSlot, 0, ClickType.PICKUP, p);
        h.assertTrue(menu.getCarried().isEmpty() && p.getMainHandItem() == held, "the held box cannot be picked up");
        menu.clicked(0, menu.hotbarIndex, ClickType.SWAP, p);
        h.assertTrue(p.getMainHandItem() == held, "nor swapped out");
        // the box leaves the hand some other way: the menu goes dead before anything moves
        p.getInventory().setItem(0, ItemStack.EMPTY);
        menu.clicked(0, 0, ClickType.PICKUP, p);
        h.assertTrue(menu.getCarried().isEmpty(), "dead menu hands nothing out");
        h.assertFalse(menu.stillValid(p), "menu closes");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void barrelKeepConservesItems(GameTestHelper h) {
        BlockPos rel = new BlockPos(2, 1, 2);
        h.setBlock(rel, Blocks.BARREL);
        BarrelBlockEntity barrel = (BarrelBlockEntity) h.getBlockEntity(rel);
        for (int i = 0; i < 10; i++) barrel.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        h.assertTrue(Inventory.keepBarrel(h.getLevel(), h.absolutePos(rel), barrel, 3), "kept");
        int total = 0;
        for (ItemEntity e : h.getEntities(EntityType.ITEM)) {
            ItemStack s = e.getItem();
            if (s.is(Items.COBBLESTONE)) total += s.getCount();
            if (s.is(Items.BARREL)) {
                for (ItemStack in : s.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).nonEmptyItems()) total += in.getCount();
            }
        }
        h.assertValueEqual(total, 640, "every cobblestone accounted for exactly once");
        h.assertTrue(h.getLevel().getBlockState(h.absolutePos(rel)).isAir(), "barrel removed");
        // nesting is refused
        h.setBlock(rel, Blocks.BARREL);
        BarrelBlockEntity b2 = (BarrelBlockEntity) h.getBlockEntity(rel);
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT))));
        b2.setItem(0, box);
        h.assertFalse(Inventory.keepBarrel(h.getLevel(), h.absolutePos(rel), b2, 27), "no barrels holding full boxes");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void containerMovesConserveItems(GameTestHelper h) {
        ServerPlayer p = player(h, "rummager", 10);
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(5, new ItemStack(Items.DIRT, 10));
        chest.setItem(7, new ItemStack(Items.STONE, 30));
        p.getInventory().setItem(12, new ItemStack(Items.DIRT, 64));
        p.getInventory().setItem(13, new ItemStack(Items.DIAMOND, 3));
        p.getInventory().setItem(2, new ItemStack(Items.DIRT, 5)); // hotbar stays
        Inventory.depositMatching(p, chest);
        Inventory.sort(chest);
        h.assertValueEqual(chest.countItem(Items.DIRT) + count(p, Items.DIRT), 79, "dirt conserved");
        h.assertValueEqual(count(p, Items.DIAMOND), 3, "unmatched items stay");
        h.assertValueEqual(p.getInventory().getItem(2).getCount(), 5, "hotbar untouched");
        h.assertValueEqual(chest.countItem(Items.STONE), 30, "sort conserves");
        // pack rat: overflow storage holds exactly what left the stack
        ItemStack loose = new ItemStack(Items.GRAVEL, 64);
        Inventory.packInsert(p, loose, 9);
        int stored = 0;
        for (ItemStack s : Inventory.pack(p)) if (s.is(Items.GRAVEL)) stored += s.getCount();
        h.assertValueEqual(stored + loose.getCount(), 64, "pack rat conserves");
        // hotbar rotate is a pure permutation
        p.getInventory().setItem(0, new ItemStack(Items.APPLE, 7));
        Inventory.rotateRows(p, 3);
        Inventory.rotateRows(p, 3);
        Inventory.rotateRows(p, 3);
        Inventory.rotateRows(p, 3);
        h.assertValueEqual(p.getInventory().getItem(0).getCount(), 7, "four turns of four rows come back home");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void stoneSenseConvertsExactly(GameTestHelper h) {
        ServerPlayer p = player(h, "stone_sense", 10);
        p.getInventory().selected = 0;
        p.getInventory().setItem(0, new ItemStack(Items.STONE, 64));
        p.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        int made = Crafting.convert(p, Crafting.cut(Items.STONE, Items.STONE_SLAB, 2), (int) hit(p, "stone_sense").value());
        h.assertValueEqual(made, 74, "all stone used");
        h.assertValueEqual(count(p, Items.STONE_SLAB), 148, "two slabs per stone");
        ServerPlayer lo = player(h, "stone_sense", 1);
        lo.getInventory().selected = 0;
        lo.getInventory().setItem(0, new ItemStack(Items.STONE, 64));
        lo.getInventory().setItem(9, new ItemStack(Items.STONE, 10));
        Crafting.convert(lo, Crafting.cut(Items.STONE, Items.STONE_SLAB, 2), (int) hit(lo, "stone_sense").value());
        h.assertValueEqual(count(lo, Items.STONE), 10, "Lv1 converts one stack");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void bottledXpNeverGrows(GameTestHelper h) {
        ServerPlayer p = player(h, "bottled_wisdom", 10);
        p.giveExperiencePoints(100);
        int before = Xp.totalXp(p);
        ItemStack bottles = new ItemStack(Items.GLASS_BOTTLE, 4);
        p.getInventory().setItem(0, bottles);
        h.assertTrue(Xp.bottle(p, bottles, hit(p, "bottled_wisdom")), "bottled");
        int stored = 0;
        for (ItemStack s : p.getInventory().items) {
            var cd = s.get(DataComponents.CUSTOM_DATA);
            if (s.is(Items.EXPERIENCE_BOTTLE) && cd != null) stored += cd.copyTag().getInt(Xp.TAG_XP);
        }
        h.assertTrue(stored > 0 && stored <= before - Xp.totalXp(p), "never more XP out than went in");
        h.succeed();
    }

    // ------------------------------------------------------------------ mixin-backed perks

    /** HabitsApiMixin: lucky_charm scales skill XP per action; Lv10 (+100%) earns about twice Lv1 (+5%). */
    @GameTest(template = "empty")
    public static void luckyCharmRaisesSkillXp(GameTestHelper h) {
        com.habits.core.SkillDef pick = null;
        for (com.habits.core.SkillDef d : com.habits.core.SkillRegistry.byTrigger(com.habits.core.TriggerType.CUSTOM)) {
            var t = d.trigger();
            if (t.chance() >= 0.05 && t.min() <= 1 && t.item().isEmpty() && t.entity().isEmpty() && !d.id().equals(Habits.id("lucky_charm"))
                    && t.match().isPresent() && t.match().get().ids().size() == 1 && t.match().get().tags().isEmpty()) { pick = d; break; }
        }
        h.assertTrue(pick != null, "test needs a custom-event skill");
        net.minecraft.resources.ResourceLocation event = pick.trigger().match().get().ids().get(0), skill = pick.id();
        long[] xp = new long[2];
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            ServerPlayer p = player(h, "lucky_charm", lvl);
            p.setData(CoreRegistries.COLLECTION, HabitsApi.collection(p).with(skill, new SkillProgress(1, 0, true)));
            com.habits.core.SkillXp.TEST_PLAYERS.add(p.getUUID());
            for (int n = 0; n < 20; n++) HabitsApi.onTrigger(p, com.habits.core.TriggerType.CUSTOM, com.habits.core.TriggerContext.of(event));
            com.habits.core.SkillXp.flush(p);
            com.habits.core.SkillXp.TEST_PLAYERS.remove(p.getUUID());
            SkillProgress s = HabitsApi.collection(p).get(skill);
            long total = s.progress();
            for (int l = 1; l < s.level(); l++) total += com.habits.core.Leveling.xpToNext(l, pick.rarity());
            xp[i++] = total;
        }
        h.assertTrue(xp[0] > 0, "Lv1 earns XP");
        h.assertTrue(xp[1] > xp[0] * 1.5, "Lv10 earns about double: " + xp[0] + " vs " + xp[1]);
        h.succeed();
    }

    static ItemStack anvil(ServerPlayer p, ItemStack left, ItemStack right) {
        net.minecraft.world.inventory.AnvilMenu menu = new net.minecraft.world.inventory.AnvilMenu(1, p.getInventory(), ContainerLevelAccess.NULL);
        menu.getSlot(0).set(left);
        menu.getSlot(1).set(right);
        return menu.getSlot(2).getItem();
    }

    /** PennyAnvilMenuMixin: a 200-level repair is Too Expensive at Lv1 (cap 40) and goes through at Lv10 (no cap). A
     * stack in the left slot keeps vanilla's cap at any level (dupe guard: one book on a whole stack). */
    @GameTest(template = "empty")
    public static void pennyPincherRaisesTooExpensiveCap(GameTestHelper h) {
        var sharp = h.getLevel().registryAccess().holderOrThrow(Enchantments.SHARPNESS);
        java.util.function.Supplier<ItemStack> book = () -> net.minecraft.world.item.EnchantedBookItem.createForEnchantment(
                new net.minecraft.world.item.enchantment.EnchantmentInstance(sharp, 1));
        java.util.function.IntFunction<ItemStack> sword = n -> {
            ItemStack s = new ItemStack(Items.DIAMOND_SWORD, n);
            s.set(DataComponents.REPAIR_COST, 200);
            return s;
        };
        h.assertTrue(anvil(player(h, "penny_pincher", 1), sword.apply(1), book.get()).isEmpty(), "Lv1: still Too Expensive");
        ServerPlayer p10 = player(h, "penny_pincher", 10);
        ItemStack out = anvil(p10, sword.apply(1), book.get());
        h.assertTrue(!out.isEmpty() && EnchantmentHelper.getItemEnchantmentLevel(sharp, out) == 1, "Lv10: no cap, sword enchanted");
        // anvil slots clamp swords to 1, and no vanilla stackable item takes a book enchant, so check the guard directly
        h.assertValueEqual(UtilityHooks.anvilCap(p10, new ItemStack(Items.BOOK, 2)), 40, "a stack keeps the vanilla cap");
        h.assertValueEqual(UtilityHooks.anvilCap(p10, new ItemStack(Items.BOOK)), Integer.MAX_VALUE, "Lv10 single item: no cap");
        h.assertValueEqual(UtilityHooks.anvilCost(p10, 100), 50, "Lv10 halves the cost");
        h.assertValueEqual(UtilityHooks.anvilCost(player(h, null, 0), 100), 100, "no skill, no change");
        h.succeed();
    }

    /** overforge (Mythical): unlocks from a 30+ level anvil job only; Lv10 lifts the Too Expensive cap; a taken
     * anvil result at max goes one past it (fixture at 100%), and never two past. */
    @GameTest(template = "empty")
    public static void overforgeAnvilTriggerCapAndOnePastMax(GameTestHelper h) {
        var def = com.habits.core.SkillRegistry.get(Habits.id("overforge"));
        ServerPlayer t = player(h, null, 0);
        com.habits.core.SkillXp.TEST_PLAYERS.add(t.getUUID());
        var anvil = com.habits.core.TriggerContext.of(Items.DIAMOND_SWORD).withAmount(29);
        com.habits.core.HabitsApi.onTrigger(t, com.habits.core.TriggerType.ANVIL, anvil);
        h.assertValueEqual(HabitsApi.level(t, def.id()), 0, "29 levels: not unlocked");
        com.habits.core.HabitsApi.onTrigger(t, com.habits.core.TriggerType.ANVIL, anvil.withAmount(30));
        h.assertValueEqual(HabitsApi.level(t, def.id()), 1, "30 levels: unlocked");
        com.habits.core.SkillXp.TEST_PLAYERS.remove(t.getUUID());

        var sharp = h.getLevel().registryAccess().holderOrThrow(Enchantments.SHARPNESS);
        ItemStack pricey = new ItemStack(Items.DIAMOND_SWORD);
        pricey.set(DataComponents.REPAIR_COST, 200);
        ItemStack book = net.minecraft.world.item.EnchantedBookItem.createForEnchantment(new net.minecraft.world.item.enchantment.EnchantmentInstance(sharp, 1));
        h.assertTrue(anvil(player(h, "overforge", 9), pricey.copy(), book.copy()).isEmpty(), "Lv9: still Too Expensive");
        h.assertFalse(anvil(player(h, "overforge", 10), pricey.copy(), book.copy()).isEmpty(), "Lv10: no cap");

        var perk = def.perk();
        var fixture = com.habits.core.SkillRegistry.testFixture(new com.habits.core.SkillDef(Habits.id("test/overforge"), def.rarity(),
                def.category(), def.trigger(), def.icon(), def.art(), new com.habits.core.PerkSpec(perk.type(), perk.mechanic(), perk.params(),
                0, 0, java.util.Optional.of(java.util.Collections.nCopies(10, 100.0))), def.name(), def.foundBy(), def.perkText()));
        ServerPlayer p = player(h, null, 0);
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(fixture.id(), new SkillProgress(1, 0, true)));
        ItemStack out = new ItemStack(Items.DIAMOND_SWORD);
        out.enchant(sharp, 5);
        p.containerMenu.setCarried(out);
        NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.AnvilRepairEvent(p, new ItemStack(Items.DIAMOND_SWORD), ItemStack.EMPTY, out));
        h.assertValueEqual(EnchantmentHelper.getItemEnchantmentLevel(sharp, out), 6, "Sharpness V taken as VI");
        NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.player.AnvilRepairEvent(p, new ItemStack(Items.DIAMOND_SWORD), ItemStack.EMPTY, out));
        h.assertValueEqual(EnchantmentHelper.getItemEnchantmentLevel(sharp, out), 6, "never past +1");
        p.containerMenu.setCarried(ItemStack.EMPTY);
        h.succeed();
    }

    /** BundleItemMixin + BundleContentsMixin: right-clicking 64-stacks of cobble onto a bundle. Lv1 fits 1.5x a vanilla
     * bundle, Lv10 10x; every item ends up either in the bundle or still in hand (nothing made or lost). */
    @GameTest(template = "empty")
    public static void bundleUpHoldsMoreAndConserves(GameTestHelper h) {
        int[] held = new int[2];
        int i = 0;
        for (int lvl : new int[]{1, 10}) {
            ServerPlayer p = player(h, "bundle_up", lvl);
            ItemStack bundle = new ItemStack(Items.BUNDLE);
            net.minecraft.world.inventory.Slot slot = new net.minecraft.world.inventory.Slot(new SimpleContainer(bundle), 0, 0, 0);
            int left = 0;
            for (int n = 0; n < 12; n++) {
                ItemStack cobble = new ItemStack(Items.COBBLESTONE, 64);
                bundle.getItem().overrideOtherStackedOnMe(bundle, cobble, slot, net.minecraft.world.inventory.ClickAction.SECONDARY, p,
                        net.minecraft.world.entity.SlotAccess.NULL);
                left += cobble.getCount();
            }
            int in = 0;
            for (ItemStack s : bundle.get(DataComponents.BUNDLE_CONTENTS).items()) in += s.getCount();
            h.assertValueEqual(in + left, 12 * 64, "Lv" + lvl + " conserves cobble");
            held[i++] = in;
        }
        h.assertValueEqual(held[0], 96, "Lv1 1.5x of 64");
        h.assertValueEqual(held[1], 640, "Lv10 10x of 64");
        // no skill: vanilla 64, and a bundle stuffed past 64 by the perk takes nothing more from someone without it
        ServerPlayer none = player(h, null, 0);
        ItemStack vanilla = new ItemStack(Items.BUNDLE);
        net.minecraft.world.inventory.Slot slot = new net.minecraft.world.inventory.Slot(new SimpleContainer(vanilla), 0, 0, 0);
        ItemStack cobble = new ItemStack(Items.COBBLESTONE, 64);
        vanilla.getItem().overrideOtherStackedOnMe(vanilla, cobble.copyWithCount(64), slot, net.minecraft.world.inventory.ClickAction.SECONDARY, none,
                net.minecraft.world.entity.SlotAccess.NULL);
        vanilla.getItem().overrideOtherStackedOnMe(vanilla, cobble, slot, net.minecraft.world.inventory.ClickAction.SECONDARY, none,
                net.minecraft.world.entity.SlotAccess.NULL);
        h.assertValueEqual(cobble.getCount(), 64, "vanilla bundle is full at 64");
        h.succeed();
    }

    /** UtilityNet ender key: tier 3 (Lv10) opens the ender chest, tier 2 (Lv5) does not. */
    @GameTest(template = "empty")
    public static void enderLinkKeyAtLv10(GameTestHelper h) {
        // a FakePlayer's openMenu is a no-op, so check the decision
        h.assertFalse(Inventory.enderKey(player(h, "ender_link", 5)), "Lv5 has no key");
        h.assertTrue(Inventory.enderKey(player(h, "ender_link", 10)), "Lv10 key opens the ender chest");
        h.assertFalse(Inventory.enderKey(player(h, null, 0)), "no skill, no key");
        h.succeed();
    }

    /** bookworm (client tooltip) and rummager (client tint) read these; the table's list rolled from the synced seed
     * holds the server's clue, which is what lets the client show the rest of the offer. */
    @GameTest(template = "empty")
    public static void bookwormHintsFromSyncedSeed(GameTestHelper h) {
        h.assertValueEqual(UtilityHooks.bookwormHints(player(h, "bookworm", 1)), 2, "Lv1 shows a second hint");
        h.assertValueEqual(UtilityHooks.bookwormHints(player(h, "bookworm", 10)), Integer.MAX_VALUE, "Lv3+ shows all");
        h.assertValueEqual(UtilityHooks.bookwormHints(player(h, null, 0)), 0, "no skill, vanilla hint");
        h.assertTrue(UtilityHooks.rummager(player(h, "rummager", 1)) && !UtilityHooks.rummager(player(h, null, 0)), "rummager tint gate");
        ServerPlayer p = player(h, "bookworm", 10);
        var menu = new net.minecraft.world.inventory.EnchantmentMenu(1, p.getInventory(), ContainerLevelAccess.create(h.getLevel(), h.absolutePos(new BlockPos(1, 1, 1))));
        menu.getSlot(0).set(new ItemStack(Items.DIAMOND_SWORD));
        menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 3));
        var reg = h.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        int checked = 0;
        for (int row = 0; row < 3; row++) {
            if (menu.costs[row] <= 0 || menu.enchantClue[row] < 0) continue;
            int clue = menu.enchantClue[row], lvl = menu.levelClue[row];
            var list = menu.getEnchantmentList(h.getLevel().registryAccess(), menu.getSlot(0).getItem(), row, menu.costs[row]);
            h.assertTrue(list.stream().anyMatch(e -> reg.getId(e.enchantment.value()) == clue && e.level == lvl), "row " + row + " list holds the clue");
            checked++;
        }
        h.assertTrue(checked > 0, "the table made offers");
        h.succeed();
    }

    // ------------------------------------------------------------------ timing budget

    /** Every utility mechanic's tick handlers at Lv10 for one player, 2000 times, with a full bag and a crowd nearby. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void tickBudget(GameTestHelper h) {
        ServerPlayer p = player(h, null, 0);
        HabitsCollection c = HabitsCollection.EMPTY;
        for (var def : SkillRegistry.all()) {
            if (def.perk().mechanic().isPresent() && def.perk().mechanic().get().getPath().equals(def.id().getPath())) {
                c = c.with(def.id(), new SkillProgress(10, 0, true));
            }
        }
        p.setData(CoreRegistries.COLLECTION, c);
        Item[] stuff = {Items.DIRT, Items.STONE, Items.OAK_LOG, Items.SAND, Items.APPLE, Items.STICK, Items.BONE, Items.STRING};
        for (int i = 0; i < 36; i++) p.getInventory().setItem(i, new ItemStack(stuff[i % stuff.length], 1 + i));
        for (int i = 0; i < 20; i++) h.spawn(EntityType.ZOMBIE, new BlockPos(1 + i % 5, 1, 1 + i / 5));
        for (int i = 0; i < 40; i++) h.spawnItem(Items.COBBLESTONE, 2 + i % 4, 2, 2 + i / 10);
        // warm up, then the median of 5 blocks of 400 ticks (each walks every cadence slot 20 times): one GC pause or a
        // busy machine in one block can't sink the average
        double[] blocks = new double[6];
        for (int b = 0; b < blocks.length; b++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 400; i++) {
                p.tickCount = i % 20; // walk through every cadence slot, 0 is the heavy one
                NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(p));
            }
            blocks[b] = (System.nanoTime() - t0) / 1000.0 / 400;
        }
        double[] timed = java.util.Arrays.copyOfRange(blocks, 1, blocks.length); // block 0 is the JIT warm-up
        java.util.Arrays.sort(timed);
        double avgUs = timed[2];
        com.mojang.logging.LogUtils.getLogger().info("[habits] utility tick budget: {} us per player tick on average (all 90 skills Lv10)", String.format("%.1f", avgUs));
        h.assertTrue(avgUs < 250, "average player tick under 250 us, was " + avgUs);
        h.succeed();
    }
}
