package com.habits.perk.crafting;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.SkillProgress;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** One GameTest per mechanic group of the crafting spec batch: bench, furnace, anvil and grindstone, gear care, alchemy, market. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class CraftingTests {
    private CraftingTests() {}

    /** A test player owning the given crafting skills at Lv10. */
    static ServerPlayer player(GameTestHelper h, String... skills) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-craft"));
        C.TEST_PLAYERS.add(p.getUUID());
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        HabitsCollection c = HabitsCollection.EMPTY;
        for (String s : skills) c = c.with(Habits.id("crafting/" + s), new SkillProgress(10, 0, true));
        p.setData(CoreRegistries.COLLECTION, c);
        return p;
    }

    static HabitsApi.PerkHit hit(ServerPlayer p, String skill) {
        HabitsApi.PerkHit hit = HabitsApi.mechanicHit(p, Habits.id("crafting/" + skill));
        if (hit == null) throw new IllegalStateException("no hit for " + skill);
        return hit;
    }

    @GameTest(template = "empty")
    public static void benchTunesCraftedGear(GameTestHelper h) {
        ServerPlayer p = player(h, "whetstone", "reinforced", "pyrotechnician");
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        Bench.tune(p, sword);
        Bench.tune(p, sword); // never twice
        long mods = sword.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().stream().filter(m -> m.modifier().id().equals(Bench.WHETSTONE)).count();
        h.assertTrue(mods == 1, "one whetstone modifier, got " + mods);
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        Bench.tune(p, chest);
        h.assertTrue(chest.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().stream()
                .anyMatch(m -> m.attribute().is(Attributes.ARMOR) && m.modifier().amount() == 2), "Lv10 reinforced adds 2 armor");
        h.assertTrue(chest.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().size() >= 3, "vanilla armor modifiers kept");
        ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
        rocket.set(DataComponents.FIREWORKS, new Fireworks(3, List.of()));
        Bench.tune(p, rocket);
        Bench.tune(p, rocket);
        h.assertTrue(rocket.get(DataComponents.FIREWORKS).flightDuration() == 6, "flight capped at max_flight 6");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void forgePocketsAndCollect(GameTestHelper h) {
        ServerPlayer p = player(h, "quick_collect");
        p.getInventory().add(new ItemStack(Items.COAL, 40));
        SimpleContainer fuel = new SimpleContainer(1);
        Forge.load(p, new Slot(fuel, 0, 0, 0), 32, s -> s.is(Items.COAL));
        h.assertTrue(fuel.getItem(0).getCount() == 32 && p.getInventory().countItem(Items.COAL) == 8, "pocket moves, never copies");
        BlockPos at = h.absolutePos(new BlockPos(2, 1, 2));
        h.getLevel().setBlockAndUpdate(at, Blocks.FURNACE.defaultBlockState());
        FurnaceBlockEntity f = (FurnaceBlockEntity) h.getLevel().getBlockEntity(at);
        f.setItem(2, new ItemStack(Items.IRON_INGOT, 12));
        int moved = Forge.collect(p, p.blockPosition(), hit(p, "quick_collect").value());
        h.assertTrue(moved == 12 && f.getItem(2).isEmpty() && p.getInventory().countItem(Items.IRON_INGOT) == 12, "collected " + moved);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void anvilGrindAndTableEnchants(GameTestHelper h) {
        ServerPlayer p = player(h);
        var reg = p.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(reg.getHolderOrThrow(Enchantments.SHARPNESS), 3);
        ItemStack book = Anvil.savedBook(p, sword, ItemStack.EMPTY);
        h.assertTrue(book.is(Items.ENCHANTED_BOOK) && EnchantmentHelper.getEnchantmentsForCrafting(book)
                .getLevel(reg.getHolderOrThrow(Enchantments.SHARPNESS)) == 3, "disenchanter saves sharpness III");
        ItemStack oneBook = book.copy();
        h.assertTrue(Anvil.savedBook(p, oneBook).isEmpty(), "books never feed the disenchanter");
        h.assertTrue(Anvil.echo(p, sword) && EnchantmentHelper.getEnchantmentsForCrafting(sword).size() == 2, "echo adds a second enchant");
        h.assertTrue(EnchantmentHelper.getEnchantmentsForCrafting(sword).getLevel(reg.getHolderOrThrow(Enchantments.SMITE)) == 0,
                "echo respects exclusive enchants");
        ItemStack taken = new ItemStack(Items.DIAMOND_SWORD);
        taken.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Edge"));
        ItemStack emptied = taken.copy();
        emptied.setCount(0);
        p.getInventory().add(taken);
        ItemStack found = Anvil.real(p, emptied);
        h.assertTrue(found != null && found.is(Items.DIAMOND_SWORD) && p.getInventory().items.contains(found), "shift-click result found in the bag");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void careHeirloomSalvageMend(GameTestHelper h) {
        ServerPlayer p = player(h, "heirloom", "tool_care");
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        p.setItemInHand(InteractionHand.MAIN_HAND, pick);
        pick.setDamageValue(pick.getMaxDamage() - 1);
        pick.hurtAndBreak(5, h.getLevel(), p, i -> {});
        h.assertTrue(pick.isEmpty(), "pickaxe broke");
        Care.broke(p, Items.IRON_PICKAXE);
        h.assertTrue(Care.material(Items.IRON_PICKAXE).test(new ItemStack(Items.IRON_INGOT)), "iron tools salvage iron");
        ItemStack sword = new ItemStack(Items.STONE_SWORD);
        sword.setDamageValue(10);
        h.runAfterDelay(3, () -> {
            h.assertTrue(p.getInventory().countItem(Items.IRON_PICKAXE) == 1, "heirloom gave the pickaxe back");
            p.setItemInHand(InteractionHand.MAIN_HAND, sword);
            Care.mendTick(p, 1);
            h.assertTrue(sword.getDamageValue() == 9, "tool care mends one point");
            Care.mendTick(p, 1);
            h.assertTrue(sword.getDamageValue() == 9, "and waits for its cooldown");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void alchemyShareAndRush(GameTestHelper h) {
        ServerPlayer p = player(h, "brew_rush");
        var pet = h.spawn(EntityType.WOLF, new BlockPos(3, 1, 1));
        pet.setTame(true, true);
        pet.setOwnerUUID(p.getUUID());
        var stray = h.spawn(EntityType.WOLF, new BlockPos(1, 1, 3));
        int shared = Alchemy.share(p, new PotionContents(Potions.SWIFTNESS), 6);
        h.assertTrue(shared == 1 && pet.hasEffect(MobEffects.MOVEMENT_SPEED) && !stray.hasEffect(MobEffects.MOVEMENT_SPEED), "only your pet shares, got " + shared);
        BlockPos at = h.absolutePos(new BlockPos(2, 1, 2));
        h.getLevel().setBlockAndUpdate(at, Blocks.BREWING_STAND.defaultBlockState());
        BrewingStandBlockEntity stand = (BrewingStandBlockEntity) h.getLevel().getBlockEntity(at);
        stand.dataAccess.set(0, 400);
        h.assertTrue(Alchemy.rush(p, stand, hit(p, "brew_rush")) && stand.dataAccess.get(0) == 100, "Lv10 skips 15 s");
        h.assertTrue(!Alchemy.rush(p, stand, hit(p, "brew_rush")), "cooldown");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void marketRerollOnlyFreshVillagers(GameTestHelper h) {
        ServerPlayer p = player(h, "second_opinion");
        Villager v = h.spawn(EntityType.VILLAGER, new BlockPos(2, 1, 2));
        v.setVillagerData(v.getVillagerData().setProfession(VillagerProfession.LIBRARIAN).setLevel(1));
        v.getOffers();
        h.assertTrue(Market.reroll(p, v, hit(p, "second_opinion")) && !v.getOffers().isEmpty(), "fresh librarian rerolls");
        Villager old = h.spawn(EntityType.VILLAGER, new BlockPos(3, 1, 3));
        old.setVillagerData(old.getVillagerData().setProfession(VillagerProfession.LIBRARIAN).setLevel(1));
        old.setVillagerXp(5);
        h.assertTrue(!Market.reroll(player(h, "second_opinion"), old, hit(p, "second_opinion")), "a traded villager is locked");
        h.succeed();
    }
}
