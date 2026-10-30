package com.habits.perk.crafting;

import com.habits.Habits;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.SkillProgress;
import com.habits.core.Specs;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Crafting class mechanics (Smith): one test for the Runewright, one for the Mythsmith's Masterworks. */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class SmithTests {
    private SmithTests() {}

    /** A test player of {@code clazz} owning the given class skills at Lv10 ("" = the class passive itself). */
    static ServerPlayer classed(GameTestHelper h, String clazz, String... skills) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-smith"));
        C.TEST_PLAYERS.add(p.getUUID());
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        ResourceLocation cls = Habits.id("class/crafting/" + clazz);
        HabitsCollection c = HabitsCollection.EMPTY;
        for (String s : skills) c = c.with(s.isEmpty() ? cls : Habits.id("class/crafting/" + clazz + "_" + s), new SkillProgress(10, 0, true));
        p.setData(CoreRegistries.SPECS, new Specs(List.of(), false, List.of(cls)));
        p.setData(CoreRegistries.COLLECTION, c);
        return p;
    }

    static float hurt(net.minecraft.world.entity.LivingEntity v, net.minecraft.world.damagesource.DamageSource src, float amt) {
        var e = new LivingIncomingDamageEvent(v, new DamageContainer(src, amt));
        Smith.onHurt(e);
        return e.getAmount();
    }

    /** Runewright pushes enchants up but never past max + 1 at Lv10; Runic Surge adds damage per level, capped. */
    @GameTest(template = "empty")
    public static void runewright(GameTestHelper h) {
        ServerPlayer p = classed(h, "runewright", "", "runic_surge");
        var reg = p.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        var sharp = reg.getHolderOrThrow(Enchantments.SHARPNESS);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(sharp, 5);
        HabitsApi.PerkHit rw = HabitsApi.mechanicHit(p, Smith.RUNEWRIGHT);
        h.assertTrue(rw != null, "runewright active for its class");
        for (int i = 0; i < 40; i++) Smith.runes(p, sword, rw);
        h.assertTrue(EnchantmentHelper.getEnchantmentsForCrafting(sword).getLevel(sharp) == 6, "sharpness V rises to VI and stops");

        var z = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        p.setItemInHand(InteractionHand.MAIN_HAND, sword);
        float dmg = hurt(z, h.getLevel().damageSources().playerAttack(p), 10);
        h.assertTrue(Math.abs(dmg - 11.8f) < 0.01, "6 levels x 3% = +18%, got " + dmg);
        sword.enchant(reg.getHolderOrThrow(Enchantments.LOOTING), 3);
        sword.enchant(reg.getHolderOrThrow(Enchantments.UNBREAKING), 3);
        sword.enchant(reg.getHolderOrThrow(Enchantments.FIRE_ASPECT), 2);
        sword.enchant(reg.getHolderOrThrow(Enchantments.KNOCKBACK), 2);
        dmg = hurt(z, h.getLevel().damageSources().playerAttack(p), 10);
        h.assertTrue(Math.abs(dmg - 14) < 0.01, "capped at 40%, got " + dmg);
        z.discard();
        h.succeed();
    }

    /** A Masterwork gets the maker's mark, better stats and durability once; Maker's Bond and Living Metal read the mark. */
    @GameTest(template = "empty")
    public static void mythsmithMasterworks(GameTestHelper h) {
        ServerPlayer p = classed(h, "mythsmith", "", "makers_bond", "living_metal", "signature");
        HabitsApi.PerkHit ms = HabitsApi.mechanicHit(p, Smith.MYTHSMITH);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        int dur = sword.getMaxDamage();
        h.assertTrue(Smith.masterwork(p, sword, ms) && !Smith.masterwork(p, sword, ms), "a masterwork once");
        h.assertTrue(Smith.own(p, sword) && sword.has(DataComponents.CUSTOM_NAME), "marked and named");
        // Lv10: damage +25% x signature 1.6 = +40% of iron sword's 5 = +2; durability +100% x 1.6 = +160%
        h.assertTrue(sword.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().stream()
                .anyMatch(m -> m.attribute().is(Attributes.ATTACK_DAMAGE) && Math.abs(m.modifier().amount() - 2) < 0.01), "bonus damage +2");
        h.assertTrue(sword.getMaxDamage() == Math.round(dur * 2.6), "durability x2.6, got " + sword.getMaxDamage());
        ItemStack chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
        Smith.masterwork(p, chest, ms);
        h.assertTrue(chest.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().stream().filter(m -> m.attribute().is(Attributes.ARMOR)).count() == 2,
                "vanilla armor kept plus the bonus");

        var z = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        p.setItemInHand(InteractionHand.MAIN_HAND, sword);
        h.assertTrue(Math.abs(hurt(z, h.getLevel().damageSources().playerAttack(p), 10) - 13) < 0.01, "maker's bond +30%");
        p.setItemSlot(EquipmentSlot.CHEST, chest);
        h.assertTrue(Math.abs(hurt(p, h.getLevel().damageSources().mobAttack(z), 10) - 9.5) < 0.01, "one own armor piece cuts 5%");

        sword.setDamageValue(50);
        int mended = 0;
        for (int i = 0; i < 40; i++) mended += Smith.mend(p, HabitsApi.mechanicHit(p, Smith.LIVING_METAL));
        h.assertTrue(mended > 0 && sword.getDamageValue() == 50 - mended, "living metal mends, mended " + mended);
        ItemStack plain = new ItemStack(Items.IRON_SWORD);
        plain.setDamageValue(50);
        p.setItemInHand(InteractionHand.MAIN_HAND, plain);
        for (int i = 0; i < 40; i++) Smith.mend(p, HabitsApi.mechanicHit(p, Smith.LIVING_METAL));
        h.assertTrue(plain.getDamageValue() == 50, "never someone else's or plain gear");
        z.discard();
        h.succeed();
    }

    /** Ratings: only Crafting picks roll; a Myth sword gets +60% damage, a name and two weapon options; thorns never ping-pong. */
    @GameTest(template = "empty")
    public static void gearRatings(GameTestHelper h) {
        ServerPlayer p = classed(h, "mythsmith");
        ItemStack plain = new ItemStack(Items.IRON_SWORD);
        for (int i = 0; i < 50; i++) Rating.onCrafted(p, plain);
        h.assertTrue(Rating.tier(plain) == Rating.Tier.NORMAL && !plain.has(DataComponents.CUSTOM_DATA), "no Crafting pick, never rated");
        p.setData(CoreRegistries.SPECS, new Specs(List.of(com.habits.core.Category.WARRIOR, com.habits.core.Category.CRAFTING), false, List.of()));
        double[] o = Rating.odds(1);
        h.assertTrue(java.util.Arrays.stream(o).sum() < 100, "odds leave room for Normal");

        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        int dur = sword.getMaxDamage();
        Rating.apply(p, sword, Rating.Tier.MYTH);
        h.assertTrue(Rating.tier(sword) == Rating.Tier.MYTH && sword.has(DataComponents.CUSTOM_NAME), "myth, named");
        h.assertTrue(sword.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().stream()
                .anyMatch(m -> m.attribute().is(Attributes.ATTACK_DAMAGE) && Math.abs(m.modifier().amount() - 3) < 0.01), "+60% of 5 = +3");
        h.assertTrue(sword.getMaxDamage() == Math.round(dur * 2.5), "durability x2.5");
        long opts = java.util.Arrays.stream(Rating.Opt.values()).filter(op -> Rating.has(sword, op)).count();
        h.assertTrue(opts == 2 && java.util.Arrays.stream(Rating.Opt.values()).filter(op -> Rating.has(sword, op)).allMatch(op -> op.kind == Rating.Kind.WEAPON), "two weapon options");

        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        CustomData.update(DataComponents.CUSTOM_DATA, chest, t -> {
            var tag = new net.minecraft.nbt.CompoundTag();
            var l = new net.minecraft.nbt.ListTag();
            l.add(net.minecraft.nbt.StringTag.valueOf("THORNS"));
            tag.put("opts", l);
            t.put(Rating.KEY, tag);
        });
        var z = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        z.setItemSlot(EquipmentSlot.CHEST, chest.copy());
        p.setItemSlot(EquipmentSlot.CHEST, chest);
        var e = new LivingIncomingDamageEvent(p, new DamageContainer(h.getLevel().damageSources().mobAttack(z), 10));
        Rating.onHurt(e); // zombie gets thorns back; its own thorns must not answer
        h.assertTrue(z.getHealth() < z.getMaxHealth(), "thorns reflect");
        z.discard();
        h.succeed();
    }

    /** Blacksmith's Focus counts same-item streaks (capped) and never pushes odds past 100%; forged sets need one maker. */
    @GameTest(template = "empty")
    public static void focusAndSets(GameTestHelper h) {
        ServerPlayer p = classed(h, "mythsmith");
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        for (int i = 0; i < 15; i++) Rating.focus(p, sword);
        h.assertTrue(Rating.focus(p, sword) == CraftCfg.MAX_FOCUS.get(), "streak capped");
        h.assertTrue(Rating.focus(p, new ItemStack(Items.IRON_AXE)) == 0, "another item starts over");
        int normal = 0;
        for (int i = 0; i < 2000; i++) if (Rating.roll(p, 1, CraftCfg.MAX_FOCUS.get()) == Rating.Tier.NORMAL) normal++;
        h.assertTrue(normal < 40, "full focus at full skill leaves almost no Normal, got " + normal);

        ServerPlayer other = classed(h, "mythsmith");
        var z = h.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 3));
        Item[] armor = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
        for (int i = 0; i < 4; i++) {
            ItemStack a = new ItemStack(armor[i]);
            Rating.apply(i < 2 ? p : other, a, Rating.Tier.RARE);
            z.setItemSlot(((net.minecraft.world.item.ArmorItem) armor[i]).getEquipmentSlot(), a);
        }
        h.assertTrue(Rating.setPieces(z) == 2 && Rating.setTaken(2) == 0.92f, "2 + 2 from different makers is a 2-piece set");
        for (int i = 2; i < 4; i++) {
            ItemStack a = new ItemStack(armor[i]);
            Rating.apply(p, a, Rating.Tier.RARE);
            z.setItemSlot(((net.minecraft.world.item.ArmorItem) armor[i]).getEquipmentSlot(), a);
        }
        h.assertTrue(Rating.setPieces(z) == 4, "full set");
        var e = new LivingIncomingDamageEvent(z, new DamageContainer(h.getLevel().damageSources().playerAttack(p), 10));
        Rating.onHurt(e);
        h.assertTrue(Math.abs(e.getAmount() - 8.5f) < 0.01, "4-piece takes 15% less, got " + e.getAmount());
        z.discard();
        h.succeed();
    }

    /** Enhancement: +3% of base per level; a safe fail keeps the level, a high fail drops one, a break-level fail can shatter. */
    @GameTest(template = "empty")
    public static void enhancement(GameTestHelper h) {
        ServerPlayer p = classed(h, "mythsmith");
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        Enhance.setLevel(sword, 5);
        h.assertTrue(Enhance.level(sword) == 5 && sword.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().stream()
                .anyMatch(m -> m.attribute().is(Attributes.ATTACK_DAMAGE) && Math.abs(m.modifier().amount() - 0.75) < 0.01), "+5 = +15% of 5");
        Enhance.setLevel(sword, 2);
        h.assertTrue(sword.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().stream()
                .filter(m -> m.modifier().id().getPath().startsWith("enhance/")).count() == 1, "old level's modifier replaced");

        ItemStack out = sword.copy();
        Enhance.setLevel(out, 3);
        Enhance.outcome(p, out, 2, 0);
        h.assertTrue(Enhance.level(out) == 3, "success keeps the new level");
        Enhance.setLevel(out, 4);
        Enhance.outcome(p, out, 3, 99.99);
        h.assertTrue(Enhance.level(out) == 3 && !out.isEmpty(), "safe fail stays at +3");
        Enhance.setLevel(out, 7);
        Enhance.outcome(p, out, 6, 99.99);
        h.assertTrue(Enhance.level(out) == 5 && !out.isEmpty(), "fail past safe drops to +5");
        int broke = 0;
        for (int i = 0; i < 60; i++) {
            ItemStack hi = sword.copy();
            Enhance.setLevel(hi, 9);
            Enhance.outcome(p, hi, 8, 99.99);
            if (hi.isEmpty()) broke++;
            else h.assertTrue(Enhance.level(hi) == 7, "a non-break fail at +9 drops to +7");
        }
        h.assertTrue(broke > 5 && broke < 40, "about a third shatter, got " + broke);
        h.succeed();
    }

    /** Rated potions: Epic lasts 1.5x, Legendary is one level higher and gains a bonus effect. */
    @GameTest(template = "empty")
    public static void ratedPotions(GameTestHelper h) {
        ServerPlayer p = classed(h, "mythsmith");
        ItemStack epic = net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.SWIFTNESS);
        int base = epic.get(DataComponents.POTION_CONTENTS).getAllEffects().iterator().next().getDuration();
        Brew.apply(p, epic, Rating.Tier.EPIC);
        var eff = new java.util.ArrayList<net.minecraft.world.effect.MobEffectInstance>();
        epic.get(DataComponents.POTION_CONTENTS).getAllEffects().forEach(eff::add);
        h.assertTrue(eff.size() == 1 && eff.get(0).getDuration() == (int) (base * 1.5) && eff.get(0).getAmplifier() == 0, "epic: x1.5, got " + eff);
        h.assertTrue(Rating.tier(epic) == Rating.Tier.EPIC && epic.has(DataComponents.CUSTOM_NAME), "stamped and named");

        ItemStack leg = net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.SWIFTNESS);
        Brew.apply(p, leg, Rating.Tier.LEGENDARY);
        eff.clear();
        leg.get(DataComponents.POTION_CONTENTS).getAllEffects().forEach(eff::add);
        h.assertTrue(eff.size() == 2 && eff.get(0).getAmplifier() == 1, "legendary: level up + one bonus, got " + eff);
        h.succeed();
    }

    /** Shift-click crafting hands the event an emptied stack; the craft still rolls (focus counts it) on the bag copy. */
    @GameTest(template = "empty")
    public static void shiftClickCraftRolls(GameTestHelper h) {
        ServerPlayer p = classed(h, "mythsmith");
        p.setData(CoreRegistries.SPECS, new Specs(List.of(com.habits.core.Category.WARRIOR, com.habits.core.Category.CRAFTING), false, List.of()));
        for (int i = 0; i < 3; i++) {
            ItemStack made = new ItemStack(Items.IRON_SWORD);
            p.getInventory().add(made.copy());
            made.setCount(0); // what quickMoveStack leaves behind
            made.setCount(1);
            p.containerMenu.setCarried(made); // a normal click: the result is on the cursor
            Smith.crafted(p, made);
            p.containerMenu.setCarried(ItemStack.EMPTY);
        }
        h.assertTrue(Rating.FOCUS.get(p.getUUID()) != null && Rating.FOCUS.get(p.getUUID()).count() == 3, "all three shift-clicked crafts rolled");
        h.succeed();
    }

    /** The real path: a crafting table, diamonds for 10 boots, one shift-click. Every pair of boots rolls (focus counts 10)
     * and each crafted pair is either plain or rated on its own. */
    @GameTest(template = "empty")
    public static void shiftClickTenBootsRealMenu(GameTestHelper h) {
        ServerPlayer p = classed(h, "mythsmith");
        p.setData(CoreRegistries.SPECS, new Specs(List.of(com.habits.core.Category.WARRIOR, com.habits.core.Category.CRAFTING), false, List.of()));
        p.getInventory().clearContent();
        BlockPos at = h.absolutePos(new BlockPos(2, 1, 2));
        var menu = new net.minecraft.world.inventory.CraftingMenu(1, p.getInventory(),
                net.minecraft.world.inventory.ContainerLevelAccess.create(h.getLevel(), at));
        p.containerMenu = menu;
        for (int slot : new int[]{1, 3, 4, 6}) menu.getSlot(slot).set(new ItemStack(Items.DIAMOND, 10)); // D.D / D.D
        menu.slotsChanged(menu.getSlot(1).container);
        h.assertTrue(menu.getSlot(0).getItem().is(Items.DIAMOND_BOOTS), "boots in the result slot");
        menu.clicked(0, 0, net.minecraft.world.inventory.ClickType.QUICK_MOVE, p);
        int boots = p.getInventory().countItem(Items.DIAMOND_BOOTS);
        h.assertTrue(boots == 10, "ten boots crafted, got " + boots);
        var streak = Rating.FOCUS.get(p.getUUID());
        h.assertTrue(streak != null && streak.count() == 10, "all ten rolled, got " + (streak == null ? 0 : streak.count()));
        h.succeed();
    }

    /** A new crafter (no Crafting levels) still rates about a quarter of their gear through the real craft hook. */
    @GameTest(template = "empty")
    public static void newCrafterRateShare(GameTestHelper h) {
        ServerPlayer p = classed(h, "mythsmith");
        p.setData(CoreRegistries.SPECS, new Specs(List.of(com.habits.core.Category.WARRIOR, com.habits.core.Category.CRAFTING), false, List.of()));
        int rated = 0;
        for (int i = 0; i < 400; i++) {
            ItemStack boots = new ItemStack(i % 2 == 0 ? Items.DIAMOND_BOOTS : Items.IRON_SWORD); // alternating: no focus
            Rating.onCrafted(p, boots);
            if (Rating.tier(boots) != Rating.Tier.NORMAL) rated++;
        }
        h.assertTrue(rated > 70 && rated < 150, "about 26% rated, got " + rated + "/400");
        h.succeed();
    }
}
