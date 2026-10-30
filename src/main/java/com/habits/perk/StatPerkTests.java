package com.habits.perk;

import com.habits.Habits;
import com.habits.core.Category;
import com.habits.core.CoreRegistries;
import com.habits.core.HabitsApi;
import com.habits.core.HabitsCollection;
import com.habits.core.PerkSpec;
import com.habits.core.PerkType;
import com.habits.core.Rarity;
import com.habits.core.SkillDef;
import com.habits.core.SkillProgress;
import com.habits.core.SkillRegistry;
import com.habits.core.TriggerSpec;
import com.habits.core.TriggerType;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntConsumer;

/** Every stat PerkType at Lv1 vs Lv10, plus toggled off. The three catalog users (crossbow_sniper, hard_shell,
 * long_arm) run through their real skill; every other type through a hand-made fixture def (SkillRegistry.testFixture). */
@GameTestHolder(Habits.MODID)
@PrefixGameTestTemplate(false)
public class StatPerkTests {
    private StatPerkTests() {}

    static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.randomUUID(), "habits-stats"));
        Vec3 at = h.absoluteVec(new Vec3(2.5, 1, 2.5));
        p.moveTo(at.x, at.y, at.z);
        return p;
    }

    /** A fixture skill of {@code type}: Lv1 = {@code lv1}, Lv10 = {@code lv10}, linear between. */
    static SkillDef fixture(PerkType type, double lv1, double lv10, Map<String, String> params) {
        PerkSpec perk = new PerkSpec(Optional.of(type), Optional.empty(), params, lv1, (lv10 - lv1) / 9, Optional.empty());
        TriggerSpec never = new TriggerSpec(TriggerType.CUSTOM, Optional.empty(), Optional.empty(), Optional.empty(), 0, 0);
        return SkillRegistry.testFixture(new SkillDef(Habits.id("test/stat_" + type.getSerializedName()), Rarity.COMMON, Category.EXPLORATION,
                never, ResourceLocation.withDefaultNamespace("stone"), ResourceLocation.withDefaultNamespace("stone"), perk,
                Optional.empty(), Optional.empty(), Optional.empty()));
    }

    static void own(ServerPlayer p, SkillDef def, int level, boolean enabled) {
        p.setData(CoreRegistries.COLLECTION, HabitsCollection.EMPTY.with(def.id(), new SkillProgress(level, 0, enabled)));
    }

    /** Runs {@code check} at Lv1 (arg 1), Lv10 (arg 10) and Lv10 toggled off (arg 0). */
    static void levels(ServerPlayer p, SkillDef def, IntConsumer check) {
        own(p, def, 1, true); check.accept(1);
        own(p, def, 10, true); check.accept(10);
        own(p, def, 10, false); check.accept(0);
    }

    static double pick(int lv, double at1, double at10, double off) {
        return lv == 1 ? at1 : lv == 10 ? at10 : off;
    }

    static void near(GameTestHelper h, double actual, double expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < 1e-3, what + ": expected " + expected + " got " + actual);
    }

    static SkillDef real(GameTestHelper h, String path) {
        SkillDef d = SkillRegistry.get(Habits.id(path));
        h.assertTrue(d != null, "catalog skill " + path + " missing");
        return d;
    }

    // ---------------------------------------------------------------- catalog users

    @GameTest(template = "empty")
    public static void damageBonusCrossbowSniper(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = real(h, "crossbow_sniper");
        IronGolem golem = h.spawn(EntityType.IRON_GOLEM, 1, 1, 1);
        Arrow bolt = new Arrow(h.getLevel(), p, new ItemStack(Items.ARROW), new ItemStack(Items.CROSSBOW));
        Arrow bowArrow = new Arrow(h.getLevel(), p, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        levels(p, def, lv -> {
            var e = new LivingIncomingDamageEvent(golem, new DamageContainer(p.damageSources().arrow(bolt, p), 10));
            StatEvents.incomingDamage(e);
            near(h, e.getAmount(), 10 * (1 + def.perkValue(lv) / 100), "crossbow Lv" + lv);
            var bow = new LivingIncomingDamageEvent(golem, new DamageContainer(p.damageSources().arrow(bowArrow, p), 10));
            StatEvents.incomingDamage(bow);
            near(h, bow.getAmount(), 10, "bow is not a crossbow");
        });
        h.assertTrue(def.perkValue(10) > def.perkValue(1), "Lv10 beats Lv1");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void mobEffectHardShell(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = real(h, "hard_shell");
        p.setShiftKeyDown(true);
        levels(p, def, lv -> {
            StatTicks.effects(p);
            var eff = p.getEffect(MobEffects.DAMAGE_RESISTANCE);
            if (lv == 0) h.assertTrue(eff == null, "toggled off removes it");
            else h.assertValueEqual(eff == null ? -1 : eff.getAmplifier(), (int) Math.round(def.perkValue(lv)) - 1, "amplifier Lv" + lv);
        });
        own(p, def, 10, true);
        StatTicks.effects(p);
        p.setShiftKeyDown(false);
        StatTicks.effects(p);
        h.assertTrue(p.getEffect(MobEffects.DAMAGE_RESISTANCE) == null, "standing up removes it");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void reachLongArm(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = real(h, "long_arm");
        double block = p.getAttributeBaseValue(Attributes.BLOCK_INTERACTION_RANGE), entity = p.getAttributeBaseValue(Attributes.ENTITY_INTERACTION_RANGE);
        levels(p, def, lv -> {
            StatTicks.attributes(p);
            near(h, p.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE), block + (lv == 0 ? 0 : def.perkValue(lv)), "block reach Lv" + lv);
            near(h, p.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE), entity, "kind=block leaves entity reach");
        });
        StatTicks.forget(p.getUUID());
        h.succeed();
    }

    // ---------------------------------------------------------------- fixtures

    @GameTest(template = "empty")
    public static void miningSpeed(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.MINING_SPEED, 10, 100, Map.of("block", "minecraft:stone"));
        levels(p, def, lv -> {
            var e = new PlayerEvent.BreakSpeed(p, Blocks.STONE.defaultBlockState(), 2, null);
            StatEvents.breakSpeed(e);
            near(h, e.getNewSpeed(), 2 * (1 + pick(lv, 10, 100, 0) / 100), "stone Lv" + lv);
            var dirt = new PlayerEvent.BreakSpeed(p, Blocks.DIRT.defaultBlockState(), 2, null);
            StatEvents.breakSpeed(dirt);
            near(h, dirt.getNewSpeed(), 2, "dirt filtered out");
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void attribute(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.ATTRIBUTE, 2, 20, Map.of("attribute", "minecraft:generic.max_health"));
        double base = p.getAttributeBaseValue(Attributes.MAX_HEALTH);
        levels(p, def, lv -> {
            StatTicks.attributes(p);
            near(h, p.getAttributeValue(Attributes.MAX_HEALTH), base + pick(lv, 2, 20, 0), "max health Lv" + lv);
        });
        SkillDef cond = fixture(PerkType.ATTRIBUTE, 50, 50, Map.of("attribute", "minecraft:armor", "operation", "add_value", "condition", "sneaking") /* bare id: the generic. fallback */);
        own(p, cond, 1, true);
        StatTicks.attributes(p);
        near(h, p.getAttributeValue(Attributes.ARMOR), 0, "condition false: no armor");
        p.setShiftKeyDown(true);
        StatTicks.attributes(p);
        near(h, p.getAttributeValue(Attributes.ARMOR), Math.min(30, 50), "condition true: armor (capped at 30)");
        StatTicks.forget(p.getUUID());
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void damageTypeResist(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.DAMAGE_TYPE_RESIST, 10, 100, Map.of("damage", "#minecraft:is_fall"));
        levels(p, def, lv -> {
            var e = new LivingIncomingDamageEvent(p, new DamageContainer(p.damageSources().fall(), 10));
            StatEvents.incomingDamage(e);
            near(h, e.getAmount(), 10 * (1 - Math.min(pick(lv, 10, 100, 0), PerkInit.RESIST_CAP.get()) / 100), "fall Lv" + lv + " (capped)");
            var lava = new LivingIncomingDamageEvent(p, new DamageContainer(p.damageSources().lava(), 10));
            StatEvents.incomingDamage(lava);
            near(h, lava.getAmount(), 10, "lava filtered out");
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void dropBonus(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.DROP_BONUS, 100, 1000, Map.of("block", "minecraft:stone"));
        BlockPos pos = h.absolutePos(new BlockPos(1, 1, 1));
        levels(p, def, lv -> {
            List<ItemEntity> drops = new ArrayList<>(List.of(new ItemEntity(h.getLevel(), pos.getX(), pos.getY(), pos.getZ(), new ItemStack(Items.COBBLESTONE))));
            StatEvents.blockDrops(new BlockDropsEvent(h.getLevel(), pos, Blocks.STONE.defaultBlockState(), null, drops, p, ItemStack.EMPTY));
            h.assertValueEqual(drops.size(), 1 + (int) pick(lv, 1, 10, 0), "stone drops Lv" + lv);
            List<ItemEntity> mob = new ArrayList<>(List.of(new ItemEntity(h.getLevel(), pos.getX(), pos.getY(), pos.getZ(), new ItemStack(Items.LEATHER))));
            Cow cow = h.spawn(EntityType.COW, 1, 1, 1);
            StatEvents.livingDrops(new LivingDropsEvent(cow, p.damageSources().playerAttack(p), mob, true));
            h.assertValueEqual(mob.size(), 1, "a block-only perk ignores mobs");
            cow.discard();
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void xpBonus(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.XP_BONUS, 100, 1000, Map.of());
        levels(p, def, lv -> {
            ExperienceOrb orb = new ExperienceOrb(h.getLevel(), p.getX(), p.getY(), p.getZ(), 10);
            StatEvents.xpPickup(new PlayerXpEvent.PickupXp(p, orb));
            h.assertValueEqual(orb.value, (int) (10 * (1 + pick(lv, 100, 1000, 0) / 100)), "orb Lv" + lv);
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void hungerSaveViaMixin(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.HUNGER_SAVE, 10, 100, Map.of());
        levels(p, def, lv -> {
            p.getFoodData().setExhaustion(0);
            p.causeFoodExhaustion(2); // goes through PlayerMixin
            near(h, p.getFoodData().getExhaustionLevel(), 2 * (1 - Math.min(pick(lv, 10, 100, 0), PerkInit.HUNGER_SAVE_CAP.get()) / 100), "exhaustion Lv" + lv + " (capped)");
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void foodBonus(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.FOOD_BONUS, 20, 200, Map.of("item", "minecraft:bread"));
        ItemStack bread = new ItemStack(Items.BREAD); // 5 nutrition
        levels(p, def, lv -> {
            p.getFoodData().setFoodLevel(0);
            StatEvents.eat(new LivingEntityUseItemEvent.Finish(p, bread, 0, ItemStack.EMPTY));
            h.assertValueEqual(p.getFoodData().getFoodLevel(), (int) (5 * pick(lv, 20, 200, 0) / 100), "extra food Lv" + lv);
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void durabilitySaveViaMixin(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.DURABILITY_SAVE, 0, 100, Map.of());
        levels(p, def, lv -> {
            ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
            pick.hurtAndBreak(10, h.getLevel(), p, item -> {}); // goes through ItemStackMixin
            h.assertValueEqual(pick.getDamageValue(), lv == 10 ? 0 : 10, "wear Lv" + lv);
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void cropGrowthAura(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.CROP_GROWTH_AURA, 0, 100, Map.of("radius", "3"));
        BlockPos crop = h.absolutePos(new BlockPos(3, 1, 3));
        levels(p, def, lv -> {
            h.getLevel().setBlock(crop, Blocks.WHEAT.defaultBlockState(), 2);
            StatTicks.cropAura(p);
            h.assertValueEqual(((CropBlock) Blocks.WHEAT).getAge(h.getLevel().getBlockState(crop)), lv == 10 ? 1 : 0, "wheat age Lv" + lv);
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void fishingLuckAndSpeedViaAccessTransformer(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef luck = fixture(PerkType.FISHING_LUCK, 1, 3, Map.of());
        levels(p, luck, lv -> {
            FishingHook hook = new FishingHook(p, h.getLevel(), 0, 0);
            h.getLevel().addFreshEntity(hook);
            h.assertValueEqual(hook.luck, (int) pick(lv, 1, 3, 0), "luck Lv" + lv);
            hook.discard();
        });
        SkillDef speed = fixture(PerkType.FISHING_SPEED, 1, 3, Map.of());
        levels(p, speed, lv -> {
            FishingHook hook = new FishingHook(p, h.getLevel(), 0, 0);
            h.getLevel().addFreshEntity(hook);
            h.assertValueEqual(hook.lureSpeed, (int) pick(lv, 100, 300, 0), "lure ticks Lv" + lv);
            hook.discard();
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void lootingViaMixin(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.LOOTING_BONUS, 1, 3, Map.of());
        var looting = h.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.LOOTING);
        var sharp = h.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.SHARPNESS);
        levels(p, def, lv -> {
            h.assertValueEqual(EnchantmentHelper.getEnchantmentLevel(looting, p), (int) pick(lv, 1, 3, 0), "looting Lv" + lv);
            h.assertValueEqual(EnchantmentHelper.getEnchantmentLevel(sharp, p), 0, "only looting");
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void smeltBonus(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.SMELT_BONUS, 100, 1000, Map.of());
        levels(p, def, lv -> {
            p.getInventory().clearContent();
            StatEvents.smelt(new PlayerEvent.ItemSmeltedEvent(p, new ItemStack(Items.IRON_INGOT, 2)));
            h.assertValueEqual(p.getInventory().countItem(Items.IRON_INGOT), 2 * (int) pick(lv, 1, 10, 0), "extra ingots Lv" + lv);
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void breedTwins(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.BREED_TWINS, 0, 100, Map.of("entity", "minecraft:cow"));
        Cow a = h.spawn(EntityType.COW, 1, 1, 1), b = h.spawn(EntityType.COW, 1, 1, 2);
        levels(p, def, lv -> {
            int before = h.getEntities(EntityType.COW).size();
            StatEvents.twin(p, a, b, a.getBreedOffspring(h.getLevel(), b));
            h.assertValueEqual(h.getEntities(EntityType.COW).size() - before, lv == 10 ? 1 : 0, "twin Lv" + lv);
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void tameChance(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef def = fixture(PerkType.TAME_CHANCE, 0, 100, Map.of());
        Wolf lv1 = h.spawn(EntityType.WOLF, 1, 1, 1), lv10 = h.spawn(EntityType.WOLF, 3, 1, 1);
        // a wild wolf ate a bone without taming (vanilla failed): the perk rolls one tick later
        own(p, def, 1, true);
        attempt(p, lv1);
        own(p, def, 10, true);
        attempt(p, lv10);
        h.runAfterDelay(3, () -> {
            h.assertFalse(lv1.isTame(), "Lv1 (0%) stays wild");
            h.assertTrue(lv10.isTame() && p.getUUID().equals(lv10.getOwnerUUID()), "Lv10 (100%) tamed by the player");
            h.succeed();
        });
    }

    private static void attempt(ServerPlayer p, Wolf wolf) {
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BONE, 2));
        StatEvents.tameAttempt(new PlayerInteractEvent.EntityInteract(p, InteractionHand.MAIN_HAND, wolf));
        p.getMainHandItem().shrink(1);
    }

    @GameTest(template = "empty")
    public static void tradeEnchantAnvilDiscounts(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef trade = fixture(PerkType.TRADE_DISCOUNT, 10, 50, Map.of());
        levels(p, trade, lv -> {
            MerchantOffers offers = new MerchantOffers();
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 20), new ItemStack(Items.BREAD), 10, 1, 0.05f));
            StatHooks.tradeDiscount(offers, p);
            h.assertValueEqual(offers.get(0).getCostA().getCount(), 20 - (int) pick(lv, 2, 10, 0), "trade cost Lv" + lv);
        });
        SkillDef anvil = fixture(PerkType.ANVIL_DISCOUNT, 10, 50, Map.of());
        levels(p, anvil, lv -> h.assertValueEqual(StatHooks.anvilCost(p, 10), 10 - (int) pick(lv, 1, 5, 0), "anvil cost Lv" + lv));
        SkillDef ench = fixture(PerkType.ENCHANT_DISCOUNT, 0, 100, Map.of());
        p.containerMenu = new EnchantmentMenu(1, p.getInventory());
        levels(p, ench, lv -> {
            var e = new PlayerXpEvent.LevelChange(p, -3);
            StatEvents.enchantCost(e);
            h.assertValueEqual(e.getLevels(), lv == 10 ? 0 : -3, "enchant levels paid Lv" + lv);
        });
        p.containerMenu = p.inventoryMenu;
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void regenLifestealThornsCrit(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef regen = fixture(PerkType.REGEN, 6, 60, Map.of());
        levels(p, regen, lv -> {
            p.setHealth(5);
            StatTicks.regen(p);
            near(h, p.getHealth(), 5 + pick(lv, 6, 60, 0) / 60, "regen per second Lv" + lv);
        });
        IronGolem golem = h.spawn(EntityType.IRON_GOLEM, 1, 1, 1);
        SkillDef steal = fixture(PerkType.LIFESTEAL, 10, 100, Map.of());
        levels(p, steal, lv -> {
            p.setHealth(5);
            DamageContainer c = new DamageContainer(p.damageSources().playerAttack(p), 10);
            StatEvents.damagePost(new LivingDamageEvent.Post(golem, c));
            near(h, p.getHealth(), Math.min(20, 5 + 10 * pick(lv, 10, 100, 0) / 100), "lifesteal Lv" + lv);
        });
        SkillDef thorns = fixture(PerkType.THORNS, 10, 100, Map.of());
        levels(p, thorns, lv -> {
            IronGolem attacker = h.spawn(EntityType.IRON_GOLEM, 3, 1, 3);
            float full = attacker.getHealth();
            DamageSource hit = p.damageSources().mobAttack(attacker);
            StatEvents.damagePost(new LivingDamageEvent.Post(p, new DamageContainer(hit, 10)));
            near(h, full - attacker.getHealth(), 10 * pick(lv, 10, 100, 0) / 100, "reflected Lv" + lv);
            attacker.discard();
        });
        SkillDef crit = fixture(PerkType.CRIT_DAMAGE, 10, 100, Map.of());
        levels(p, crit, lv -> {
            var e = new CriticalHitEvent(p, golem, 1.5f, true);
            StatEvents.crit(e);
            near(h, e.getDamageMultiplier(), 1.5 * (1 + pick(lv, 10, 100, 0) / 100), "crit Lv" + lv);
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void magnetAndAutoReplant(GameTestHelper h) {
        ServerPlayer p = player(h);
        SkillDef magnet = fixture(PerkType.MAGNET_PICKUP, 1, 10, Map.of());
        levels(p, magnet, lv -> {
            ItemEntity item = new ItemEntity(h.getLevel(), p.getX() + 4, p.getY() + 0.5, p.getZ(), new ItemStack(Items.STICK));
            item.setNoPickUpDelay();
            item.setDeltaMovement(Vec3.ZERO);
            h.getLevel().addFreshEntity(item);
            StatTicks.magnet(p);
            h.assertTrue((item.getDeltaMovement().x < 0) == (lv == 10), "pulled only at Lv10 (radius 10 > 4), Lv" + lv);
            item.discard();
        });
        SkillDef replant = fixture(PerkType.AUTO_REPLANT, 0, 100, Map.of());
        BlockPos pos = h.absolutePos(new BlockPos(1, 1, 3));
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        levels(p, replant, lv -> {
            h.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            List<ItemEntity> drops = new ArrayList<>(List.of(new ItemEntity(h.getLevel(), pos.getX(), pos.getY(), pos.getZ(), new ItemStack(Items.WHEAT_SEEDS, 2))));
            StatEvents.blockDrops(new BlockDropsEvent(h.getLevel(), pos, wheat.getStateForAge(7), null, drops, p, ItemStack.EMPTY));
            boolean planted = h.getLevel().getBlockState(pos).is(Blocks.WHEAT);
            h.assertValueEqual(planted, lv == 10, "replanted Lv" + lv);
            h.assertValueEqual(drops.get(0).getItem().getCount(), lv == 10 ? 1 : 2, "seed spent Lv" + lv);
        });
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void clientSideTypesReadTheirValue(GameTestHelper h) {
        ServerPlayer p = player(h);
        // CLIMB_SPEED moves the local player and SHOW_COORDS draws a HUD: both client-only, so check the value they read
        for (PerkType t : new PerkType[]{PerkType.CLIMB_SPEED, PerkType.SHOW_COORDS}) {
            SkillDef def = fixture(t, 1, 10, Map.of());
            levels(p, def, lv -> near(h, Stats.sum(p, t), pick(lv, 1, 10, 0), t + " Lv" + lv));
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void conditionGrammar(GameTestHelper h) {
        ServerPlayer p = player(h);
        h.assertTrue(Conditions.test(p, null) && Conditions.test(p, " "), "blank always holds");
        h.assertFalse(Conditions.test(p, "sneaking"), "not sneaking");
        p.setShiftKeyDown(true);
        h.assertTrue(Conditions.test(p, "sneaking"), "sneaking");
        h.assertTrue(Conditions.test(p, "dimension=minecraft:overworld"), "overworld");
        h.assertFalse(Conditions.test(p, "sneaking,dimension=minecraft:the_nether"), "all terms must hold");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_LOG));
        h.assertTrue(Conditions.test(p, "holding=#minecraft:logs"), "holding a log tag");
        p.setHealth(p.getMaxHealth());
        h.assertTrue(Conditions.test(p, "full_health") && !Conditions.test(p, "low_health"), "full health");
        p.setHealth(4);
        h.assertTrue(Conditions.test(p, "low_health"), "low health");
        h.assertFalse(Conditions.test(p, "not_a_condition"), "unknown never holds");
        h.assertFalse(Conditions.test(p, "in_lava"), "not in lava");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void everyPerkTypeIsTested(GameTestHelper h) {
        // guards this file: a new PerkType must get a test above (types are listed by the test that covers them)
        List<PerkType> covered = List.of(PerkType.DAMAGE_BONUS, PerkType.MOB_EFFECT_CONDITIONAL, PerkType.REACH, PerkType.MINING_SPEED,
                PerkType.ATTRIBUTE, PerkType.DAMAGE_TYPE_RESIST, PerkType.DROP_BONUS, PerkType.XP_BONUS, PerkType.HUNGER_SAVE,
                PerkType.FOOD_BONUS, PerkType.DURABILITY_SAVE, PerkType.CROP_GROWTH_AURA, PerkType.FISHING_LUCK, PerkType.FISHING_SPEED,
                PerkType.LOOTING_BONUS, PerkType.SMELT_BONUS, PerkType.BREED_TWINS, PerkType.TAME_CHANCE, PerkType.TRADE_DISCOUNT,
                PerkType.ENCHANT_DISCOUNT, PerkType.ANVIL_DISCOUNT, PerkType.REGEN, PerkType.LIFESTEAL, PerkType.CRIT_DAMAGE,
                PerkType.THORNS, PerkType.CLIMB_SPEED, PerkType.MAGNET_PICKUP, PerkType.AUTO_REPLANT, PerkType.SHOW_COORDS);
        for (PerkType t : PerkType.values()) h.assertTrue(covered.contains(t), t + " has no test");
        h.assertTrue(HabitsApi.perkValues(player(h), PerkType.REACH).isEmpty(), "fresh player has no perks");
        h.succeed();
    }
}
